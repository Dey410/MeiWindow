package com.repl.bubbledrawer.launch

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect

/**
 * Reflective bridge to HyperOS/MIUI's freeform (小窗) stack state — the piece the
 * "更多页" needs to sit exactly where the current 小窗 is (方案 B).
 *
 * Verified against the jars pulled from the target device
 * (`work/miui_fw/{framework,miui-framework}.jar`, decompiled to `out/single/`):
 *
 *  - `miui.app.MiuiFreeFormManager.getAllFreeFormStackInfosOnDisplay(int)` →
 *    `List<MiuiFreeFormManager.MiuiFreeFormStackInfo>` with public fields
 *    `packageName / stackId / userId / displayId / bounds / smallWindowBounds /
 *    visible / windowState / inPinMode / isForegroundPin …` and
 *    `isInFreeFormMode() / isInMiniFreeFormMode()`.
 *    `MiuiMultiWindowUtils.hasSmallFreeform()` (same jar) uses exactly the
 *    predicate `windowState == 1 && !inPinMode` — mirrored in [StackInfo.isNormalWindow].
 *  - `android.util.MiuiMultiWindowUtils.getActivityOptions(Context, String, boolean noCheck,
 *    int launchBoundsLeft, int launchBoundsTop)` (line 711) builds freeform options with
 *    `setLaunchWindowingMode(5)` + `setLaunchBounds(getCustomFreeformRect(…))`; the
 *    sentinel `-1073741824` (= [POS_AUTO]) means "let the ROM pick the position", any
 *    other left/top pins the window there (clamped inside the freeform accessible area).
 *    `miui.app.MiuiFreeFormManager.getActivityOptions(Context, String, boolean, int, int)`
 *    exists as the same-shaped fallback.
 *  - `ActivityOptions.setFreeformAnimation(boolean)` is a MIUI addition; the ROM's own
 *    mini-window launch (`AppMiniWindowManagerImpl$launchMiniWindowActivity$1:133-135`)
 *    calls `getActivityOptions(ctx, pkg, true, false)` and then disables that animation.
 *
 * Everything is reflective + best-effort: a missing class/method returns null/empty and
 * the caller falls back (fullscreen launch, centred panel), so a non-MIUI ROM still works.
 */
object MiuiFreeform {

    /** `MiuiMultiWindowUtils` "no custom position" sentinel. */
    const val POS_AUTO = -1073741824

    data class StackInfo(
        val packageName: String,
        val stackId: Int,
        val displayId: Int,
        val bounds: Rect,
        val smallBounds: Rect?,
        val visible: Boolean,
        val windowState: Int,
        val inPinMode: Boolean,
        val foregroundPin: Boolean,
    ) {
        /** `MiuiMultiWindowUtils.hasSmallFreeform()` predicate. */
        val isNormalWindow: Boolean get() = windowState == 1 && !inPinMode
    }

    /** All freeform stacks of one display; empty list when the ROM has no such API. */
    fun stacks(displayId: Int = 0): List<StackInfo> {
        val raw = runCatching {
            Class.forName("miui.app.MiuiFreeFormManager")
                .getMethod("getAllFreeFormStackInfosOnDisplay", Int::class.javaPrimitiveType)
                .invoke(null, displayId) as? List<*>
        }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { it?.let(::toStack) }
    }

    /**
     * The stack the user is looking at — visible normal window first, then any visible
     * stack, then the first one with real bounds. Null when nothing is open.
     */
    fun currentStack(displayId: Int = 0): StackInfo? = pick(stacks(displayId))

    /** Same selection rule as [currentStack], for a list already read once (logging). */
    fun pick(list: List<StackInfo>): StackInfo? {
        val withBounds = list.filter { !it.bounds.isEmpty }
        return withBounds.firstOrNull { it.visible && it.isNormalWindow }
            ?: withBounds.firstOrNull { it.visible }
            ?: withBounds.firstOrNull()
    }

