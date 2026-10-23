package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.repl.bubbledrawer.lightopen.FreeformStormGuard
import com.repl.bubbledrawer.lightopen.LightOpen
import com.repl.bubbledrawer.lightopen.LightOpenDecision
import com.repl.bubbledrawer.lightopen.LightOpenSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 【轻量打开】system_server 侧的唯一一个钩子：`分享至应用` / `文本操作` / `应用间跳转`。
 *
 * ## 为什么是这一个方法
 *
 * 真机 ROM（miui-services.jar，OS4.0.0.32）里这条链是：
 *
 * ```
 * ActivityStarter.executeRequest
 *   → ActivityStarterImpl.resolveActivity(Request, ActivityTaskSupervisor)   // miui-services.jar
 *        request.activityOptions = MiuiFreeformServiceStub.getInstance()
 *                                     .checkStartActivityByFreeForm(...)      ← 返回值写回 Request
 *   → MiuiFreeformServiceImpl.checkStartActivityByFreeForm(...) : SafeActivityOptions
 *        关键判定： if (supportFreeformByDeveloper && startActivityByFreeForm(aInfo, intent, fullscreenPackageName))
 *        命中后：  ActivityOptions ff = MiuiMultiWindowUtils.getActivityOptions(ctx, 目标包, true)
 *                  modifyOptionsByFreeFormOptions(opts, ff)      // setLaunchWindowingMode(5) = FREEFORM
 * ```
 *
 * 被我们改返回值的 `startActivityByFreeForm` 本体只是一次**纯查询**（无副作用）：
 *
 * ```java
 * private boolean startActivityByFreeForm(ActivityInfo aInfo, Intent intent, String fullscreenPackageName) {
 *     if (mSecurityManagerInternal == null)
 *         mSecurityManagerInternal = LocalServices.getService(SecurityManagerInternal.class);
 *     return mSecurityManagerInternal.checkGameBoosterPayPassAsUser(
 *             aInfo.packageName, intent, UserHandle.getUserId(aInfo.applicationInfo.uid), fullscreenPackageName);
 * }
 * ```
 *
 * 所以"命中 → 直接返回 true/false；不命中 → chain.proceed()"是安全的：跳过它不丢任何状态，
 * 真正的落窗动作由外层与 ActivityStarter 完成。
 *
 * ## 护栏（判定在 [LightOpen.jumpDecision]，这里只提供输入）
 *
 * ROM 自己只排除：真实调用进程是 SystemUI、aInfo 为空、`needFullScreen`+游戏中心、非默认屏协同，
 * 以及"目标包 == 当前前台包"。**它不排除桌面**：桌面点图标时 caller 是 `com.miui.home`（不是
 * SystemUI）、目标包 != 前台包（前台是桌面）—— 少一条护栏，全局小窗模式下桌面启动也会变小窗。
 * 因此这里额外提供两道：前台是否属于 HOME 包集合（桌面/负一屏/最近任务）、前台包是否为空
 * （锁屏/无明确前台）。
 */
