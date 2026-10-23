package com.repl.bubbledrawer.diagnostics

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「哪些成功、哪些有问题」—— 把模块自己的日志读成一张体检表。
 *
 * 为什么值得这么做：模块的每一处功能都以一行日志记录自己的结局（hook 装没装上、这一笔摆放在
 * 哪儿、ROM 给的系数是多少、避让有没有动它），但那是给开发者的 logcat。测试机往往没有电脑、
 * 拿不到 adb，于是「功能没生效」只能靠猜。这个分析器把同一批日志翻译成人的结论：每一项
 * 通过/注意/有问题 + 一句话原因 + 原始证据行，页面上可以直接复制出来发给别人。
 *
 * 关键约定：**只认日志里真实存在的字符串**（见 `FlymeFreeformController` / `SystemUiHookInstaller`
 * 的 `log(...)` 调用）。格式随版本演进过，所以每个字段的解析都同时接受新旧写法 —— 用户机器上
 * 装的可能是上一版，日志里也会混着上一版的行。缺字段不算失败（老版本没有而已），
 * 但**该有的行整条不见**才算失败。
 */
enum class CheckStatus {
    PASS,
    WARN,
    FAIL,
    INFO,
}

/** 体检表里的一组（页面上按组出卡片）。 */
enum class CheckGroup(val title: String) {
    MODULE("模块与进程"),
    HOOKS("Hook 安装"),
    SETTINGS("设置与同步"),
    PLACEMENT("居中与尺寸"),
    BEHAVIOUR("避让与迷你小窗"),
    GESTURE("边角手势"),
}

/**
 * 一条结论。[evidence] 是按下结论的那一行原文（已裁掉 LSPosed 的日志前缀），复制结果时一起带走。
 */
data class DiagnosisCheck(
    val id: String,
    val group: CheckGroup,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val evidence: String? = null,
)

/** 应用里当前的设置 —— 用来判断系统界面里读到的是不是同一份（开关没同步 = 功能静默失效）。 */
data class ExpectedSettings(
    val freeformEnabled: Boolean,
    val center: Boolean,
    val scale: Int,
    val noOffset: Boolean,
)

data class Diagnosis(
    val checks: List<DiagnosisCheck>,
    val lineCount: Int,
    val moduleLineCount: Int,
    val raw: List<String>,
) {
    val failed: Int get() = checks.count { it.status == CheckStatus.FAIL }
    val warned: Int get() = checks.count { it.status == CheckStatus.WARN }

    /** 一句话结论，页头用。 */
    val headline: String
        get() = when {
            moduleLineCount == 0 -> "日志里没有模块输出"
            failed > 0 && warned > 0 -> "$failed 项有问题、$warned 项需要注意"
            failed > 0 -> "$failed 项有问题"
            warned > 0 -> "$warned 项需要注意，其余正常"
            else -> "全部正常"
        }

    /** 复制按钮的内容：结论 + 每项 + 关键原文，够别人据此定位。 */
    fun toText(): String = buildString {
        appendLine("魅窗诊断 —— $headline")
        appendLine("读取 ${lineCount} 行日志，其中模块输出 ${moduleLineCount} 行")
        for (group in CheckGroup.entries) {
            val inGroup = checks.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            appendLine()
            appendLine("【${group.title}】")
            for (check in inGroup) {
                appendLine("- [${check.status.name}] ${check.title}：${check.detail}")
                check.evidence?.let { appendLine("    $it") }
            }
        }
    }
}

object ModuleLogDiagnosis {

    /**
     * 日志里属于本模块的行。
     *
     * 认两种形态：logcat 的 tag（`I BubbleDrawer: …`）、LSPosed 模块日志的模块 id
     * （`(com.android.systemui)[com.repl.bubbledrawer,BubbleDrawer,…] …`），以及模块自己那套
     * 消息前缀 —— 最后一条是为了让"手工粘一段日志进来"也能用（不依赖采集器带 tag）。
     */
    private val MESSAGE_MARKERS = listOf(
        "HOOK_INSTALLED_", "HOOK_SKIPPED_", "HOOK_FAILED_",
        "CENTERED_BOUNDS", "CENTER_SKIPPED", "CENTER_SCREEN_MISMATCH", "BOUNDS_CENTER_",
        "FREEFORM_SCALE", "FREEFORM_OFFSET_SKIPPED", "RESTORE_AVOID_SKIPPED",
        "PROBE prefs", "PREFS_APPLIED", "HOT_RELOADED", "SYSTEMUI_RESTART",
        "MINI_SWIPE", "SYSTEMUI_CORNER_SPY", "FLYME_FREEFORM_HOOKS_REINSTALL_FAILED",
        // 边角手势（见 CornerInputMonitor 与 LauncherMonitorRegion）：MON_DOWN/MON_STROKE_END 是每一笔的结局，
        // MON_SYSTEM_CANCEL 是"这一笔被系统抢走"的唯一证据，ARBITER_ 是 system_server 对 swipe-up 的裁剪仲裁。
        "MON_DOWN", "MON_STROKE_END", "MON_ACTIVATE", "MON_SYSTEM_CANCEL", "MON_INPUT_READY",
        "MON_PILFER", "MON_WARMUP", "SYSTEMUI_CORNER_READY", "MON_IME_PROBE", "ARBITER_",
        // 功能页（快捷方式）：扫没扫到、送没送到、面板里的编辑有没有交给 App。
        "SHORTCUT_SCAN", "SHORTCUT_REFRESH_REJECTED", "FUNCTION_WRITE_HANDOFF",
        "FUNCTION_REMOTE_CHANGED", "FUNCTION_SYNC_FROM_APP_",
        // 轻量打开（六开关）：两个进程各装一次钩子，命中时各留一行。
        "LIGHT_OPEN_",
    )

    fun moduleLines(lines: List<String>): List<String> =
        lines.filter { line ->
            line.contains("com.repl.bubbledrawer") || line.contains("/BubbleDrawer") ||
                line.contains("BubbleDrawer:") || line.contains("BubbleDrawer]") ||
                MESSAGE_MARKERS.any(line::contains)
        }

    fun analyze(lines: List<String>, expected: ExpectedSettings? = null): Diagnosis {
        val relevant = moduleLines(lines)
        val checks = buildList {
            addAll(moduleChecks(relevant))
            addAll(hookChecks(relevant))
            addAll(settingsChecks(relevant, expected))
            addAll(placementChecks(relevant))
            addAll(behaviourChecks(relevant))
            addAll(cornerChecks(relevant))
        }
        return Diagnosis(
            checks = checks,
            lineCount = lines.size,
            moduleLineCount = relevant.size,
            raw = relevant.takeLast(RAW_LINES).map(::trimPrefix),
        )
    }

    private const val RAW_LINES = 25

    // -----------------------------------------------------------------------------------------
    // 模块与进程
    // -----------------------------------------------------------------------------------------

