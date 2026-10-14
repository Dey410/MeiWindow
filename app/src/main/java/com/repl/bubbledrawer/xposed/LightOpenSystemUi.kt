package com.repl.bubbledrawer.xposed

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.repl.bubbledrawer.lightopen.LightOpen
import com.repl.bubbledrawer.lightopen.LightOpenDecision
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

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
 * 桌面的小窗快捷方式都是这么干的）。
 *
 * ## 结构：一个"上膛 → 消费"的漏斗（2026-10-14 真机两轮反馈后改成这样）
 *
 * SystemUI 里所有"点通知/长按磁贴"的启动最后都汇到同一个地方造 options：
 * `ActivityStarterUtilsKt.createActivityOptions(callerDisplayId, RemoteAnimationAdapter)`。
 * 于是：
 *
 * 1. **上膛**：在各个入口（通知的两个 Runnable、控制中心长按、`ActivityStarter.post…`）算好这次
 *    启动该不该小窗，把 `(目标包, 是否 PendingIntent, 用的哪个 Context)` 放进一个带 TTL 的槽；
 * 2. **消费**：`createActivityOptions` 被调到时就地把它换成小窗 bundle，MIUI 自己的锁屏解锁、
 *    通知动画、面板收起、启动动画全部保持原样（不再像上一版那样"接管这次启动"）。
 *
 * 这样做的原因：真机上"长按磁贴没反应"的两种可能（磁贴不是 `QSTileImpl` 子类、MIUI 名单里没有
 * 这个包）都能被覆盖，而且每条路径都会留一行日志（`LIGHT_OPEN_ARM_*` / `LIGHT_OPEN_OPTIONS_*`），
 * 下次再"没反应"能直接看出是哪一环。
 *
 * ## 通知：两条完全不同的入口
 *
 * | 入口 | 落点 | 备注 |
 * |---|---|---|
 * | 通知栏列表里点通知 | `…InjectorImpl$startNotificationIntentInjected$1.run()` | bundle 由 `startNotificationIntentPublic` 算好 |
 * | 悬浮通知（灵动岛/横幅）点开 | `…InjectorImpl$startIslandIntent$1.run()` / `postStartActivityDismissingKeyguard(PendingIntent, …)` | 前者 bundle 是空 options |
 */
