package com.repl.bubbledrawer.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.UserHandle
import android.util.DisplayMetrics
import com.repl.bubbledrawer.data.MultiUserHelper
import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * "Stay on the current page" launch: asks the system to open the app in a
 * freeform desktop window ABOVE the calling page — the portable analogue of the
 * Flyme flow (SlideLaunchAppSettings/MoreAppWindow launch via
 * `AppLauncherWindow.m9253Y` with bundle `start_windowmode=true` :792-810, and
 * the framework floats the app over the still-visible selection page).
 *
 * Two vendor paths, then a generic path, then fullscreen fallback:
 *  1. HyperOS/MIUI: `MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, false)`
 *     — VERIFIED in the decompiled target SystemUI 17.03:
 *     AppMiniWindowManagerImpl$launchMiniWindowActivity$1.java:133 is exactly
 *     this call (plus setFreeformAnimation(false), line 135) before
 *     PendingIntent.send(..., bundle). Static framework util → reflectable from
 *     any app; the framework may still gate the actual freeform switch for
 *     non-system callers (LSPosed phase lifts that fully).
 *     When the launch knows where it was triggered from ([trigger]) the 5-argument
 *     overload with explicit `launchBoundsLeft/Top` is used instead
 *     (MiuiMultiWindowUtils:711) — the same entry point 安全中心's 侧边栏小窗 uses
 *     (`FreeformUtil.m21966e` → `getActivityOptions(ctx, pkg, true, x, y)`).
 *  2. AOSP 15/16 "desktop windowing": public `setLaunchBounds(rect)` honoured
 *     as freeform when desktop mode is enabled; @hide
 *     `setLaunchWindowingMode(1)` where the ODM gate is on.
 *  3. Anything rejected → plain fullscreen launch (never worse than v0.1).
 *
 * ## Task identity: `NEW_TASK` only — never `MULTIPLE_TASK`
 *
 * The launch intent carries `FLAG_ACTIVITY_NEW_TASK` and nothing else, matching every 小窗
 * launch this ROM performs itself:
 *  - sidebar / gamebox — `FreeformUtil` (`com.miui.gamebooster.utils.AbstractC6721e0.m21981o`,
 *    in the pulled `com.miui.securitycenter` APK) adds exactly `addFlags(268435456)`;
 *  - WMShell linkage — `MultiTaskingLinkageTransition.startSidebarLaunchFreeform:627-657`
 *    builds the options with `MiuiMultiWindowUtils.makeActivityOptions` and sends the app's
 *    own launcher intent, again with no per-launch task flag.
 *
 * New-task-ness is OPT-IN in this ROM: the caption 新建小窗 button is the only path that wants
 * a second window, so it says so explicitly (`ActivityOptions.setForceLaunchNewTask`,
 * `MiuiCaptionClickListener.handleNewWindowClicked:290-292`), reuses a background task by id
 * when one exists (:239-243) and refuses past two freeform windows (:58 `MAX_FREEFORM_COUNT`,
 * `showMaxFreeformToastIfNeeded:448-470`); the plain 小窗 button merely converts the current
 * window (:155-181).
 *
 * `FLAG_ACTIVITY_MULTIPLE_TASK` means "always start a NEW task, never bring an existing one
 * to the front". With an app that is already running (fullscreen or in another small window)
 * the ROM therefore starts a SECOND instance, and every further launch stacks another
 * identical 小窗 — which is exactly the "小窗是个新实例 / 能开出好几个一模一样的小窗" report.
 * MIUI's own ActivityStarter spells the policy out in `ActivityStarterImpl.resolveReusableTask`
 * (pulled `miui-services.jar`): a freeform launch whose options report
 * `getForceLaunchNewTask()` returns `(true, null)` = "do not reuse". Without the flag the
 * existing task is reused and MIUI moves that task into the small window.
 *
 * `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` is dropped with it: that is launcher semantics
 * ("restart the app at its root activity"), not 小窗 semantics — the sidebar resumes the app
 * where the user left it.
 */
class FreeformLaunchStrategy(private val trigger: FreeformTrigger? = null) : ILaunchStrategy {

