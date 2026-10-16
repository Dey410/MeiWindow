package com.repl.bubbledrawer.contactbar

import android.app.ActivityOptions
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.repl.bubbledrawer.launch.FlymeFreeformController
import com.repl.bubbledrawer.launch.MiuiFreeform
import com.repl.bubbledrawer.xposed.CornerInputMonitor
import com.repl.bubbledrawer.xposed.RemotePrefs

/**
 * The floating contact bar under the small window: Flyme's
 * `com.flyme.systemuitools.windowmode.views.C2831I` ("WMNotificationView"), ported to HyperOS.
 *
 * What Flyme does and what we do instead:
 *
 * | Flyme | here |
 * |---|---|
 * | overlay window type 2056 + `meizuFlags`, anchored from `WindowManagerExt.getWindowModeBound(0,0,windowingMode)` | overlay window type 2024 (the module's proven spy/panel identity), anchored from the live `MiuiFreeformModeTaskInfo` |
 * | geometry scaled by `dMin = live/origin bound` recomputed on every bound change | rect read verbatim from `getActiveTaskScaleBounds()` on every frame |
 * | shown for `windowingMode == 11 \|\| 1035`, hidden when no conversations | shown for `mMode == 0`, hidden for mini/pinned |
 *
 * Two user requirements drive the frame loop:
 *
 *  1. **宽度始终 = 小窗宽度.** HyperOS lets the user drag-resize the window, and WMShell exposes no
 *     per-frame callback at all (verified: `MiuiFreeformModeTaskRepository` only fires appeared /
 *     vanished / mode-changed, and `MiuiFreeformModeResizeHandler.handleResize` pushes geometry
 *     straight into the gesture animation). So the bar polls the task object once per frame —
 *     `getActiveTaskScaleBounds()` returns the destination rect while a gesture/animation is in
 *     flight, which keeps the bar's edge glued to the window's edge.
 *  2. **迷你小窗不显示.** `mMode` 1 (迷你) / 3 (迷你贴边) / 2 (普通贴边) all mean "no room for a bar"
 *     — the window is a thumbnail pinned at a screen edge — so the bar is removed from the window
 *     manager (not merely hidden: an added-but-invisible overlay would still eat touches).
 */
