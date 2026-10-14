package com.repl.bubbledrawer.lightopen

/**
 * 「轻量打开」纯逻辑层 —— 不碰 Android 框架（只用字符串常量），全部可在 JVM 单测里跑。
 *
 * 六个开关对应两种完全不同的实现机制（见 docs/light-open.md）：
 *
 *  - **system_server 闸门**（分享至应用 / 文本操作 / 应用间跳转）：Hook
 *    `MiuiFreeformServiceImpl.startActivityByFreeForm(ActivityInfo, Intent, String): boolean`
 *    的返回值。这个方法本体只是一次纯查询
 *    （`SecurityManagerInternal.checkGameBoosterPayPassAsUser(...)`），返回 true 时外层
 *    `checkStartActivityByFreeForm` 就会用 `MiuiMultiWindowUtils.getActivityOptions(...)`
 *    造出 `launchWindowingMode = 5`（FREEFORM）的 ActivityOptions，再被
 *    `ActivityStarterImpl.resolveActivity` 写回 `Request.activityOptions`，于是这次启动落在小窗里。
 *  - **SystemUI 注入**（竖屏/横屏点通知、控制中心长按）：这些启动的调用进程是
 *    `com.android.systemui`，ROM 在那个闸门里直接早退（"systemui launch return"），改闸门没用；
 *    必须由 SystemUI 自己把小窗 ActivityOptions 塞进这次启动。
 *
 * 本文件只回答「这一次启动该不该被改成小窗」，返回三态：
 * [LightOpenDecision.FORCE_FREEFORM]（强制小窗）/ [LightOpenDecision.FORCE_FULLSCREEN]（强制全屏，
 * 用来压掉 MIUI 原生的轻量打开）/ [LightOpenDecision.LEAVE]（不干预，保持系统默认）。
 */
enum class LightOpenDecision {
    LEAVE,
    FORCE_FREEFORM,
    FORCE_FULLSCREEN,
}

/** 一份不可变的开关快照；由 [LightOpen.from] 从远程 prefs 读出来。 */
data class LightOpenSettings(
    val enabled: Boolean = false,
    val notificationPortrait: Boolean = false,
    val notificationLandscape: Boolean = false,
    val controlCenter: Boolean = false,
    val shareToApp: Boolean = false,
    val textAction: Boolean = false,
    val appJump: Boolean = false,
    val jumpMode: Int = LightOpen.MODE_WHITE,
    val jumpTargets: Set<String> = emptySet(),
) {
    val jumpTargetCount: Int get() = jumpTargets.size

    /** 黑名单模式（名单外一律小窗）——UI 上要给出更重的提示，所以单独暴露。 */
    val globalFreeform: Boolean get() = appJump && jumpMode == LightOpen.MODE_BLACK
}

object LightOpen {

    /** 应用间跳转：名单内才小窗。 */
    const val MODE_WHITE = 0

    /** 应用间跳转：名单外全部小窗（全局小窗），名单内强制全屏。 */
    const val MODE_BLACK = 1

    const val MAX_TARGETS = 200
    const val MAX_TARGET_LEN = 200

    // 动作常量自己写：纯逻辑层不 import android.content.Intent（单测里那是 stub）。
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH"

    // ---------------------------------------------------------------- 名单编解码

