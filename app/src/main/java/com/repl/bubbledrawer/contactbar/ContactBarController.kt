package com.repl.bubbledrawer.contactbar

import android.app.ActivityOptions
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.repl.bubbledrawer.launch.FlymeFreeformController
import com.repl.bubbledrawer.launch.MiuiFreeform
import com.repl.bubbledrawer.xposed.RemotePrefs

/**
 * The floating contact bar under the small window: Flyme's
 * `com.flyme.systemuitools.windowmode.views.C2831I` ("WMNotificationView"), ported to HyperOS.
 *
 * 架构（2026-10-18 改）：
 *
 * | Flyme | 这里的实现 |
 * |---|---|
 * | 悬浮窗类型 2056 + `meizuFlags`，位置来自 `WindowManagerExt.getWindowModeBound(0,0,windowingMode)` | 挂在 `TaskDisplayArea` 下的**一块 SurfaceControl**（`RootTaskDisplayAreaOrganizer.attachToDisplayArea`），视图用 `SurfaceControlViewHost` 托管；Z 序 `setRelativeLayer(sc, 窗口 leash, +1)` |
 * | 每帧按 `dMin = live/origin bound` 重算几何 | 每帧用 `SurfaceControl.Transaction.setPosition()` 直接改硬件位置（不过 `system_server`） |
 * | `windowingMode == 11 \|\| 1035` 显示，没有会话就隐藏 | 普通小窗 (`mMode == 0`) 显示，迷你/贴边隐藏 |
 *
 * 为什么不再是 `WindowManager` 的窗口（用户 2026-10-18 让重构，原话「现在它作为一个视图插进小窗」）：
 * 以前每次 `addView` / `updateViewLayout` 都是一次同步 binder 到 `system_server` 的 WMS 重新摆窗口，
 * 比 WMShell 自己（同一个进程里的 Folme 动画）晚 1~2 帧 —— 拖动时条"甩"在窗口后面。现在位置和 Z 序
 * 都在本进程内改，与窗口同一帧生效。
 *
 * 两条用户要求仍然是这个循环存在的理由：
 *
 *  1. **跟手，且宽度始终 = 小窗宽度.** 所以几何每帧从 `MiuiFreeformModeTaskInfo` 现读现摆，
 *     不缓存、不插值。
 *  2. **迷你小窗不显示.** `mMode` 1（迷你）/3（迷你贴边）/2（普通贴边）都没有摆条的地方 ——
 *     整块 surface `hide()`，SurfaceFlinger 层面既不绘制也不收触摸。
 *
 * Z 序要特别注意（A19 那条用户反馈「下拉通知栏之后联系人条叠在通知上面」）：条跟着**窗口的 leash**
 * 排 Z（`setRelativeLayer`），而通知中心/控制中心是整个图层层级之上的窗口，所以条照旧"该被盖住时
 * 被盖住"，又永远在小窗之上（不会被窗口本身压住）。
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
    private var rootSurface: SurfaceControl? = null
    private var viewHost: SurfaceControlViewHost? = null
    private var currentLeash: SurfaceControl? = null

    /**
     * 上一次建立 surface/视图失败的时刻。失败后**不能每帧重试**：拖一个小窗就是 60 次/秒的
     * 反射建 surface + 60 行日志，而失败原因（ROM 改动、显示区没准备好）不会在 16ms 内变。
     */
    private var attachFailedAt = 0L

    private val tx = SurfaceControl.Transaction()

    /** `SurfaceControl.Transaction` 里还没进公开 SDK 的那几个，走反射并只解析一次。 */
    private val txSetTrustedOverlay by lazy { txMethod("setTrustedOverlay", SurfaceControl::class.java, java.lang.Boolean.TYPE) }
    private val txSetRelativeLayer by lazy { txMethod("setRelativeLayer", SurfaceControl::class.java, SurfaceControl::class.java, java.lang.Integer.TYPE) }
    private val txRemove by lazy { txMethod("remove", SurfaceControl::class.java) }

    private fun txMethod(name: String, vararg params: Class<*>): java.lang.reflect.Method? =
        runCatching { SurfaceControl.Transaction::class.java.getMethod(name, *params) }.getOrNull()

    /** 反射调一次；void 方法的 `invoke` 返回 null，所以不能用返回值判断成功。 */
    private fun txCall(method: java.lang.reflect.Method?, vararg args: Any?): Boolean {
        method ?: return false
        return runCatching { method.invoke(tx, *args); true }.getOrDefault(false)
    }

    /**
     * `RootTaskDisplayAreaOrganizer.attachToDisplayArea(int, SurfaceControl.Builder)`。
     * 故意**不做缓存**：`getRootTaskDisplayAreaOrganizer()` 在控制器 hook 装好之前会返回 null，
     * 缓存成 lazy 的话那次 null 就是永久的（条再也不出现）。
     */
    private fun attachMethodFor(organizer: Any): java.lang.reflect.Method? = runCatching {
        organizer.javaClass.getMethod(
            "attachToDisplayArea",
            Int::class.javaPrimitiveType,
            SurfaceControl.Builder::class.java,
        ).apply { isAccessible = true }
    }.getOrNull()

    /** 视图宿主的两个隐藏构造器 —— 探测一次就记住（失败路径每 [ATTACH_RETRY_MS] 会再走一遍）。 */
    private var wwmConstructor: java.lang.reflect.Constructor<*>? = null
    private var scvhConstructor: java.lang.reflect.Constructor<*>? = null

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
        // 自检：视图宿主的构造器能不能探测到（探测太窄的那次就是条整个不显示，见 resolveViewHostConstructor）。
        onMain { runCatching { logViewHostProbe() } }
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
        onFrame()
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
            destroySurface()
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
        onFrame()
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
            // 失败退避（见 [attachFailedAt]）：拖动中每帧重试 = 60 次/秒的反射建 surface。
            if (SystemClock.uptimeMillis() - attachFailedAt >= ATTACH_RETRY_MS) attach(info, rect)
        } else if (rect != lastRect) {
            update(info, rect)
        } else {
            checkLeash(info)
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

    // ---------------------------------------------------------------- surface plumbing
    //
    // 联系人条**不是**窗口：一块挂在 TaskDisplayArea 下的 SurfaceControl + 一个
    // SurfaceControlViewHost 托管的视图（见类注释）。这里每一条路径都必须自己扛住异常 ——
    // 它们全在主线程的 Choreographer 回调和输入回调里，抛出去就是系统界面进程崩掉。

    /** 显示区/显示取不到时返回 null（绝不 `error()`：那是主线程崩溃）。 */
    private fun displayOrNull(): Display? {
        val dm = runCatching {
            host.getSystemService(android.hardware.display.DisplayManager::class.java)
        }.getOrNull()
        return runCatching { dm?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
            ?: runCatching { host.display }.getOrNull()
            ?: runCatching { dm?.displays?.firstOrNull() }.getOrNull()
    }

    /**
     * 建立（或复用）条的那块 surface：`RootTaskDisplayAreaOrganizer.attachToDisplayArea(displayId,
     * builder)` 把它挂到显示区下 —— 和黑色遮罩（`FreeformBackgroundDecoration`）同一套机制，
     * 所以在 HyperOS 上是验证过的路径。
     */
    private fun ensureSurface(taskId: Int): SurfaceControl? {
        rootSurface?.let {
            if (it.isValid) return it
            // 失效了（SurfaceFlinger 重启、显示区重建、旋转…）—— 后面所有 transaction 都会抛
            // IllegalArgumentException，所以必须整块换新的，不能"先用着试试"。
            log(Log.WARN, "CONTACT_BAR_SURFACE_STALE taskId=$taskId")
            destroySurface()
        }
        val organizer = FlymeFreeformController.getRootTaskDisplayAreaOrganizer()
        if (organizer == null) {
            log(Log.WARN, "CONTACT_BAR_ORGANIZER_NULL")
            return null
        }
        val attachMethod = attachMethodFor(organizer) ?: run {
            log(Log.WARN, "CONTACT_BAR_ATTACH_METHOD_NULL")
            return null
        }
        val displayId = displayOrNull()?.displayId ?: Display.DEFAULT_DISPLAY
        val created = runCatching {
            val builder = SurfaceControl.Builder().setName("ContactBarSurface-$taskId")
            runCatching {
                SurfaceControl.Builder::class.java.getMethod("setContainerLayer").invoke(builder)
            }
            attachMethod.invoke(organizer, displayId, builder)
            builder.build()
        }.onFailure {
            logger(Log.WARN, "CONTACT_BAR_SURFACE_FAILED displayId=$displayId", it)
        }.getOrNull() ?: return null
        rootSurface = created
        log(Log.INFO, "CONTACT_BAR_SURFACE taskId=$taskId displayId=$displayId name=$created")
        return created
    }

    /**
     * `WindowlessWindowManager(Configuration, SurfaceControl, IBinder)` —— 视图的"窗口管理器"，
     * 它把 `setView` 进来的视图子 surface 挂到我们那块 surface 下（**不经过 system_server**）。
     * 构造签名按版本探测，结果缓存：这条路径失败时是每 [ATTACH_RETRY_MS] 试一次，
     * 不缓存的话每次都要遍历一遍构造器。
     */
    private fun createWindowlessWindowManager(config: Configuration, rootSurface: SurfaceControl): Any {
        val cls = Class.forName("android.view.WindowlessWindowManager")
        val ctor = wwmConstructor ?: run {
            val found = cls.declaredConstructors
                .filter { candidate ->
                    val types = candidate.parameterTypes
                    types.size >= 2 &&
                        types[0].isAssignableFrom(config.javaClass) &&
                        types[1].isAssignableFrom(rootSurface.javaClass) &&
                        types.drop(2).all { !it.isPrimitive }
                }
                .minByOrNull { it.parameterCount }
            if (found == null) {
                error("No WindowlessWindowManager(Configuration, SurfaceControl, …) in ${cls.declaredConstructors.toList()}")
            }
            runCatching { found.isAccessible = true }
            found.also { wwmConstructor = it }
        }
        val args = arrayOfNulls<Any>(ctor.parameterCount)
        args[0] = config
        args[1] = rootSurface
        return ctor.newInstance(*args)
    }

    /**
     * 视图宿主。构造签名各版本不一样（这台 ROM 上是
     * `SurfaceControlViewHost(Context, Display, WindowlessWindowManager, String)`，和迷你小窗长按菜单
     * 用的同一个），所以按"前三个参数对得上"来挑，剩下的参数按类型给默认值 —— 但**不能**只认
     * `boolean`：那会把唯一存在的那个四参构造器筛掉，条就永远建不起来（2026-10-18 用户实测：
     * 日志里只有 `CONTACT_BAR_VIEW_HOST_FAILED`，条不显示）。
     */
    private fun createSurfaceControlViewHost(context: Context, display: Display, wwm: Any): SurfaceControlViewHost {
        val ctor = resolveViewHostConstructor(context, display, wwm.javaClass)
        val args = arrayOfNulls<Any>(ctor.parameterCount)
        args[0] = context
        args[1] = display
        args[2] = wwm
        for (index in 3 until ctor.parameterCount) {
            args[index] = when {
                ctor.parameterTypes[index] == java.lang.Boolean.TYPE -> false
                ctor.parameterTypes[index] == java.lang.Integer.TYPE -> 0
                ctor.parameterTypes[index] == String::class.java -> "ContactBar"
                else -> null
            }
        }
        return ctor.newInstance(*args) as SurfaceControlViewHost
    }

    /**
     * 就地挑出能用的那个构造器（结果缓存）。**探测规则必须宽松**：这台 ROM 上只有四参那个
     * （第四个参数是 String），三参的公开构造器不存在 —— 收窄过一次就是"条永远不出现"。
     */
    private fun resolveViewHostConstructor(
        context: Context,
        display: Display,
        wwmClass: Class<*>,
    ): java.lang.reflect.Constructor<*> {
        scvhConstructor?.let { return it }
        val found = SurfaceControlViewHost::class.java.declaredConstructors
            .filter { candidate ->
                val types = candidate.parameterTypes
                types.size >= 3 &&
                    types[0].isAssignableFrom(context.javaClass) &&
                    types[1].isAssignableFrom(display.javaClass) &&
                    types[2].isAssignableFrom(wwmClass) &&
                    types.drop(3).all { !it.isPrimitive || it == java.lang.Boolean.TYPE }
            }
            // 参数最少的最"正统"：三参 > 四参（多出来的那个是名字/调试开关）。
            .minByOrNull { it.parameterCount }
        if (found == null) {
            error(
                "No SurfaceControlViewHost(Context, Display, WindowlessWindowManager, …) in " +
                    SurfaceControlViewHost::class.java.declaredConstructors.toList(),
            )
        }
        runCatching { found.isAccessible = true }
        scvhConstructor = found
        return found
    }

    /** 加载时的一次性自检：构造器探测有没有结果（这版 ROM 上曾经因为探测太窄而整条不显示）。 */
    private fun logViewHostProbe() {
        val display = displayOrNull() ?: return
        val wwmClass = runCatching { Class.forName("android.view.WindowlessWindowManager") }.getOrNull()
            ?: return
        runCatching {
            val ctor = resolveViewHostConstructor(host, display, wwmClass)
            log(
                Log.INFO,
                "CONTACT_BAR_VIEW_HOST_CTOR ok=true params=" +
                    ctor.parameterTypes.joinToString { it.simpleName },
            )
        }.onFailure {
            logger(Log.WARN, "CONTACT_BAR_VIEW_HOST_CTOR ok=false", it)
        }
    }

    /** 视图的"窗口参数"。`token` 必须留空：WWM 不是 `system_server`，随机 token 只会让每个尺寸变化都变成一个新窗口。 */
    private fun viewLayoutParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        title = "bubble contact bar"
        windowAnimations = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        @Suppress("DEPRECATION")
        systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        runCatching { setFitInsetsTypes(0) }
        // 条在窗口 Z 序之外（挂在显示区下），HyperOS 的模糊/合成用它判断"可以盖在别人上面"。
        runCatching {
            WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(this)
        }
    }

    /**
     * 把视图交给 host（首次）。三步台阶，和迷你小窗长按菜单同一套（那套在这台 ROM 上验证过）：
     * `setView(View, WindowManager.LayoutParams)` → 自定义 `LayoutParams` 包装 → `setView(View, int, int)`。
     * 全失败返回 false，调用方负责拆掉半成品。
     */
    private fun bindViewToHost(vh: SurfaceControlViewHost, v: View, width: Int, height: Int): Boolean {
        val lp = viewLayoutParams(width, height)
        val byPublicParams = runCatching {
            SurfaceControlViewHost::class.java
                .getMethod("setView", View::class.java, WindowManager.LayoutParams::class.java)
                .invoke(vh, v, lp)
            true
        }.getOrDefault(false)
        if (byPublicParams) return true

        val twoParams = vh.javaClass.methods.firstOrNull {
            it.name == "setView" && it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(v.javaClass)
        }
        if (twoParams != null) {
            val paramType = twoParams.parameterTypes[1]
            val converted = runCatching {
                paramType.getConstructor(WindowManager.LayoutParams::class.java).newInstance(lp)
            }.getOrNull() ?: runCatching {
                paramType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .newInstance(width, height)
            }.getOrNull()
            if (converted != null) {
                val ok = runCatching { twoParams.invoke(vh, v, converted); true }.getOrDefault(false)
                if (ok) return true
            }
        }

        return runCatching { vh.setView(v, width, height); true }.getOrDefault(false)
    }

    /**
     * 改视图尺寸（小窗被拖动缩放时每帧可能都变）。先试 `relayout(int, int)`；有的版本没有它，
     * 就重新 `setView` 一次 —— 两条路都失败就让调用方整块重建，而不是留一个尺寸不对的条。
     */
    private fun resizeViewHost(vh: SurfaceControlViewHost?, v: View, width: Int, height: Int): Boolean {
        if (vh == null) return false
        if (runCatching { vh.relayout(width, height); true }.getOrDefault(false)) return true
        return bindViewToHost(vh, v, width, height)
    }

    /** `SurfaceControl.Transaction` 没有公开的 show/hide（旧实现里 `invoke` 返回 null，两条路都会跑）。 */
    private fun showSurface(sc: SurfaceControl) {
        tx.setVisibility(sc, true)
    }

    private fun hideSurface(sc: SurfaceControl) {
        tx.setVisibility(sc, false)
    }

    /**
     * 窗口的 leash —— Z 序的参照物。`getLeash()` 读不到时退到控制器那套（运行中的 task / `mLeash`）。
     * 每帧最多一次，访问器在 [FreeformTask] 里按类缓存。
     */
    private fun resolveLeash(info: Any): SurfaceControl? =
        FreeformTask.leash(info) ?: FlymeFreeformController.instance?.getTaskLeash(info)

    /**
     * 把条摆到 [rect] 并让它可见。失败时**拆掉整块 surface** 并记下 [attachFailedAt]
     * （下一轮 [ATTACH_RETRY_MS] 后再试），绝不留下"看起来加了、其实什么都没显示"的状态 ——
     * 那会让条永远不再出现（以前 `added` 就是这么被置上的）。
     */
    private fun attach(info: Any, rect: Rect) {
        val taskId = FreeformTask.taskId(info)
        val sc = ensureSurface(taskId) ?: return failed("SURFACE_UNAVAILABLE")
        val display = displayOrNull() ?: return failed("NO_DISPLAY")
        val v = ensureView()
        applyCorner(v, info)
        v.bind(items, fallbackIcon(), ::openConversation, ::onRemoveConversation)

        if (viewHost == null) {
            val created = runCatching {
                val wwm = createWindowlessWindowManager(host.resources.configuration, sc)
                val vh = createSurfaceControlViewHost(host, display, wwm)
                if (!bindViewToHost(vh, v, rect.width(), rect.height())) {
                    runCatching { vh.release() }
                    error("setView failed")
                }
                vh
            }.onFailure {
                logger(Log.WARN, "CONTACT_BAR_VIEW_HOST_FAILED taskId=$taskId", it)
            }.getOrNull() ?: return failed("VIEW_HOST_UNAVAILABLE")
            viewHost = created
        } else if (!resizeViewHost(viewHost, v, rect.width(), rect.height())) {
            return failed("RELAYOUT_FAILED")
        }

        val leash = resolveLeash(info)
        val applied = runCatching {
            currentLeash = leash
            runCatching { txCall(txSetTrustedOverlay, sc, true) }
            if (leash != null && leash.isValid && txCall(txSetRelativeLayer, sc, leash, 1)) {
                // 相对窗口 leash 排 Z：通知中心/控制中心在更上层，照旧盖得住它（A19）。
            } else {
                currentLeash = null
                tx.setLayer(sc, LAYER_WITHOUT_LEASH)
            }
            tx.setPosition(sc, rect.left.toFloat(), rect.top.toFloat())
            tx.setCrop(sc, Rect(0, 0, rect.width(), rect.height()))
            showSurface(sc)
            tx.apply()
            true
        }.onFailure {
            logger(Log.WARN, "CONTACT_BAR_SHOW_FAILED rect=$rect", it)
        }.getOrDefault(false)
        if (!applied) return failed("TRANSACTION_FAILED")

        added = true
        attachFailedAt = 0L
        hideReason = null
        v.fadeIn()
        log(
            Log.INFO,
            "CONTACT_BAR_SHOW rect=$rect items=${items.size} pkg=$currentPkg taskId=$taskId " +
                "leash=${leash != null} relative=${currentLeash != null}",
        )
    }

    /** 失败收尾：拆掉半成品（下一次从零开始），并让下一帧不要立刻重试。 */
    private fun failed(reason: String) {
        attachFailedAt = SystemClock.uptimeMillis()
        if (rootSurface != null || viewHost != null) destroySurface()
        if (hideReason != reason) log(Log.WARN, "CONTACT_BAR_HIDE reason=$reason")
        added = false
        hideReason = reason
    }

    private fun update(info: Any, rect: Rect) {
        val sc = rootSurface
        if (sc == null || !sc.isValid) {
            // surface 没了：这不是"位置不对"，只能是整块重建（见 [ensureSurface]）。
            log(Log.WARN, "CONTACT_BAR_SURFACE_LOST")
            destroySurface()
            return
        }
        val v = view ?: return
        val old = lastRect
        val sizeChanged = old == null || old.width() != rect.width() || old.height() != rect.height()
        if (sizeChanged && !resizeViewHost(viewHost, v, rect.width(), rect.height())) {
            logger(Log.WARN, "CONTACT_BAR_RELAYOUT_FAILED rect=$rect", null)
            destroySurface()
            return
        }

        val leash = resolveLeash(info)
        val applied = runCatching {
            if (leash != null && leash.isValid && leash !== currentLeash &&
                txCall(txSetRelativeLayer, sc, leash, 1)
            ) {
                currentLeash = leash
            }
            if (sizeChanged) tx.setCrop(sc, Rect(0, 0, rect.width(), rect.height()))
            tx.setPosition(sc, rect.left.toFloat(), rect.top.toFloat())
            showSurface(sc)
            tx.apply()
            true
        }.onFailure {
            logger(Log.WARN, "CONTACT_BAR_UPDATE_FAILED rect=$rect", it)
        }.getOrDefault(false)
        if (!applied) {
            // 这一帧没摆对就别硬撑：拆掉，下一帧整块重建（拖一次也就多一次重建）。
            destroySurface()
            return
        }
        applyCorner(v, info)
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

    /** 位置没变也要看一眼：换窗口（两个小窗）时 Z 序的参照物跟着换。 */
    private fun checkLeash(info: Any) {
        val sc = rootSurface ?: return
        if (!sc.isValid) {
            destroySurface()
            return
        }
        val leash = resolveLeash(info) ?: return
        if (!leash.isValid || leash === currentLeash) return
        val applied = runCatching {
            if (!txCall(txSetRelativeLayer, sc, leash, 1)) return@runCatching false
            tx.apply()
            true
        }.getOrDefault(false)
        if (applied) currentLeash = leash
    }

    /**
     * Hide the contact bar surface. Deliberately keeps [items] and [currentPkg]: a
     * mini window that is restored to a normal one, or a bar hidden by a transient state, must come
     * back instantly instead of waiting for the next notification refresh.
     */
    private fun detach(reason: String) {
        val sc = rootSurface
        if (sc != null && sc.isValid) {
            runCatching {
                hideSurface(sc)
                tx.apply()
            }.onFailure { logger(Log.WARN, "CONTACT_BAR_HIDE_FAILED reason=$reason", it) }
        }
        if (added || hideReason != reason) {
            log(Log.INFO, "CONTACT_BAR_HIDE reason=$reason")
        }
        added = false
        lastRect = null
        hideReason = reason
    }

    /** 彻底拆掉 surface 与视图宿主（任务消失、热重载、重建失败时）。 */
    private fun destroySurface() {
        val vh = viewHost
        viewHost = null
        runCatching { vh?.release() }
            .onFailure { logger(Log.WARN, "CONTACT_BAR_HOST_RELEASE_FAILED", it) }

        val sc = rootSurface
        rootSurface = null
        if (sc != null && sc.isValid) {
            runCatching {
                if (!txCall(txRemove, sc)) tx.reparent(sc, null)
                tx.apply()
            }.onFailure { logger(Log.WARN, "CONTACT_BAR_SURFACE_REMOVE_FAILED", it) }
        }
        view = null
        added = false
        lastRect = null
        currentLeash = null
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
        log(
            Log.INFO,
            "CONTACT_BAR_DATA pkg=$pkg items=${items.size} live=${recent.lastLiveCount} " +
                "remembered=${(items.size - recent.lastLiveCount).coerceAtLeast(0)}",
        )
        val v = view
        if (v != null) {
            v.bind(items, fallbackIcon(), ::openConversation, ::onRemoveConversation)
        }
        scheduleFrame()
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
        destroySurface()
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

        /** 建立 surface/视图宿主失败后的重试间隔 —— 别每帧重试（见 [attachFailedAt]）。 */
        const val ATTACH_RETRY_MS = 500L

        /**
         * 拿不到窗口 leash 时的绝对 Z。显示区这一层的子图层里它排在所有任务之上，
         * 而通知中心/控制中心仍在整个图层层级之上 —— 退化路径也不会压住面板。
         */
        const val LAYER_WITHOUT_LEASH = 25000

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
