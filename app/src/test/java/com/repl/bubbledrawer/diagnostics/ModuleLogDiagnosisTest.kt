package com.repl.bubbledrawer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 诊断的判据：日志进、结论出。
 *
 * 样本取自真实设备日志（`build/os4log` 下抓下来的 `mod*.log`，OS4 上一切正常的那台）以及本次改动
 * 新加的字段，所以这里同时锁住三件事：**健康日志不许报错**、**每一种已知故障都要被抓到**、
 * **新旧两种日志格式都能读**（用户机器上装的可能是上一版）。
 */
class ModuleLogDiagnosisTest {

    private companion object {
        /** 真机日志的两种前缀（LSPosed 模块日志 / logcat），用来验证裁剪与识别。 */
        const val LSPD_PREFIX =
            "[ 2026-10-10T23:26:04.589    10231: 28108: 28108 I/LSPosedFramework ] " +
                "(com.android.systemui)[com.repl.bubbledrawer,BubbleDrawer,6dcc-1a1266af691-2-13d,0,1] "
        const val LOGCAT_PREFIX = "10-11 10:20:26.123  1234  5678 I BubbleDrawer: "

        /** 真机上一台 OS4（一切正常）的安装记录 —— 注意老版本没有 `hooks=` / `rect=` 计数。 */
        val HEALTHY = listOf(
            "${LOGCAT_PREFIX}HOOK_INSTALLED_MiuiFreeformModeTaskRepository",
            "${LOGCAT_PREFIX}HOOK_INSTALLED_MiuiMultiWindowUtils.getFreeFormScale hooks=9",
            "${LOGCAT_PREFIX}HOOK_INSTALLED_MiuiFreeformLaunchConfig.getFreeformArg scale=2 leftMargin=4",
            "${LOGCAT_PREFIX}HOOK_INSTALLED_MiuiMultiWindowUtils.avoidAsPossible",
            "${LOGCAT_PREFIX}HOOK_INSTALLED_BoundsCentering rect=15 rectFailed=0 interact=3 interactFailed=0 mini=1 miniFailed=0",
            "${LOGCAT_PREFIX}HOOK_INSTALLED_RestoreAvoid",
            "${LOGCAT_PREFIX}HOOK_INSTALLED_onBottomCaptionHandleMotionEvents",
            "${LOGCAT_PREFIX}HOOK_INSTALLED_MiuiFreeformModeAnimation.startGestureAnimation",
            "${LOGCAT_PREFIX}PROBE prefs[enabled=true center=true scale=80 mini=true dim=true outside=true noOffset=false]",
            // 真机原样的一行：976x1561 的窗口在 1220x2656 的屏幕上，落点 (122, 547) 就是正中。
            LSPD_PREFIX + "CENTERED_BOUNDS rect=Rect(122, 547 - 1342, 2499) scale=0.8 src=rom " +
                "applied=0.8 assumed=0.8 targetScale=0.8 avoided=false visual=(976x1561) screen=(1220 x 2656)",
            "${LOGCAT_PREFIX}FREEFORM_SCALE getFreeFormScale(FanContext,true,false,1,true,false,mark.via,1220,2656) " +
                "target=0.8 rom=0.8 used=0.8 clamped=false",
        )

        /** 期望设置 = 应用里读到的值。 */
        val EXPECTED = ExpectedSettings(freeformEnabled = true, center = true, scale = 80, noOffset = false)
    }

    private fun checksOf(lines: List<String>) = ModuleLogDiagnosis.analyze(lines, EXPECTED).checks
    private fun fail(check: DiagnosisCheck?, vararg needles: String) {
        assertTrue("expected a check, got null", check != null)
        assertEquals("expected FAIL, got ${check!!.status} (${check.detail})", CheckStatus.FAIL, check.status)
        for (needle in needles) {
            assertTrue("detail should mention '$needle': ${check.detail}", check.detail.contains(needle))
        }
    }

    @Test
    fun aHealthyDeviceReportsNothingWrong() {
        val diagnosis = ModuleLogDiagnosis.analyze(HEALTHY, EXPECTED)

        assertEquals("no failures expected: " + diagnosis.checks.filter { it.status == CheckStatus.FAIL }, 0, diagnosis.failed)
        assertEquals("全部正常", diagnosis.headline)
        assertTrue(diagnosis.checks.any { it.id == "placement.last" && it.status == CheckStatus.PASS })
        assertTrue(diagnosis.checks.any { it.id == "settings.effective" && it.status == CheckStatus.PASS })
    }