class LightOpenSystemUi(
    private val module: XposedModule,
    private val prefs: SharedPreferences,
    private val contextProvider: () -> Context? = { null },
) {
    private val watcher = LightOpenWatcher(prefs)
    private var loader: ClassLoader? = null
    private var cachedContext: Context? = null

    /** 获取可用的 Context，依次尝试传入的 fallback、缓存值、contextProvider()、ActivityThread.currentApplication()。 */
    private fun getContext(fallback: Context? = null): Context? {
        if (fallback != null) {
            cachedContext = fallback
            return fallback
        }
        cachedContext?.let { return it }
        val provided = runCatching { contextProvider() }.getOrNull()
        if (provided != null) {
            cachedContext = provided
            return provided
        }
        val app = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
        if (app != null) {
            cachedContext = app
            return app
        }
        return null
    }

    /** 一次"上膛"：下一步 `createActivityOptions` 该给哪个包塞小窗 options。 */
    private data class Armed(
        val packageName: String,
        val pendingIntentLaunch: Boolean,
        val source: String,
        val decision: LightOpenDecision,
        val context: Context?,
        val expiresAtUptime: Long,
    )

    private val armed = AtomicReference<Armed?>(null)

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
        getContext()
        installNotificationHook(NOTIFICATION_INJECTED_CLASS, "injected")
        installNotificationHook(ISLAND_CLASS, "island")
        installNotificationArmHook()
        installDynamicIslandAnimHook()
        installControlCenterHook()
        installTileViewLongClickHook()
        installPendingIntentArms()
        installOptionsFunnel()
        installPendingIntentSendHook()
        installContextLaunchHook()
    }

    // ------------------------------------------------------------------ 悬浮通知（灵动岛 / 横幅通知）

    /**
     * 悬浮通知（灵动岛/横幅）在屏幕已解锁时的点击入口：
     * `DynamicIslandWindowAnimController.openAppUseDefaultAnim(String packageName, PendingIntent pendingIntent)`
     *
     * 真机取证（HyperOS SystemUI）：
     * 点击悬浮通知时，`DynamicIslandController.onDynamicPluginCallback_openApp` 会调用
     * `animController.openAppUseDefaultAnim(packageName, pendingIntent)`。
     * 原方法内部仅构建一个普通全屏的 ActivityOptions 并直接调用 `pendingIntent.send(bundle)`。
     *
     * 在这里拦截：如果判定为小窗，则使用小窗 bundle 启动并更新控制器状态，不走原本全屏启动逻辑。
     */
    private fun installDynamicIslandAnimHook() {
        val animClass = runCatching {
            loadClass("com.android.systemui.statusbar.notification.DynamicIslandWindowAnimController")
        }.getOrNull()
        if (animClass == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_ISLAND_ANIM_UNAVAILABLE class=false")
            return
        }
        val method = runCatching {
            animClass.getDeclaredMethod("openAppUseDefaultAnim", String::class.java, PendingIntent::class.java)
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_ISLAND_ANIM_UNAVAILABLE method=false")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.island.defaultanim")
            .intercept { chain ->
                val settings = watcher.current()
                val packageName = chain.args.getOrNull(0) as? String
                val pendingIntent = chain.args.getOrNull(1) as? PendingIntent
                val animController = chain.thisObject
                val context = getContext(readField(animController, "context") as? Context)
                val portrait = context?.let { isPortrait(it) } ?: true
                val decision = if (!settings.enabled || packageName == null || packageName in SYSTEM_PACKAGES) {
                    LightOpenDecision.LEAVE
                } else {
                    LightOpen.notificationDecision(settings, packageName, portrait)
                }
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_ISLAND_ANIM_ENTER pkg=$packageName portrait=$portrait decision=$decision " +
                        "pi=${pendingIntent != null} ${settings.summary()}",
                )
                if (decision != LightOpenDecision.FORCE_FREEFORM || packageName == null || pendingIntent == null || context == null) {
                    return@intercept chain.proceed()
                }
                val bundle = freeformBundle(context, packageName, pendingIntentLaunch = true)
                if (bundle == null) {
                    module.log(Log.WARN, TAG, "LIGHT_OPEN_ISLAND_ANIM_NO_BUNDLE pkg=$packageName")
                    return@intercept chain.proceed()
                }
                module.log(Log.INFO, TAG, "LIGHT_OPEN_ISLAND_ANIM_APPLIED pkg=$packageName")
                writeField(animController, "mPendingIntent", pendingIntent)
                runCatching {
                    pendingIntent.send(context, 0, null, null, null, null, bundle)
                }.onFailure { err ->
                    module.log(Log.WARN, TAG, "LIGHT_OPEN_ISLAND_ANIM_SEND_FAILED pkg=$packageName", err)
                    return@intercept chain.proceed()
                }
                null
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_ISLAND_ANIM_HOOK_INSTALLED")
    }

    // ------------------------------------------------------------------ 兜底：进程内所有"真正发起的启动"

    /**
     * `PendingIntent.send(...)` / `PendingIntent.sendAndReturnResult(...)` ——
     * 覆盖所有带 options Bundle 参数的 send 重载（包含 1 参的 `send(Bundle)`、7 参的
     * `send(Context, int, Intent, ..., Bundle)` 以及 `sendAndReturnResult`）。
     *
     * 无论悬浮通知还是列表通知，最终发起的 PendingIntent 都带着 options Bundle，在此处作为统一兜底。
     */
    private fun installPendingIntentSendHook() {
        val onFinished = runCatching { loadClass("android.app.PendingIntent\$OnFinished") }.getOrNull()
        val candidates = PendingIntent::class.java.declaredMethods.filter { candidate ->
            (candidate.name == "send" || candidate.name == "sendAndReturnResult") &&
                candidate.parameterTypes.contains(Bundle::class.java)
        }
        if (candidates.isEmpty()) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_SEND_TARGET_UNAVAILABLE")
            return
        }
        for ((index, candidate) in candidates.withIndex()) {
            val bundleIndex = candidate.parameterTypes.indexOfFirst { it == Bundle::class.java }
            if (bundleIndex < 0) continue
            module.hook(candidate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.lightopen.send$index")
                .intercept { chain ->
                    val pendingIntent = chain.thisObject as? PendingIntent
                    val fillIn = chain.args.firstOrNull { it is Intent } as? Intent
                    val context = getContext(chain.args.firstOrNull { it is Context } as? Context)
                    val settings = watcher.current()
                    if (!settings.enabled || pendingIntent == null || context == null) {
                        return@intercept chain.proceed()
                    }

                    // 如果已经注入过小窗 bundle，直接放行，避免重复处理
                    val currentBundle = chain.args.getOrNull(bundleIndex) as? Bundle
                    if (currentBundle?.getBoolean("bubbledrawer.lightopen.applied") == true) {
                        module.log(
                            Log.INFO,
                            TAG,
                            "LIGHT_OPEN_SEND_ALREADY_APPLIED name=${candidate.name} n=${candidate.parameterCount}",
                        )
                        return@intercept chain.proceed()
                    }

                    val innerIntent = runCatching {
                        PendingIntent::class.java.getMethod("getIntent").invoke(pendingIntent) as? Intent
                    }.getOrNull()
                    val creator = runCatching { pendingIntent.creatorPackage }.getOrNull()
                    val innerPackage = innerIntent?.component?.packageName ?: innerIntent?.`package`
                    val fillInPackage = fillIn?.component?.packageName ?: fillIn?.`package`
                    val target = listOf(innerPackage, fillInPackage, creator)
                        .firstOrNull { it != null && it !in SYSTEM_PACKAGES }
                    val kind = classifyLaunch()
                    val decision = decideLaunch(settings, kind, target, context)
                    module.log(
                        Log.INFO,
                        TAG,
                        "LIGHT_OPEN_SEND_CALL name=${candidate.name} n=${candidate.parameterCount} kind=$kind target=$target " +
                            "inner=$innerPackage fillIn=$fillInPackage creator=$creator decision=$decision " +
                            "bundleIndex=$bundleIndex stack=${stackSummary()}",
                    )
                    if (decision != LightOpenDecision.FORCE_FREEFORM || target == null) {
                        return@intercept chain.proceed()
                    }
                    val bundle = freeformBundle(context, target, pendingIntentLaunch = true)
                        ?: return@intercept chain.proceed()
                    val args = chain.args.toMutableList()
                    args[bundleIndex] = bundle
                    module.log(Log.INFO, TAG, "LIGHT_OPEN_SEND_APPLIED name=${candidate.name} n=${candidate.parameterCount} pkg=$target")
                    chain.proceed(args.toTypedArray())
                }
        }
        module.log(
            Log.INFO,
            TAG,
            "LIGHT_OPEN_SEND_HOOK_INSTALLED methods=${candidates.size} onFinished=${onFinished != null}",
        )
    }

    /** 调用栈摘要（跳过我们自己和反射/框架的帧），用于定位"到底是谁在发起这次启动"。 */
    private fun stackSummary(): String = Throwable().stackTrace
        .asSequence()
        .filterNot { frame ->
            val name = frame.className
            name.startsWith("com.repl.bubbledrawer") || name.startsWith("java.lang.reflect") ||
                name.startsWith("io.github.libxposed") || name.startsWith("android.app.PendingIntent") ||
                name.startsWith("android.app.ContextImpl") || name.startsWith("android.os.Handler")
        }
        .take(6)
        .joinToString("<") { "${it.className.substringAfterLast('.')}.${it.methodName}" }

    /**
     * `ContextImpl.startActivity(Intent, Bundle)` —— SystemUI（含插件）**进程内**直接发起的启动
     * 都会经过这里（控制中心磁贴、面板卡片、灵动岛自绘视图……）。带上我们的小窗 bundle 再 proceed。
     */
    private fun installContextLaunchHook() {
        val implClass = runCatching { loadClass("android.app.ContextImpl") }.getOrNull()
        val method = runCatching {
            implClass?.getDeclaredMethod("startActivity", Intent::class.java, Bundle::class.java)
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_CONTEXT_LAUNCH_TARGET_UNAVAILABLE")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.context.start")
            .intercept { chain ->
                val intent = chain.args.getOrNull(0) as? Intent
                val settings = watcher.current()
                val context = getContext(chain.thisObject as? Context)
                if (!settings.enabled || intent == null || context == null) {
                    return@intercept chain.proceed()
                }
                val target = targetPackage(context, intent)
                val kind = classifyLaunch()
                val decision = decideLaunch(settings, kind, target, context)
                // 只记"目标像一个真实应用"的启动：SystemUI 自己的窗口操作太多，全打会淹没日志。
                if (target == null || target in SYSTEM_PACKAGES) return@intercept chain.proceed()
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_CONTEXT_LAUNCH_ENTER kind=$kind target=$target decision=$decision " +
                        "options=${chain.args.getOrNull(1) != null} stack=${stackSummary()}",
                )
                if (decision != LightOpenDecision.FORCE_FREEFORM) {
                    return@intercept chain.proceed()
                }
                val bundle = freeformBundle(context, target, pendingIntentLaunch = kind == LaunchKind.NOTIFICATION)
                    ?: return@intercept chain.proceed()
                module.log(Log.INFO, TAG, "LIGHT_OPEN_CONTEXT_LAUNCH_APPLIED kind=$kind pkg=$target")
                chain.proceed(arrayOf<Any?>(intent, bundle))
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_CONTEXT_LAUNCH_HOOK_INSTALLED")
    }

    /** 按来源选规则：灵动岛/通知用"通知"规则，磁贴/控制中心用"控制中心"规则，其它不干预。 */
    private fun decideLaunch(
        settings: com.repl.bubbledrawer.lightopen.LightOpenSettings,
        kind: LaunchKind,
        target: String?,
        context: Context,
    ): LightOpenDecision = when {
        target == null || target in SYSTEM_PACKAGES -> LightOpenDecision.LEAVE
        kind == LaunchKind.NOTIFICATION ->
            LightOpen.notificationDecision(settings, target, isPortrait(context))
        kind == LaunchKind.CONTROL_CENTER -> LightOpen.controlCenterDecision(settings, target)
        else -> LightOpen.notificationDecision(settings, target, isPortrait(context))
    }

    // ------------------------------------------------------------------ 通知的统一入口（最重要的一个）

    /**
     * `StatusBarNotificationActivityStarter.startNotificationIntentPublic(PendingIntent, Intent, NotificationEntry)`：
     * **所有**"点通知"最终都进这里（通知栏列表、悬浮通知横幅、灵动岛、以及插件画的那些视图），
     * 它在 803 行调 `ActivityStarterUtilsKt.createActivityOptions(...)` 造 bundle —— 也就是我们
     * 漏斗那一头。所以在这里按通知规则上膛，就能覆盖全部通知点击路径（插件里的也算，因为这个类
     * 在 SystemUI 自己的 dex 里）。
     *
     * 之前只钩了两个内部 Runnable（`…Injected$1` / `…startIslandIntent$1`），真机上"悬浮通知点开"
     * 一条都没命中 —— 说明它走的是别的分支；现在从源头兜住。
     */
    private fun installNotificationArmHook() {
        val starterClass = runCatching {
            loadClass("com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter")
        }.getOrNull()
        if (starterClass == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_NOTIFICATION_ARM_TARGET_UNAVAILABLE class=false")
            return
        }
        // 真机签名是老长一串（7 个参数）：
        //   startNotificationIntentPublic(PendingIntent, Intent, NotificationEntry,
        //                                 ExpandableNotificationRow, boolean, boolean, boolean)
        // 所以按**名字 + 参数最多**去找，别写死参数表 —— 上一版写死 3 个参数就是这样
        // 得到 `LIGHT_OPEN_NOTIFICATION_ARM_TARGET_UNAVAILABLE` 的。
        val method = runCatching {
            starterClass.declaredMethods
                .filter { it.name == "startNotificationIntentPublic" }
                .maxByOrNull { it.parameterCount }
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_NOTIFICATION_ARM_TARGET_UNAVAILABLE method=false")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.arm.notification")
            .intercept { chain ->
                val settings = watcher.current()
                if (!settings.enabled) return@intercept chain.proceed()
                val pendingIntent = chain.args.getOrNull(0) as? PendingIntent
                val intent = chain.args.getOrNull(1) as? Intent
                val entry = chain.args.getOrNull(2)
                val row = chain.args.getOrNull(3) as? android.view.View
                val context = getContext(row?.context)
                // 真机取证（2026-10-14 19:43）：`creatorPackage` 对**通知**并不可靠 ——
                // 点通知时 MIUI 传进来的 PendingIntent 有 `pkg=android`（框架造的）与
                // `pkg=com.android.systemui`（SystemUI 自己造的）两种，都不是发通知的应用。
                // 发通知的应用在 NotificationEntry 的 SBN 上（`mSbn.packageName`），那才是"这个通知属于谁"。
                val sbnPackage = entryPackage(entry)
                val intentPackage = intent?.component?.packageName ?: intent?.`package`
                val creatorPackage = runCatching { pendingIntent?.creatorPackage }.getOrNull()
                val packageName = sbnPackage ?: intentPackage ?: creatorPackage
                val portrait = context?.let { isPortrait(it) } ?: true
                val decision = if (packageName == null || packageName in SYSTEM_PACKAGES) {
                    LightOpenDecision.LEAVE
                } else {
                    LightOpen.notificationDecision(settings, packageName, portrait)
                }
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_NOTIFICATION_ARM_ENTER pkg=$packageName sbn=$sbnPackage intent=$intentPackage " +
                        "creator=$creatorPackage portrait=$portrait decision=$decision " +
                        "entry=${entry != null} ${settings.summary()}",
                )
                if (decision == LightOpenDecision.FORCE_FREEFORM && packageName != null && context != null) {
                    arm(
                        packageName = packageName,
                        pendingIntentLaunch = true,
                        source = "notifPublic",
                        decision = decision,
                        context = context,
                    )
                }
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_NOTIFICATION_ARM_HOOK_INSTALLED params=${method.parameterCount}")
    }

    // ------------------------------------------------------------------ 磁贴（含插件磁贴）

    /**
     * 控制中心磁贴的**长按**最终是视图里的
     * `MiuiQSTileBaseView$$ExternalSyntheticLambda2.onLongClick(View)` → `tile.longClick(null)`
     * （`QSTile` 是插件接口，磁贴本体在**插件 APK** 里，`tile.longClick` 的实现我们钩不到；
     * 但这个 lambda 在 SystemUI 自己的 dex 里，而且它捕获了磁贴对象 `f$0`）。
     *
     * 于是：钩这个 lambda 拿到磁贴对象 → 反射问它的 `getLongClickIntent()`（`MiuiQSTile` 与
     * `QSTileImpl` 都有这个方法）→ 按控制中心规则上膛，然后放行（磁贴自己照常走 `longClick`）。
     */
    private fun installTileViewLongClickHook() {
        val viewClass = runCatching {
            loadClass("com.android.systemui.qs.tileimpl.MiuiQSTileBaseView")
        }.getOrNull()
        val lambdaClass = runCatching {
            loadClass("com.android.systemui.qs.tileimpl.MiuiQSTileBaseView\$\$ExternalSyntheticLambda2")
        }.getOrNull()
        val method = runCatching {
            lambdaClass?.getDeclaredMethod("onLongClick", android.view.View::class.java)
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_TILE_VIEW_TARGET_UNAVAILABLE view=${viewClass != null}")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.arm.tileview")
            .intercept { chain ->
                val settings = watcher.current()
                if (!settings.enabled || !settings.controlCenter) return@intercept chain.proceed()
                val tile = readField(chain.thisObject, "f\$0")
                val view = chain.args.getOrNull(0) as? android.view.View
                val tileContext = tile?.let { readField(it, "mContext") as? Context }
                val context = getContext(view?.context ?: tileContext)
                val intent = tile?.let { longClickIntentOf(it) }
                val target = intent?.let { context?.let { ctx -> targetPackage(ctx, it) } }
                val decision = LightOpen.controlCenterDecision(settings, target)
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_TILE_VIEW_ENTER tile=${tile?.javaClass?.simpleName} target=$target " +
                        "decision=$decision ${settings.summary()}",
                )
                if (decision == LightOpenDecision.FORCE_FREEFORM && target != null && context != null) {
                    arm(target, pendingIntentLaunch = false, source = "tileView", decision = decision, context = context)
                }
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_TILE_VIEW_HOOK_INSTALLED")
    }

    /** 磁贴的 `getLongClickIntent()`：类或它实现的接口上找（`MiuiQSTile` / `QSTileImpl` 都有）。 */
    private fun longClickIntentOf(tile: Any): Intent? {
        var type: Class<*>? = tile.javaClass
        while (type != null) {
            runCatching {
                type.getDeclaredMethod("getLongClickIntent").also { it.isAccessible = true }.invoke(tile)
            }.getOrNull()?.let { return it as? Intent }
            for (face in type.interfaces) {
                runCatching { face.getMethod("getLongClickIntent").invoke(tile) }
                    .getOrNull()?.let { return it as? Intent }
            }
            type = type.superclass
        }
        return null
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
                val context = getContext((readField(self, "this\$0")?.let { readField(it, "mContext") }) as? Context)
                if (pendingIntent == null || context == null) {
                    return@intercept chain.proceed()
                }
                val packageName = runCatching { pendingIntent.creatorPackage }.getOrNull()
                val portrait = isPortrait(context)
                val settings = watcher.current()
                val decision = LightOpen.notificationDecision(settings, packageName, portrait)
                if (settings.enabled) {
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

    // ------------------------------------------------------------------ 控制中心长按

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
                val context = getContext(readField(tile, "mContext") as? Context)
                val settings = watcher.current()
                if (context == null || !settings.enabled || !settings.controlCenter) {
                    return@intercept chain.proceed()
                }
                val intent = runCatching {
                    tile.javaClass.getMethod("getLongClickIntent").invoke(tile) as? Intent
                }.getOrNull()
                val target = intent?.let { targetPackage(context, it) }
                val decision = LightOpen.controlCenterDecision(settings, target)
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_CONTROL_CENTER_ENTER tile=${tile.javaClass.simpleName} target=$target " +
                        "decision=$decision",
                )
                if (target == null || decision != LightOpenDecision.FORCE_FREEFORM) {
                    return@intercept chain.proceed()
                }
                // 只上膛，不接管：这次启动仍旧走 MIUI 自己的 ActivityStarter（动画/面板收起都保持原样），
                // 只是它造 options 的时候会把小窗 bundle 拿走。
                arm(target, pendingIntentLaunch = false, source = "tile", decision = decision, context = context)
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_CONTROL_CENTER_HOOK_INSTALLED")
    }

    // ------------------------------------------------------------------ PendingIntent 入口（悬浮通知）

    /**
     * `悬浮通知点开`真机走的不是通知那两个 Runnable：它进的是 SystemUI 的
     * `ActivityStarter.postStartActivityDismissingKeyguard(PendingIntent, Controller)`
     * （点开一条横幅 = "启动这个 PendingIntent 并收起锁屏/面板"）。
     *
     * 这里按**通知**的规则上膛（方向 + `creatorPackage`），并且加两道保守限制，避免顺手把
     * SystemUI 自己的跳转也小窗了：creator 必须是普通应用（uid ≥ 10000），且必须是普通 Activity 启动。
     *
     * **Intent 重载**（`postStartActivityDismissingKeyguard(Intent, …)`）是**控制中心磁贴长按**用的：
     * 磁贴本体在插件 APK（`miui.systemui.plugin`）里，实测它调的就是这个 API
     * （插件 dex 里 `postStartActivityDismissingKeyguard` 出现 2 次，`ActivityStarter` 18 次），
     * 而磁贴自己的 `longClick` 实现我们钩不到 —— 所以在这一层按控制中心规则兜住。
     */
    private fun installPendingIntentArms() {
        val starterClass = runCatching { loadClass("com.android.systemui.statusbar.phone.ActivityStarterImpl") }
            .getOrNull()
        val controllerClass = runCatching {
            loadClass("com.android.systemui.animation.ActivityTransitionAnimator\$Controller")
        }.getOrNull()
        if (starterClass == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_ARM_TARGET_UNAVAILABLE")
            return
        }
        val pendingIntentSignatures = buildList {
            add(arrayOf(PendingIntent::class.java))
            if (controllerClass != null) add(arrayOf(PendingIntent::class.java, controllerClass))
        }
        var installed = 0
        for (signature in pendingIntentSignatures) {
            val method = runCatching {
                starterClass.getDeclaredMethod("postStartActivityDismissingKeyguard", *signature)
            }.getOrNull() ?: continue
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.lightopen.arm.pendingintent$installed")
                .intercept { chain ->
                    val pendingIntent = chain.args.firstOrNull() as? PendingIntent
                    val context = getContext(readField(chain.thisObject, "mContext") as? Context)
                    if (pendingIntent != null && context != null) {
                        armFromPendingIntent(pendingIntent, context, "post.pi")
                    }
                    chain.proceed()
                }
            installed++
        }
        // Intent 重载（控制中心磁贴；插件的 longClick 走这里）
        val intentSignatures = buildList {
            add(arrayOf(Intent::class.java, Int::class.javaPrimitiveType))
            if (controllerClass != null) {
                add(arrayOf(Intent::class.java, Int::class.javaPrimitiveType, controllerClass))
                add(
                    arrayOf(
                        Intent::class.java,
                        Int::class.javaPrimitiveType,
                        controllerClass,
                        String::class.java,
                    ),
                )
            }
        }
        var intentInstalled = 0
        for (signature in intentSignatures) {
            val method = runCatching {
                starterClass.getDeclaredMethod("postStartActivityDismissingKeyguard", *signature)
            }.getOrNull() ?: continue
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.lightopen.arm.intent$intentInstalled")
                .intercept { chain ->
                    val intent = chain.args.firstOrNull() as? Intent
                    val context = getContext(readField(chain.thisObject, "mContext") as? Context)
                    if (intent != null && context != null) {
                        armFromIntent(intent, context, "post.intent")
                    }
                    chain.proceed()
                }
            intentInstalled++
        }
        module.log(
            Log.INFO,
            TAG,
            "LIGHT_OPEN_ARM_PENDING_INTENT_INSTALLED count=$installed intent=$intentInstalled",
        )
    }

    private fun armFromIntent(intent: Intent, context: Context, source: String) {
        val settings = watcher.current()
        if (!settings.enabled) return
        val target = targetPackage(context, intent)
        // 同一个 API 既被控制中心磁贴用、也被灵动岛/焦点通知用（真机取证：插件 dex 里
        // `miui.systemui.util.FocusNotificationUtil` 与各 `*Tile` 都调它），靠调用栈区分是谁发起的。
        val kind = classifyLaunch()
        val decision = when (kind) {
            LaunchKind.NOTIFICATION ->
                LightOpen.notificationDecision(settings, target, isPortrait(context))
            LaunchKind.CONTROL_CENTER -> LightOpen.controlCenterDecision(settings, target)
            LaunchKind.UNKNOWN -> LightOpenDecision.LEAVE
        }
        module.log(
            Log.INFO,
            TAG,
            "LIGHT_OPEN_ARM_INTENT_ENTER source=$source kind=$kind target=$target " +
                "decision=$decision ${settings.summary()}",
        )
        if (target == null || decision != LightOpenDecision.FORCE_FREEFORM) return
        arm(
            packageName = target,
            // 通知点开是"启动一个 PendingIntent 语义"（可能已有全屏任务）→ 需要 NEW_TASK|MULTIPLE_TASK；
            // 磁贴长按是普通 startActivity → 不需要。
            pendingIntentLaunch = kind == LaunchKind.NOTIFICATION,
            source = "$source/$kind",
            decision = decision,
            context = context,
        )
    }

    private enum class LaunchKind { NOTIFICATION, CONTROL_CENTER, UNKNOWN }

    /**
     * 调用栈分类：`postStartActivityDismissingKeyguard(Intent, …)` 是所有 SystemUI/插件跳转的公共出口，
     * 但"点通知"和"长按磁贴"要用两套不同的规则，只能看是谁调的。
     * 通知优先（灵动岛/焦点通知的类名更容易识别），磁贴作为兜底。
     */
    private fun classifyLaunch(): LaunchKind {
        var kind = LaunchKind.UNKNOWN
        for (frame in Throwable().stackTrace) {
            val name = frame.className
            if (name.contains("FocusNotification") || name.contains("miui.systemui.notification") ||
                name.contains("DynamicIsland") || name.contains("dynamicisland") ||
                name.contains("Notification") || name.contains("notification") ||
                name.contains("Notif") || name.contains("HeadsUp") || name.contains("headsUp") ||
                name.contains("Island") || name.contains("island")
            ) {
                return LaunchKind.NOTIFICATION
            }
            if (name.contains("quicksettings") || name.contains("controlcenter") ||
                name.contains("qs.tileimpl") || name.contains("MiuiQSTile") ||
                name.contains("android.systemui.qs") || name.contains("QSTile")
            ) {
                kind = LaunchKind.CONTROL_CENTER
            }
        }
        return kind
    }

    private fun armFromPendingIntent(pendingIntent: PendingIntent, context: Context, source: String) {
        val settings = watcher.current()
        if (!settings.enabled) return
        val creatorPackage = runCatching { pendingIntent.creatorPackage }.getOrNull()
        val creatorUid = runCatching { pendingIntent.creatorUid }.getOrDefault(-1)
        // 点悬浮通知时 MIUI 递进来的是一个 **SystemUI 自己造的** PendingIntent（真机日志：
        // `pkg=com.android.systemui uid=10231`，因为 SystemUI 是普通应用 uid，按 uid 过滤挡不住），
        // 所以 creator 不能直接当"通知属于谁"，要退回它包着的那个真实 Intent（`pi.getIntent()`，@hide）。
        val innerIntent = runCatching {
            PendingIntent::class.java.getMethod("getIntent").invoke(pendingIntent) as? Intent
        }.getOrNull()
        val innerPackage = innerIntent?.component?.packageName ?: innerIntent?.`package`
        val packageName = creatorPackage?.takeIf { it !in SYSTEM_PACKAGES }
            ?: innerPackage?.takeIf { it !in SYSTEM_PACKAGES }
        val portrait = isPortrait(context)
        val decision = LightOpen.notificationDecision(settings, packageName, portrait)
        module.log(
            Log.INFO,
            TAG,
            "LIGHT_OPEN_ARM_PI_ENTER source=$source pkg=$packageName creator=$creatorPackage uid=$creatorUid " +
                "inner=$innerPackage portrait=$portrait decision=$decision",
        )
        if (decision != LightOpenDecision.FORCE_FREEFORM || packageName == null) return
        arm(packageName, pendingIntentLaunch = true, source = source, decision = decision, context = context)
    }

    /** 通知属于哪个应用：`NotificationEntry.mSbn.packageName`（ExpandedNotification 继承 StatusBarNotification）。 */
    private fun entryPackage(entry: Any?): String? = runCatching {
        val sbn = readField(entry, "mSbn") ?: entry ?: return null
        sbn.javaClass.getMethod("getPackageName").invoke(sbn) as? String
    }.getOrNull()

    // ------------------------------------------------------------------ options 漏斗

    /**
     * `ActivityStarterUtilsKt.createActivityOptions(int callerDisplayId, RemoteAnimationAdapter)`：
     * SystemUI 所有启动最终都在这里造 bundle（通知列表、悬浮通知、磁贴长按、媒体控件……）。
     */
    private fun installOptionsFunnel() {
        val utilsClass = runCatching { loadClass("com.android.systemui.statusbar.phone.ActivityStarterUtilsKt") }
            .getOrNull()
        val adapterClass = runCatching { loadClass("android.view.RemoteAnimationAdapter") }.getOrNull()
        val method = runCatching {
            utilsClass?.getDeclaredMethod(
                "createActivityOptions",
                Int::class.javaPrimitiveType,
                adapterClass,
            )
        }.getOrNull()
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_OPTIONS_TARGET_UNAVAILABLE")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.options")
            .intercept { chain ->
                val pending = armed.getAndSet(null) ?: return@intercept chain.proceed()
                val now = SystemClock.uptimeMillis()
                if (now > pending.expiresAtUptime) {
                    module.log(Log.INFO, TAG, "LIGHT_OPEN_OPTIONS_EXPIRED source=${pending.source} pkg=${pending.packageName}")
                    return@intercept chain.proceed()
                }
                val settings = watcher.current()
                if (!settings.enabled || pending.decision != LightOpenDecision.FORCE_FREEFORM) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "LIGHT_OPEN_OPTIONS_SKIPPED source=${pending.source} pkg=${pending.packageName} " +
                            "why=${if (!settings.enabled) "disabled" else pending.decision}",
                    )
                    return@intercept chain.proceed()
                }
                val context = getContext(pending.context)
                val bundle = context?.let {
                    freeformBundle(it, pending.packageName, pendingIntentLaunch = pending.pendingIntentLaunch)
                }
                if (bundle == null) {
                    module.log(Log.WARN, TAG, "LIGHT_OPEN_OPTIONS_NO_OPTIONS source=${pending.source} pkg=${pending.packageName}")
                    return@intercept chain.proceed()
                }
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_OPTIONS_APPLIED source=${pending.source} pkg=${pending.packageName}",
                )
                bundle
            }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_OPTIONS_HOOK_INSTALLED")
    }

    private fun arm(
        packageName: String,
        pendingIntentLaunch: Boolean,
        source: String,
        decision: LightOpenDecision,
        context: Context?,
    ) {
        armed.set(
            Armed(
                packageName = packageName,
                pendingIntentLaunch = pendingIntentLaunch,
                source = source,
                decision = decision,
                context = context,
                expiresAtUptime = SystemClock.uptimeMillis() + ARM_TTL_MS,
            ),
        )
    }

    private fun isPortrait(context: Context): Boolean =
        context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /** 配置快照摘要：判定不符合预期时，第一眼要看的就是"钩子当时看到的开关是什么"。 */
    private fun com.repl.bubbledrawer.lightopen.LightOpenSettings.summary(): String =
        "settings={enabled=$enabled notifP=$notificationPortrait notifL=$notificationLandscape " +
            "cc=$controlCenter share=$shareToApp text=$textAction jump=$appJump mode=$jumpMode " +
            "targets=$jumpTargetCount}"

    /** 目标包：优先 Intent 的显式组件，其次 `package`，最后交给 PackageManager 解析。 */
    private fun targetPackage(context: Context, intent: Intent): String? {
        runCatching { intent.component?.packageName }.getOrNull()?.let { return it }
        runCatching { intent.`package` }.getOrNull()?.let { return it }
        return runCatching {
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 共用

    /**
     * 小窗 ActivityOptions → Bundle。
     *
     * 先问 MIUI（`MiuiMultiWindowUtils`），它返回 null 时退到我们自己造的 freeform options ——
     * MIUI 那份名单里没有的应用（日志 `unsupported freeform mode`，实测 `com.tencent.mobileqq`、
     * `com.android.settings` 都会命中）否则永远不会小窗，表现为"长按磁贴没反应"（真机反馈）。
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
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType)
                    .invoke(options, 1)
            }
        }
        val bundle = runCatching { options.toBundle() }.getOrNull() ?: return null
        // MIUI 的通知启动 bundle 自带这个标志（android.pendingIntent.backgroundActivityAllowed=1），
        // 我们自己造 bundle 时补上，否则后台启动限制会拦掉这次 launch。
        bundle.putInt("android.pendingIntent.backgroundActivityAllowed", 1)
        bundle.putBoolean("bubbledrawer.lightopen.applied", true)
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
        // 中间两项（本实例 loader / 系统 loader）才是宿主真正那条链。
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

        /** 上膛后等这个漏斗被调的宽限时间（毫秒）。超时就作废，避免影响无关启动。 */
        const val ARM_TTL_MS = 5000L

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

        /**
         * 系统界面自身的包名：它们既不是"发通知的应用"，也不是"要被小窗的目标"。
         * 真机日志里点通知时出现的就是这两个（MIUI 用自己的 PendingIntent 转手）。
         */
        val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")
    }
}