    override fun launch(context: Context, app: BubbleApp): Boolean {
        val userHandle = MultiUserHelper.getUserHandle(app.userId)
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val act = if (userHandle != null && launcherApps != null) {
            runCatching { launcherApps.getActivityList(app.packageName, userHandle).firstOrNull() }.getOrNull()
        } else null

        val comp = act?.componentName ?: MultiUserHelper.findComponentForPackage(context, app.packageName, app.userId)

        val intent = (if (comp != null) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = comp
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(app.packageName)
        })?.apply {
            // Task-identity parity with the ROM's own 小窗 launches (see class KDoc):
            // NEW_TASK only. MULTIPLE_TASK used to force a brand-new task on every launch,
            // so a running app got a second instance in a second small window instead of
            // being moved into the one window the user asked for.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false

        val opts = miuiOptions(context, app.packageName) ?: aospOptions(context)
        val existingTask = reuseExistingTask(opts, app.packageName, app.userId)
        val bundle = opts.toBundle()
        logLaunchOptions(context, app.packageName, opts, existingTask)

        if (app.userId != 0 && userHandle != null) {
            // 1. Reflective Context.startActivityAsUser (works directly in SystemUI)
            val asUserOk = runCatching {
                val method = Context::class.java.getMethod(
                    "startActivityAsUser",
                    Intent::class.java,
                    android.os.Bundle::class.java,
                    UserHandle::class.java,
                )
                method.invoke(context, intent, bundle, userHandle)
                true
            }.getOrElse { false }
            if (asUserOk) return true

            // 2. Public LauncherApps.startMainActivity
            if (comp != null && launcherApps != null) {
                val launcherOk = runCatching {
                    launcherApps.startMainActivity(comp, userHandle, null, bundle)
                    true
                }.getOrElse { false }
                if (launcherOk) return true
            }
        }

        return try {
            context.startActivity(intent, bundle)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Hand the ROM the EXISTING task of this app, so that turning it into a small window applies
     * the小窗 geometry instead of keeping the task's old configuration.
     *
     * Report 2026-10-09 (user): "全新打开是正常小窗，但若不是全新打开则会变成不正常比例，
     * 不正常的接近手机比例" — a reused task kept its fullscreen config (1220x2656 = the phone
     * shape, then scaled) while the correct小窗 is ≈9:16, which is what the ROM's own
     * `getCustomFreeformRect` asks for (1220x1952) and what a fresh launch gets.
     *
     * This mirrors the one ROM path that reuses deliberately —
     * `MiuiCaptionClickListener.handleNewWindowClicked:239-243` does
     * `activityOptions.setLaunchTaskId(recentTask.taskId)` before sending the freeform options.
     */
    private fun reuseExistingTask(opts: ActivityOptions, pkg: String, userId: Int): Int {
        val taskId = MiuiFreeform.reusableTaskId(pkg, userId)
        if (taskId <= 0) return -1
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                .invoke(opts, taskId)
        }
        return taskId
    }

    /**
     * Diagnostics for the one thing that decides the small window's SHAPE (2026-10-09 report:
     * a fresh launch came up 16:9 while reusing a background task kept the phone ratio).
     *
     * The window geometry is whatever `launchBounds` + `launchWindowingMode` we hand over, and
     * a reused task keeps its own configuration instead — so printing our bounds next to the
     * ROM's own computation (`MiuiFreeform.defaultFreeformRect` = `getCustomFreeformRect(...)`)
     * and the live screen size tells us immediately whether the shape came from our options,
     * from the fallback path, or from the framework.
     */
    private fun logLaunchOptions(context: Context, pkg: String, opts: ActivityOptions, existingTask: Int) {
        runCatching {
            val dm: DisplayMetrics = context.resources.displayMetrics
            val bounds = runCatching { opts.launchBounds }.getOrNull()
            val mode = runCatching {
                ActivityOptions::class.java.getMethod("getLaunchWindowingMode").invoke(opts) as? Int
            }.getOrNull()
            val romRect = runCatching { MiuiFreeform.defaultFreeformRect(context, pkg) }.getOrNull()
            val ratio = bounds?.let { "%.3f".format(it.width().toFloat() / it.height()) } ?: "-"
            val romRatio = romRect?.let { "%.3f".format(it.width().toFloat() / it.height()) } ?: "-"
            android.util.Log.i(
                "BubbleDrawer",
                "LAUNCH_OPTS pkg=$pkg mode=$mode reuseTask=$existingTask bounds=$bounds ratio=$ratio " +
                    "romRect=$romRect romRatio=$romRatio " +
                    "screen=${dm.widthPixels}x${dm.heightPixels} trigger=$trigger",
            )
        }
    }

