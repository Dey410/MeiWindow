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
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.launch.FlymeFreeformController
import com.repl.bubbledrawer.launch.MiuiFreeform
import com.repl.bubbledrawer.shortcut.AggregatePayment
import com.repl.bubbledrawer.shortcut.ShortcutRepository
import com.repl.bubbledrawer.xposed.ModuleResources
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

    /**
     * 聚合支付（QuickPay）模式：条脱离会话数据、贴在**聚合支付小窗**下方 —— Flyme 的
     * `QuickPayWindowHelper` 驱动的就是同一个 `C2831I`（`m9387K` 塞四个支付入口、
     * `m9058C(true)` 置 QuickPayWindowShowing）。触发时直接以小窗打开默认入口（微信扫一扫），
     * 小窗出现（Appeared/Focused 事件）后条像会话条一样跟随它；几何/可见性与会话模式共用
     * 同一条帧循环链，差别只在数据源（[bindPay] vs 会话 bind）。
     */
    private var payActive = false
    private var payEntries: List<AggregatePayment.PayAction> = emptyList()

    /** 当前支付小窗的 taskId（0 = 还没到位）。用于"窗内切换弹成新窗"时关掉旧的那扇。 */
    private var payTaskId = 0

    /**
     * 正在等待到位的支付小窗的包名。触发/切换入口时武装；`Appeared/Focused/ToFront` 里包名
     * 匹配的事件就是"我们打开的那扇窗"（采用它），不匹配的是用户自己开的窗（结束支付会话）。
     * [PAY_PENDING_TIMEOUT_MS] 内没等到就放弃会话（启动失败/落全屏的兜底）。
     */
    private var pendingPayPackage: String? = null
    private var pendingPayExpiry: Runnable? = null

    /** 换入口流程：旧窗关完（Vanished）后要发射的新条目；null = 没有在等的。 */
    private var pendingRelaunch: AggregatePayment.PayAction? = null
    private var pendingRelaunchFired = false
    private var pendingRelaunchFallback: Runnable? = null

    /** taskId → the `MiuiFreeformModeTaskInfo` we have seen for it (bounded, main thread only). */
    private val taskById = HashMap<Int, Any>()

    @Volatile
    private var task: Any? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RemotePrefs.KEY_CONTACT_BAR) {
            onMain {
                log(Log.INFO, "CONTACT_BAR_PREF_CHANGED enabled=${enabled()} thread=${Thread.currentThread().name}")
                // 聚合支付不受这个开关管（它是被显式点出来的，不是小窗的附属条）。
                if (!payActive && !enabled()) detach("PREF_OFF")
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
        if (payActive) {
            when {
                isPendingPay(taskInfo) -> {
                    // 我们打开的支付小窗到位了：条贴到它下面；旧支付窗口（换入口的场合）关掉。
                    adoptPayTask(taskInfo)
                }
                isSameAsAdoptedPayWindow(taskInfo) -> {
                    // 同一个支付 App 又冒出一扇窗：支付宝的 ShortcutsLauncherActivity 桥接会
                    // 先开一扇再让主页面开另一扇。ID 更大的那扇才是后落的真页面，接过来；
                    // 旧的那扇（ID 更小）忽略，绝不能因此结束支付会话（2026-10-24 用户：
                    // 「微信跳转到支付宝后下方的聚合支付条消失」）。
                    if (isNewerPayTask(taskInfo)) {
                        log(
                            Log.INFO,
                            "QUICK_PAY_SECOND_WINDOW pkg=${FreeformTask.packageName(taskInfo)} " +
                                "taskId=${FreeformTask.taskId(taskInfo)} old=$payTaskId",
                        )
                        adoptPayTask(taskInfo)
                    } else {
                        log(
                            Log.INFO,
                            "QUICK_PAY_IGNORE_OLD_WINDOW taskId=${FreeformTask.taskId(taskInfo)} " +
                                "current=$payTaskId",
                        )
                        return@onMain
                    }
                }
                pendingRelaunch != null || pendingPayPackage != null -> {
                    // 换窗空档里别的窗口（桌面、旧窗收尾）出现：等我们的新窗，别结束会话。
                    log(
                        Log.INFO,
                        "QUICK_PAY_IGNORE_APPEARED pkg=${FreeformTask.packageName(taskInfo)} " +
                            "taskId=${FreeformTask.taskId(taskInfo)}",
                    )
                    return@onMain
                }
                else -> {
                    // 用户自己开的无关小窗：支付会话让位，会话条按原有链路接管。
                    dismissQuickPay("WINDOW_APPEARED")
                }
            }
        }
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
        if (payActive) {
            if (pendingPayPackage != null) {
                // 换入口流程里关掉的旧支付窗口：预期之内，会话继续等新窗口。
                log(Log.INFO, "QUICK_PAY_OLD_CLOSING taskId=$taskId")
                return@onMain
            }
            // 支付宝桥接窗先关、真页面后落：把条交给同一 App 里 ID 更大的那扇窗。
            val handoff = newerPayWindow(taskId, current)
            if (handoff != null) {
                log(
                    Log.INFO,
                    "QUICK_PAY_HANDOFF closing=$taskId -> ${FreeformTask.taskId(handoff)}",
                )
                task = null
                adoptPayTask(handoff)
                scheduleFrame()
                return@onMain
            }
            // 支付小窗被用户关掉：会话结束，条收起。
            log(Log.INFO, "CONTACT_BAR_TASK_CLOSING taskId=$taskId (pay window)")
            dismissQuickPay("PAY_WINDOW_CLOSING")
            return@onMain
        }
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
        if (payActive && known != null && known !== task) {
            when {
                isPendingPay(known) -> adoptPayTask(known)
                isSameAsAdoptedPayWindow(known) -> {
                    if (!isNewerPayTask(known)) return@onMain
                    adoptPayTask(known)
                }
                pendingRelaunch != null || pendingPayPackage != null -> return@onMain
                else -> {
                    dismissQuickPay("WINDOW_TO_FRONT")
                    return@onMain
                }
            }
        }
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
        if (payActive && task !== taskInfo) {
            when {
                isPendingPay(taskInfo) -> adoptPayTask(taskInfo)
                isSameAsAdoptedPayWindow(taskInfo) -> {
                    if (!isNewerPayTask(taskInfo)) return@onMain
                    adoptPayTask(taskInfo)
                }
                pendingRelaunch != null || pendingPayPackage != null -> {
                    log(
                        Log.INFO,
                        "QUICK_PAY_IGNORE_FOCUSED pkg=${FreeformTask.packageName(taskInfo)} " +
                            "taskId=${FreeformTask.taskId(taskInfo)}",
                    )
                    return@onMain
                }
                else -> {
                    // 焦点移到无关窗口：结束支付会话，随后的正常流程把条交给那个窗口。
                    dismissQuickPay("WINDOW_FOCUSED_OTHER")
                }
            }
        }
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
            log(Log.INFO, "CONTACT_BAR_TASK_VANISHED taskId=$taskId pay=$payActive")
            val switching = payActive && pendingPayPackage != null
            val reason = when {
                switching -> "PAY_SWITCH"
                payActive -> "PAY_CLOSED"
                else -> "TASK_VANISHED"
            }
            if (payActive && !switching) {
                // 支付 App 的另一扇窗还在（支付宝桥接窗关掉、真页面已落）：交给它，别结束会话。
                val handoff = newerPayWindow(taskId, current)
                if (handoff != null) {
                    log(
                        Log.INFO,
                        "QUICK_PAY_HANDOFF vanished=$taskId -> ${FreeformTask.taskId(handoff)}",
                    )
                    task = null
                    adoptPayTask(handoff)
                    scheduleFrame()
                    return@onMain
                }
                // 支付小窗没了（用户关掉/崩溃）：会话结束。
                payActive = false
                payEntries = emptyList()
                payTaskId = 0
                clearPendingPay()
            }
            task = null
            unfocused = false
            detach(reason)
            destroySurface()
            // 换入口：旧窗彻底没了，给 SurfaceFlinger 一拍再发射新窗（太贴着关窗动画发射
            // 会被 ROM 吞掉——"关了但新窗不来"）。
            val relaunch = pendingRelaunch
            if (switching && relaunch != null && !pendingRelaunchFired) {
                main.postDelayed({ fireRelaunch(relaunch) }, RELAUNCH_DELAY_MS)
            }
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

    // ---------------------------------------------------------------- quick pay（聚合支付模式）

    /**
     * 触发聚合支付（Flyme `m9075w` 的等价物）：解析四个支付入口，**直接以小窗打开默认入口**
     * （微信扫一扫，用户 2026-10-24：「官方逻辑是打开一个扫一扫小窗，然后下面出现聚合支付条」），
     * 小窗到位后条贴到它下方。一个应用都没装/一个入口都解析不出来时提示后返回。
     */
    fun showAggregateQuickPay() = onMain {
        if (payActive) {
            log(Log.INFO, "QUICK_PAY_ALREADY_UP")
            return@onMain
        }
        val entries = AggregatePayment.resolveEntries(host)
        if (entries.isEmpty()) {
            log(Log.WARN, "QUICK_PAY_NO_ENTRIES")
            runCatching {
                android.widget.Toast.makeText(
                    ModuleResources.packageContext(host) ?: host,
                    R.string.aggregate_pay_unavailable,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            return@onMain
        }
        payActive = true
        payEntries = entries
        payTaskId = 0
        currentPkg = null
        hideReason = null
        closingUntil = 0L
        unfocused = false
        val first = entries.first()
        val firstPkg = first.launch?.packageName
        if (firstPkg == null) {
            payActive = false
            payEntries = emptyList()
            return@onMain
        }
        armPendingPay(firstPkg)
        FlymeFreeformController.suppressAvoidOffsetFor(PAY_OFFSET_SUPPRESS_MS)
        launchPayEntryInNewWindow(first, reuseTaskId = reuseTaskIdFor(firstPkg))
        scheduleNext()
        log(Log.INFO, "QUICK_PAY_SHOW entries=${entries.size} default=${first.label}")
    }

    /** 结束支付会话（用户关掉支付小窗 / 焦点移去别的窗口 / 启动迟迟不到位）。 */
    fun dismissQuickPay(reason: String) = onMain {
        if (!payActive) return@onMain
        payActive = false
        payEntries = emptyList()
        payTaskId = 0
        clearPendingPay()
        pendingRelaunch = null
        pendingRelaunchFired = false
        pendingRelaunchFallback?.let { main.removeCallbacks(it) }
        pendingRelaunchFallback = null
        FlymeFreeformController.avoidOffsetSuppressedUntil = 0L
        detach(reason)
        // 有小窗在的话立刻排一帧，把会话条摆回去，不留"付完款条没了"的空窗。
        if (task != null) scheduleFrame()
    }

    private fun onPayEntryClick(entry: AggregatePayment.PayAction) {
        val item = entry.launch
        if (item == null) {
            log(Log.WARN, "QUICK_PAY_CLICK_NO_LAUNCH label=${entry.label}")
            return
        }
        val info = task
        // 同应用换页：支付宝的深链可以在原任务里跳，**不关窗**；微信不行 —— 它的
        // ShortCutDispatchActivity 是 singleInstance 且只在 onCreate 分发，只能关掉旧页、
        // 重新拉一扇（同一位置的一扇窗，不会并排多开）。用户 2026-10-24：「同应用跳转
        // 不唤起新小窗」对支付宝成立，微信受它自己的分发 Activity 限制。
        if (info != null &&
            FreeformTask.packageName(info) == item.packageName &&
            entry.direct?.action == null
        ) {
            FlymeFreeformController.suppressAvoidOffsetFor(PAY_OFFSET_SUPPRESS_MS)
            val reuse = FreeformTask.taskId(info)
            val ok = launchPayEntryInNewWindow(entry, reuseTaskId = reuse)
            log(Log.INFO, "QUICK_PAY_SWITCH_IN_PLACE label=${entry.label} taskId=$reuse ok=$ok")
            if (ok) {
                scheduleFrame()
                return
            }
        }
        // 无论哪条路都先武装 pending：新小窗到位会被事件流采用（条跟着挪过去）；同应用复用
        // 启动被 ROM 吞掉时，超时后清 pending、会话原地保留。
        armPendingPay(item.packageName)
        // 换窗落位期间临时压掉 ROM 的错开（avoidAsPossible）——旧窗在关、新窗就位，错开会让
        // 支付小窗连同它下面的支付条一起挪到偏位上。
        FlymeFreeformController.suppressAvoidOffsetFor(PAY_OFFSET_SUPPRESS_MS)
        if (info == null) {
            launchPayEntryInNewWindow(entry)
            return
        }
        // 切换 = **关旧窗 → 等它关完 → 再开新窗**（用户 2026-10-24 指定的兜底语义）。不能连发：
        // close 是异步动画，紧跟着的启动会被 ROM 整个吞掉（"关了但新窗不来"，同轮实测）；
        // 直接开也会被同应用的既有 freeform 任务静默复用成"点了没反应"。
        pendingRelaunch = entry
        pendingRelaunchFired = false
        closePayTask(info)
        payTaskId = 0
        // 兜底：Vanished 迟迟不来（关窗被拒/动画卡住）也要把新窗发出去。
        pendingRelaunchFallback?.let { main.removeCallbacks(it) }
        val fallback = Runnable {
            pendingRelaunchFallback = null
            if (payActive && !pendingRelaunchFired && pendingRelaunch === entry) {
                log(Log.WARN, "QUICK_PAY_RELAUNCH_FALLBACK label=${entry.label}")
                fireRelaunch(entry)
            }
        }
        pendingRelaunchFallback = fallback
        main.postDelayed(fallback, RELAUNCH_FALLBACK_MS)
    }

    /** 旧窗关完（或兜底超时）后发射新窗。只发一次。 */
    private fun fireRelaunch(entry: AggregatePayment.PayAction) {
        if (pendingRelaunchFired) return
        pendingRelaunchFired = true
        pendingRelaunch = null
        pendingRelaunchFallback?.let { main.removeCallbacks(it) }
        pendingRelaunchFallback = null
        // 落位窗口重新计时：从发射到新窗 placement 还有几百毫秒。
        FlymeFreeformController.suppressAvoidOffsetFor(PAY_OFFSET_SUPPRESS_MS)
        val reuse = reuseTaskIdFor(entry.launch?.packageName)
        log(Log.INFO, "QUICK_PAY_RELAUNCH label=${entry.label} reuseTask=$reuse")
        launchPayEntryInNewWindow(entry, reuseTaskId = reuse)
    }

    /**
     * 降级条目（**没有** ShortcutInfo：应用装了但没发布对应快捷方式）的 Intent 显式化。
     *
     * 微信主界面那条只有隐式 action（`ShortCutDispatchAction`，无 component），从 SystemUI
     * 直启隐式 intent 会被系统静默拦掉（start 返回成功但界面毫无反应，adb 实测）。用 PM 解析
     * 出系统本来会命中的目标，把 component 钉上。**快捷方式条目不要走这里** —— LauncherApps
     * 拿到的 ShortcutInfo 根本没有 intents（系统按 CLONE_REMOVE_FOR_LAUNCHER 剥掉了），
     * 只能 `LauncherApps.startShortcut`，见 [launchPayEntryInNewWindow]。
     */
    private fun payIntent(item: com.repl.bubbledrawer.shortcut.ShortcutItem): android.content.Intent? {
        val parsed = item.intentUri
            ?.let { runCatching { android.content.Intent.parseUri(it, 0) }.getOrNull() }
            ?: return null
        if (parsed.component == null) {
            val resolved = runCatching {
                host.packageManager.queryIntentActivities(parsed, 0)
            }.getOrNull()?.firstOrNull() ?: return null
            parsed.component = android.content.ComponentName(
                resolved.activityInfo.packageName,
                resolved.activityInfo.name,
            )
        }
        return parsed
    }

    /**
     * 支付入口的 freeform options。[reuseTaskId] > 0 时把 `setLaunchTaskId` 一起带上：
     * 同应用换页时这是"在原窗里跳"而不是"再开一扇"的关键（MIUI 的
     * `MiuiCaptionClickListener.handleNewWindowClicked` 也是这么复用任务）。
     */
    private fun payLaunchOptions(
        item: com.repl.bubbledrawer.shortcut.ShortcutItem,
        reuseTaskId: Int,
    ): android.os.Bundle? {
        val opts = MiuiFreeform.makeActivityOptions(host, item.packageName)
            ?: MiuiFreeform.activityOptions(host, item.packageName, noCheck = true)
            ?: runCatching {
                ActivityOptions.makeBasic().also {
                    runCatching {
                        ActivityOptions::class.java
                            .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                            .invoke(it, 5) // WINDOWING_MODE_FREEFORM
                    }
                }
            }.getOrNull()
            ?: return null
        if (reuseTaskId > 0) setLaunchTaskId(opts, reuseTaskId)
        return MiuiFreeform.withoutFreeformAnimation(opts).toBundle()
    }

    private fun setLaunchTaskId(options: ActivityOptions, taskId: Int) {
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                .invoke(options, taskId)
        }
    }

    /**
     * 应用专属直达 Intent：支付宝走 `alipays://`，微信用它自己的
     * `ShortCutDispatchAction` + `LauncherUI.Shortcut.LaunchType`。
     *
     * 必须自己拼、自己 `startActivityAsUser`，不能把微信交给 `LauncherApps.startShortcut`：
     * 后者只管把 Intent 送进 LauncherUI，微信内部再起 `BaseScanUI` / `WalletOfflineEntranceUI`
     * 时如果 LauncherUI 任务不是小窗，页面就全屏；而系统快捷方式通道也不会把我们的
     * freeform options 传给微信内部的第二次启动。自己拼才能让这次启动带上
     * `setLaunchTaskId` + freeform options，微信内部的页面按 MIUI 的“源是小窗”链式继承
     * 继续落在小窗里（2026-10-24 用户实测：支付宝正常，微信收付款全屏且之后扫一扫也全屏）。
     */
    private fun payDirectIntent(entry: AggregatePayment.PayAction): android.content.Intent? {
        val direct = entry.direct ?: return null
        val intent = when {
            direct.scheme != null -> android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(direct.scheme),
            )
            direct.action != null -> android.content.Intent(direct.action)
            else -> return null
        }
        val pkg = entry.launch?.packageName
        if (pkg != null) {
            intent.setPackage(pkg)
            direct.component?.let { intent.component = android.content.ComponentName(pkg, it) }
        }
        direct.extraKey?.let { intent.putExtra(it, direct.extraValue) }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** 当前所有微信任务（taskId → top activity 类名），用 REAL_GET_TASKS 读。 */
    private fun wechatTasks(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
            val tasks = atm.javaClass
                .getMethod("getTasks", Int::class.javaPrimitiveType)
                .invoke(atm, 40) as? List<*> ?: return@runCatching
            for (t in tasks) {
                val component = runCatching {
                    t?.javaClass?.getField("topActivity")?.get(t) as? android.content.ComponentName
                }.getOrNull() ?: runCatching {
                    t?.javaClass?.getField("realActivity")?.get(t) as? android.content.ComponentName
                }.getOrNull() ?: runCatching {
                    t?.javaClass?.getField("baseActivity")?.get(t) as? android.content.ComponentName
                }.getOrNull() ?: continue
                if (component.packageName != "com.tencent.mm") continue
                val id = runCatching {
                    t?.javaClass?.getField("taskId")?.getInt(t) ?: -1
                }.getOrDefault(-1)
                if (id > 0) out += id to component.className
            }
        }.onFailure { logger(Log.WARN, "QUICK_PAY_WECHAT_TASKS_FAILED", it) }
        return out
    }

    private fun removeTask(taskId: Int) {
        runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
            atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId)
            log(Log.INFO, "QUICK_PAY_REMOVE_TASK taskId=$taskId")
        }.onFailure { logger(Log.WARN, "QUICK_PAY_REMOVE_TASK_FAILED taskId=$taskId", it) }
    }

    /**
     * 微信的 `ShortCutDispatchActivity` 是 `singleInstance`，而且只在 `onCreate` 里读
     * `LauncherUI.Shortcut.LaunchType` 分发（反编译确认没有 `onNewIntent`）。上一次点击的
     * 实例只要还活着（登录检查/页面启动中），下一次 startActivity 只会走 `onNewIntent`
     * —— 它不处理 —— 于是 `startActivity` 返回成功、页面不动（2026-10-24「微信两个页面
     * 切换经常失败」）。每次发射前把它的任务删掉，保证拿到一个新实例。
     */
    private fun clearWechatDispatchTask() {
        for ((id, cls) in wechatTasks()) {
            if (cls == "com.tencent.mm.ui.ShortCutDispatchActivity") removeTask(id)
        }
    }

    /**
     * 清掉所有微信任务后再启动支付页。
     *
     * 微信的支付页和聊天页共用 `UIPageFragmentActivity` / `LauncherUI`，从 `RunningTaskInfo`
     * 里无法可靠区分“支付页”还是聊天页；而只要旧任务还在，`ShortCutDispatchActivity` 启动
     * 页面时带的 `FLAG_ACTIVITY_CLEAR_TOP` 就会把那个任务（可能已经是全屏）整个提到前面，
     * 新页面也跟着全屏（用户 2026-10-24：「微信打开收付款是全屏，退出去下次扫一扫也变全屏」）。
     *
     * 所以每次微信支付启动前把微信任务全部移除，保证从干净的小窗任务长出来。聊天记录是
     * 持久化的，移除任务只关窗口、不丢消息。
     */
    private fun clearWechatTasks() {
        for ((id, _) in wechatTasks()) removeTask(id)
    }

    /**
     * 跨应用换窗时，复用目标应用已有的任务（可能是全屏任务）让 MIUI 按小窗参数重新落位 ——
     * 不这么做，应用从后台任务被拉起时会保留全屏配置，就是"偶尔还是全屏"。
     *
     * 但**不能**复用我们自己正在管理的支付小窗：那扇正在关（taskId 马上失效），复用它只会把
     * 新页面塞进一个正在消失的任务里。
     */
    private fun reuseTaskIdFor(pkg: String?): Int {
        if (pkg.isNullOrEmpty()) return -1
        val id = MiuiFreeform.reusableTaskId(pkg, 0)
        if (id <= 0) return -1
        return if (taskById.containsKey(id)) -1 else id
    }

    /**
     * 以新小窗打开支付入口。
     *
     * 顺序：**应用专属直达 Intent → `LauncherApps.startShortcut` → intentUri 兜底**。
     *
     * 为什么直达排在 startShortcut 前面（2026-10-24 真机）：
     *
     *  - `LauncherApps` 拿到的 `ShortcutInfo.getIntents()` 恒为 null（系统按
     *    CLONE_REMOVE_FOR_LAUNCHER 剥掉），所以只能靠 `startShortcut` 或自己拼；
     *  - 但 `startShortcut` 只是把 Intent 交给微信的 `LauncherUI`，**我们的 freeform
     *    options 传不进微信内部第二次启动**（`BaseScanUI` / `WalletOfflineEntranceUI`
     *    自己带 `FLAG_ACTIVITY_NEW_TASK`）。LauncherUI 一旦落在全屏任务，收付款就是全屏；
     *    而且这个全屏任务会被后续快捷方式复用，扫一扫也跟着全屏（用户实测）。
     *  - 自己拼出微信的 `ShortCutDispatchAction` + `LaunchType`，用
     *    `startActivityAsUser` + freeform options + `setLaunchTaskId` 发出去，MIUI 才能
     *    把 LauncherUI 任务按小窗重排，微信内部的页面再按“源是小窗”链式继承 freeform。
     */
    private fun launchPayEntryInNewWindow(
        entry: AggregatePayment.PayAction,
        reuseTaskId: Int = 0,
    ): Boolean {
        val item = entry.launch ?: return false
        val wechatDirect = entry.direct?.action != null
        // 微信：每次发射前清掉旧的 ShortCutDispatchActivity 实例 + 旧支付页面任务。
        // 分发 Activity 只在 onCreate 分发，旧实例还活着时新点击只会走 onNewIntent（它不处理）；
        // 旧 BaseScanUI/Wallet 任务还在时，CLEAR_TOP 会把它整个（可能全屏）提到前面。
        if (wechatDirect) {
            clearWechatDispatchTask()
            clearWechatTasks()
        }
        val reuse = if (wechatDirect) 0 else reuseTaskId
        val bundle = payLaunchOptions(item, reuse)
        // 1) 应用专属直达 Intent（微信的 ShortCutDispatchAction / 支付宝的 alipays://）。
        val direct = payDirectIntent(entry)
        if (direct != null && startIntentAsUser(direct, bundle, 0)) {
            log(
                Log.INFO,
                "QUICK_PAY_LAUNCH_NEW_WINDOW label=${entry.label} via=direct " +
                    "reuseTask=$reuse ok=true",
            )
            return true
        }
        // 2) 官方快捷方式通道。
        if (item.shortcutInfo != null &&
            ShortcutRepository.launchShortcut(host, item, useFreeform = true, optionsBundle = bundle)
        ) {
            log(
                Log.INFO,
                "QUICK_PAY_LAUNCH_NEW_WINDOW label=${entry.label} via=shortcut " +
                    "reuseTask=$reuseTaskId ok=true",
            )
            return true
        }
        // 3) 没有 ShortcutInfo（应用装了但没发布对应快捷方式，退到主界面 Intent）才直启。
        val intent = payIntent(item)
        if (intent != null && startIntentAsUser(intent, bundle, 0)) {
            log(
                Log.INFO,
                "QUICK_PAY_LAUNCH_NEW_WINDOW label=${entry.label} via=intent " +
                    "reuseTask=$reuseTaskId ok=true",
            )
            return true
        }
        val ok = ShortcutRepository.launchShortcut(
            host,
            item,
            useFreeform = true,
            optionsBundle = bundle,
        )
        log(Log.INFO, "QUICK_PAY_LAUNCH_NEW_WINDOW label=${entry.label} via=startShortcut ok=$ok")
        return ok
    }

    private fun closePayTask(info: Any) {
        val taskId = FreeformTask.taskId(info)
        if (taskId <= 0) return
        // 先走 ROM 自己的关窗（带动画）。但它的前两级（closeFullOrFreeform / exitFreeformTask）
        // 可能只是把窗口**退出 freeform** —— 任务带着旧页面回到全屏后台，紧接着的新窗启动复用
        // 这个任务，页面自然还是旧的（"新小窗不跳到相应页面"，2026-10-24 实测：关掉收付款小窗
        // 后 dumpsys 里躺着全屏的 WalletOfflineEntranceUI 任务）。补一刀 removeTask 把任务从
        // 系统里彻底拿掉，新窗启动才会带着新 Intent 全新落页。
        val closed = runCatching {
            FlymeFreeformController.instance?.closeTask(info, taskId)
            true
        }.getOrDefault(false)
        val removed = runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId)
            true
        }.getOrDefault(false)
        log(Log.INFO, "QUICK_PAY_CLOSE_OLD taskId=$taskId closed=$closed removed=$removed")
    }

    /** 我们打开的支付小窗到位了：接管它作为条的宿主；旧的支付窗口还在就关掉。 */
    private fun adoptPayTask(taskInfo: Any) {
        clearPendingPay()
        val oldId = payTaskId
        val newId = FreeformTask.taskId(taskInfo)
        payTaskId = newId
        task = taskInfo
        currentPkg = FreeformTask.packageName(taskInfo)
        hideReason = null
        closingUntil = 0L
        unfocused = false
        fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
        log(Log.INFO, "QUICK_PAY_WINDOW_ADOPTED new=$newId old=$oldId")
        // 新窗已经落位：临时压错开的窗口使命结束，恢复 ROM 原行为。
        FlymeFreeformController.avoidOffsetSuppressedUntil = 0L
        // 条可能正以会话条目（或上一个窗口的支付条目）显示着：藏掉让下一帧重新 attach ——
        // attachBar 才会执行 bindPay，直接 update 是不会换内容的。
        if (added) detach("PAY_REBIND")
        if (oldId > 0 && newId > 0 && oldId != newId) {
            taskById[oldId]?.let { closePayTask(it) }
        }
    }

    /** 支付会话是否该把 [taskInfo]（或它的包名）当成"自己打开的那扇窗"。 */
    private fun isPendingPay(taskInfo: Any): Boolean {
        val pending = pendingPayPackage ?: return false
        return FreeformTask.packageName(taskInfo) == pending
    }

    private fun armPendingPay(pkg: String) {
        pendingPayPackage = pkg
        pendingPayExpiry?.let { main.removeCallbacks(it) }
        val expiry = Runnable {
            pendingPayExpiry = null
            val pending = pendingPayPackage ?: return@Runnable
            pendingPayPackage = null
            // 小窗一直没到位：还没adopt过任何窗口（task == null）就放弃整个会话；
            // 窗内切换的场合（task 还在）会话照常活着，只是不再等事件。
            if (task == null) {
                log(Log.WARN, "QUICK_PAY_PENDING_TIMEOUT pkg=$pending")
                dismissQuickPay("PAY_PENDING_TIMEOUT")
            }
        }
        pendingPayExpiry = expiry
        main.postDelayed(expiry, PAY_PENDING_TIMEOUT_MS)
    }

    private fun clearPendingPay() {
        pendingPayPackage = null
        pendingPayExpiry?.let { main.removeCallbacks(it) }
        pendingPayExpiry = null
    }

    /** 当前已 adopt 的支付窗是不是同一个 App（支付宝桥接窗会短暂出现两扇同包任务）。 */
    private fun isSameAsAdoptedPayWindow(taskInfo: Any): Boolean {
        val adopted = task ?: return false
        val pkg = FreeformTask.packageName(adopted) ?: return false
        return FreeformTask.packageName(taskInfo) == pkg
    }

    /** 任务 ID 比当前支付窗更大 = 后落的那扇（MIUI 的任务号单调递增）。 */
    private fun isNewerPayTask(taskInfo: Any): Boolean =
        FreeformTask.taskId(taskInfo) > payTaskId

    /**
     * 同一个 App 里比 [closingId] 更新的那扇普通可见窗。支付宝的
     * `ShortcutsLauncherActivity` 桥接和它拉起的真页面会各占一个任务：桥接先出现、ID 更小，
     * 真页面后出现、ID 更大。桥接关掉/消失时把支付条交给真页面，而不是直接收条。
     */
    private fun newerPayWindow(closingId: Int, current: Any): Any? {
        val pkg = FreeformTask.packageName(current) ?: return null
        return taskById.values.firstOrNull {
            FreeformTask.taskId(it) > closingId &&
                FreeformTask.packageName(it) == pkg &&
                FreeformTask.isNormal(it) &&
                FreeformTask.isVisible(it)
        }
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
        // 支付小窗还没到位的空档：靠 Appeared/Focused 事件推进 + pending 超时兜底，无需空转。
        if (task == null) return
        if (!payActive && !enabled()) return
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
        if (task == null || (!payActive && !enabled())) return
        if (idleMode) {
            idleMode = false
            log(Log.INFO, "CONTACT_BAR_LOOP per-frame (window moving)")
        }
        fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
        onFrame()
        scheduleFrame()
    }

    private fun onFrame() {
        // 聚合支付不受 contactBar 开关管（它是被显式点出来的）；窗口几何与会话模式共用同一条链，
        // 差别只在数据源：支付模式不读通知会话，条上画的是支付入口。
        if (!payActive && !enabled()) {
            if (added) detach("DISABLED")
            return
        }
        val info = task ?: run {
            // 支付小窗还没到位（刚触发，等 Appeared/Focused 事件）—— 空档里不画任何东西。
            if (payActive && added) detach("PAY_WAIT_WINDOW")
            return
        }
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
        if (payActive) {
            // 支付模式：不读会话数据（条上是支付入口，refreshData 会用会话把它顶掉）。
            currentPkg = pkg
        } else {
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

    private fun attach(info: Any, rect: Rect) {
        attachBar(rect, FreeformTask.taskId(info), info) { v ->
            if (payActive) v.bindPay(payEntries, ::onPayEntryClick)
            else v.bind(items, fallbackIcon(), ::openConversation, ::onRemoveConversation)
        }
    }

    /**
     * 把条摆到 [rect] 并让它可见（会话/聚合支付共用的那套 surface 三步台阶）。[info] 为 null
     * 即聚合支付模式：没有小窗 → 没有 leash（Z 走绝对层 [LAYER_WITHOUT_LEASH]）、圆角用默认值。
     *
     * 失败时**拆掉整块 surface** 并记下 [attachFailedAt]（下一轮 [ATTACH_RETRY_MS] 后再试），
     * 绝不留下"看起来加了、其实什么都没显示"的状态 —— 那会让条永远不再出现（以前 `added` 就是
     * 这么被置上的）。
     */
    private fun attachBar(rect: Rect, taskId: Int, info: Any?, bind: (ContactBarView) -> Unit) {
        val sc = ensureSurface(taskId) ?: return failed("SURFACE_UNAVAILABLE")
        val display = displayOrNull() ?: return failed("NO_DISPLAY")
        val v = ensureView()
        if (info != null) {
            applyCorner(v, info)
        } else {
            // 聚合支付没有小窗可取圆角：回默认 13dp（[applyCorner] 的缓存按模式切换重置）。
            cornerShown = Float.NaN
            v.setCornerRadius(null)
        }
        bind(v)

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

        val leash = info?.let { resolveLeash(it) }
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
            "CONTACT_BAR_SHOW pay=$payActive rect=$rect taskId=$taskId " +
                "items=${if (payActive) payEntries.size else items.size} pkg=$currentPkg " +
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

    private fun update(info: Any, rect: Rect) = updateBar(rect, info)

    private fun updateBar(rect: Rect, info: Any?) {
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

        val leash = info?.let { resolveLeash(it) }
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
        if (info != null) applyCorner(v, info)
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
        // 支付模式不消费会话数据：通知回调照常来，但不能把支付条目顶掉。
        if (payActive) return@onMain
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
        clearPendingPay()
        pendingRelaunch = null
        pendingRelaunchFired = false
        pendingRelaunchFallback?.let { main.removeCallbacks(it) }
        pendingRelaunchFallback = null
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

        /**
         * 触发/换入口后等支付小窗到位的时限。超时即放弃：还没有任何窗口被adopt过（task == null）
         * 就结束会话，否则只是不再等待事件（窗内切换成功的场合）。
         */
        const val PAY_PENDING_TIMEOUT_MS = 4000L

        /** 换窗落位期间临时压掉 ROM 错开（avoidAsPossible）的时限。 */
        const val PAY_OFFSET_SUPPRESS_MS = 5000L

        /** 换入口：旧窗 Vanished 后等多久再发射新窗（贴着关窗动画发射会被 ROM 吞掉）。 */
        const val RELAUNCH_DELAY_MS = 450L

        /** 换入口：Vanished 兜底——迟迟不来也照发（关窗被拒/动画卡住的场合）。 */
        const val RELAUNCH_FALLBACK_MS = 1500L
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

/**
 * 聚合支付的执行入口（面板点击 / App 端广播都会走到它）。`SystemUiHookInstaller` 建好控制器时
 * 赋值 —— 与 [onFreeformWindowMotion] 同一套跨对象接线：控制器归 installer 所有，触发方
 * （FanHost 的面板点击、广播接收器）不该知道它内部怎么建。
 */
@Volatile
var quickPayTrigger: (() -> Unit)? = null