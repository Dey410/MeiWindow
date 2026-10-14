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
 * 【轻量打开】SystemUI 侧的两个钩子：`竖屏/横屏点通知以小窗打开` 与 `控制中心长按以小窗打开`。
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
 * 反过来说，**SystemUI 自己带过去的 ActivityOptions 会被原样采用** —— 所以这两处改成"由我们把
 * 小窗 options 塞进这次启动"，用的就是 MIUI 自己的官方姿势（`AppMiniWindowManagerImpl`
 * 的迷你窗、桌面的小窗快捷方式都是这么干的）：
 *
 * ```java
 * ActivityOptions opts = MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, /*noCheck*/ true, false);
 * ...
 * pendingIntent.send(ctx, 0, fillIn, null, null, null, opts.toBundle());
 * ```
 *
 * ## 钩子点一：通知点击
 *
 * `StatusBarNotificationActivityStarter.startNotificationIntentPublic(...)` 算出启动用的 Bundle 后，
 * 把它交给 `StatusBarNotificationActivityStarterInjectorImpl$startNotificationIntentInjected$1`
 * （一个 Runnable）在 `UiOffloadThread` 上 `PendingIntent.send(ctx, 0, fillIn, ..., bundle)`。
 * 那个 Runnable 的 `$bundle` 是 **public 非 final 的合成字段** —— 我们在它的 `run()` 之前把字段换掉，
 * MIUI 的锁屏解锁、通知动画、异步发送全部保持原样，只是这次启动的窗口模式变成小窗。
 *
 * ## 钩子点二：控制中心长按
 *
 * `QSTileImpl.handleLongClick(Expandable)` 会 `getLongClickIntent()` 再交给
 * `mActivityStarter.postStartActivityDismissingKeyguard(intent, 0, controller)`。SystemUI 的
 * `ActivityStarter` 接口**没有**带 ActivityOptions 的重载，所以这里直接接管这一次启动：
 * 用小窗 options 起，再尽力收起面板；锁屏状态下不接管（交给 MIUI 走解锁流程）。
 *
 * 已知取舍（真机验证后再决定要不要补）：没有调用 MIUI 的
 * `ControlCenterUtils.getSettingsSplitIntent(ctx, intent)`，也就是说磁贴长按页的"设置分屏"包装被跳过了。
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

    fun install(classLoader: ClassLoader) {
        loader = classLoader
        watcher.refresh()
        installNotificationHook()
        installControlCenterHook()
    }

    // ------------------------------------------------------------------ 通知

    private fun installNotificationHook() {
        // 注意 `$` 在 Kotlin 字符串里要转义：这是 R8 给那个 Runnable 生成的稳定名字。
        val className = "com.android.systemui.statusbar.phone" +
            ".StatusBarNotificationActivityStarterInjectorImpl\$startNotificationIntentInjected\$1"
        val method = runCatching {
            loadClass(className)?.getDeclaredMethod("run")
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_NOTIFICATION_TARGET_UNAVAILABLE")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.notification")
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
                if (decision != LightOpenDecision.FORCE_FREEFORM || packageName == null) {
                    return@intercept chain.proceed()
                }
                val bundle = freeformBundle(context, packageName, pendingIntentLaunch = true)
                if (bundle == null) {
                    module.log(Log.WARN, TAG, "LIGHT_OPEN_NOTIFICATION_NO_OPTIONS pkg=$packageName")
                    return@intercept chain.proceed()
                }
                writeField(self, "\$bundle", bundle)
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_NOTIFICATION pkg=$packageName portrait=$portrait",
                )
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_NOTIFICATION_HOOK_INSTALLED")
    }

    // ------------------------------------------------------------------ 控制中心

    private fun installControlCenterHook() {
        val tileClass = runCatching {
            loadClass("com.android.systemui.qs.tileimpl.QSTileImpl")
        }.getOrNull()
        val expandableClass = runCatching {
            loadClass("com.android.systemui.animation.Expandable")
        }.getOrNull()
        val method = runCatching {
            tileClass?.getDeclaredMethod("handleLongClick", expandableClass)
        }.getOrNull()
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
                // 锁屏交给 MIUI：它要负责解锁流程与动画。
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (keyguard?.isKeyguardLocked == true) return@intercept chain.proceed()

                val intent = runCatching {
                    tile.javaClass.getMethod("getLongClickIntent").invoke(tile) as? Intent
                }.getOrNull() ?: return@intercept chain.proceed()
                val target = targetPackage(context, intent) ?: return@intercept chain.proceed()
                val decision = LightOpen.controlCenterDecision(settings, target)
                if (decision != LightOpenDecision.FORCE_FREEFORM) return@intercept chain.proceed()
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
     * 小窗 ActivityOptions → Bundle。拿不到 options（应用不支持小窗 / MIUI 名单挡住）就返回 null，
     * 调用方保持原生行为 —— 这条路径 MIUI 自己会打
     * `MiuiMultiWindowUtils: pkg name: X unsupported freeform mode`。
     *
     * [pendingIntentLaunch] = true 时补 `setPendingIntentLaunchFlags(0x18000000)`（NEW_TASK |
     * MULTIPLE_TASK）—— 这是从 MIUI 自己的通知迷你窗抄来的
     * （`AppMiniWindowManagerImpl$launchMiniWindowActivity$1.run()`：目标不在迷你窗缓存里时才设）：
     * 应用已经有一个全屏任务时，没有 MULTIPLE_TASK 的话 PendingIntent 只会把那个任务提到前台
     * （于是仍旧全屏），加上它才会真的新开一个任务，窗口模式才有机会落成小窗。
     */
    private fun freeformBundle(context: Context, packageName: String, pendingIntentLaunch: Boolean): Bundle? = runCatching {
        val options = activityOptionsMethod?.invoke(null, context, packageName, true, false) as? ActivityOptions
            ?: return null
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
        val bundle = options.toBundle()
        // MIUI 的通知启动 bundle 自带这个标志（android.pendingIntent.backgroundActivityAllowed=1），
        // 我们自己造 bundle 时补上，否则后台启动限制会拦掉这次 launch。
        bundle.putInt("android.pendingIntent.backgroundActivityAllowed", 1)
        bundle
    }.getOrNull()

    private fun readField(owner: Any?, name: String): Any? = runCatching {
        val field: Field = owner!!.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.get(owner)
    }.getOrNull()

    private fun writeField(owner: Any?, name: String, value: Any?) {
        runCatching {
            val field: Field = owner!!.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(owner, value)
        }.onFailure { module.log(Log.WARN, TAG, "LIGHT_OPEN_FIELD_WRITE_FAILED $name", it) }
    }

    private fun loadClass(name: String): Class<*>? = runCatching {
        Class.forName(name, false, loader ?: javaClass.classLoader)
    }.getOrNull()

    private companion object {
        const val TAG = "LightOpenSystemUi"

        /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK（MIUI 通知迷你窗用的同一份值）。 */
        const val PENDING_INTENT_LAUNCH_FLAGS = 0x18000000
    }
}