class ContactBarController(
    private val host: Context,
    private val prefs: SharedPreferences,
    private val logger: (Int, String, Throwable?) -> Unit,
) : FreeformObserver {

    private val wm = host.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val recent = RecentConversations(host, logger)

    /**
     * Every window/view operation happens on the SystemUI **main** thread, whatever thread the event
     * arrived on. The freeform hooks fire on the WMShell thread (`wmshell.main`) — a view created there
     * belongs to that thread's ViewRootImpl, so a later refresh from the main thread (the notification
     * listener posts there) dies with `CalledFromWrongThreadException: Expected: wmshell.main Calling:
     * main`. Doing everything on main also means one Choreographer, one input thread, no interleaving.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** The main thread's Choreographer — never `Choreographer.getInstance()` off-thread. */
    @Volatile
    private var mainChoreographer: Choreographer? = null

    /** Diagnostics go to LSPosed's module log and logcat (prefix `CONTACT_BAR_`). */
    private fun log(priority: Int, message: String) = logger(priority, message, null)

    private var view: ContactBarView? = null
    private var added = false

    @Volatile
    private var frameScheduled = false

    /** 空闲时的采样定时器（窗口不动时不再是每帧）——与 [frameScheduled] 互斥地推进同一个循环。 */
    private var idleScheduled = false
    private var frames = 0
    private var items: List<Conversation> = emptyList()
    private var currentPkg: String? = null
    private var lastRect: Rect? = null
    private var lastLogAt = 0L

    /**
     * `refreshData` 对"为什么没有内容"的结论（`NOT_IM_APP` 之类）；没有结论时 `onFrame` 才用
     * 自己的 `NO_CONTACTS`。见 [ContactBarController.onFrame]。
     */
    private var lastDataReason: String? = null

    /** 当前是按帧跑还是按 [IDLE_TICK_MS] 采样 —— 只在切换时打一行，见 [scheduleNext]/[kick]。 */
    private var idleMode = false
    private var hideReason: String? = null
    private var fastUntil = 0L
    private var cornerShown = Float.NaN

    /** While set, the bar stays hidden: the window is closing, so it must not ride the animation. */
    private var closingUntil = 0L

    /**
     * Another 小窗 holds the focus, so this window's bar must not show. Cleared the moment this window
     * is brought to the front again (`onFreeformTaskToFront`).
     */
    private var unfocused = false

    /** taskId → the `MiuiFreeformModeTaskInfo` we have seen for it (bounded, main thread only). */
    private val taskById = HashMap<Int, Any>()

    @Volatile
    private var task: Any? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RemotePrefs.KEY_CONTACT_BAR) {
            onMain {
                log(Log.INFO, "CONTACT_BAR_PREF_CHANGED enabled=${enabled()} thread=${Thread.currentThread().name}")
                if (!enabled()) detach("PREF_OFF")
                scheduleFrame()
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Bind the frame loop to the main thread's Choreographer up front (the constructor itself may
        // run on the module's own thread after a hot reload).
        onMain { mainChoreographer = Choreographer.getInstance() }
        // 窗口一动就切回逐帧跟随（ROM 的 `startGestureAnimation` hook 转发，见 [onFreeformWindowMotion]）。
        onFreeformWindowMotion = { onMain { kick() } }
        RecentConversations.onChanged = {
            onMain {
                // Capture first, then render: HyperOS cancels an app's notifications the moment the
                // app is opened, so anything not remembered now would never reach the bar.
                recent.observe()
                refreshData(force = true)
            }
        }
        // Whatever is already posted when the module loads (install, SystemUI restart) counts as
        // recent conversations too.
        onMain { recent.observe() }
    }

    // ---------------------------------------------------------------- FreeformObserver

    override fun onFreeformTaskAppeared(taskInfo: Any) = onMain {
        rememberTask(taskInfo)
        if (task !== taskInfo) {
            task = taskInfo
            currentPkg = null
            hideReason = null
            closingUntil = 0L
            unfocused = false
            log(
                Log.INFO,
                "CONTACT_BAR_TASK_APPEARED pkg=${FreeformTask.packageName(taskInfo)} " +
                    "mode=${FreeformTask.mode(taskInfo)} taskId=${FreeformTask.taskId(taskInfo)}",
            )
        }
        scheduleFrame()
    }

    /**
     * The window is going away (swipe-up dismiss / ✕ / maximize): remove the bar now and stay hidden
     * until either the task is gone or [CLOSE_LATCH_MS] passes — the latter so an aborted transition
     * cannot leave the bar permanently hidden.
     */
    override fun onFreeformTaskClosing(taskId: Int) = onMain {
        val current = task ?: return@onMain
        if (FreeformTask.taskId(current) != taskId) return@onMain
        log(Log.INFO, "CONTACT_BAR_TASK_CLOSING taskId=$taskId")
        closingUntil = SystemClock.uptimeMillis() + CLOSE_LATCH_MS
        detach("CLOSING")
    }

    /**
     * Focus moved to a freeform window: **the bar belongs to the focused one**, so switch to it if we
     * know it, otherwise hide.
     *
     * This is the fix for using two 小窗 at once: tapping the other window took the bar away (correct),
     * and nothing else ever reported the tap that comes back — a plain tap is neither a move nor a
     * resize gesture, so only this event announces it.
     */
    override fun onFreeformTaskToFront(taskId: Int) = onMain {
        val known = taskById[taskId]
        val current = task
        if (known != null && known !== current) {
            log(
                Log.INFO,
                "CONTACT_BAR_FOCUS_SWITCH taskId=$taskId pkg=${FreeformTask.packageName(known)} " +
                    "from=${current?.let { FreeformTask.taskId(it) }}",
            )
            task = known
            currentPkg = null
            hideReason = null
            closingUntil = 0L
            unfocused = false
            fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
            scheduleFrame()
            return@onMain
        }
        if (current != null && FreeformTask.taskId(current) == taskId) {
            if (unfocused) {
                log(Log.INFO, "CONTACT_BAR_REGAINED_FOCUS taskId=$taskId")
                unfocused = false
                hideReason = null
                fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
            }
            scheduleFrame()
            return@onMain
        }
        // Another window we never saw an event for holds the focus: hide until it tells us more.
        if (!unfocused) {
            log(Log.INFO, "CONTACT_BAR_LOST_FOCUS taskId=$taskId")
            unfocused = true
        }
        detach("UNFOCUSED")
    }

    /**
     * A resize/move gesture on a window: re-target if it is a different task, and track every frame
     * for the duration of the drag (the gesture is what `宽度实时一致` hinges on).
     */
    override fun onFreeformTaskFocused(taskInfo: Any) = onMain {
        rememberTask(taskInfo)
        if (task !== taskInfo) {
            task = taskInfo
            currentPkg = null
            unfocused = false
            log(
                Log.INFO,
                "CONTACT_BAR_TASK_FOCUSED pkg=${FreeformTask.packageName(taskInfo)} " +
                    "mode=${FreeformTask.mode(taskInfo)} taskId=${FreeformTask.taskId(taskInfo)}",
            )
        }
        fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
        scheduleFrame()
    }

    override fun onFreeformTaskVanished(taskId: Int) = onMain {
        taskById.remove(taskId)
        val current = task ?: return@onMain
        if (FreeformTask.taskId(current) == taskId) {
            log(Log.INFO, "CONTACT_BAR_TASK_VANISHED taskId=$taskId")
            task = null
            unfocused = false
            detach("TASK_VANISHED")
        }
    }

    /** Remember the objects behind task ids, so a bare "this task came to front" can be resolved. */
    private fun rememberTask(taskInfo: Any) {
        val id = FreeformTask.taskId(taskInfo)
        if (id <= 0) return
        if (taskById.size > MAX_TRACKED_TASKS) taskById.clear()
        taskById[id] = taskInfo
    }

    override fun onFreeformTaskModeChanged(taskInfo: Any, oldMode: Int, newMode: Int) = onMain {
        rememberTask(taskInfo)
        if (task !== taskInfo) task = taskInfo
        log(Log.INFO, "CONTACT_BAR_MODE_CHANGED old=$oldMode new=$newMode")
        // 0 = 普通小窗 (bar visible), 1/2/3 = 迷你/贴边 (bar must go away).
        if (newMode != FreeformTask.MODE_NORMAL) detach("MODE_$newMode") else hideReason = null
        scheduleFrame()
    }

    // ---------------------------------------------------------------- frame loop

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        onMain {
            val choreographer = mainChoreographer ?: Choreographer.getInstance().also { mainChoreographer = it }
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Defensive: a callback must never touch views off the main thread.
            scheduleFrame()
            return@FrameCallback
        }
        onFrame()
        scheduleNext()
    }

    /**
     * 下一次什么时候再看：窗口在动（或刚动过 [FAST_WINDOW_MS] 内）就下一帧 —— 贴边跟随必须逐帧；
     * 否则退到 [IDLE_TICK_MS] 的定时器。
     *
     * 为什么要退：（2026-10-11 性能复查）以前只要有一个普通小窗存在，Choreographer 回调就**按帧常驻**
     * —— 60 次/秒的主线程唤醒，空闲时也照样反射读状态、每 700ms 写一行模块日志（主线程文件 I/O），
     * 而这些工作在没有移动的窗口上得到的是同一个答案。现在静止时 4 次/秒，动起来仍然逐帧。
     */
    private fun scheduleNext() {
        if (task == null || !enabled()) return
        if (SystemClock.uptimeMillis() < fastUntil) {
            scheduleFrame()
        } else {
            if (!idleMode) {
                idleMode = true
                log(Log.INFO, "CONTACT_BAR_LOOP idle (${IDLE_TICK_MS}ms sampling)")
            }
            scheduleIdleTick()
        }
    }

    private fun scheduleIdleTick() {
        if (idleScheduled) return
        idleScheduled = true
        main.postDelayed(idleTick, IDLE_TICK_MS)
    }

    private val idleTick = Runnable {
        idleScheduled = false
        onFrame()
        scheduleNext()
    }

    /**
     * 「窗口正在动」的事件入口 —— 由 `FlymeFreeformController` 的
     * `MiuiFreeformModeAnimation.startGestureAnimation` hook 转发（ROM 每个 move/resize 都经过它）。
     * 事件比定时器快：拖动一开始就跟上，不必等下一次 tick。
     */
    private fun kick() {
        if (task == null || !enabled()) return
        if (idleMode) {
            idleMode = false
            log(Log.INFO, "CONTACT_BAR_LOOP per-frame (window moving)")
        }
        fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
        scheduleFrame()
    }

    private fun onFrame() {
        if (!enabled()) {
            if (added) detach("DISABLED")
            return
        }
        val info = task ?: return
        frames++

        // A resize/move drag registers an animation (MiuiFreeformModeResizeHandler pushes every
        // move into `startGestureAnimation`), which is exactly when the bar must track per frame.
        // 空闲时不再逐帧醒来，而是由 [scheduleNext] 退到定时器 + [kick] 的事件唤醒。
        val animating = FreeformTask.isAnimating(info)
        val now = SystemClock.uptimeMillis()
        if (animating) fastUntil = now + FAST_WINDOW_MS

        // Closing: hide immediately and stay hidden while the window animates away (the user's
        // "关闭小窗动画期间不要跟随" rule). Two sources: the exit hook and the task's own EXITING state.
        if (now < closingUntil) {
            detach("CLOSING")
            return
        }
        if (FreeformTask.isExiting(info)) {
            closingUntil = now + CLOSE_LATCH_MS
            detach("EXITING")
            return
        }

        // Another 小窗 owns the focus: this window's bar stays away until it is brought back.
        if (unfocused) {
            detach("UNFOCUSED")
            return
        }

        // Mode gate: this is the user's "迷你小窗不应显示" rule and it also covers the pinned
        // states, where the window is half off-screen.
        if (!FreeformTask.isNormal(info)) {
            detach(if (FreeformTask.isMini(info)) "MINI" else "MODE_${FreeformTask.mode(info)}")
            return
        }
        if (!FreeformTask.isVisible(info)) {
            detach("NOT_VISIBLE")
            return
        }

        val bounds = FreeformTask.visualBounds(info)
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            detach("NO_BOUNDS")
            return
        }

        // Size gate: a window dragged down to a thumbnail has no room for a name row (user setting,
        // 0 disables it). Expressed against the screen width, which is what the slider shows.
        val minPct = minWidthPct()
        if (minPct > 0) {
            val screenWidth = wm.currentWindowMetrics.bounds.width()
            if (bounds.width() * 100 < minPct * screenWidth) {
                // Stable reason so a drag through the threshold logs once, not per percentage point.
                detach("TOO_SMALL")
                return
            }
        }

        // The window's package can change inside the same task (小窗切换到另一个会话/应用).
        val pkg = FreeformTask.packageName(info)
        if (pkg != null && pkg != currentPkg) {
            currentPkg = pkg
            refreshData(force = true)
        } else if (frames % DATA_EVERY_FRAMES == 0) {
            refreshData(force = false)
        }

        if (items.isEmpty()) {
            // [refreshData] 已经知道原因（不是聊天应用 / 没有会话）时不重复报一个笼统的 NO_CONTACTS：
            // 以前这两行在同一个 pass 里一前一后地打，看上去就是"每帧都在隐藏又隐藏"。
            detach(lastDataReason ?: "NO_CONTACTS")
            return
        }

        val rect = layoutFor(bounds)
        if (rect == null) {
            // No room that avoids the keyboard — the window already fills what is left above it.
            detach("IME_NO_ROOM")
            return
        }
        if (!added) {
            attach(info, rect)
        } else if (rect != lastRect) {
            update(info, rect)
        }
        lastRect = rect
        logGeometry(info, bounds, rect)
    }

    /**
     * Window rect for the bar: same width as the small window, hugging its bottom edge — and never
     * over the keyboard.
     *
     * The IME is why this returns null: Flyme's bar records the keyboard state and ignores it
     * (`C2831I.f10206B` is write-only), because there the ROM moves the window out of the way. On
     * HyperOS the window can stay put, so when the space under the window is inside the keyboard the
     * bar moves above the *window* instead; if even that would sit behind the keyboard, it stays
     * hidden rather than covering the keys. Returns null in that case.
     */
    private fun layoutFor(bounds: Rect): Rect? {
        val metrics = wm.currentWindowMetrics
        val screenH = metrics.bounds.height()
        val screenW = metrics.bounds.width()
        val systemBars = runCatching {
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        }.getOrDefault(android.graphics.Insets.NONE)
        val navBottom = systemBars.bottom
        val statusTop = systemBars.top

        // Keyboard top edge: everything below it must stay clear.
        val imeTop = if (FlymeFreeformController.isImeShowing()) {
            (screenH - FlymeFreeformController.imeHeightPx()).coerceAtLeast(statusTop)
        } else {
            0
        }
        val limitBottom = if (imeTop > 0) minOf(screenH - navBottom, imeTop) else screenH - navBottom

        // The bar follows the window's width (Flyme parity). A 小窗 is never wider than the display —
        // the scale hook clamps it even in 横屏 — but the coerce keeps the bar on screen regardless.
        val width = bounds.width().coerceIn(dp(90), screenW)
        var x = bounds.left
        if (x + width > screenW) x = screenW - width
        if (x < 0) x = 0

        val height = barHeight()
        val gap = dp(GAP_DP)
        val below = bounds.bottom + gap
        val above = bounds.top - height - gap
        val y = when {
            below + height <= limitBottom -> below
            above >= statusTop && above + height <= limitBottom -> above
            else -> return null
        }
        return Rect(x, y, x + width, y + height)
    }

    private fun barHeight(): Int = ensureView().barHeightPx()

    private fun ensureView(): ContactBarView = view ?: ContactBarView(host).also {
        // 6dp vertical padding keeps the 36dp avatar + name row inside the 63dp bar.
        it.setPaddingDp(10, 6)
        view = it
    }

    // ---------------------------------------------------------------- window plumbing

    private fun attach(info: Any, rect: Rect) {
        val v = ensureView()
        applyCorner(v, info)
        v.bind(items, fallbackIcon(), ::openConversation, ::onRemoveConversation)
        val shownType = runCatching {
            wm.addView(v, params(rect, barWindowType))
            barWindowType
        }.recoverCatching { refusal ->
            // ROM 不认 APPLICATION_OVERLAY（需要 SYSTEM_ALERT_WINDOW 的 app-op；SystemUI 的 uid 上没被
            // 显式 allow 过的话就会被拒）—— 那就退回原来的类型 + trusted：图层回到老样子，但条还在。
            // 宁可"又压住通知"，也不能让联系人条整个消失。
            logger(Log.WARN, "CONTACT_BAR_ADD_RETRY type=$barWindowType", refusal)
            val fallback = CornerInputMonitor.CORNER_WINDOW_TYPE
            wm.addView(v, params(rect, fallback, trusted = true))
            fallback
        }.onFailure {
            logger(Log.WARN, "CONTACT_BAR_ADD_FAILED rect=$rect", it)
        }.getOrNull() ?: return
        added = true
        hideReason = null
        v.fadeIn()
        attachedType = shownType
        attachedTrusted = shownType != barWindowType
        log(
            Log.INFO,
            "CONTACT_BAR_SHOW rect=$rect items=${items.size} pkg=$currentPkg type=$shownType",
        )
    }

    private fun update(info: Any, rect: Rect) {
        val v = view ?: return
        // 用**当前真正生效的**类型更新（可能是退回后的 2024 + trusted）：换类型会让 updateViewLayout 抛异常。
        runCatching { wm.updateViewLayout(v, params(rect, attachedType, attachedTrusted)) }
            .onFailure { logger(Log.WARN, "CONTACT_BAR_UPDATE_FAILED", it) }
        applyCorner(v, info)
        // 以前这里还要"宽度变了就按新容量重绑一次"（宽了多显示几个）。条改成可左右滑动之后，
        // 哪些头像可见由手势决定，窗口变化只影响窗口本身 —— [attach] 时绑的那份列表继续用。
    }

    /**
     * Corner radius of the window itself (`MiuiFreeformModeTaskInfo.getCornerRadius()`, screen px), so
     * the bar's corners match the 小窗 instead of looking like a pill. 13dp when the ROM stays silent.
     */
    private fun applyCorner(view: ContactBarView, info: Any) {
        val radius = FreeformTask.cornerRadius(info) ?: return
        if (radius == cornerShown) return
        cornerShown = radius
        view.setCornerRadius(radius)
    }

    /**
     * Remove the overlay from the window manager. Deliberately keeps [items] and [currentPkg]: a
     * mini window that is restored to a normal one, or a bar hidden by a transient state, must come
     * back instantly instead of waiting for the next notification refresh.
     */
    private fun detach(reason: String) {
        view?.let { v ->
            view = null
            runCatching { wm.removeViewImmediate(v) }
        }
        if (added || hideReason != reason) {
            log(Log.INFO, "CONTACT_BAR_HIDE reason=$reason")
        }
        added = false
        lastRect = null
        hideReason = reason
    }

    /**
     * 联系人条的窗口类型 —— **故意不是** `CornerInputMonitor.CORNER_WINDOW_TYPE`（2024 / trusted）。
     *
     * 用户 2026-10-12：「还是想办法调低图层吧，通知中心不显示了，控制中心还是显示」。
     * 设备实测（`dumpsys window windows` 的 `mBaseLayer`，控制中心打开时）：
     *
     * ```
     * 281000 NAVIGATION_BAR_PANEL  bubble contact bar      ← 原来的条（2024 + setTrustedOverlay）
     * 251000 NAVIGATION_BAR_PANEL  bubble slide ime probe  ← 同一个类型，没有 trusted
     * 241000 SYSTEM_OVERLAY        AntiMistakeTouchView
     * 231000 STATUS_BAR_SUB_PANEL  NotificationModalWindowManager
     * 181000 NOTIFICATION_SHADE    NotificationShade      ← 通知中心
     * 171000 STATUS_BAR            StatusBar
     * 151000 APPLICATION_OVERLAY   ShellDropTarget        ← 同一个进程里的 APPLICATION_OVERLAY
     * 131000 BASE_APPLICATION      QQ（小窗）
     * ```
     *
     * 也就是说：`setTrustedOverlay` 会把窗口提到窗帘**之上**（281000 vs 同类型的 251000），而
     * `TYPE_APPLICATION_OVERLAY` 落在 **151000** —— 正好在通知中心（181000）、控制中心那几个面板
     * （231000/241000）和导航栏之下，又在小窗（131000）与输入法（111000）之上：**该盖住它的都盖得住，
     * 该被它盖住的还盖得住**。所以条改成这个类型，并且不再设 trusted（Panel 那套留着手势探针与扇面用）。
     */
    private val barWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** 实际生效的类型（`APPLICATION_OVERLAY` 被拒时退回 2024 + trusted，见 [attach]）。 */
    private var attachedType = barWindowType

    private var attachedTrusted = false

    private fun params(
        rect: Rect,
        type: Int,
        trusted: Boolean = false,
    ) = WindowManager.LayoutParams(
        rect.width(),
        rect.height(),
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        title = "bubble contact bar"
        windowAnimations = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        @Suppress("DEPRECATION")
        systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        runCatching { setFitInsetsTypes(0) }
        if (trusted) {
            runCatching {
                WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(this)
            }
        }
    }

    // ---------------------------------------------------------------- data

    /** Rebuilds the avatar row for the app that currently owns the small window (main thread only). */
    fun refreshData(force: Boolean) = onMain {
        val info = task ?: return@onMain
        val pkg = FreeformTask.packageName(info)
        if (pkg == null || pkg !in RecentConversations.IM_WHITELIST) {
            items = emptyList()
            lastDataReason = "NOT_IM_APP"
            detach("NOT_IM_APP")
            return@onMain
        }
        val list = recent.conversations(pkg, force)
        if (list == items) return@onMain
        lastDataReason = null
        items = list
        val v = view ?: return@onMain
        v.bind(items, fallbackIcon(), ::openConversation, ::onRemoveConversation)
        log(
            Log.INFO,
            "CONTACT_BAR_DATA pkg=$pkg items=${items.size} live=${recent.lastLiveCount} " +
                "remembered=${(items.size - recent.lastLiveCount).coerceAtLeast(0)}",
        )
    }

    /**
     * Swipe-to-remove (Flyme: `ContactListView.C2929d.onSwiped` → `C2831I.m9363I`). The conversation is
     * dropped from the memory and suppressed until something newer arrives for that chat — and the bar
     * disappears when that was the last one.
     */
    private fun onRemoveConversation(conversation: Conversation) {
        recent.forget(conversation)
        val info = task
        val pkg = info?.let { FreeformTask.packageName(it) }
        items = if (pkg != null) recent.conversations(pkg, force = true) else emptyList()
        if (items.isEmpty()) {
            detach("EMPTY_AFTER_REMOVE")
            return
        }
        val v = view ?: return
        v.bind(items, fallbackIcon(), ::openConversation, ::onRemoveConversation)
    }

    /** Flyme falls back to the app icon when a notification carries no avatar. */
    private fun fallbackIcon() = runCatching {
        // 这张图同样必须复制（[ownIconCopy]）：`getApplicationIcon` 给的是 **ROM 图标缓存**里那张，
        // HyperOS 会在自己的时机回收它 —— 直接交给 ImageView 就是
        // `Canvas: trying to use a recycled bitmap`（`SlideIconView` 的注释里记着同一件事）。
        val pkg = currentPkg ?: return@runCatching null
        ownIconCopy(host, host.packageManager.getApplicationIcon(pkg))
    }.getOrNull()

    // ---------------------------------------------------------------- tap → conversation

    /**
     * Open the tapped conversation **inside the existing small window** — Flyme's
     * `C2831I.mo9405g` verbatim:
     *
     * ```java
     * Intent intent = c0653b.m3435c();          // the Intent behind the click, kept in the model
     * Bundle bundle = m9407t();                 // ActivityOptions.makeBasic().toBundle()
     * bundle.putBoolean("start_windowmode", true);
     * if (!m9364J(pi, intent, bundle))          // pi.send(ctx, 0, intent, null, null, null, bundle)
     *     AbstractC7062g.m24760j(ctx, intent, user);  // startActivityAsUser(intent, bundle, user)
     * ```
     *
     * Two details matter on HyperOS, and both were missing before:
     *  - the **Intent** is sent along (fill-in) and, more importantly, is kept as the fallback: opening
     *    an app cancels its notifications and WeChat cancels their PendingIntents with them, so
     *    `send()` starts throwing `CanceledException` for chats whose notification is gone — the Intent
     *    still starts fine. Without it the tap degraded to "open WeChat's main UI", which is the
     *    "sometimes it works, sometimes it doesn't" report.
     *  - the fallback keeps the **freeform options** (Flyme's bundle carries `start_windowmode`); a plain
     *    `send()` would open the chat fullscreen instead of in the window.
     *
     * MIUI spelling of the same intent: freeform `ActivityOptions` + `setLaunchTaskId` of the window we
     * are attached to (`MiuiCaptionClickListener.handleNewWindowClicked:239-243`).
     */
    private fun openConversation(conversation: Conversation) {
        val info = task
        val options = freeformOptions(conversation.pkg, info)
        val bundle = options?.toBundle()
        val pi = conversation.pendingIntent
        val intent = conversation.launchIntent?.let { android.content.Intent(it) }
        val taskId = FreeformTask.taskId(info)

        // 1. Flyme's primary path: the PendingIntent, with the stored Intent as fill-in + window mode.
        if (pi != null && bundle != null) {
            val sent = runCatching {
                pi.send(host, 0, intent, null, null, null, bundle)
                true
            }.getOrDefault(false)
            if (sent) {
                log(Log.INFO, "CONTACT_BAR_OPEN_PI pkg=${conversation.pkg} reuseTask=$taskId title=${conversation.title}")
                return
            }
        }

        // 2. Flyme's fallback: start the Intent itself. Survives a cancelled PendingIntent and still
        //    lands in the small window because the options/task id are kept.
        if (intent != null) {
            val started = startIntentAsUser(intent, bundle, conversation.userId)
            if (started) {
                log(
                    Log.INFO,
                    "CONTACT_BAR_OPEN_INTENT pkg=${conversation.pkg} reuseTask=$taskId " +
                        "cancelled=${pi != null} title=${conversation.title}",
                )
                return
            }
        }

        // 3. No stored Intent (older memory entry): plain send, at least it opens the chat.
        val plain = runCatching { pi?.send(); true }.getOrDefault(false)
        if (plain) {
            log(Log.INFO, "CONTACT_BAR_OPEN_PLAIN pkg=${conversation.pkg} title=${conversation.title}")
            return
        }

        val launched = openAppInCurrentWindow(conversation.pkg, options)
        logger(
            Log.WARN,
            "CONTACT_BAR_OPEN_FALLBACK pkg=${conversation.pkg} title=${conversation.title} " +
                "intent=${intent != null} pi=${pi != null} launched=$launched",
            null,
        )
    }

    /** `Context.startActivityAsUser(intent, bundle, user)` — Flyme's `AbstractC7062g.m24760j`. */
    private fun startIntentAsUser(intent: android.content.Intent, bundle: android.os.Bundle?, userId: Int): Boolean {
        val launchIntent = android.content.Intent(intent)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val user = userHandle(userId)
        if (user != null) {
            val asUser = runCatching {
                Context::class.java.getMethod(
                    "startActivityAsUser",
                    android.content.Intent::class.java,
                    android.os.Bundle::class.java,
                    android.os.UserHandle::class.java,
                ).invoke(host, launchIntent, bundle, user)
                true
            }.getOrDefault(false)
            if (asUser) return true
        }
        return runCatching {
            if (bundle != null) host.startActivity(launchIntent, bundle) else host.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    /** `UserHandle.of(id)` is hidden; user 0 / unknown falls back to this process's own handle. */
    private fun userHandle(userId: Int): android.os.UserHandle? {
        if (userId <= 0) return android.os.Process.myUserHandle()
        return runCatching {
            android.os.UserHandle::class.java
                .getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, userId) as android.os.UserHandle
        }.getOrNull() ?: android.os.Process.myUserHandle()
    }

    /** MIUI freeform launch options pinned to the window we are attached to. */
    private fun freeformOptions(pkg: String, info: Any?): ActivityOptions? {        val options = runCatching { MiuiFreeform.activityOptions(host, pkg, noCheck = true) }.getOrNull()
            ?: runCatching { MiuiFreeform.makeActivityOptions(host, pkg) }.getOrNull()
            ?: return null
        val taskId = FreeformTask.taskId(info)
        if (taskId > 0) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                    .invoke(options, taskId)
            }
        }
        return runCatching { MiuiFreeform.withoutFreeformAnimation(options) }.getOrDefault(options)
    }

    private fun openAppInCurrentWindow(pkg: String, options: ActivityOptions?): Boolean = runCatching {
        val intent = host.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        if (options != null) host.startActivity(intent, options.toBundle()) else host.startActivity(intent)
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- lifecycle

    private fun enabled(): Boolean = runCatching { RemotePrefs.read(prefs).contactBar }.getOrDefault(false)

    /** Hide-threshold in percent of the screen width; 0 disables the size gate entirely. */
    private fun minWidthPct(): Int = runCatching { RemotePrefs.read(prefs).contactBarMinWidthPct }.getOrDefault(0)

    private fun logGeometry(info: Any, bounds: Rect, rect: Rect) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLogAt < GEOMETRY_LOG_MS) return
        lastLogAt = now
        log(
            Log.INFO,
            "CONTACT_BAR_GEOM mode=${FreeformTask.mode(info)} visual=$bounds bar=$rect " +
                "scale=${runCatching { info.javaClass.getMethod("getFreeformScale").invoke(info) }.getOrNull()} " +
                "animating=${FreeformTask.isAnimating(info)} items=${items.size}",
        )
    }

    fun dispose() = onMain {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        if (RecentConversations.onChanged != null) RecentConversations.onChanged = null
        main.removeCallbacks(idleTick)
        idleScheduled = false
        onFreeformWindowMotion = null
        detach("DISPOSE")
        task = null
        // Also unregister our notification listener: a hot reload replaces our classes but not the
        // objects the previous generation registered inside SystemUI.
        runCatching { RecentConversations.detachListener() }
        log(Log.INFO, "CONTACT_BAR_DISPOSED")
    }

    private fun dp(value: Int): Int =
        (value * host.resources.displayMetrics.density).toInt()

    private companion object {
        /** Gap between the window's bottom edge and the bar (Flyme: 10dp margin, 63dp bar). */
        const val GAP_DP = 6

        /**
         * How long the bar stays hidden after the close gesture commits. The task only vanishes when
         * the closing animation finishes; the latch covers the gap, and expiring it means an aborted
         * transition cannot leave the bar hidden forever.
         */
        const val CLOSE_LATCH_MS = 1200L

        /** Notification data is re-read at most this often; callbacks refresh immediately. */
        const val DATA_EVERY_FRAMES = 40

        /** Geometry diagnostics are rate-limited so a resize drag cannot flood logcat. */
        const val GEOMETRY_LOG_MS = 700L

        /** How long after an animation ends the bar keeps tracking every single frame. */
        const val FAST_WINDOW_MS = 400L

        /**
         * 空闲时的采样间隔：窗口静止时几何不会变，几百毫秒看一眼就够 —— 这一条同时也是
         * "ROM 的移动事件 hook 没装上"时的兜底（[kick] 接到的才是逐帧路径）。
         */
        const val IDLE_TICK_MS = 250L

        /** At most this many freeform tasks are remembered for focus lookups (two is the ROM's cap). */
        const val MAX_TRACKED_TASKS = 4
    }
}

/**
 * 「窗口正在动」的事件入口 —— 由 `FlymeFreeformController` 的 `startGestureAnimation` hook 调用
 * （跑在 `wmshell.main` 上，所以实现里只做一次 post 回主线程），见 [ContactBarController.kick]。
 *
 * 联系人条用它把「逐帧跟随」限制在真正需要的那段时间里：空闲时按 `IDLE_TICK_MS` 采样，窗口一动立刻
 * 切回逐帧 —— 事件比定时器快，拖动的第一帧就跟得上（这是把常驻 Choreographer 回调换成定时器采样
 * 之后仍然不丢跟随的原因）。
 */
@Volatile
var onFreeformWindowMotion: (() -> Unit)? = null