    /**
     * Bounds of the freeform window the user is looking at, whatever created it.
     *
     * `getAllFreeFormStackInfosOnDisplay` only lists MIUI's own tracked stacks; a window
     * produced through `MiuiMultiWindowUtils.getActivityOptions` (what the fan launches)
     * may not appear there — which is exactly what `stacks=0` in the panel log means. So
     * fall back to the activity manager: the first running task whose windowing mode is
     * freeform carries its own configuration bounds.
     *
     * @return bounds to package name, or null when no freeform window exists.
     */
    fun currentWindow(context: Context, displayId: Int = 0): Pair<Rect, String>? {
        val tracked = pick(stacks(displayId))
        if (tracked != null && !tracked.bounds.isEmpty) return tracked.bounds to tracked.packageName
        return freeformTask(context)
    }

    /** Activity-manager fallback of [currentWindow]: the first running freeform task. */
    fun freeformTask(context: Context): Pair<Rect, String>? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val tasks = runCatching { am.getRunningTasks(20) }.getOrNull() ?: return null
        for (task in tasks) {
            val freeform = runCatching {
                task.javaClass.getMethod("isFreeform").invoke(task) as? Boolean
            }.getOrNull() ?: false
            if (freeform != true) continue
            val config = runCatching {
                task.javaClass.getField("configuration").get(task) as? Configuration
            }.getOrNull() ?: continue
            val windowConfig = runCatching {
                Configuration::class.java.getMethod("getWindowConfiguration").invoke(config)
            }.getOrNull() ?: continue
            val bounds = runCatching {
                windowConfig.javaClass.getMethod("getBounds").invoke(windowConfig) as? Rect
            }.getOrNull() ?: continue
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            val pkg = task.topActivity?.packageName
                ?: task.baseActivity?.packageName
                ?: "?"
            return bounds to pkg
        }
        return null
    }

    /**
     * Where MIUI itself would put a freeform window for [packageName] — the same
     * `MiuiMultiWindowUtils.getCustomFreeformRect` the launch options use. Last-resort
     * placement source: this ROM's SystemUI runs under a plain app uid, so neither the
     * MIUI stack list nor `getRunningTasks` yields the current window (both came back
     * empty on device), leaving the ROM's own footprint as the best available answer.
     */
    fun defaultFreeformRect(context: Context, packageName: String? = null): Rect? {
        if (packageName != null) {
            customFreeformRect(context, packageName, POS_AUTO, POS_AUTO)?.let { return it }
        }
        return runCatching {
            Class.forName("android.util.MiuiMultiWindowUtils")
                .getMethod("getFreeformRect", Context::class.java)
                .invoke(null, context) as? Rect
        }.getOrNull()
    }

    /**
     * `getCustomFreeformRect` at an explicit position — the ROM's own rect *sized* for [pkg] and
     * *pinned* at `left/top` (`POS_AUTO` for either = the ROM picks it).
     *
     * Two overloads are known to exist on MIUI/HyperOS builds; both are tried, in the order this
     * module's own calls were verified:
     *  1. `(Context, int left, int top, boolean, String pkg, boolean)` — the shape
     *     [defaultFreeformRect] has used since 2026-10-09 (the ROM's own builder chain);
     *  2. `(Context, int left, int top, boolean, String pkg)` — the shape 安全中心's
     *     `FreeformUtil.getCustomFreeformRect` reflectively calls (verified in the APK shipped on
     *     pudding: `AbstractC6721e0.m21977k`).
     *
     * The *size* always comes from the ROM here, never from the caller — only the position is ours.
     */
    fun customFreeformRect(context: Context, packageName: String, left: Int, top: Int): Rect? =
        sixArgRect(context, packageName, left, top)
            ?: fiveArgRect(context, packageName, left, top)

    private fun sixArgRect(context: Context, packageName: String, left: Int, top: Int): Rect? = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod(
                "getCustomFreeformRect",
                Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType,
            ).invoke(null, context, left, top, false, packageName, normalFreeForm(context, packageName)) as? Rect
    }.getOrNull()

    private fun fiveArgRect(context: Context, packageName: String, left: Int, top: Int): Rect? = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod(
                "getCustomFreeformRect",
                Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, String::class.java,
            ).invoke(null, context, left, top, normalFreeForm(context, packageName), packageName) as? Rect
    }.getOrNull()

    /** `MiuiMultiWindowUtils.isNormalFreeForm(Context, String, ComponentName)`, default `true`. */
    private fun normalFreeForm(context: Context, packageName: String): Boolean = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod("isNormalFreeForm", Context::class.java, String::class.java, Class.forName("android.content.ComponentName"))
            .invoke(null, context, packageName, null) as? Boolean ?: true
    }.getOrDefault(true)

    /**
     * `MiuiMultiWindowUtils.getVisualBounds(Rect, float)` — the rect a小窗 is *drawn* as: the
     * unscaled task bounds times the scale the ROM applies (`getScaledBounds` in our own
     * [com.repl.bubbledrawer.contactbar.FreeformTask] is the same operation).
     *
     * Needed because a小窗's position is the origin of its **unscaled** bounds while its visible
     * width is this: placing by the unscaled width would leave a 30%-smaller window short of the
     * edge it was supposed to hug.
     */
    fun visualBounds(bounds: Rect, scale: Float): Rect? = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod("getVisualBounds", Rect::class.java, Float::class.javaPrimitiveType)
            .invoke(null, bounds, scale) as? Rect
    }.getOrNull()

    /** `MiuiMultiWindowUtils.getOriFreeformScale(Context, boolean)` — the scale in use, 0 when absent. */
    fun scale(context: Context, packageName: String): Float = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod("getOriFreeformScale", Context::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, context, normalFreeForm(context, packageName)) as? Float ?: 0f
    }.getOrDefault(0f)

    /**
     * 小窗落点：贴在用户划出扇形的**那一边** (报告 2026-10-11: "横屏不管在左侧触发还是右侧触发，
     * 小窗都会显示在左侧")。
     *
     * 算法与 ROM 自己的小窗入口一致 (`com.miui.securitycenter` 的 `FreeformUtil`:
     * `getFreeformWindowPositionX` — 触发点在屏幕左半边就贴左边，否则右边对齐，用的是**可见**宽度)，
     * 只是把「点哪个图标」换成了「从哪个角落划出来」。用户点名的参照物就是它，而它用的正是
     * `getActivityOptions(ctx, pkg, true, left, top)` 这同一个入口 ([activityOptions])。
     *
     * 只在**横屏**接管：竖屏时 ROM 自己的落点就是居中的 (`getLeftMargin`)，小窗可见宽度占屏宽七成，
     * 那是用户一直在用的位置 —— 没有理由让它跟着角落动。(竖屏要一起改，把这里的判断去掉即可。)
     *
     * @return the ROM's own rect for [packageName] moved to the triggered side, or null when the
     *   placement should stay the ROM's business (portrait, or the ROM has no rect function here).
     */
    fun triggerSideRect(
        context: Context,
        packageName: String,
        anchorX: Int,
        display: Rect,
    ): Rect? {
        if (!isWideScreen(display.width(), display.height())) return null
        val base = customFreeformRect(context, packageName, POS_AUTO, POS_AUTO) ?: return null
        if (base.width() <= 0 || base.height() <= 0) return null
        val scale = scale(context, packageName)
        val visualW = if (scale.isUsableScale()) {
            visualBounds(base, scale)?.width() ?: base.width()
        } else {
            // No scale answer: the visible width is at most the unscaled one, so hug with that —
            // the window then sits short of the edge instead of hanging over it.
            base.width()
        }
        val margin = Math.round(FREEFORM_SIDE_MARGIN_DP * context.resources.displayMetrics.density)
        val left = triggerSideLeft(anchorX, visualW, display.width(), margin)
        // `base.top` is the ROM's own vertical answer for this very window (this method *is* the
        // computation its launch path performs), so only the horizontal position changes.
        return Rect(left, base.top, left + base.width(), base.top + base.height())
    }

    /**
     * MIUI freeform [ActivityOptions], pinned to `position.left/top` when given.
     * Returns null when neither vendor entry point exists (caller falls back).
     */
    fun activityOptions(
        context: Context,
        pkg: String,
        noCheck: Boolean = true,
        position: Rect? = null,
    ): ActivityOptions? {
        if (position != null) {
            options5("android.util.MiuiMultiWindowUtils", context, pkg, noCheck, position.left, position.top)
                ?.let { return it }
            options5("miui.app.MiuiFreeFormManager", context, pkg, noCheck, position.left, position.top)
                ?.let { return it }
        }
        options4("android.util.MiuiMultiWindowUtils", context, pkg, noCheck)?.let { return it }
        return options4("miui.app.MiuiFreeFormManager", context, pkg, noCheck)
    }

    /**
     * Task id of [pkg]'s existing (background or fullscreen) task, or -1 — the input for
     * `ActivityOptions.setLaunchTaskId`.
     *
     * Needed for the small-window SHAPE: launching a freeform window for an app that already
     * runs makes the system reparent that task, which keeps its fullscreen configuration
     * (1220x2656 on this phone = the phone's proportions) instead of the small-window rect the
     * ROM computes. Handing over the task id is what MIUI itself does when it deliberately
     * reuses a task (`MiuiCaptionClickListener.handleNewWindowClicked:239-243`).
     *
     * `ActivityTaskManager.getService().getTasks()` is a hidden but ordinary call; it answers
     * with the caller's visible tasks (SystemUI holds REAL_GET_TASKS, so it sees the app tasks;
     * the module's own process just gets its own list, i.e. no reuse → fresh launch as before).
     */
    fun reusableTaskId(pkg: String, userId: Int): Int = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val tasks = atm.javaClass
            .getMethod("getTasks", Int::class.javaPrimitiveType)
            .invoke(atm, 30) as? List<*>
        tasks?.firstOrNull { task ->
            val c = task?.javaClass ?: return@firstOrNull false
            fun field(name: String): Any? = runCatching { c.getField(name).get(task) }.getOrNull()
            val base = field("baseActivity") as? android.content.ComponentName
            val real = field("realActivity") as? android.content.ComponentName
            val taskUser = field("userId") as? Int
            val owner = base?.packageName ?: real?.packageName
            owner == pkg && (taskUser == null || taskUser == userId)
        }?.let { task ->
            runCatching { task.javaClass.getField("taskId").getInt(task) }.getOrNull()
        }
    }.getOrNull() ?: -1

    /** `ActivityOptions.setFreeformAnimation(false)` — MIUI addition, best effort. */
    fun withoutFreeformAnimation(options: ActivityOptions): ActivityOptions {
        runCatching {
            ActivityOptions::class.java
                .getMethod("setFreeformAnimation", Boolean::class.javaPrimitiveType)
                .invoke(options, false)
        }
        return options
    }

    /**
     * `MiuiMultiWindowUtils.makeActivityOptions(context, pkg, left, top)` — the ROM's raw
     * option builder, i.e. what [activityOptions] wraps after its gates.
     *
     * Worth a separate entry point because [activityOptions] (like every `getActivityOptions`
     * overload) starts with `checkAuthority(context)`:
     *
     * ```java
     * public static boolean checkAuthority(Context context) {
     *     if (isThirdPartApp(context.getApplicationInfo())) {          // uid >= 10000 && !FLAG_SYSTEM
     *         return MiuiMultiWindowAdapter.getAuthorisedFreeformList(false)
     *                 .contains(context.getContentResolver().getPackageName());
     *     }
     *     return true;
     * }
     * ```
     *
     * So a launch made from a third-party context (our own manage Activity — the fan itself
     * runs on SystemUI's identity and passes) gets `null` there and would silently degrade to
     * a bare `setLaunchWindowingMode(5)` hint without MIUI's `miuiConfigFlag=2`,
     * `freeformScale` and `normalFreeForm` extras. `makeActivityOptions` has no such gate —
     * it is the very method the ROM's sidebar linkage calls
     * (`MultiTaskingLinkageTransition.startSidebarLaunchFreeform:632`), which is why it is
     * the better fallback.
     */
    fun makeActivityOptions(
        context: Context,
        pkg: String,
        left: Int = POS_AUTO,
        top: Int = POS_AUTO,
    ): ActivityOptions? = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod(
                "makeActivityOptions",
                Context::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            .invoke(null, context, pkg, left, top) as? ActivityOptions
    }.getOrNull()

    // ---------------- reflection plumbing ----------------

    private fun options4(
        clazz: String,
        context: Context,
        pkg: String,
        noCheck: Boolean,
    ): ActivityOptions? = runCatching {
        Class.forName(clazz).getMethod(
            "getActivityOptions",
            Context::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        ).invoke(null, context, pkg, noCheck) as? ActivityOptions
    }.getOrNull()

    private fun options5(
        clazz: String,
        context: Context,
        pkg: String,
        noCheck: Boolean,
        left: Int,
        top: Int,
    ): ActivityOptions? = runCatching {
        Class.forName(clazz).getMethod(
            "getActivityOptions",
            Context::class.java, String::class.java, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(null, context, pkg, noCheck, left, top) as? ActivityOptions
    }.getOrNull()

    private fun toStack(o: Any): StackInfo? = runCatching {
        val c = o.javaClass
        fun f(name: String): Any? = runCatching { c.getField(name).get(o) }.getOrNull()
        val pkg = f("packageName") as? String ?: return null
        StackInfo(
            packageName = pkg,
            stackId = f("stackId") as? Int ?: -1,
            displayId = f("displayId") as? Int ?: 0,
            bounds = f("bounds") as? Rect ?: Rect(),
            smallBounds = f("smallWindowBounds") as? Rect,
            visible = f("visible") as? Boolean ?: true,
            windowState = f("windowState") as? Int ?: -1,
            inPinMode = f("inPinMode") as? Boolean ?: false,
            foregroundPin = f("isForegroundPin") as? Boolean ?: false,
        )
    }.getOrNull()
}