    private fun moduleChecks(lines: List<String>): List<DiagnosisCheck> = buildList {
        val installs = lines.filter { it.contains("HOOK_INSTALLED_") }
        add(
            DiagnosisCheck(
                id = "module.loaded",
                group = CheckGroup.MODULE,
                title = "模块在系统界面里运行",
                status = if (lines.isNotEmpty()) CheckStatus.PASS else CheckStatus.FAIL,
                detail = if (lines.isNotEmpty()) {
                    "读到 ${lines.size} 行模块输出，${installs.size} 次 hook 安装记录"
                } else {
                    "日志里一行模块输出都没有：模块没在 com.android.systemui 里跑起来（LSPosed 作用域 / 模块未启用 / 系统界面还没重启）"
                },
                evidence = lines.lastOrNull()?.let(::trimPrefix),
            ),
        )

        // 热重载会把上一代 hook 全部摘掉（`adb install -r` 之后必然发生）。只有当最后一次热重载
        // **之后**又出现了安装记录，才说明重装成功了 —— 这正是「装完新包功能静默失效」的判据。
        val lastReload = lines.indexOfLast { it.contains("HOT_RELOADED") }
        val lastInstall = lines.indexOfLast { it.contains("HOOK_INSTALLED_") }
        if (lastReload >= 0) {
            val survived = lastInstall > lastReload
            val unhooked = Regex("unhooked=(\\d+)").find(lines[lastReload])?.groupValues?.get(1)
            add(
                DiagnosisCheck(
                    id = "module.hot_reload",
                    group = CheckGroup.MODULE,
                    title = "安装/热重载之后 hook 已重建",
                    status = if (survived) CheckStatus.PASS else CheckStatus.FAIL,
                    detail = if (survived) {
                        "最近一次热重载（摘掉 ${unhooked ?: "?"} 个 hook）之后又装上了 ${lines.size - lastReload - 1} 行安装记录"
                    } else {
                        "最后一次热重载发生在此后，没有任何 hook 被重装 —— 当前进程里的 hook 是死的，" +
                            "重启系统界面即可恢复（主页顶栏的刷新按钮）"
                    },
                    evidence = trimPrefix(lines[lastReload]),
                ),
            )
        }
        lines.firstOrNull { it.contains("FLYME_FREEFORM_HOOKS_REINSTALL_FAILED") }?.let { line ->
            add(
                DiagnosisCheck(
                    id = "module.reinstall_failed",
                    group = CheckGroup.MODULE,
                    title = "hook 重装失败",
                    status = CheckStatus.FAIL,
                    detail = "热重载后重装小窗 hook 抛异常了，当前进程里的相关功能不可用",
                    evidence = trimPrefix(line),
                ),
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Hook 安装
    // -----------------------------------------------------------------------------------------

    private fun hookChecks(lines: List<String>): List<DiagnosisCheck> = buildList {
        // 小窗 hook（居中/遮罩/手势全挂在这一批上）。
        val bounds = lines.lastOrNull { it.contains("HOOK_INSTALLED_BoundsCentering") }
        val counts = bounds?.let {
            Regex("rect=(\\d+) rectFailed=(\\d+) interact=(\\d+) interactFailed=(\\d+) mini=(\\d+) miniFailed=(\\d+)")
                .find(it)?.groupValues?.drop(1)?.map(String::toIntOrNull)
        }
        val boundsOk = when {
            bounds == null -> false
            counts == null || counts.size < 6 -> true // 老版本没有计数，装上就算过
            else -> (counts[0] ?: 0) > 0 && (counts[4] ?: 0) > 0 && (counts[1] ?: 0) == 0 && (counts[5] ?: 0) == 0
        }
        add(
            DiagnosisCheck(
                id = "hooks.bounds",
                group = CheckGroup.HOOKS,
                title = "小窗 hook（居中 / 缩放 / 避让）",
                status = when {
                    bounds == null -> CheckStatus.FAIL
                    boundsOk -> CheckStatus.PASS
                    else -> CheckStatus.FAIL
                },
                detail = when {
                    bounds == null ->
                        "没有安装记录：小窗居中、黑色遮罩、联系人条都不会生效"
                    counts == null || counts.size < 6 ->
                        "已安装（旧版本日志没有分项计数）"
                    !boundsOk ->
                        "装上了但有缺口：rect=${counts[0]} rectFailed=${counts[1]} interact=${counts[2]} " +
                            "interactFailed=${counts[3]} mini=${counts[4]} miniFailed=${counts[5]} —— " +
                            "0 表示 ROM 里没找到对应方法（版本不匹配）"
                    else ->
                        "rect=${counts[0]}（框架 rect 函数）interact=${counts[2]}（全屏→小窗）mini=${counts[4]}（迷你→普通）"
                },
                evidence = bounds?.let(::trimPrefix),
            ),
        )

        val noMatch = lines.lastOrNull { it.contains("BOUNDS_CENTER_NO_MATCH") }
        if (noMatch != null) {
            add(
                DiagnosisCheck(
                    id = "hooks.no_match",
                    group = CheckGroup.HOOKS,
                    title = "居中的注入点没找到",
                    status = CheckStatus.FAIL,
                    detail = "这台 ROM 上模块要找的方法不在（下面列出它实际有的）：小窗居中无处下手",
                    evidence = trimPrefix(noMatch),
                ),
            )
        }

        // 缩放钩子：它决定小窗的尺寸，也是居中算术的依据。
        val scaleHook = lines.lastOrNull { it.contains("HOOK_INSTALLED_MiuiMultiWindowUtils.getFreeFormScale") }
        val scaleSkipped = lines.lastOrNull { it.contains("HOOK_SKIPPED_FreeformScale") }
        add(
            DiagnosisCheck(
                id = "hooks.scale",
                group = CheckGroup.HOOKS,
                title = "小窗尺寸 hook",
                status = when {
                    scaleSkipped != null -> CheckStatus.FAIL
                    scaleHook != null -> CheckStatus.PASS
                    else -> CheckStatus.WARN
                },
                detail = when {
                    scaleSkipped != null -> "没装上（框架里没有这个类）：小窗尺寸完全由 ROM 决定，设置里的缩放滑块无效"
                    scaleHook != null ->
                        Regex("hooks=(\\d+)").find(scaleHook)?.groupValues?.get(1)
                            ?.let { "已安装 $it 个重载（getFreeFormScale / getOriFreeformScale）" }
                            ?: "已安装（旧版本日志没有计数）"
                    else -> "日志里没有这一项的记录"
                },
                evidence = (scaleSkipped ?: scaleHook)?.let(::trimPrefix),
            ),
        )

        // 尺寸配置钩子：模块靠它把「缩放」设置交给 ROM；没装上时 ROM 用自己的分辨率表定尺寸，
        // 于是「小窗多大」和「设置是多少」不是一回事（也正因为此，居中必须按 ROM 的系数算）。
        val configHook = lines.lastOrNull { it.contains("HOOK_INSTALLED_MiuiFreeformLaunchConfig.getFreeformArg") }
        val configSkipped = lines.lastOrNull { it.contains("HOOK_SKIPPED_FreeformLaunchConfig") }
        add(
            DiagnosisCheck(
                id = "hooks.launch_config",
                group = CheckGroup.HOOKS,
                title = "尺寸配置 hook（缩放设置是否说了算）",
                status = when {
                    configSkipped != null -> CheckStatus.FAIL
                    configHook != null -> CheckStatus.PASS
                    else -> CheckStatus.WARN
                },
                detail = when {
                    configSkipped != null ->
                        "没装上：这台 ROM 的小窗尺寸由它自己的分辨率表决定，设置里的缩放值不生效；" +
                            "居中会按 ROM 实际给的系数摆放（看下面「最近一次居中」的 applied/assumed）"
                    configHook != null -> "已安装，缩放设置就是 ROM 用的尺寸系数"
                    else -> "日志里没有这一项的记录"
                },
                evidence = (configSkipped ?: configHook)?.let(::trimPrefix),
            ),
        )

        // 迷你→普通恢复之后 ROM 还会再避让一次，模块的决定要被保留才谈得上居中。
        val restoreHook = lines.lastOrNull { it.contains("HOOK_INSTALLED_RestoreAvoid") }
        val restoreSkipped = lines.lastOrNull { it.contains("HOOK_SKIPPED_RestoreAvoid") }
        val restoreFailed = lines.lastOrNull { it.contains("HOOK_FAILED_RestoreAvoid") }
        add(
            DiagnosisCheck(
                id = "hooks.restore_avoid",
                group = CheckGroup.HOOKS,
                title = "还原小窗的二次避让拦截",
                status = when {
                    restoreFailed != null -> CheckStatus.WARN
                    restoreSkipped != null -> CheckStatus.WARN
                    restoreHook != null -> CheckStatus.PASS
                    else -> CheckStatus.INFO
                },
                detail = when {
                    restoreFailed != null -> "安装抛异常了（见原文），迷你小窗还原后可能被 ROM 再推走一次"
                    restoreSkipped != null -> "没装上（ROM 里没有这个方法）：还原时 ROM 的第二次避让无法拦截"
                    restoreHook != null -> "已安装：迷你小窗还原后模块摆好的位置不会再被 ROM 的避让推走"
                    else -> "日志里没有这一项的记录（可能是旧版本）"
                },
                evidence = (restoreFailed ?: restoreSkipped ?: restoreHook)?.let(::trimPrefix),
            ),
        )

        val gesture = lines.lastOrNull { it.contains("HOOK_INSTALLED_onBottomCaptionHandleMotionEvents") }
        val anim = lines.lastOrNull { it.contains("HOOK_INSTALLED_MiuiFreeformModeAnimation.startGestureAnimation") }
        add(
            DiagnosisCheck(
                id = "hooks.gesture",
                group = CheckGroup.HOOKS,
                title = "小白条手势 hook",
                status = if (gesture != null && anim != null) CheckStatus.PASS else CheckStatus.FAIL,
                detail = if (gesture != null && anim != null) {
                    "小白条拖动 / 上滑成迷你小窗的两处注入点都在"
                } else {
                    val missing = buildList {
                        if (gesture == null) add("小白条运动事件")
                        if (anim == null) add("手势动画")
                    }.joinToString("、")
                    "缺少 $missing：小窗拖动、上滑迷你小窗会失效"
                },
                evidence = (anim ?: gesture)?.let(::trimPrefix),
            ),
        )

        // 「回桌面不收迷你」：这里只回答"拦得住吗" —— 那两处入口是否装上了。装不上（旧 ROM 没有这两个
        // 方法）时开关打开也不会生效，上滑回桌面照样把所有小窗收成迷你小窗。
        val homeKeepHook = lines.lastOrNull {
            it.contains("HOOK_INSTALLED_MiuiFreeformModeController.allFreeformFromFreeformToMini")
        }
        val homeKeepSkipped = lines.lastOrNull { it.contains("FREEFORM_HOME_KEEP_SKIPPED") }
        // 装不上有两种性质完全不同的原因：方法不存在（这台 ROM 本来就没有批量迷你化 → INFO）与安装抛异常
        // （该拦却没拦住 → FAIL）。日志里分得开就别混成一句"开关可能没用"。
        val homeKeepUnavailable = lines.lastOrNull { it.contains("HOME_KEEP_HOOK_UNAVAILABLE") }
        val homeKeepFailed = lines.lastOrNull { it.contains("HOME_KEEP_HOOK_FAILED_") }
        add(
            DiagnosisCheck(
                id = "hooks.home_keep_freeform",
                group = CheckGroup.HOOKS,
                title = "回桌面不收迷你 hook",
                status = when {
                    homeKeepHook != null -> CheckStatus.PASS
                    homeKeepFailed != null -> CheckStatus.FAIL
                    homeKeepUnavailable != null -> CheckStatus.INFO
                    else -> CheckStatus.WARN
                },
                detail = when {
                    homeKeepHook != null && homeKeepSkipped != null ->
                        "已安装，并且已经拦下过（${homeKeepSkipped.substringAfter("via=").trim()}）"
                    homeKeepHook != null -> "已安装：开关打开后回桌面 / 进最近任务都不会再把小窗收成迷你小窗"
                    homeKeepFailed != null -> "安装抛异常了：开关打开也拦不住，回桌面仍会把所有小窗收成迷你小窗"
                    homeKeepUnavailable != null ->
                        "这台 ROM 里没有批量迷你化的入口（" +
                            homeKeepUnavailable.substringAfter("reason=").trim() + "），开关不会生效，也不需要生效"
                    else -> "日志里没有这一项（旧版本）：升级到带这个开关的版本后才会出现"
                },
                evidence = (homeKeepSkipped ?: homeKeepFailed ?: homeKeepUnavailable ?: homeKeepHook)
                    ?.let(::trimPrefix),
            ),
        )

        val installed = lines.filter { it.contains("HOOK_INSTALLED_") }.distinctBy { it.substringAfter("HOOK_INSTALLED_").take(40) }
        if (installed.isNotEmpty()) {
            add(
                DiagnosisCheck(
                    id = "hooks.count",
                    group = CheckGroup.HOOKS,
                    title = "已安装的 hook 种类",
                    status = CheckStatus.INFO,
                    detail = "${installed.size} 种：" + installed.joinToString("、") {
                        it.substringAfter("HOOK_INSTALLED_").substringBefore(' ').substringBefore('(')
                    },
                ),
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // 设置与同步
    // -----------------------------------------------------------------------------------------

    private fun settingsChecks(lines: List<String>, expected: ExpectedSettings?): List<DiagnosisCheck> = buildList {
        // 系统界面进程里真正读到的设置：`PROBE prefs[...]`（手动探针）或 `CENTER_SKIPPED src=…`。
        val effectiveLine = lines.lastOrNull { it.contains("PROBE prefs") }
            ?: lines.lastOrNull { it.contains("CENTER_SKIPPED ") }
        val enabled = effectiveLine?.let { Regex("enabled=(true|false)").find(it)?.groupValues?.get(1)?.toBoolean() }
        val center = effectiveLine?.let { Regex("center=(true|false)").find(it)?.groupValues?.get(1)?.toBoolean() }
        val scale = effectiveLine?.let { Regex("scale=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        if (effectiveLine == null) {
            add(
                DiagnosisCheck(
                    id = "settings.effective",
                    group = CheckGroup.SETTINGS,
                    title = "系统界面读到的设置",
                    status = CheckStatus.INFO,
                    detail = "日志里还没有这一项（点一下主页顶栏的刷新按钮重启系统界面，模块启动时会打印一次）",
                ),
            )
        } else {
            val mismatch = expected != null &&
                (enabled != null && enabled != expected.freeformEnabled || center != null && center != expected.center)
            add(
                DiagnosisCheck(
                    id = "settings.effective",
                    group = CheckGroup.SETTINGS,
                    title = "系统界面读到的设置",
                    status = if (mismatch) CheckStatus.FAIL else CheckStatus.PASS,
                    detail = buildString {
                        append("系统界面里：总开关=")
                        append(enabled?.let { if (it) "开" else "关" } ?: "?")
                        append("、居中=")
                        append(center?.let { if (it) "开" else "关" } ?: "?")
                        scale?.let { append("、缩放=$it%") }
                        if (expected != null) {
                            append("（应用里：总开关=")
                            append(if (expected.freeformEnabled) "开" else "关")
                            append("、居中=")
                            append(if (expected.center) "开" else "关")
                            append("、缩放=${expected.scale}%）")
                        }
                        if (mismatch) append(" —— 两边不一致，设置没同步进系统界面，功能会静默失效")
                    },
                    evidence = trimPrefix(effectiveLine),
                ),
            )
        }

        // 居中被开关挡住时会留一行 CENTER_SKIPPED：把原因直接说出来，比"没生效"有用得多。
        // 这里的开关值取自**这一行自己**（而不是探针那行）：它记录的就是"那一次"看到的设置。
        val skipped = lines.lastOrNull { it.contains("CENTER_SKIPPED ") }
        if (skipped != null) {
            val src = Regex("src=(\\S+)").find(skipped)?.groupValues?.get(1) ?: "?"
            val skippedEnabled = Regex("enabled=(true|false)").find(skipped)?.groupValues?.get(1)?.toBoolean()
            val skippedCenter = Regex("center=(true|false)").find(skipped)?.groupValues?.get(1)?.toBoolean()
            val why = buildString {
                append("最近一次要摆放窗口时被跳过（来源 $src）：")
                if (skippedEnabled == false) append("总开关在系统界面里是关的；")
                if (skippedCenter == false) append("「小窗居中」是关的；")
                if (skippedEnabled != false && skippedCenter != false) append("参数位置和预期不符（见原文）；")
            }
            add(
                DiagnosisCheck(
                    id = "settings.center_gate",
                    group = CheckGroup.SETTINGS,
                    title = "居中的开关状态",
                    status = if (skippedEnabled == false || skippedCenter == false) CheckStatus.FAIL else CheckStatus.WARN,
                    detail = why,
                    evidence = trimPrefix(skipped),
                ),
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // 居中与尺寸
    // -----------------------------------------------------------------------------------------

    private fun placementChecks(lines: List<String>): List<DiagnosisCheck> = buildList {
        // 最近一次"摆好了"的记录：`CENTERED_BOUNDS rect=… scale=… [src=… applied=… assumed=…] visual=… screen=…`
        val centered = lines.lastOrNull { it.contains("CENTERED_BOUNDS rect=") }
        if (centered == null) {
            add(
                DiagnosisCheck(
                    id = "placement.last",
                    group = CheckGroup.PLACEMENT,
                    title = "最近一次居中",
                    status = CheckStatus.WARN,
                    detail = "日志里没有模块摆放小窗的记录。开一个小窗、或点一下迷你小窗还原，" +
                        "再回来重新诊断即可看到结果",
                ),
            )
        } else {
            val rect = Regex("rect=Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)").find(centered)
                ?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
            val visual = Regex("visual=\\((\\d+)x(\\d+)\\)").find(centered)
                ?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
            val screen = Regex("screen=\\((\\d+)\\s*x\\s*(\\d+)\\)").find(centered)
                ?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
            if (rect == null || rect.size < 4 || visual == null || visual.size < 2 || screen == null || screen.size < 2) {
                add(
                    DiagnosisCheck(
                        id = "placement.last",
                        group = CheckGroup.PLACEMENT,
                        title = "最近一次居中",
                        status = CheckStatus.INFO,
                        detail = "读到了摆放记录，但字段不全（可能是旧版本日志格式）",
                        evidence = trimPrefix(centered),
                    ),
                )
            } else {
                // 居中的定义就是这一个等式：左上角 = (屏幕 − 可见尺寸) / 2。模块自己算得对不对、
                // 有没有被别的东西挪走，都在这里现形。
                val wantLeft = ((screen[0] - visual[0]) / 2f).roundToInt()
                val wantTop = ((screen[1] - visual[1]) / 2f).roundToInt()
                val dx = rect[0] - wantLeft
                val dy = rect[1] - wantTop
                val offCentre = abs(dx) > 2 || abs(dy) > 2
                val avoided = Regex("avoided=(true|false)").find(centered)?.groupValues?.get(1)?.toBoolean()
                add(
                    DiagnosisCheck(
                        id = "placement.last",
                        group = CheckGroup.PLACEMENT,
                        title = "最近一次居中",
                        status = if (offCentre) CheckStatus.FAIL else CheckStatus.PASS,
                        detail = if (offCentre) {
                            "摆放结果不在屏幕中央：窗口 ${visual[0]}x${visual[1]} 在 ${screen[0]}x${screen[1]} 的屏幕上" +
                                "应该在 ($wantLeft, $wantTop)，实际在 (${rect[0]}, ${rect[1]})，偏 ($dx, $dy) px" +
                                if (avoided == true) "（这次还被第二个小窗避让挪过：见避让那组）" else ""
                        } else {
                            "窗口 ${visual[0]}x${visual[1]} 落在 ${screen[0]}x${screen[1]} 屏幕正中 (${rect[0]}, ${rect[1]})"
                        },
                        evidence = trimPrefix(centered),
                    ),
                )
            }

            // 摆放用的系数：ROM 真正会用的那个值 vs 模块推断的值。两者不等时窗口会整体偏半个差值，
            // 而且"放大/缩小"这一步会拿错尺寸去判重叠。
            val applied = Regex("applied=([0-9.]+|null)").find(centered)?.groupValues?.get(1)
            val assumed = Regex("assumed=([0-9.]+|null)").find(centered)?.groupValues?.get(1)
            val src = Regex("src=(\\w+)").find(centered)?.groupValues?.get(1)
            if (src != null || applied != null) {
                val a = applied?.toFloatOrNull()
                val b = assumed?.toFloatOrNull()
                val mismatch = a != null && b != null && abs(a - b) > 0.005f
                add(
                    DiagnosisCheck(
                        id = "placement.scale_source",
                        group = CheckGroup.PLACEMENT,
                        title = "摆放系数取自哪里",
                        status = when {
                            mismatch -> CheckStatus.WARN
                            src == "setting" -> CheckStatus.WARN
                            else -> CheckStatus.PASS
                        },
                        detail = when {
                            mismatch ->
                                "ROM 实际使用的系数 ${a} 和模块的设置推断值 ${b} 不一样：这台 ROM 的小窗尺寸" +
                                    "不是设置值决定的（见上面「尺寸配置 hook」）。居中已按 ROM 的值计算，" +
                                    "但尺寸本身不跟设置"
                            src == "setting" ->
                                "没能从 ROM 问到系数，用了设置值兜底（applied=$applied assumed=$assumed）"
                            else ->
                                "来自 ROM 自己：applied=$applied（设置推断值 assumed=$assumed）"
                        },
                        evidence = trimPrefix(centered),
                    ),
                )
            }
        }

        // 尺寸：ROM 把设置值压小了就是这里看出来的（横屏正常，竖屏就说明 ROM 自己有另一套值）。
        val scaleLine = lines.lastOrNull { it.contains("FREEFORM_SCALE ") }
        if (scaleLine != null) {
            val target = Regex("target=([0-9.]+)").find(scaleLine)?.groupValues?.get(1)?.toFloatOrNull()
            val rom = Regex("rom=([0-9.]+|null)").find(scaleLine)?.groupValues?.get(1)?.toFloatOrNull()
            val used = Regex("used=([0-9.]+)").find(scaleLine)?.groupValues?.get(1)?.toFloatOrNull()
            val smaller = target != null && used != null && used < target - 0.005f
            add(
                DiagnosisCheck(
                    id = "placement.size",
                    group = CheckGroup.PLACEMENT,
                    title = "小窗尺寸",
                    status = if (smaller) CheckStatus.WARN else CheckStatus.PASS,
                    detail = if (smaller) {
                        "设置 ${(target!! * 100).roundToInt()}%，实际 ${(used!! * 100).roundToInt()}%" +
                            (rom?.let { "（ROM 自己算出来的是 ${(it * 100).roundToInt()}%）" } ?: "") +
                            "：这台 ROM 的尺寸系数比设置值小，多出来的部分会被它压掉"
                    } else {
                        "设置与实际一致（used=$used rom=$rom）"
                    },
                    evidence = trimPrefix(scaleLine),
                ),
            )
        }

        val screenMismatch = lines.lastOrNull { it.contains("CENTER_SCREEN_MISMATCH") }
        if (screenMismatch != null) {
            add(
                DiagnosisCheck(
                    id = "placement.screen_mismatch",
                    group = CheckGroup.PLACEMENT,
                    title = "屏幕尺寸口径不一致",
                    status = CheckStatus.WARN,
                    detail = "两个进程对屏幕尺寸的说法不一样（模块已统一用 ROM 的口径换算居中）",
                    evidence = trimPrefix(screenMismatch),
                ),
            )
        }

        val wideSkipped = lines.lastOrNull {
            it.contains("CENTERED_BOUNDS_SKIPPED_WIDE") || it.contains("CENTERED_BOUNDS_SKIPPED_TOO_BIG")
        }
        if (wideSkipped != null) {
            add(
                DiagnosisCheck(
                    id = "placement.skipped",
                    group = CheckGroup.PLACEMENT,
                    title = "有一次居中主动放弃了",
                    status = CheckStatus.WARN,
                    detail = "窗口比屏幕还大（横屏系数没被压住时会出现），这时居中只会让它两边都出屏，" +
                        "所以保留了 ROM 自己的摆放",
                    evidence = trimPrefix(wideSkipped),
                ),
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // 避让与迷你小窗
    // -----------------------------------------------------------------------------------------

    private fun behaviourChecks(lines: List<String>): List<DiagnosisCheck> = buildList {
        val avoided = lines.lastOrNull { it.contains("CENTERED_BOUNDS_AVOIDED") }
        val avoidSkipped = lines.lastOrNull { it.contains("CENTERED_BOUNDS_AVOID_SKIPPED") }
        val offsetSkipped = lines.lastOrNull { it.contains("FREEFORM_OFFSET_SKIPPED") }
        add(
            DiagnosisCheck(
                id = "behaviour.avoid",
                group = CheckGroup.BEHAVIOUR,
                title = "「禁止小窗偏移」的实际表现",
                status = CheckStatus.INFO,
                detail = when {
                    offsetSkipped != null ->
                        "开关已生效：${count(lines, "FREEFORM_OFFSET_SKIPPED")} 次 ROM 的错开被拦下（日志 ${trimPrefix(offsetSkipped)}）"
                    avoided != null ->
                        "开关是关的：最近一次为新窗口避让而挪动了位置（${trimPrefix(avoided)}）"
                    avoidSkipped != null ->
                        "没有需要避让的窗口（${trimPrefix(avoidSkipped)}）"
                    else -> "日志里还没有避让相关的记录"
                },
                evidence = (offsetSkipped ?: avoided ?: avoidSkipped)?.let(::trimPrefix),
            ),
        )

        val restoreSkipped = lines.lastOrNull { it.contains("RESTORE_AVOID_SKIPPED") }
        if (restoreSkipped != null) {
            add(
                DiagnosisCheck(
                    id = "behaviour.restore_avoid",
                    group = CheckGroup.BEHAVIOUR,
                    title = "还原小窗没有被二次避让推走",
                    status = CheckStatus.PASS,
                    detail = "迷你小窗还原成普通小窗时，ROM 在模块之后又跑的那次避让被跳过了",
                    evidence = trimPrefix(restoreSkipped),
                ),
            )
        }

        val mini = lines.lastOrNull {
            it.contains("MINI_SWIPE_NATIVE") || it.contains("MINI_SWIPE_FALLBACK") ||
                it.contains("MINI_SWIPE_CONVERTED_BY_ROM")
        }
        if (mini != null) {
            val fallback = mini.contains("MINI_SWIPE_FALLBACK")
            add(
                DiagnosisCheck(
                    id = "behaviour.mini",
                    group = CheckGroup.BEHAVIOUR,
                    title = "上滑小白条 → 迷你小窗",
                    status = CheckStatus.PASS,
                    detail = if (fallback) {
                        "这台 ROM 的手势里没有迷你分支（HyperOS 3），由模块在松手后驱动 ROM 的转换接口完成"
                    } else {
                        "ROM 自己完成了转换（模块只操纵了输入与动画类型）"
                    },
                    evidence = trimPrefix(mini),
                ),
            )
        }

        val restart = lines.lastOrNull { it.contains("SYSTEMUI_RESTART exit=") }
        if (restart != null) {
            val exit = restart.substringAfter("SYSTEMUI_RESTART exit=").trim().toIntOrNull()
            add(
                DiagnosisCheck(
                    id = "behaviour.restart",
                    group = CheckGroup.BEHAVIOUR,
                    title = "重启系统界面",
                    status = if (exit == 0) CheckStatus.PASS else CheckStatus.WARN,
                    detail = if (exit == 0) {
                        "最近一次重启成功"
                    } else {
                        "最近一次重启没能执行（exit=$exit）：多半是没给设置应用 root 权限"
                    },
                    evidence = trimPrefix(restart),
                ),
            )
        }

        // 气压重按校准：用户报「重按不灵」时，先看这里 —— 是没校准过、校准失败（样本不足），
        // 还是校准过但阈值偏高。只有日志里真的出现过校准相关记录才出这一项（没碰过就不占位）。
        val calibrationFinished = lines.lastOrNull { it.contains("PRESSURE_CALIBRATION_FINISHED") }
        val calibrationSample = lines.lastOrNull { it.contains("PRESSURE_CALIBRATION_SAMPLE") }
        val calibrationUnsupported = lines.lastOrNull { it.contains("PRESSURE_CALIBRATION_UNSUPPORTED") }
        val calibrationSent = lines.lastOrNull { it.contains("PRESSURE_CALIBRATION_SENT") }
        val calibrationRetry = lines.lastOrNull { it.contains("PRESSURE_CALIBRATION_RESULT_RETRY") }
        val calibrationEvidence = calibrationFinished ?: calibrationUnsupported ?: calibrationSample
        if (calibrationEvidence != null) {
            val succeeded = calibrationFinished?.contains("valid=true") == true
            // 投递那一半单独说：「算出了阈值」和「设置页看到了」是两件事，2026-10-13 那次
            // 「按满 5 次页面仍显示取消校准」就是后者坏了而前者正常。
            val sentVia = calibrationSent?.let { markerWord(it, "via=") }
            val retryVia = calibrationRetry?.let { markerWord(it, "via=") }
            val delivered = when {
                calibrationSent == null -> null
                sentVia != null && sentVia != "none" -> true
                // 第一次没落地、重投成功也算送到了（那正是重投存在的意义）
                retryVia != null && retryVia != "none" -> true
                else -> false
            }
            val delivery = when {
                delivered == null -> ""
                delivered && calibrationRetry == null -> "；投递通道 $sentVia"
                delivered -> "；第一次投递没落地，已自动重投（via=$retryVia）"
                else -> "；结果没能送到设置应用（App 被强停或未运行？），页面可能仍停在「校准中」"
            }
            add(
                DiagnosisCheck(
                    id = "behaviour.pressure_calibration",
                    group = CheckGroup.BEHAVIOUR,
                    title = "气压重按校准",
                    status = when {
                        delivered == false -> CheckStatus.WARN
                        calibrationFinished == null -> CheckStatus.INFO
                        succeeded -> CheckStatus.PASS
                        else -> CheckStatus.WARN
                    },
                    detail = when {
                        calibrationUnsupported != null && calibrationFinished == null ->
                            "本机没有可用的气压计，重按翻页用不了"
                        calibrationFinished == null ->
                            "校准还没结束（最近一次按压：${trimPrefix(calibrationSample ?: calibrationEvidence)}）"
                        succeeded -> buildString {
                            append("校准成功：")
                            append(markerInt(calibrationFinished, "validCount=") ?: 0)
                            append(" 个有效样本，阈值 ")
                            append(markerFloat(calibrationFinished, "threshold=") ?: 0f)
                            append(" hPa")
                            if (calibrationFinished.contains("reason=timeout")) {
                                append("（超时后用已收集的样本收尾）")
                            }
                            if (calibrationFinished.contains("clamped=true")) {
                                append("；原始结果超出可用区间，已夹到 0.15~4.0 hPa")
                            }
                            append(delivery)
                        }
                        else -> "本次校准没有成功：有效样本不足 3 个（按压没能让气压升高），阈值仍在用灵敏度预设或上一次的校准值" + delivery
                    },
                    evidence = trimPrefix(calibrationEvidence),
                ),
            )
        }

        // 功能页（快捷方式）：功能列表是 SystemUI 扫出来、经 ContentProvider 交给 App 的，
        // 「页面上什么都没有」可能是好几种完全不同的原因 —— 枚举不完整（LauncherApps 没答上来）、
        // 扫到了但没送到、只是内容没变而跳过了重复上报、或者面板里的编辑没能交给 App 落盘。
        val scan = lines.lastOrNull { it.contains("SHORTCUT_SCAN") }
        val functionHandoff = lines.lastOrNull { it.contains("FUNCTION_WRITE_HANDOFF") }
        val refreshRejected = lines.lastOrNull { it.contains("SHORTCUT_REFRESH_REJECTED") }
        val shortcutEvidence = scan ?: functionHandoff ?: refreshRejected
        if (shortcutEvidence != null) {
            val result = scan?.let { markerWord(it, "result=") }
            val items = scan?.let { markerInt(it, "items=") } ?: 0
            // 广播只报「发出去了没有」：sendBroadcast 不告诉我们对面收没收下（那是 App 进程的事，
            // 它的日志不在这份文件里）。所以措辞是「已发出」，不是「已送达」。
            val handoffFailed = functionHandoff?.contains("broadcast=false") == true
            add(
                DiagnosisCheck(
                    id = "behaviour.shortcuts",
                    group = CheckGroup.BEHAVIOUR,
                    title = "功能页（快捷方式）",
                    status = when {
                        handoffFailed -> CheckStatus.WARN
                        result == "FAILED" -> CheckStatus.WARN
                        result == "SENT" && items > 0 -> CheckStatus.PASS
                        else -> CheckStatus.INFO
                    },
                    detail = when {
                        scan == null -> "还没有扫描记录：SystemUI 侧一次都没枚举过功能列表"
                        result == "INCOMPLETE" ->
                            "这次枚举不完整（LauncherApps 没答上来或还没解锁），已按「不可信」处理：" +
                                "**没有**用它覆盖设置应用里的列表（页面这时只显示静态兜底的那几条）"
                        result == "FAILED" ->
                            "扫到 $items 条但**没能交给设置应用**（分片没被确认），功能页会一直空着"
                        result == "SENT" && items > 0 ->
                            "最近一次枚举 $items 条，已交给设置应用" +
                                // 注意用 " ms="：`items=137` 里也含 "ms="，裸 marker 会取到 137。
                                (scan.let { markerInt(it, " ms=") }?.let { "（耗时 ${it}ms）" } ?: "")
                        result == "UNCHANGED" -> "内容与上一次一致，跳过了重复上报（正常）"
                        result == "EMPTY" -> "这次枚举到 0 条（真的没有可枚举的快捷方式），已通知设置应用清掉旧条目"
                        items == 0 -> "枚举到 0 条功能"
                        else -> "最近一次枚举 $items 条（result=${result ?: "?"}）"
                    } + if (handoffFailed) "；另外面板里刚才那次功能收藏的编辑**没能发出去**（广播失败）" else "",
                    evidence = trimPrefix(shortcutEvidence),
                ),
            )
        }

        // 轻量打开（六开关）：六项分两个进程生效 —— 通知/控制中心在 SystemUI，分享/文本操作/
        // 应用间跳转在 system_server（热重载会重建，见 docs/light-open.md §4）。"没生效"因此至少有三种
        // 完全不同的原因，这一条把它们分开：钩子没装上、装上了但一次都没命中（护栏放行）、
        // 或者已经按名单改写窗口模式。
        val gateInstalled = lines.lastOrNull { it.contains("LIGHT_OPEN_GATE_INSTALLED") }
        val notificationInstalled = lines.lastOrNull { it.contains("LIGHT_OPEN_NOTIFICATION_HOOK_INSTALLED") }
        val controlCenterInstalled = lines.lastOrNull { it.contains("LIGHT_OPEN_CONTROL_CENTER_HOOK_INSTALLED") }
        val targetUnavailable = lines.lastOrNull {
            it.contains("LIGHT_OPEN_GATE_TARGET_UNAVAILABLE") ||
                it.contains("LIGHT_OPEN_NOTIFICATION_TARGET_UNAVAILABLE") ||
                it.contains("LIGHT_OPEN_CONTROL_CENTER_TARGET_UNAVAILABLE") ||
                it.contains("LIGHT_OPEN_SEND_TARGET_UNAVAILABLE") ||
                it.contains("LIGHT_OPEN_ISLAND_ANIM_UNAVAILABLE") ||
                it.contains("LIGHT_OPEN_*_CLASS_MISSING") ||
                it.contains("LIGHT_OPEN_SYSTEMUI_CLASS_MISSING") ||
                it.contains("LIGHT_OPEN_GATE_CLASS_MISSING")
        }
        val lastIntervention = lines.lastOrNull {
            it.contains("LIGHT_OPEN_FREEFORM") || it.contains("LIGHT_OPEN_FULLSCREEN") ||
                it.contains("LIGHT_OPEN_NOTIFICATION ") || it.contains("LIGHT_OPEN_CONTROL_CENTER ") ||
                it.contains("LIGHT_OPEN_SEND_APPLIED") || it.contains("LIGHT_OPEN_ISLAND_ANIM_APPLIED") ||
                it.contains("LIGHT_OPEN_CONTEXT_LAUNCH_APPLIED") || it.contains("LIGHT_OPEN_OPTIONS_APPLIED")
        }
        val anyLightOpen = gateInstalled != null || notificationInstalled != null ||
            controlCenterInstalled != null || targetUnavailable != null
        if (anyLightOpen) {
            val freeformCount = count(lines, "LIGHT_OPEN_FREEFORM")
            val fullscreenCount = count(lines, "LIGHT_OPEN_FULLSCREEN")
            add(
                DiagnosisCheck(
                    id = "behaviour.light_open",
                    group = CheckGroup.BEHAVIOUR,
                    title = "轻量打开（六开关）",
                    status = when {
                        targetUnavailable != null -> CheckStatus.WARN
                        gateInstalled == null || notificationInstalled == null ||
                            controlCenterInstalled == null -> CheckStatus.INFO
                        freeformCount + fullscreenCount > 0 -> CheckStatus.PASS
                        else -> CheckStatus.INFO
                    },
                    detail = buildString {
                        append("SystemUI 侧：点通知=")
                        append(if (notificationInstalled != null) "已装" else "未见")
                        append("，控制中心=")
                        append(if (controlCenterInstalled != null) "已装" else "未见")
                        append("；system_server 侧闸门=")
                        append(
                            if (gateInstalled != null) {
                                "已装"
                            } else {
                                "未见（这两项要重启手机，且 LSPosed 作用域要含系统框架）"
                            },
                        )
                        append("。这份日志里强制小窗 $freeformCount 次、强制全屏 $fullscreenCount 次。")
                        if (freeformCount + fullscreenCount == 0 && gateInstalled != null) {
                            append("开关已开却一次都没命中：多半是护栏放行（前台是桌面/锁屏/同应用），")
                            append("或者目标应用不支持小窗（日志里有 unsupported freeform mode）。")
                        }
                    },
                    evidence = trimPrefix((targetUnavailable ?: lastIntervention ?: gateInstalled).orEmpty()),
                ),
            )
        }
    }

    private fun count(lines: List<String>, marker: String): Int = lines.count { it.contains(marker) }

    /** 取 `marker=` 后面的整数（取不到返回 null；日志行里 marker 之后就是值本身）。 */
    private fun markerInt(line: String, marker: String): Int? =
        line.substringAfter(marker, "").trim().takeWhile { it.isDigit() || it == '-' }.toIntOrNull()

    /** 取 `marker=` 后面的浮点数。 */
    private fun markerFloat(line: String, marker: String): Float? =
        line.substringAfter(marker, "").trim().takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull()

    /** 取 `marker=` 后面的一个「词」（到下个空格为止），例如 `via=provider`。 */
    private fun markerWord(line: String, marker: String): String? =
        line.substringAfter(marker, "").trim().substringBefore(' ').takeIf { it.isNotEmpty() }

    // -----------------------------------------------------------------------------------------
    // 边角手势 —— 「扇形弹出来的时候，桌面是不是也跟着回了」
    // -----------------------------------------------------------------------------------------

    /**
     * 这一组回答用户报的那个现象：**全面屏手势（回桌面）和扇形面板一起弹出**。
     *
     * 机理（设备实测）：角落的 DOWN 必须在 MiuiHome 的原生识别器之前把指针流抢过来
     * （`InputManagerService: pilferPointers` 落在触摸后约 2 ms，晚一点 launcher 就已经
     * `app requestRedirect` 接管了，它的 HOME 是"看最初几个向上采样"就决定的）。日志里能
     * 证明这件事的只有三行：
     *  - `MON_DOWN … mode=pre-own|observe … lat=N`：这一笔有没有抢、抢得多快；
     *  - `MON_SYSTEM_CANCEL … owned=true`：抢了之后又被别人拿走（就是"一起弹出"的那一刻）；
     *  - `SYSTEMUI_CORNER_READY transport=…`：走的是手势监听（能抢）还是降级的 SPY 窗口（抢不了）。
     */
    private fun cornerChecks(lines: List<String>): List<DiagnosisCheck> = buildList {
        // The monitor itself states the transport on every settings application
        // (`PREFS_APPLIED … transport=monitor|spy-view`), and that line is emitted AFTER the
        // transport has been decided — unlike `SYSTEMUI_CORNER_READY`, which on the hot-reload
        // path runs before the main thread has started the monitor and therefore says
        // `pending`. Newest `transport=` line wins; `pending` is not a verdict.
        val transportLine = lines.lastOrNull {
            it.contains("transport=") &&
                (it.contains("PREFS_APPLIED") || it.contains("SYSTEMUI_CORNER_READY"))
        }
        val inputReady = lines.lastOrNull { it.contains("MON_INPUT_READY") }
        if (transportLine != null || inputReady != null) {
            val fallback = transportLine?.contains("transport=spy-view") == true
            val pending = transportLine?.contains("transport=pending") == true
            add(
                DiagnosisCheck(
                    id = "gesture.transport",
                    group = CheckGroup.GESTURE,
                    title = "角落手势的采集通道",
                    status = when {
                        fallback -> CheckStatus.FAIL
                        pending -> CheckStatus.INFO
                        else -> CheckStatus.PASS
                    },
                    detail = when {
                        fallback ->
                            "降级成了 SPY 窗口：这条通道抢不下指针流，角落滑动时系统会同时执行回桌面"
                        pending ->
                            "还没确定：这一行是系统界面启动途中打的，等一次 `PREFS_APPLIED … transport=` 再看"
                        else ->
                            "手势监听（DO_NOT_PILFER）：可以在 DOWN 时抢下指针流"
                    },
                    evidence = trimPrefix((transportLine ?: inputReady)!!),
                ),
            )
        }

        val down = lines.lastOrNull { it.contains("MON_DOWN ") }
        if (down != null) {
            val mode = Regex("mode=(\\S+)").find(down)?.groupValues?.get(1)
            val lat = Regex("lat=(-?\\d+)").find(down)?.groupValues?.get(1)?.toIntOrNull()
            val recv = Regex("recv=(-?\\d+)").find(down)?.groupValues?.get(1)?.toIntOrNull()
            val pil = Regex("pil=(-?\\d+)").find(down)?.groupValues?.get(1)?.toIntOrNull()
            val ime = Regex("ime=(\\d+)px").find(down)?.groupValues?.get(1)?.toIntOrNull()
            val cropped = mode == "cropped"
            val observe = mode != null && mode != "pre-own" && !cropped
            val slow = !cropped && lat != null && lat > SLOW_PREOWN_MS
            // 慢在哪一段：recv = dispatcher 投递到模块线程，pil = 模块自己的处理 + binder pilferPointers。
            // 这个拆分是 2026-10-12 那次排查的关键（用户报「时灵时不灵」）：那次是 recv 大 ——
            // 看门狗每 250ms 在输入线程上做两次 binder 调用，正好把偶尔一笔 DOWN 顶到 15ms。
            val blame = when {
                recv == null || pil == null -> ""
                recv >= pil && recv > SLOW_PREOWN_MS / 2 ->
                    "；慢在投递（${recv}ms）：模块的输入线程或系统当时忙"
                pil > recv && pil > SLOW_PREOWN_MS / 2 ->
                    "；慢在处理（${pil}ms）：抢指针流那一步本身慢了"
                else -> ""
            }
            add(
                DiagnosisCheck(
                    id = "gesture.preown",
                    group = CheckGroup.GESTURE,
                    title = if (cropped) {
                        "最近一次角落按下处理方式"
                    } else {
                        "最近一次角落按下有没有抢下指针流"
                    },
                    status = when {
                        mode == null -> CheckStatus.INFO
                        cropped -> CheckStatus.PASS
                        observe -> CheckStatus.FAIL
                        slow -> CheckStatus.WARN
                        else -> CheckStatus.PASS
                    },
                    detail = when {
                        mode == null ->
                            "这一版还没有记录抢占方式（日志行：${trimPrefix(down)}）"
                        cropped ->
                            "桌面 swipe-up 已被 system_server 裁出角落，按下时无需抢占指针流" +
                                "（纯观察，普通点击原样留给应用，滑动达阈值才接管）"
                        observe && ime != null && ime > 0 ->
                            "只观察（键盘弹着，按设计不抢）：这一笔系统仍可能同时回桌面"
                        observe ->
                            "只观察、没有抢：这一笔系统可以同时执行回桌面（扇面和桌面会一起出现）"
                        slow ->
                            "抢下了，但触摸到抢占用时 ${lat}ms（投递 ${recv}ms + 处理 ${pil}ms），" +
                                "偏慢（超过 ${SLOW_PREOWN_MS}ms 就有被 launcher 抢先的风险）$blame"
                        lat == null ->
                            "抢下了指针流（这一版还没记录用时）"
                        else ->
                            "按下的 ${lat}ms 内抢下了指针流，够快（投递 ${recv}ms + 处理 ${pil}ms）"
                    },
                    evidence = trimPrefix(down),
                ),
            )
        } else {
            add(
                DiagnosisCheck(
                    id = "gesture.preown",
                    group = CheckGroup.GESTURE,
                    title = "最近一次角落按下有没有抢下指针流",
                    status = CheckStatus.INFO,
                    detail = "日志里还没有角落手势的记录（先划一次左右下角再看）",
                ),
            )
        }

        // 时延分布：仅当还在用旧的 DOWN 抢占模式（`mode=pre-own`）时才有意义；
        // 如果最新一笔已经是 `mode=cropped`（无需在 DOWN 竞速抢流），就不再把旧的抢跑时延摆出来误导用户。
        val latestCropped = down?.contains("mode=cropped") == true
        val latSummary = lines.lastOrNull { it.contains("MON_LAT ") }
        val latSlow = lines.lastOrNull { it.contains("MON_LAT_SLOW") }
        val latLine = latSummary ?: latSlow
        if (latLine != null && !latestCropped) {
            val slowCount = Regex("slow\\(>\\d+ms\\)=(\\d+)").find(latLine)?.groupValues?.get(1)?.toIntOrNull()
            val p90 = Regex("p90=(\\d+)").find(latLine)?.groupValues?.get(1)?.toIntOrNull()
            val max = Regex("max=(\\d+)").find(latLine)?.groupValues?.get(1)?.toIntOrNull()
            val bad = (slowCount ?: 0) > 0
            add(
                DiagnosisCheck(
                    id = "gesture.latency",
                    group = CheckGroup.GESTURE,
                    title = "角落按下的时延分布（抢指针流的余量）",
                    status = if (bad) CheckStatus.WARN else CheckStatus.PASS,
                    detail = if (bad) {
                        "有 ${slowCount} 笔超过 ${SLOW_PREOWN_MS}ms（最好与最差差得多，就是用户说的「时灵时不灵」）；" +
                            "p90=${p90}ms max=${max}ms —— 慢的那几笔看 `MON_LAT_SLOW` 的 recv/pil 拆分"
                    } else {
                        "全部都在 ${SLOW_PREOWN_MS}ms 以内：p90=${p90}ms max=${max}ms，抢指针流的余量充足"
                    },
                    evidence = trimPrefix(latLine),
                ),
            )
        }

        val stolen = lines.lastOrNull { it.contains("MON_SYSTEM_CANCEL") }
        if (stolen != null) {
            val owned = stolen.contains("owned=true")
            val tracking = stolen.contains("tracking=true")
            val claimed = stolen.contains("claimed=true")
            add(
                DiagnosisCheck(
                    id = "gesture.stolen",
                    group = CheckGroup.GESTURE,
                    title = "抢下的指针流有没有被系统夺回",
                    status = if (owned || tracking || claimed) CheckStatus.FAIL else CheckStatus.PASS,
                    detail = if (owned || tracking || claimed) {
                        "有：模块拿着这一笔的时候系统又把它接管了 —— 这正是「面板和回桌面一起出现」"
                    } else {
                        "没有：这一笔系统接管时模块已经放手（正常，比如手势半途被系统取消）"
                    },
                    evidence = trimPrefix(stolen),
                ),
            )
        }

        val probeMissing = lines.lastOrNull { it.contains("MON_IME_PROBE_MISSING") }
        if (probeMissing != null) {
            add(
                DiagnosisCheck(
                    id = "gesture.ime_probe",
                    group = CheckGroup.GESTURE,
                    title = "键盘高度探针",
                    status = CheckStatus.WARN,
                    detail = "探针窗口没建起来：键盘弹着的时候角落也会被抢下，键盘上的角落点击要靠补发",
                    evidence = trimPrefix(probeMissing),
                ),
            )
        }

        val arbiterCrop = lines.lastOrNull { it.contains("ARBITER_CROP ") }
        val arbiterHooked = lines.lastOrNull { it.contains("ARBITER_HOOKED") }
        if (arbiterCrop != null || arbiterHooked != null) {
            val ok = arbiterCrop?.contains("ok=true") == true
            val cropDesc = arbiterCrop?.let { markerWord(it, "crop=") }
            add(
                DiagnosisCheck(
                    id = "gesture.arbiter_crop",
                    group = CheckGroup.GESTURE,
                    title = "桌面 swipe-up 监视器角落裁剪（system_server）",
                    status = when {
                        arbiterCrop != null && ok -> CheckStatus.PASS
                        arbiterCrop != null -> CheckStatus.WARN
                        else -> CheckStatus.INFO
                    },
                    detail = when {
                        arbiterCrop != null && ok ->
                            "已把桌面 swipe-up 的输入表面裁为 $cropDesc：角落按下不会送给桌面，无需竞速"
                        arbiterCrop != null ->
                            "尝试裁剪桌面 swipe-up 失败（见原文），回退到 SystemUI 侧 DOWN 抢占"
                        else ->
                            "已在 system_server 安装监视器钩子，等待捕获 InputManagerService 并应用裁剪"
                    },
                    evidence = trimPrefix((arbiterCrop ?: arbiterHooked)!!),
                ),
            )
        }
    }

    /**
     * 触摸到抢占用时超过这个毫秒数就不再算"稳"。
     *
     * 设备实测：正常一笔是 2ms 左右（dispatcher 发布 DOWN 到我们的 pilferPointers），
     * 而 MiuiHome 的接管发生在同一毫秒级 —— 8ms 已经意味着这一笔是靠运气赢的。
     */
    private const val SLOW_PREOWN_MS = 8

    /**
     * 裁掉 LSPosed / logcat 的前缀，只留模块自己写的那句话 —— 复制出去给人看时，前缀是噪声。
     * 两种格式：
     *  - `[ 2026-10-11T10:20:26.123  1: 2: 3 I/LSPosedFramework ] (com.android.systemui)[com.repl.bubbledrawer,BubbleDrawer,id,0,1] MSG`
     *  - `10-11 10:20:26.123  1234  5678 I BubbleDrawer: MSG`
     */
    fun trimPrefix(line: String): String {
        var text = line.trim()
        LSPD_PREFIX.find(text)?.let { match ->
            if (match.range.first == 0) text = text.substring(match.range.last + 1)
        }
        LOGCAT_TAG.find(text)?.let { match ->
            if (match.range.first == 0) text = text.substring(match.range.last + 1)
        }
        return text.trim().take(300)
    }

    /**
     * `[ 时间戳 … I/LSPosedFramework ] (包名)[com.repl.bubbledrawer,BubbleDrawer,id,0,1] ` ——
     * 必须一路吃到**模块 id 那个方括号**为止：时间戳那个 `] ` 后面紧跟的也是 `(`，
     * 少匹配一段就会把 `(com.android.systemui)[com.repl…]` 当成正文留下。
     */
    private val LSPD_PREFIX =
        Regex("""^\[[^]]*]\s*(?:\([^)]*\))?\[com\.repl\.bubbledrawer,[^]]*]\s*""")

    /** `10-11 10:20:26.123  1234  5678 I BubbleDrawer: `（tag 之前的一切）。 */
    private val LOGCAT_TAG = Regex("""^.*?\bBubbleDrawer\s*:\s*""")
}