class LightOpenGate(
    private val module: XposedModule,
    private val prefs: SharedPreferences,
) {
    private val watcher = LightOpenWatcher(prefs)

    private var loader: ClassLoader? = null

    @Volatile
    private var homePackages: Set<String> = emptySet()

    @Volatile
    private var homePackagesResolvedAt: Long = 0L

    /** `ActivityTaskSupervisorImpl.MIUI_APP_LOCK_ACTIVITY_NAME`：应用锁穿透用，取不到就算了。 */
    private val appLockActivity: String? by lazy {
        runCatching {
            loadClass("com.android.server.wm.ActivityTaskSupervisorImpl")
                ?.getField("MIUI_APP_LOCK_ACTIVITY_NAME")
                ?.get(null) as? String
        }.getOrNull()
    }

    private val targetMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            loadClass("com.android.server.wm.MiuiFreeformServiceImpl")?.getDeclaredMethod(
                "startActivityByFreeForm",
                ActivityInfo::class.java,
                Intent::class.java,
                String::class.java,
            )?.also { it.isAccessible = true }
        }.getOrNull()
    }

    fun install(classLoader: ClassLoader) {
        loader = classLoader
        watcher.refresh()
        val method = targetMethod
        if (method == null) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_GATE_TARGET_UNAVAILABLE")
            return
        }
        val settings = watcher.current()
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.lightopen.gate")
            .intercept { chain ->
                val args = chain.args
                decide(
                    info = args.getOrNull(0) as? ActivityInfo,
                    intent = args.getOrNull(1) as? Intent,
                    topPackage = args.getOrNull(2) as? String,
                ) ?: chain.proceed()
            }
        module.log(
            Log.INFO,
            TAG,
            "LIGHT_OPEN_GATE_INSTALLED enabled=${settings.enabled} jump=${settings.appJump} " +
                "mode=${settings.jumpMode} targets=${settings.jumpTargetCount} " +
                "share=${settings.shareToApp} text=${settings.textAction}",
        )
        installStormBreaker()
    }

    /**
     * 小窗风暴熔断（用户 2026-10-23，见 [com.repl.bubbledrawer.lightopen.FreeformStormGuard]）：
     * 酷安的通知目标页在小窗里自我重启死循环，每轮开新任务 + ROM 链式继承 freeform，
     * 31 秒弹了上百个小窗。
     *
     * 拦 `MiuiMultiWindowUtils.getActivityOptions` 的全部重载：**每次"要给小窗参数"都过这里**
     * （链式继承 `addLaunchFreeformActivityOptionIfNeed` 造参数、以及闸门命中后 ROM 自己造参数）。
     * 计数达阈值就对该包冷却；冷却期返回 null —— 这个方法本来就允许返回 null
     * （"该应用不给小窗"，MIUI 名单外的应用天天拿到 null），ROM 各调用方都按 null 走原生路径。
     */
    private fun installStormBreaker() {
        val utils = loadClass("android.util.MiuiMultiWindowUtils")
        val methods = utils?.declaredMethods?.filter { it.name == "getActivityOptions" }.orEmpty()
        if (methods.isEmpty()) {
            module.log(Log.WARN, TAG, "LIGHT_OPEN_STORM_TARGET_UNAVAILABLE")
            return
        }
        for ((index, method) in methods.withIndex()) {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.lightopen.storm$index")
                .intercept { chain ->
                    // 签名是 (Context, String 包名, …)，包名永远在 1；兜底再找第一个字符串。
                    val pkg = chain.args.getOrNull(1) as? String
                        ?: chain.args.filterIsInstance<String>().firstOrNull()
                    if (pkg.isNullOrEmpty()) return@intercept chain.proceed()
                    if (FreeformStormGuard.processWide.isSuppressing(pkg)) {
                        module.log(
                            Log.INFO,
                            TAG,
                            "LIGHT_OPEN_STORM_SUPPRESS pkg=$pkg remainingMs=${FreeformStormGuard.processWide.remainingCooldownMs(pkg)}",
                        )
                        return@intercept null
                    }
                    if (FreeformStormGuard.processWide.record(pkg)) {
                        module.log(
                            Log.WARN,
                            TAG,
                            "LIGHT_OPEN_STORM_TRIGGER pkg=$pkg cooldownMs=${FreeformStormGuard.processWide.remainingCooldownMs(pkg)}",
                        )
                    }
                    chain.proceed()
                }
        }
        module.log(Log.INFO, TAG, "LIGHT_OPEN_STORM_BREAKER_INSTALLED methods=${methods.size}")
    }

    /** 返回 null = 不干预（走原生判定）。 */
    private fun decide(info: ActivityInfo?, intent: Intent?, topPackage: String?): Any? {
        // 「模块自己的 App 进程点名要小窗」——两条通道，先后各有理由（见 LightOpen.
        // EXTRA_FREEFORM_REQUEST 与 RemotePrefs.KEY_LAUNCH_PRIME 的注释）：
        //   1. Intent 自带标记：同一次 binder 调用、零延迟，正常情况走它；
        //   2. 一次性口令：安全中心的「xx 想要打开 xx」拦截框若重造 Intent 会丢 extras，
        //      而用户点「允许」之后的**第二次**启动才是真正要落窗的那一次 —— 只剩它能认出来。
        //
        // 这两条判定故意放在**所有开关之前**：跟的是设置页的「小窗启动」，与「轻量打开」那六个
        // 开关无关。也故意不看 `topPackage`：拦截框确认之后前台是 `com.miui.securitycenter`。
        val target = rawTargetPackage(info, intent)
        val marker = intent?.getBooleanExtra(LightOpen.EXTRA_FREEFORM_REQUEST, false) == true
        if (marker && target != null) {
            if (LightOpen.requestedFreeformDecision(target) == LightOpenDecision.FORCE_FREEFORM) {
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_FREEFORM_REQUEST via=intent target=$target top=$topPackage",
                )
                return true
            }
            // 拦截框自己：不接管（它绝不能变成小窗）。
            return null
        }
        val primed = primeTarget()
        if (primed != null && target != null && primed == target &&
            LightOpen.requestedFreeformDecision(target) == LightOpenDecision.FORCE_FREEFORM
        ) {
            module.log(
                Log.INFO,
                TAG,
                "LIGHT_OPEN_FREEFORM_REQUEST via=prime target=$target top=$topPackage",
            )
            return true
        }
        if (marker) return null // 带了标记却解析不出目标：不接管

        val settings = watcher.current()
        if (!settings.enabled) return null
        if (!settings.appJump && !settings.shareToApp && !settings.textAction) return null

        val top = topPackage?.takeIf { it.isNotEmpty() }
        // 「文本操作」判定：
        // 1. 系统 TextClassifier 标记（选中文本后弹出的编辑条/浮动菜单条目，带 EXTRA_FROM_TEXT_CLASSIFIER）
        // 2. Intent 显式携带选中文本 extra（EXTRA_PROCESS_TEXT / EXTRA_TEXT / EXTRA_HTML_TEXT）
        val fromClassifier = intent != null && intent.hasExtra(LightOpen.EXTRA_FROM_TEXT_CLASSIFIER)
        val hasSelectedText = intent != null && (
            fromClassifier ||
                intent.hasExtra(Intent.EXTRA_PROCESS_TEXT) ||
                intent.hasExtra(Intent.EXTRA_TEXT) ||
                intent.hasExtra(Intent.EXTRA_HTML_TEXT)
            )
        val decision = LightOpen.jumpDecision(
            settings = settings,
            action = intent?.action,
            targetPackage = target,
            topFullscreenPackage = top,
            isHomeTop = top != null && isHomePackage(top),
            hasSelectedText = hasSelectedText,
            fromTextClassifier = fromClassifier,
        )
        // 探针：文本开关开着时，把"像文本操作"的每一次都记下来（不管最后判成什么），
        // 这样"误伤"和"该生效却没生效"都能从日志里直接对上。
        if (settings.textAction && isTextProbe(intent)) {
            val extrasKeys = intent?.extras?.keySet()?.joinToString("|") ?: "-"
            val clip = intent?.clipData?.let { "yes(${it.itemCount})" } ?: "no"
            module.log(
                Log.INFO,
                TAG,
                "LIGHT_OPEN_TEXT_PROBE action=${intent?.action} scheme=${runCatching { intent?.data?.scheme }.getOrNull()} " +
                    "type=${intent?.type} comp=${runCatching { intent?.component?.flattenToShortString() }.getOrNull() ?: "-"} " +
                    "flags=0x${(intent?.flags ?: 0).toString(16)} extras=[$extrasKeys] clip=$clip " +
                    "cats=${intent?.categories?.joinToString("|") ?: "-"} target=$target top=$top " +
                    "hasSelectedText=$hasSelectedText fromClassifier=$fromClassifier decision=$decision",
            )
        }
        return when (decision) {
            LightOpenDecision.LEAVE -> null
            LightOpenDecision.FORCE_FREEFORM -> {
                module.log(
                    Log.INFO,
                    TAG,
                    "LIGHT_OPEN_FREEFORM target=$target top=$top action=${intent?.action} " +
                        "why=${why(settings, target, intent, hasSelectedText, fromClassifier)}",
                )
                true
            }
            LightOpenDecision.FORCE_FULLSCREEN -> {
                module.log(Log.INFO, TAG, "LIGHT_OPEN_FULLSCREEN target=$target top=$top")
                false
            }
        }
    }

    private fun why(
        settings: LightOpenSettings,
        target: String?,
        intent: Intent?,
        hasSelectedText: Boolean,
        fromClassifier: Boolean,
    ): String = when {
        settings.appJump && target != null && target in settings.jumpTargets -> "list"
        settings.shareToApp && LightOpen.isShareAction(intent?.action) -> "share"
        settings.textAction && LightOpen.isTextAction(intent?.action, hasSelectedText, fromClassifier) -> "text"
        settings.globalFreeform -> "global"
        else -> "?"
    }

    /** 文本操作探针：`PROCESS_TEXT`/`WEB_SEARCH`，`VIEW`，或者带 TextClassifier extra 的 Intent。 */
    private fun isTextProbe(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        return action == Intent.ACTION_PROCESS_TEXT || action == Intent.ACTION_WEB_SEARCH ||
            action == Intent.ACTION_VIEW || intent.hasExtra(LightOpen.EXTRA_FROM_TEXT_CLASSIFIER)
    }

    /**
     * 一次性口令里的目标包名（没过期才有值，见 [RemotePrefs.KEY_LAUNCH_PRIME]）。
     *
     * 直接读 prefs 而不是 [LightOpenWatcher] 的快照：口令只活 8 秒，而快照最长可能旧 2 秒 ——
     * 那正好是这条通道最需要新鲜的窗口。
     */
    private fun primeTarget(): String? = runCatching {
        val (pkg, deadline) = RemotePrefs.decodeLaunchPrime(
            prefs.getString(RemotePrefs.KEY_LAUNCH_PRIME, null),
        ) ?: return null
        if (android.os.SystemClock.elapsedRealtime() > deadline) return null
        pkg
    }.getOrNull()

    /**
     * ROM 的 `getRawPackageName`：应用锁场景下 Intent 指向的是应用锁 Activity，真实目标在
     * `android.intent.extra.INTENT` 里。不穿透的话，被锁应用的跳转会按 `com.miui.securitycore` 匹配名单。
     */
    private fun rawTargetPackage(info: ActivityInfo?, intent: Intent?): String? {
        val packageName = info?.packageName ?: return null
        val lock = appLockActivity ?: return packageName
        val component = runCatching { intent?.component }.getOrNull() ?: return packageName
        if (runCatching { component.flattenToShortString() }.getOrNull() != lock) return packageName
        val raw = runCatching {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("android.intent.extra.INTENT")
        }.getOrNull() ?: return packageName
        return runCatching { raw.component?.packageName }.getOrNull() ?: packageName
    }

    /** 当前前台包是不是桌面（含第三方桌面）/负一屏这类 HOME 角色。60 秒缓存一次。 */
    private fun isHomePackage(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - homePackagesResolvedAt > HOME_CACHE_MS) {
            homePackages = resolveHomePackages()
            homePackagesResolvedAt = now
        }
        return packageName in homePackages
    }

    private fun resolveHomePackages(): Set<String> = runCatching {
        val context = systemContext() ?: return emptySet()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val result = LinkedHashSet<String>()
        val resolved: List<ResolveInfo> = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolved) {
            info.activityInfo?.packageName?.let(result::add)
        }
        // MIUI 桌面/负一屏/最近任务都在 com.miui.home 下；显式带上，防止 query 结果被 OEM 收窄后漏掉。
        result.add(MIUI_HOME)
        result
    }.getOrDefault(emptySet())

    /**
     * system_server 自己的 Context。`ActivityThread` 是 @hide，编译期拿不到（模块编的是公开 SDK），
     * 所以这里全程反射 —— 与 [ModuleMain.systemContext] 同一手法。
     */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull()

    private fun loadClass(name: String): Class<*>? {
        var last: Throwable? = null
        // system_server 的框架类（miui-services.jar 里的 com.android.server.wm.*）**不是** boot
        // classpath 上就能 `Class.forName` 到的：真机实测 boot loader / 系统 classloader 都会
        // ClassNotFound，只有"宿主自己那条 classloader 链"能找到。所以候选顺序是：
        //   启动时拿到的 loader → 本模块实例的 loader（热重载后是新的一条，父链挂在宿主上）→
        //   系统 Context 的 loader → 系统 classloader → boot。
        // 少了中间两项，热重载后闸门会以 `LIGHT_OPEN_GATE_CLASS_MISSING` 静默失效（真机踩到）。
        for (candidate in candidateLoaders()) {
            try {
                return Class.forName(name, false, candidate)
            } catch (throwable: Throwable) {
                last = throwable
            }
        }
        module.log(Log.WARN, TAG, "LIGHT_OPEN_GATE_CLASS_MISSING $name", last)
        return null
    }

    private fun candidateLoaders(): List<ClassLoader?> = listOf(
        loader,
        javaClass.classLoader,
        runCatching { systemContext()?.classLoader }.getOrNull(),
        ClassLoader.getSystemClassLoader(),
        null,
    )

    private companion object {
        const val TAG = "LightOpenGate"
        const val MIUI_HOME = "com.miui.home"
        const val HOME_CACHE_MS = 60_000L
    }
}