    /**
     * HyperOS path — `MiuiMultiWindowUtils.getActivityOptions(...)`.
     *
     * SIZE IS NOT OURS (user ruling 2026-10-09): the small window always takes the ROM's own小窗
     * rectangle, whether it was opened from a fan tile or from a tile inside the 更多 panel —
     * "扇形打开的小窗和更多里打开的小窗应该一致". Passing the 更多 panel's rect used to
     * shrink/stretch the window to the panel's shape (976x1859 on this phone vs the ROM's
     * 1220x1952), which is exactly the inconsistency reported; the panel's own size stays a user
     * setting and is untouched.
     *
     * POSITION, on the other hand, is what the trigger decides (user report 2026-10-11: 横屏时
     * 不管从哪个角落划出来小窗都在左边) — see [MiuiFreeform.triggerSideRect], which asks the ROM
     * for its own rect and only moves it to the triggered side. Without a [trigger] the position
     * stays the ROM's business, exactly as before.
     */
    private fun miuiOptions(context: Context, pkg: String): ActivityOptions? {
        val position = trigger?.let {
            MiuiFreeform.triggerSideRect(context, pkg, it.anchorX, it.display)
        }
        return (MiuiFreeform.activityOptions(context, pkg, noCheck = true, position = position)
            // `getActivityOptions` is gated by checkAuthority() (third-party callers get
            // null); the ROM's own builder is not, and is what its sidebar linkage uses —
            // and it takes the same left/top, so a gated answer keeps the triggered side.
            ?: position?.let { MiuiFreeform.makeActivityOptions(context, pkg, it.left, it.top) }
            ?: MiuiFreeform.makeActivityOptions(context, pkg)
            ?: ActivityOptions.makeBasic().also {
                runCatching {
                    ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        .invoke(it, 5) // 5 = WINDOWING_MODE_FREEFORM
                }
            })
            .let { MiuiFreeform.withoutFreeformAnimation(it) }
    }

    /**
     * The rect a small window should have on a ROM with no MIUI geometry — the same 62% box as
     * before, moved to the triggered side when the launch knows it (see [FreeformTrigger]).
     */
    private fun aospOptions(context: Context): ActivityOptions {
        val opts = ActivityOptions.makeBasic()
        val dm: DisplayMetrics = context.resources.displayMetrics
        val screenW = trigger?.display?.width() ?: dm.widthPixels
        val screenH = trigger?.display?.height() ?: dm.heightPixels
        val w = (screenW * 0.62f).toInt()
        val h = (screenH * 0.62f).toInt()
        val bounds = Rect().also {
            val left = trigger?.let { t -> triggerSideLeft(t.anchorX, w, screenW, 0) } ?: (screenW - w) / 2
            val top = (screenH * 0.45f).toInt() - h / 2
            it.set(left, top, left + w, top + h)
        }
        runCatching { opts.setLaunchBounds(bounds) }
        runCatching {
            // @hide ActivityOptions.setLaunchWindowingMode(int); 1 = WINDOWING_MODE_FREEFORM
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, 1)
        }
        return opts
    }
}

/**
 * Where a小窗 launch was triggered from: the point on screen the user swiped out of (the fan's
 * corner) and the display as it was at that moment.
 *
 * It exists because the ROM places a小窗 on its own (in landscape that is always the same side —
 * the 2026-10-11 report), and the only way to land it on the side the user actually used is to
 * state the position in the launch options. The display is passed in rather than read here: it has
 * to be the one the fan was laid out with, which is also the one the touch coordinates came from.
 */
data class FreeformTrigger(val anchorX: Int, val display: Rect)

/** Reads the user's 小窗启动 toggle at launch time (flyme parity: every bubble
 *  launch carries `start_windowmode`; our fallback is honest fullscreen).
 *  The toggle lives in the module's shared prefs (SettingsStore writes through
 *  to the LSPosed remote group; the local mirror keeps it usable offline). */
class ConfigurableLaunchStrategy(private val context: Context) : ILaunchStrategy {
    private val gate = GateLaunchStrategy()
    private val fullscreen = FullscreenLaunchStrategy()