    @Test
    fun aWindowThatIsNotInTheMiddleIsTheHeadlineFailure() {
        // 同一台机器，只有落点被挪走 100/140px —— 正是用户说的"不居中"。
        val lines = HEALTHY.map {
            if (it.contains("CENTERED_BOUNDS rect=")) {
                it.replace("Rect(122, 547 - 1342, 2499)", "Rect(222, 407 - 1442, 2359)")
            } else {
                it
            }
        }
        val check = checksOf(lines).first { it.id == "placement.last" }

        // wantTop 是 (2656 - 1561) / 2 = 547.5 四舍五入到 548，"偏"就是与实际落点之差。
        fail(check, "不在屏幕中央", "(122, 548)", "(222, 407)", "(100, -141)")
    }

    @Test
    fun theRomUsingItsOwnSizeIsReported() {
        // 这台 ROM 没按设置给小窗定尺寸：它用 0.65，模块的设置推断值是 0.80。
        val lines = HEALTHY.map {
            if (it.contains("CENTERED_BOUNDS rect=")) {
                it.replace("applied=0.8 assumed=0.8", "applied=0.65 assumed=0.8")
            } else {
                it
            }
        }
        val check = checksOf(lines).first { it.id == "placement.scale_source" }

        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains("0.65"))
        assertTrue(check.detail.contains("不是设置值决定的"))
    }

    @Test
    fun aSizeThatDoesNotFollowTheSettingIsReported() {
        val lines = HEALTHY.map {
            if (it.contains("FREEFORM_SCALE ")) {
                it.replace("target=0.8 rom=0.8 used=0.8 clamped=false", "target=0.8 rom=0.65 used=0.65 clamped=true")
            } else {
                it
            }
        }
        val check = checksOf(lines).first { it.id == "placement.size" }

        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains("设置 80%"))
        assertTrue(check.detail.contains("实际 65%"))
    }

    @Test
    fun aHotReloadThatWasNotFollowedByAReinstallIsCaught() {
        // `adb install -r` 之后 hook 全被摘掉：安装记录在最前面，热重载压在最后。
        val lines = HEALTHY + "$LOGCAT_PREFIX" + "HOT_RELOADED process=com.android.systemui unhooked=54"
        val check = checksOf(lines).first { it.id == "module.hot_reload" }

        fail(check, "hook 是死的", "重启系统界面")
        assertTrue(check.evidence!!.contains("unhooked=54"))
    }

    @Test
    fun aReloadThatWasRepairedPasses() {
        val lines = listOf("$LOGCAT_PREFIX" + "HOT_RELOADED process=com.android.systemui unhooked=54") + HEALTHY
        val check = checksOf(lines).first { it.id == "module.hot_reload" }

        assertEquals(CheckStatus.PASS, check.status)
    }

    @Test
    fun aMissingSizeConfigHookIsTheOs3Signature() {
        val lines = HEALTHY.filterNot { it.contains("HOOK_INSTALLED_MiuiFreeformLaunchConfig") } +
            "$LOGCAT_PREFIX" + "HOOK_SKIPPED_FreeformLaunchConfig cls=false"
        val check = checksOf(lines).first { it.id == "hooks.launch_config" }

        fail(check, "没装上", "缩放值不生效")
    }

    @Test
    fun aHookThatFoundNothingInTheRomIsCaught() {
        val lines = HEALTHY.map {
            if (it.contains("HOOK_INSTALLED_BoundsCentering")) {
                it.substringBefore("HOOK_INSTALLED_BoundsCentering") +
                    "HOOK_INSTALLED_BoundsCentering rect=0 rectFailed=15 interact=3 interactFailed=0 mini=0 miniFailed=1"
            } else {
                it
            }
        } + "$LOGCAT_PREFIX" + "BOUNDS_CENTER_NO_MATCH MiniStateHandler cls=true methods=getRestoredBounds/2"
        val checks = checksOf(lines)

        fail(checks.first { it.id == "hooks.bounds" }, "rect=0", "mini=0")
        val noMatch = checks.first { it.id == "hooks.no_match" }
        assertEquals(CheckStatus.FAIL, noMatch.status)
        assertTrue(noMatch.title.contains("注入点"))
        assertTrue(noMatch.detail.contains("小窗居中无处下手"))
    }

    @Test
    fun settingsThatNeverReachedSystemUiAreCaught() {
        // 模块日志说系统界面里总开关是关的，而应用里明明是开着的。
        val lines = HEALTHY.map {
            if (it.contains("PROBE prefs")) {
                it.replace("enabled=true center=true", "enabled=false center=true")
            } else {
                it
            }
        }
        val check = checksOf(lines).first { it.id == "settings.effective" }

        fail(check, "两边不一致", "总开关=关")
    }

    @Test
    fun aCenteringAttemptThatWasSkippedSaysWhy() {
        val lines = HEALTHY + "$LOGCAT_PREFIX" +
            "CENTER_SKIPPED src=getCustomFreeformRect/7 enabled=true center=false scale=80 noOffset=false"
        val check = checksOf(lines).first { it.id == "settings.center_gate" }

        fail(check, "「小窗居中」是关的")
    }

    @Test
    fun anEmptyLogSaysTheModuleIsNotRunning() {
        val diagnosis = ModuleLogDiagnosis.analyze(emptyList(), EXPECTED)

        fail(diagnosis.checks.first { it.id == "module.loaded" }, "没在 com.android.systemui 里跑起来")
        assertEquals("日志里没有模块输出", diagnosis.headline)
    }

    @Test
    fun theOlderLogFormatStillParses() {
        // 1.1.7 之前的一行（没有 src/applied/assumed/avoided，screen 也没有空格）也要能读出落点。
        val old = LSPD_PREFIX +
            "CENTERED_BOUNDS rect=Rect(122, 547 - 1342, 2499) scale=0.8 targetScale=0.8 visual=(976x1561) screen=(1220x2656)"
        val diagnosis = ModuleLogDiagnosis.analyze(listOf(old), null)
        val check = diagnosis.checks.first { it.id == "placement.last" }

        assertEquals(CheckStatus.PASS, check.status)
        assertFalse(diagnosis.checks.any { it.id == "placement.scale_source" })
    }

    @Test
    fun theCopyTextCarriesTheVerdictsAndTheEvidence() {
        val text = ModuleLogDiagnosis.analyze(HEALTHY, EXPECTED).toText()

        assertTrue(text.contains("魅窗诊断"))
        assertTrue(text.contains("【居中与尺寸】"))
        assertTrue(text.contains("【Hook 安装】"))
        assertTrue(text.contains("CENTERED_BOUNDS rect=Rect(122, 547"))
        // 前缀是噪声，复制出去的是模块自己写的那句话。
        assertFalse(text.contains("LSPosedFramework"))
    }

    @Test
    fun prefixesAreTrimmedForBothLogFormats() {
        assertEquals(
            "HOOK_INSTALLED_RestoreAvoid",
            ModuleLogDiagnosis.trimPrefix(LSPD_PREFIX + "HOOK_INSTALLED_RestoreAvoid"),
        )
        assertEquals(
            "HOOK_INSTALLED_RestoreAvoid",
            ModuleLogDiagnosis.trimPrefix(LOGCAT_PREFIX + "HOOK_INSTALLED_RestoreAvoid"),
        )
    }

    /**
     * 拿本机从真机抓下来的整份日志跑一遍（`build/os4log/mod*.log`，见仓库根的 build 目录）：
     * 判据的语料就是这么来的，所以它至少要能识别出模块的行、给出结论，并且**不留下任何前缀**。
     * CI 上没有这份日志，`assumeTrue` 会让它跳过而不是失败。
     */
    @Test
    fun theCapturedDeviceLogIsUnderstood() {
        val log = listOf("build/os4log", "../build/os4log")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
            ?.listFiles { file -> file.name.startsWith("mod") && file.name.endsWith(".log") }
            ?.maxByOrNull { it.length() }
        org.junit.Assume.assumeTrue("no captured device log available", log != null && log.length() > 0)

        val lines = log!!.readLines()
        val moduleLines = ModuleLogDiagnosis.moduleLines(lines)
        assertTrue("the module's own lines must be recognised", moduleLines.size > 100)

        val diagnosis = ModuleLogDiagnosis.analyze(lines, expected = null)
        assertTrue("verdicts must come out", diagnosis.checks.size >= 6)
        assertFalse(
            "trimmed lines must not keep the LSPosed prefix",
            diagnosis.raw.any { it.contains("LSPosedFramework") || it.contains("com.repl.bubbledrawer,") },
        )
        // 这份日志里有一台真机"两个小窗互相避让"的记录，所以避让那组一定有话可说。
        assertTrue(diagnosis.checks.any { it.id == "behaviour.avoid" })
        assertTrue(diagnosis.toText().contains("魅窗诊断"))
    }
}
