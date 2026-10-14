package com.repl.bubbledrawer.xposed

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import com.repl.bubbledrawer.lightopen.LightOpen
import com.repl.bubbledrawer.lightopen.LightOpenDecision
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 【轻量打开】SystemUI 侧的钩子：`竖屏/横屏点通知以小窗打开` 与 `控制中心长按以小窗打开`。
 *
 * ## 为什么不能在 system_server 的闸门上做
 *
 * 这两个动作的调用进程都是 `com.android.systemui`，而 ROM 的
 * `MiuiFreeformServiceImpl.checkStartActivityByFreeForm` 开头就写着：
 *
 * ```java
 * WindowProcessController caller = atm.mProcessMap.getProcess(realCallingPid);
 * if (caller != null && "com.android.systemui".equals(caller.mName)) {
 *     Slog.d(TAG, " checkStartActivityByFreeForm systemui launch return");
 *     return bOptions;                       // ← SystemUI 的启动原样返回，闸门完全不参与
 * }
 * ```
 *
 * 反过来，**SystemUI 自己带过去的 ActivityOptions 会被原样采用** —— 所以这几处改成"由我们把
 * 小窗 options 塞进这次启动"，用 MIUI 自己的官方姿势（`AppMiniWindowManagerImpl` 的迷你窗、
 * 桌面的小窗快捷方式都是这么干的）：
 *
 * ```java
 * ActivityOptions opts = MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, /*noCheck*/ true, false);
 * pendingIntent.send(ctx, 0, fillIn, null, null, null, opts.toBundle());
 * ```
 *
 * ## 通知：两条完全不同的入口（2026-10-14 真机反馈后补齐）
 *
 * | 入口 | 落点 | 备注 |
 * |---|---|---|
 * | 通知栏列表里点通知 | `…InjectorImpl$startNotificationIntentInjected$1.run()` | bundle 由 `startNotificationIntentPublic` 算好 |
 * | **悬浮通知（灵动岛/横幅）点开** | `…InjectorImpl$startIslandIntent$1.run()` | bundle 是 **空 options**（`createActivityOptions(null,null)`） |
 *
 * 两条路径都是"在那个 Runnable 里 `PendingIntent.send(ctx, 0, …, bundle)`"，两个类的 `$bundle`
 * 都是 public 非 final 合成字段 —— 我们在 `run()` 之前把字段换掉，MIUI 的锁屏解锁、通知动画、
 * UiOffloadThread 异步发送全部保持原样。
 *
 * ## 控制中心长按
 *
 * 视图长按 → `QSTileImpl.longClick(Expandable)` → handler 消息 → `handleLongClick(Expandable)`
 * → `mActivityStarter.postStartActivityDismissingKeyguard(getLongClickIntent(), 0, controller)`。
 * SystemUI 的 `ActivityStarter` 接口**没有**带 ActivityOptions 的重载，所以这里接管这一次启动
 * （锁屏不接管，交给 MIUI 的解锁流程）。
 *
 * 小窗参数优先用 MIUI 的 `MiuiMultiWindowUtils.getActivityOptions`；它返回 null（目标不在 MIUI 的
 * freeform 支持名单里，典型就是 `com.android.settings`）时退到我们自己造的
 * `ActivityOptions.makeBasic() + setLaunchWindowingMode(FREEFORM)`，否则"长按磁贴"永远没反应。
 */