/**
 * 小窗贴边时与屏幕边缘留的余量，与 ROM 自己的「小窗可用区域」同宽
 * (`PHONE_FREEFORM_ACCESSIBLE_AREA_SIDE_MARGIN` = 6dp，见 `FreeformPlacementTest` 的说明) ——
 * 位置最终由 ROM 的 `getCustomFreeformRect` 夹取，这里只保证我们给的值本身就在屏幕内。
 */
const val FREEFORM_SIDE_MARGIN_DP = 6

/**
 * 只有**横屏**才让 [triggerSideLeft] 接管小窗落点。
 *
 * 竖屏时 ROM 自己就是居中的 (`getLeftMargin`)，小窗可见宽度占屏宽七成 —— 那是用户一直在用的
 * 位置，没有理由让它跟着角落动。横屏则是另一回事：小窗只占屏宽的三分之一，"哪一边"才是个问题，
 * 而 ROM 自己给横屏窗口的落点是固定的（用户 2026-10-11 的报告：两边都在左边）。
 */
fun isWideScreen(screenW: Int, screenH: Int): Boolean = screenW > screenH

/**
 * 贴在触发侧的水平落点 (纯算术，见 `FreeformPlacementTest`)。
 *
 * [anchorX] 是用户划出扇形的那个点 (屏幕坐标)；它在左半边 → 窗口贴左边，右半边 → 右边对齐 ——
 * 与 ROM 自己的 `FreeformUtil.getFreeformWindowPositionX` 同一套判断，只是把「点了哪个图标」
 * 换成「从哪边划出来」。半屏判断用**可见**宽度 [visualW] (小窗被缩放画出，不用它就会离边一条缝)。
 *
 * 两个方向都夹在屏幕内：ROM 那边虽然会再夹一次，但那一次是在我们看不见的地方，
 * 给个负数就等于把窗口挂到屏幕外。
 */
fun triggerSideLeft(anchorX: Int, visualW: Int, screenW: Int, marginPx: Int): Int {
    val max = (screenW - visualW - marginPx).coerceAtLeast(marginPx)
    return if (anchorX < screenW / 2) marginPx else max
}
