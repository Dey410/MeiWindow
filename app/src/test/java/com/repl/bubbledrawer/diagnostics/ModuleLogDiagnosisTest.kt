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
            // 边角手势：走手势监听（能抢），且最近一笔是「按下的 2ms 内抢下」（真机实测的正常值）。
            // 顺序照真机：安装/重装先打 CORNER_READY，监视器随后自己报 transport（它才是权威）。
            "${LOGCAT_PREFIX}SYSTEMUI_CORNER_READY transport=gesture-monitor",
            "${LOGCAT_PREFIX}PREFS_APPLIED enabled=true left=true right=true range=40 " +
                "transport=monitor cur=1220x2656 max=1220x2656 " +
                "freeform[enabled=true center=true scale=80 mini=true noOffset=false dim=true]",
            LSPD_PREFIX + "MON_DOWN side=RIGHT x=1139.0 y=2582.0 box=163.0 band=true ime=0px " +
                "mode=pre-own lat=2 cur=1220x2656 max=1220x2656",
            LSPD_PREFIX + "MON_SYSTEM_CANCEL tracking=false claimed=false kept=false owned=false " +
                "x=1207.0 y=2304.0 inSector=false foreignInjections=0",
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
    fun aCornerStrokeThatOnlyObservedIsTheHomeAndFanBug() {
        // 角落按下没抢指针流 = MiuiHome 的识别器说了算 = 面板和回桌面一起出现。
        val lines = HEALTHY.map {
            if (it.contains("MON_DOWN ")) it.replace("mode=pre-own", "mode=observe") else it
        }
        val check = checksOf(lines).first { it.id == "gesture.preown" }

        fail(check, "只观察", "系统可以同时执行回桌面")
    }

    @Test
    fun aPreOwnThatTookTooLongIsWorthAWarning() {
        // 抢是抢了，但用了 52ms —— 这一笔是靠运气赢的（正常值 ~2ms）。
        val lines = HEALTHY.map {
            if (it.contains("MON_DOWN ")) it.replace("lat=2", "lat=52") else it
        }
        val check = checksOf(lines).first { it.id == "gesture.preown" }

        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains("52ms"), )
        assertTrue(check.detail.contains("抢先"))
    }

    @Test
    fun aStreamThatWasTakenBackIsReportedAsTheReportedBug() {
        // owned=true 的那次 CANCEL：模块拿着这一笔，系统又接管了 —— 用户报的现象。
        val lines = HEALTHY.map {
            if (it.contains("MON_SYSTEM_CANCEL")) it.replace("owned=false", "owned=true") else it
        }
        val check = checksOf(lines).first { it.id == "gesture.stolen" }

        fail(check, "又把它接管了", "一起出现")
    }

    @Test
    fun theFallbackTransportIsReportedAsUnableToShield() {
        val lines = HEALTHY.map {
            if (it.contains("SYSTEMUI_CORNER_READY")) {
                it.replace("transport=gesture-monitor", "transport=spy-view-fallback")
            } else if (it.contains("PREFS_APPLIED")) {
                // 监视器没建起来时，监视器自己的那行也会写 spy-view。
                it.replace("transport=monitor", "transport=spy-view")
            } else {
                it
            }
        }
        val check = checksOf(lines).first { it.id == "gesture.transport" }

        fail(check, "降级", "回桌面")
    }

    @Test
    fun aHotReloadThatIsStillStartingIsNotAFallback() {
        // 热重载入口比主线程更早打 SYSTEMUI_CORNER_READY，那时 transport=pending；
        // 8ms 后监视器自己报 transport=monitor —— 不许把前者当成降级。
        val lines = HEALTHY.map {
            if (it.contains("SYSTEMUI_CORNER_READY")) {
                it.replace("transport=gesture-monitor", "transport=pending")
            } else {
                it
            }
        }
        val check = checksOf(lines).first { it.id == "gesture.transport" }

        assertEquals(CheckStatus.PASS, check.status)
        assertTrue(check.evidence!!.contains("transport=monitor"))
    }

    @Test
    fun anOlderLogWithoutTheLatencyFieldStillParses() {
        // 1.1.8 及以前的 MON_DOWN 没有 lat= —— 老日志不许因此报错。
        val lines = HEALTHY.map {
            if (it.contains("MON_DOWN ")) it.replace(" lat=2", "") else it
        }
        val check = checksOf(lines).first { it.id == "gesture.preown" }

        assertEquals(CheckStatus.PASS, check.status)
        assertTrue(check.detail.contains("还没记录用时"))
    }

    @Test
    fun aLogWithoutAnyCornerStrokeSaysSoInsteadOfFailing() {
        val lines = HEALTHY.filterNot { it.contains("MON_DOWN ") }
        val check = checksOf(lines).first { it.id == "gesture.preown" }

        assertEquals(CheckStatus.INFO, check.status)
        assertTrue(check.detail.contains("先划一次"))
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

    // ---------------------------------------------------------------------------------------
    // 气压重按校准（用户报「重按不灵」时最先要看的一项）
    // ---------------------------------------------------------------------------------------

    @Test
    fun aHealthyLogWithoutCalibrationSaysNothingAboutIt() {
        assertTrue(checksOf(HEALTHY).none { it.id == "behaviour.pressure_calibration" })
    }

    @Test
    fun aSuccessfulPressureCalibrationIsReportedWithItsSampleCountAndThreshold() {
        val lines = HEALTHY + listOf(
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_STARTED reason=REQUEST timeoutMs=120000 " +
                "sensor=Barometer effective=1.2",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_SAMPLE attempt=1 valid=true delta=0.31 validCount=1",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_SAMPLE attempt=5 valid=true delta=0.61 validCount=5",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_FINISHED reason=result valid=true validCount=5 " +
                "threshold=0.365 clamped=false attempts=5",
        )
        val check = checksOf(lines).firstOrNull { it.id == "behaviour.pressure_calibration" }
        assertTrue("expected a check, got null", check != null)
        assertEquals("expected PASS, got ${check!!.status} (${check.detail})", CheckStatus.PASS, check.status)
        assertTrue(check.detail, check.detail.contains("5 个有效样本"))
        assertTrue(check.detail, check.detail.contains("0.365 hPa"))
    }

    @Test
    fun aCalibrationThatRanOutOfValidSamplesWarns() {
        val lines = HEALTHY + listOf(
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_STARTED reason=REQUEST timeoutMs=120000",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_FINISHED reason=result valid=false validCount=1 attempts=5",
        )
        val check = checksOf(lines).firstOrNull { it.id == "behaviour.pressure_calibration" }
        assertTrue("expected a check, got null", check != null)
        assertEquals("expected WARN, got ${check!!.status}", CheckStatus.WARN, check.status)
        assertTrue(check.detail, check.detail.contains("有效样本不足 3 个"))
    }

    @Test
    fun aTimedOutCalibrationThatStillProducedAThresholdPasses() {
        val lines = HEALTHY + listOf(
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_FINISHED reason=timeout valid=true validCount=3 " +
                "threshold=0.42 clamped=false attempts=4",
        )
        val check = checksOf(lines).firstOrNull { it.id == "behaviour.pressure_calibration" }
        assertEquals(CheckStatus.PASS, check!!.status)
        assertTrue(check.detail, check.detail.contains("超时后用已收集的样本收尾"))
    }

    @Test
    fun aDeviceWithoutABarometerIsReportedAsUnavailable() {
        val lines = HEALTHY + listOf(
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_UNSUPPORTED reason=REQUEST",
        )
        val check = checksOf(lines).firstOrNull { it.id == "behaviour.pressure_calibration" }
        assertEquals(CheckStatus.INFO, check!!.status)
        assertTrue(check.detail, check.detail.contains("气压计"))
    }

    /**
     * 「算出了阈值」和「设置页看到了」是两件事：2026-10-13 用户报「按满 5 次页面仍显示取消校准」，
     * SystemUI 那半边完全正常，坏的是投递。诊断要把投递结果单独说出来。
     */
    @Test
    fun theDeliveryChannelIsReportedNextToTheResult() {
        val withProvider = HEALTHY + listOf(
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_FINISHED reason=result valid=true validCount=5 threshold=0.365",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_SENT via=provider stage=result attempts=5",
        )
        val viaProvider = checksOf(withProvider).first { it.id == "behaviour.pressure_calibration" }
        assertEquals(CheckStatus.PASS, viaProvider.status)
        assertTrue(viaProvider.detail, viaProvider.detail.contains("投递通道 provider"))

        val withRetry = HEALTHY + listOf(
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_FINISHED reason=result valid=true validCount=5 threshold=0.365",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_SENT via=none stage=result attempts=5",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_RESULT_RETRY via=provider stage=result",
        )
        val retried = checksOf(withRetry).first { it.id == "behaviour.pressure_calibration" }
        assertTrue(retried.detail, retried.detail.contains("自动重投"))

        val notDelivered = HEALTHY + listOf(
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_FINISHED reason=result valid=true validCount=5 threshold=0.365",
            "${LOGCAT_PREFIX}PRESSURE_CALIBRATION_SENT via=none stage=result attempts=5",
        )
        val lost = checksOf(notDelivered).first { it.id == "behaviour.pressure_calibration" }
        assertEquals(CheckStatus.WARN, lost.status)
        assertTrue(lost.detail, lost.detail.contains("没能送到设置应用"))
    }
}