class LightOpenSystemUi(
    private val module: XposedModule,
    private val prefs: SharedPreferences,
) {
    private val watcher = LightOpenWatcher(prefs)
    private var loader: ClassLoader? = null

    /** `MiuiMultiWindowUtils.getActivityOptions(Context, String, boolean, boolean)`（miui-framework.jar）。 */
    private val activityOptionsMethod: Method? by lazy {
        runCatching {
            loadClass("android.util.MiuiMultiWindowUtils")?.getMethod(
                "getActivityOptions",
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    /** `ActivityOptions.setLaunchWindowingMode(int)`：@hide，反射。 */
    private val setLaunchWindowingModeMethod: Method? by lazy {
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    fun install(classLoader: ClassLoader) {
        loader = classLoader
        watcher.refresh()
        installNotificationHook(NOTIFICATION_INJECTED_CLASS, "injected")
        installNotificationHook(ISLAND_CLASS, "island")
        installControlCenterHook()
    }

    // ------------------------------------------------------------------ 通知（列表 + 悬浮）

    private fun installNotificationHook(className: String, path: String) {
        val method = runCatching { loadClass(className)?.getDeclaredMethod("run") }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_NOTIFICATION_TARGET_UNAVAILABLE path=$path")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.notification.$path")
            .intercept { chain ->
                val self = chain.thisObject
                val pendingIntent = readField(self, "\$intent") as? PendingIntent
                val context = (readField(self, "this\$0")?.let { readField(it, "mContext") }) as? Context
                if (pendingIntent == null || context == null) {
                    return@intercept chain.proceed()
                }
                val packageName = runCatching { pendingIntent.creatorPackage }.getOrNull()
                val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                val settings = watcher.current()
                val decision = LightOpen.notificationDecision(settings, packageName, portrait)
                // 入口行：开关关着时也留一行，否则"点了没反应"和"钩子没装上"分不清。
                if (settings.enabled && (settings.notificationPortrait || settings.notificationLandscape)) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "LIGHT_OPEN_NOTIFICATION_ENTER path=$path pkg=$packageName portrait=$portrait " +
                            "decision=$decision bundle=${readField(self, "\$bundle") != null}",
                    )
                }
                if (decision != LightOpenDecision.FORCE_FREEFORM || packageName == null) {
                    return@intercept chain.proceed()
                }
                val bundle = freeformBundle(context, packageName, pendingIntentLaunch = true)
                if (bundle == null) {
                    module.log(Log.WARN, TAG, "LIGHT_OPEN_NOTIFICATION_NO_OPTIONS path=$path pkg=$packageName")
                    return@intercept chain.proceed()
                }
                if (!writeField(self, "\$bundle", bundle)) {
                    return@intercept chain.proceed()
                }
                module.log(Log.INFO, TAG, "LIGHT_OPEN_NOTIFICATION path=$path pkg=$packageName portrait=$portrait")
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_NOTIFICATION_HOOK_INSTALLED path=$path")
    }

    // ------------------------------------------------------------------ 控制中心

    private fun installControlCenterHook() {
        val tileClass = runCatching { loadClass("com.android.systemui.qs.tileimpl.QSTileImpl") }.getOrNull()
        val expandableClass = runCatching { loadClass("com.android.systemui.animation.Expandable") }.getOrNull()
        val method = runCatching { tileClass?.getDeclaredMethod("handleLongClick", expandableClass) }.getOrNull()
        if (method == null || expandableClass == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_CONTROL_CENTER_TARGET_UNAVAILABLE")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.control_center")
            .intercept { chain ->
                val tile = chain.thisObject
                val context = readField(tile, "mContext") as? Context
                val settings = watcher.current()
                if (context == null || !settings.enabled || !settings.controlCenter) {
                    return@intercept chain.proceed()
                }
                // 入口行：磁贴类名 + 解析出来的目标包 + 判定结果，"长按没反应"时这是唯一线索。
                val intent = runCatching {
                    tile.javaClass.getMethod("getLongClickIntent").invoke(tile) as? Intent
                }.getOrNull()
                val target = intent?.let { targetPackage(context, it) }
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val locked = keyguard?.isKeyguardLocked == true
                val decision = LightOpen.controlCenterDecision(settings, target)
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_CONTROL_CENTER_ENTER tile=${tile.javaClass.simpleName} target=$target " +
                        "locked=$locked decision=$decision",
                )
                if (locked || intent == null || target == null || decision != LightOpenDecision.FORCE_FREEFORM) {
                    return@intercept chain.proceed()
                }
                val bundle = freeformBundle(context, target, pendingIntentLaunch = false)
                if (bundle == null) {
                    module.log(Log.WARN, TAG, "LIGHT_OPEN_CONTROL_CENTER_NO_OPTIONS pkg=$target")
                    return@intercept chain.proceed()
                }
                runCatching { context.startActivity(intent, bundle) }
                    .onFailure { module.log(Log.WARN, TAG, "LIGHT_OPEN_CONTROL_CENTER_START_FAILED pkg=$target", it) }
                collapsePanels(tile)
                module.log(Log.INFO, TAG, "LIGHT_OPEN_CONTROL_CENTER pkg=$target")
                null
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_CONTROL_CENTER_HOOK_INSTALLED")
    }

    /** 目标包：优先 Intent 的显式组件，其次 `package`，最后交给 PackageManager 解析。 */
    private fun targetPackage(context: Context, intent: Intent): String? {
        runCatching { intent.component?.packageName }.getOrNull()?.let { return it }
        runCatching { intent.`package` }.getOrNull()?.let { return it }
        return runCatching {
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        }.getOrNull()
    }

    /** 尽力收起控制中心：拿不到 host 就算了（下一次启动/返回会自然收拢）。 */
    private fun collapsePanels(tile: Any) {
        runCatching {
            val host = readField(tile, "mHost") ?: return
            host.javaClass.getMethod("collapsePanels").invoke(host)
        }
    }

    // ------------------------------------------------------------------ 共用

    /**
     * 小窗 ActivityOptions → Bundle。
     *
     * 先问 MIUI（`MiuiMultiWindowUtils`），它返回 null 时退到我们自己造的 freeform options ——
     * MIUI 那份名单里没有的应用（典型：`com.android.settings`）否则永远不会小窗，
     * 表现为"长按磁贴没反应"（真机反馈 2026-10-14）。
     *
     * [pendingIntentLaunch] = true 时补 `setPendingIntentLaunchFlags(0x18000000)`（NEW_TASK |
     * MULTIPLE_TASK）—— 从 MIUI 自己的通知迷你窗抄来（`AppMiniWindowManagerImpl$launchMiniWindowActivity$1.run()`）：
     * 应用已经有一个全屏任务时，没有 MULTIPLE_TASK 的话 PendingIntent 只会把那个任务提到前台（仍旧全屏）。
     */
    private fun freeformBundle(context: Context, packageName: String, pendingIntentLaunch: Boolean): Bundle? {
        val miuiOptions = runCatching {
            activityOptionsMethod?.invoke(null, context, packageName, true, false) as? ActivityOptions
        }.getOrNull()
        val usedMiui = miuiOptions != null
        val options = miuiOptions ?: ownFreeformOptions() ?: return null
        if (!usedMiui) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_FALLBACK_OPTIONS pkg=$packageName")
        }
        // `setFreeformAnimation` / `setPendingIntentLaunchFlags` 都是 @hide（MIUI 自己在
        // AppMiniWindowManagerImpl 里也调它们）；模块编的是公开 SDK，所以反射调用，失败就退回默认。
        runCatching {
            ActivityOptions::class.java.getMethod("setFreeformAnimation", Boolean::class.javaPrimitiveType)
                .invoke(options, false)
        }
        if (pendingIntentLaunch) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setPendingIntentLaunchFlags", Int::class.javaPrimitiveType)
                    .invoke(options, PENDING_INTENT_LAUNCH_FLAGS)
            }.onFailure {
                module.log(Log.WARN, TAG, "LIGHT_OPEN_NO_PENDING_INTENT_FLAGS", it)
            }
        }
        val bundle = runCatching { options.toBundle() }.getOrNull() ?: return null
        // MIUI 的通知启动 bundle 自带这个标志（android.pendingIntent.backgroundActivityAllowed=1），
        // 我们自己造 bundle 时补上，否则后台启动限制会拦掉这次 launch。
        bundle.putInt("android.pendingIntent.backgroundActivityAllowed", 1)
        return bundle
    }

    /** 不依赖 MIUI 名单的兜底：直接要一个 FREEFORM 窗口模式（`WINDOWING_MODE_FREEFORM = 5`）。 */
    private fun ownFreeformOptions(): ActivityOptions? = runCatching {
        val method = setLaunchWindowingModeMethod ?: return null
        val options = ActivityOptions.makeBasic()
        method.invoke(options, WINDOWING_MODE_FREEFORM)
        options
    }.getOrNull()

    /** 写失败要能看见：字段名在 ROM 更新里改掉的话，钩子会静默失效。 */
    private fun writeField(owner: Any?, name: String, value: Any?): Boolean = runCatching {
        val field: Field = owner!!.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(owner, value)
        true
    }.getOrElse {
        module.log(Log.WARN, TAG, "LIGHT_OPEN_FIELD_WRITE_FAILED $name", it)
        false
    }

    private fun readField(owner: Any?, name: String): Any? = runCatching {
        val field: Field = owner!!.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.get(owner)
    }.getOrNull()

    private fun loadClass(name: String): Class<*>? {
        var last: Throwable? = null
        // 与 LightOpenGate 同一套回退：热重载后第一项（旧实例的 loader）可能失效，
        // 中间两项（本实例 loader / 系统 Context loader）才是宿主真正那条链。
        for (candidate in listOf(loader, javaClass.classLoader, ClassLoader.getSystemClassLoader(), null)) {
            try {
                return Class.forName(name, false, candidate)
            } catch (throwable: Throwable) {
                last = throwable
            }
        }
        module.log(Log.WARN, TAG, "LIGHT_OPEN_SYSTEMUI_CLASS_MISSING $name", last)
        return null
    }

    private companion object {
        const val TAG = "LightOpenSystemUi"

        /** 通知栏列表里点通知。 */
        const val NOTIFICATION_INJECTED_CLASS =
            "com.android.systemui.statusbar.phone" +
                ".StatusBarNotificationActivityStarterInjectorImpl\$startNotificationIntentInjected\$1"

        /** 悬浮通知（灵动岛/横幅）点开。 */
        const val ISLAND_CLASS =
            "com.android.systemui.statusbar.phone" +
                ".StatusBarNotificationActivityStarterInjectorImpl\$startIslandIntent\$1"

        /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK（MIUI 通知迷你窗用的同一份值）。 */
        const val PENDING_INTENT_LAUNCH_FLAGS = 0x18000000

        /** ViewConfiguration/WM 的 WINDOWING_MODE_FREEFORM。 */
        const val WINDOWING_MODE_FREEFORM = 5
    }
}