    /**
     * 包名列表 → 单条字符串（换行分隔）。排序 + 去重 + 清洗，保证 UI 与 hook 两侧看到同一份。
     * 远程 prefs 里存 String 而不是 StringSet：StringSet 在 LSPosed 的跨进程镜像上语义更绕，
     * 而这份数据本来就是"一次写、多次读"。
     */
    fun encodeTargets(packages: Collection<String>): String =
        packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TARGET_LEN }
            .distinct()
            .sorted()
            .take(MAX_TARGETS)
            .joinToString("\n")

    /** 容错解析：换行/逗号/分号都当分隔符，顺带做与 [encodeTargets] 相同的清洗。 */
    fun decodeTargets(raw: String?): Set<String> {
        if (raw.isNullOrEmpty()) return emptySet()
        return raw.split('\n', ',', ';')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TARGET_LEN }
            .distinct()
            .take(MAX_TARGETS)
            .toSet()
    }

    /** 从快照字段拼出判定用的设置对象。 */
    fun from(
        enabled: Boolean,
        notificationPortrait: Boolean,
        notificationLandscape: Boolean,
        controlCenter: Boolean,
        shareToApp: Boolean,
        textAction: Boolean,
        appJump: Boolean,
        jumpMode: Int,
        jumpListRaw: String?,
    ): LightOpenSettings = LightOpenSettings(
        enabled = enabled,
        notificationPortrait = notificationPortrait,
        notificationLandscape = notificationLandscape,
        controlCenter = controlCenter,
        shareToApp = shareToApp,
        textAction = textAction,
        appJump = appJump,
        jumpMode = if (jumpMode == MODE_BLACK) MODE_BLACK else MODE_WHITE,
        jumpTargets = decodeTargets(jumpListRaw),
    )

    // ---------------------------------------------------------------- 开关判定

    /**
     * 竖屏/横屏点通知是否强制小窗。
     *
     * @param creatorPackage 通知的 PendingIntent 创建者（`PendingIntent.getCreatorPackage()`），
     *        也就是"这个通知属于哪个应用" —— 与目标 Activity 可能不同（例如系统代理通知），
     *        但它是这次启动真正要带起来的应用，所以按它判定。
     * @param isPortrait 点击发生时屏幕是否为竖屏。
     */
    fun notificationDecision(
        settings: LightOpenSettings,
        creatorPackage: String?,
        isPortrait: Boolean,
    ): LightOpenDecision {
        if (!settings.enabled) return LightOpenDecision.LEAVE
        if (creatorPackage.isNullOrBlank()) return LightOpenDecision.LEAVE
        val on = if (isPortrait) settings.notificationPortrait else settings.notificationLandscape
        return if (on) LightOpenDecision.FORCE_FREEFORM else LightOpenDecision.LEAVE
    }

    /** 控制中心长按磁贴是否强制小窗。 */
    fun controlCenterDecision(
        settings: LightOpenSettings,
        targetPackage: String?,
    ): LightOpenDecision {
        if (!settings.enabled || !settings.controlCenter) return LightOpenDecision.LEAVE
        if (targetPackage.isNullOrBlank()) return LightOpenDecision.LEAVE
        return LightOpenDecision.FORCE_FREEFORM
    }

    /**
     * system_server 闸门判定（分享至应用 / 文本操作 / 应用间跳转）。
     *
     * 护栏（真机 ROM 之外我们自己加的，缺一个就会把"正常启动"当成"跳转"）：
     *  1. 当前前台是桌面/负一屏/最近任务（`isHomeTop`）→ 不干预。桌面点图标、桌面文件夹、
     *     负一屏卡片、HyperOS 最近任务卡片都落在这里；少了这条，全局小窗模式下桌面启动也会变
     *     小窗（用户 2026-10 明确否掉）。
     *  2. 前台包为空（锁屏/息屏/没有明确前台）→ 不干预。
     *  3. 目标包 == 前台包（同应用内跳转）→ 不干预。ROM 自己也会拦，这里是双保险。
     *
     * @param action 目标 Intent 的 action
     * @param dataScheme 目标 Intent 的 data scheme（http/https 用于"打开链接/文本操作"）
     * @param targetPackage 目标 Activity 的包名（ROM 内部叫 rawPackageName）
     * @param topFullscreenPackage ROM 传进来的"当前前台全屏应用包名"（排除小窗后的顶层 Activity 包名）
     * @param isHomeTop [topFullscreenPackage] 是否属于 HOME 包集合
     */
    fun jumpDecision(
        settings: LightOpenSettings,
        action: String?,
        dataScheme: String?,
        targetPackage: String?,
        topFullscreenPackage: String?,
        isHomeTop: Boolean,
    ): LightOpenDecision {
        if (!settings.enabled) return LightOpenDecision.LEAVE
        if (topFullscreenPackage.isNullOrBlank()) return LightOpenDecision.LEAVE
        if (isHomeTop) return LightOpenDecision.LEAVE
        val target = targetPackage
        if (target.isNullOrBlank()) return LightOpenDecision.LEAVE
        if (target == topFullscreenPackage) return LightOpenDecision.LEAVE

        // 黑名单是「否决票」：名单内的应用即使命中了分享/文本开关也保持全屏。
        val listed = target in settings.jumpTargets
        if (settings.appJump && settings.jumpMode == MODE_BLACK && listed) {
            return LightOpenDecision.FORCE_FULLSCREEN
        }
        if (settings.appJump) {
            if (settings.jumpMode == MODE_WHITE) {
                if (listed) return LightOpenDecision.FORCE_FREEFORM
            } else {
                return LightOpenDecision.FORCE_FREEFORM
            }
        }
        if (settings.shareToApp && isShareAction(action)) return LightOpenDecision.FORCE_FREEFORM
        if (settings.textAction && isTextAction(action, dataScheme)) return LightOpenDecision.FORCE_FREEFORM
        return LightOpenDecision.LEAVE
    }

    /** 分享至应用：系统的分享动作（含"分享多个"）。 */
    fun isShareAction(action: String?): Boolean =
        action == ACTION_SEND || action == ACTION_SEND_MULTIPLE

    /**
     * 文本操作：编辑条/选中文本工具栏里的条目。
     *
     * 真机上「选中链接 → 编辑条里的浏览器图标」走的是 `ACTION_VIEW` + http(s) data，
     * 所以这里同时认 `PROCESS_TEXT`、http(s) 的 `VIEW` 和 `WEB_SEARCH`；代价是应用内
     * 直接点链接也会被算进这个开关（实测确认后再决定要不要收窄）。
     */
    fun isTextAction(action: String?, dataScheme: String?): Boolean {
        if (action == ACTION_PROCESS_TEXT || action == ACTION_WEB_SEARCH) return true
        if (action != ACTION_VIEW) return false
        val scheme = dataScheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }
}