    override fun launch(context: Context, app: BubbleApp): Boolean {
        val useFreeform = com.repl.bubbledrawer.xposed.SettingsStore.snapshot(this.context).freeform
        return (if (useFreeform) gate else fullscreen).launch(context, app)
    }
}

/**
 * 小窗启动（**模块自己的 App 进程**）：不自己塞 ActivityOptions，只把"我要小窗"写在 Intent 上，
 * 由 system_server 的闸门用 ROM 自己的参数完成落窗。
 *
 * ## 为什么要换掉 [FreeformLaunchStrategy]（用户 2026-10-17 报的 bug）
 *
 * 现象：「在 app 主页的收藏应用管理里，点击 app 不会小窗打开，而会把魅窗自身变为小窗」。
 *
 * 从 App 进程带 MIUI 的 freeform `ActivityOptions` 直接 `startActivity` 是走不通的一条路：
 * `MiuiMultiWindowUtils.getActivityOptions` 先过 `checkAuthority()`，第三方调用者拿到 null
 * （见 [MiuiFreeform.makeActivityOptions] 的注释），我们手里只剩一个裸的
 * `setLaunchWindowingMode(5)`；这次启动还要再过系统的「应用间跳转」判定与安全中心的
 * 「xx 想要打开 xx」拦截框。结果那次 freeform 请求落到了**调用者自己**（魅窗）的那个任务上，
 * 目标应用反而没进小窗 —— 这正是用户看到的样子。
 *
 * 扇形面板不受影响：它跑在 SystemUI 进程里（system uid），`checkAuthority` 直接放行，所以
 * [FreeformLaunchStrategy] 那条路对它是正确的，此处不动。
 *
 * ## 换成了什么
 *
 * 与「应用间跳转」**完全相同**的那条系统路径：Intent 只带
 * [com.repl.bubbledrawer.lightopen.LightOpen.EXTRA_FREEFORM_REQUEST]，不带任何 options；
 * system_server 的闸门（`LightOpenGate`，钩的是 ROM 自己的
 * `MiuiFreeformServiceImpl.startActivityByFreeForm`）看到标记返回 true，ROM 便用
 * `MiuiMultiWindowUtils.getActivityOptions(ctx, 目标包, true)` 生成正式的小窗参数。
 * 这条路与调用者的身份无关，也不依赖 SystemUI 是否活着；顺带绕开了"第三方调用者"这一整类坑。
 *
 * 目标应用不支持小窗（`supportFreeformByDeveloper=false`）时 ROM 会保持全屏 —— 与 ROM 自己
 * 的侧边栏行为一致，不再出现"魅窗变小窗、目标却没小窗"。
 */
class GateLaunchStrategy : ILaunchStrategy {

    override fun launch(context: Context, app: BubbleApp): Boolean {
        val intent = appLaunchIntent(context, app, resetTaskIfNeeded = false) ?: return false
        // 首选通道：标记放在 intent 上 —— 这次启动与标记是同一次 binder 调用，闸门不会读到
        // "2 秒前的快照"；拦截框确认之后启动的就是这个 Intent，标记也照样在。
        intent.putExtra(com.repl.bubbledrawer.lightopen.LightOpen.EXTRA_FREEFORM_REQUEST, true)

        android.util.Log.i(
            "BubbleDrawer",
            "LAUNCH_GATE_REQUEST pkg=${app.packageName} userId=${app.userId} " +
                "comp=${intent.component?.flattenToShortString() ?: "-"}",
        )
        // 第二通道：一次性口令（8 秒、只认这个包）。拦截框若重造 Intent 丢了 extras，
        // 用户点「允许」之后的第二次启动还能靠它对上（见 RemotePrefs.KEY_LAUNCH_PRIME）。
        com.repl.bubbledrawer.xposed.SettingsStore.primeFreeformLaunch(context, app.packageName)

        val userHandle = MultiUserHelper.getUserHandle(app.userId)
        if (app.userId != 0 && userHandle != null) {
            val asUserOk = runCatching {
                Context::class.java.getMethod(
                    "startActivityAsUser",
                    Intent::class.java,
                    android.os.Bundle::class.java,
                    UserHandle::class.java,
                ).invoke(context, intent, null, userHandle)
                true
            }.getOrElse { false }
            if (asUserOk) return true
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
