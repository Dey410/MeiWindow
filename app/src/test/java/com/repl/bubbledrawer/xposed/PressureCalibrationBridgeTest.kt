package com.repl.bubbledrawer.xposed

import com.repl.bubbledrawer.bubble.PressureCalibrationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨进程协议的两条规则：只认已知阶段（不然会往 prefs 里写乱七八糟的值），只认可信发送方
 * （接收者是 exported 的，这条决定了「谁能改我的重按阈值」）。
 */
class PressureCalibrationBridgeTest {

    /** 这台 ROM 上 SystemUI 的真实形态：普通应用 uid + 包名 com.android.systemui。 */
    private val systemUiUid = 10231
    private val otherAppUid = 10500

    @Test
    fun `systemui is trusted even though it is not uid 1000`() {
        assertTrue(
            PressureCalibrationBridge.isTrustedSender(
                uid = systemUiUid,
                packageName = PressureCalibrationBridge.SYSTEMUI_PACKAGE,
                selfUid = 10999,
            ),
        )
    }

    @Test
    fun `an ordinary app is rejected`() {
        assertFalse(
            PressureCalibrationBridge.isTrustedSender(uid = otherAppUid, packageName = "com.evil.app", selfUid = 10999),
        )
        assertFalse(PressureCalibrationBridge.isTrustedSender(uid = otherAppUid, packageName = null, selfUid = 10999))
        assertFalse(PressureCalibrationBridge.isTrustedSender(uid = otherAppUid, packageName = "", selfUid = 10999))
    }

    /**
     * 包名这一条**不是**看 Intent 里带的字符串：`BroadcastReceiver.sentFromPackage` 由系统按发送方
     * uid 反查（发送者改不了）。SystemUI 在这台 ROM 上是普通应用 uid，所以那一条就是它唯一的身份
     * 凭证 —— 与 [PinSyncReceiver.isFromSystemUi] 同一条规则。
     */
    @Test
    fun `the systemui package name is what identifies the hook process`() {
        assertTrue(
            PressureCalibrationBridge.isTrustedSender(
                uid = systemUiUid,
                packageName = PressureCalibrationBridge.SYSTEMUI_PACKAGE,
                selfUid = 10999,
            ),
        )
    }

    @Test
    fun `system and the module app itself are trusted`() {
        assertTrue(PressureCalibrationBridge.isTrustedSender(uid = 1000, packageName = null, selfUid = 10999))
        assertTrue(PressureCalibrationBridge.isTrustedSender(uid = 10999, packageName = null, selfUid = 10999))
    }

    @Test
    fun `an unknown sender uid is not trusted`() {
        assertFalse(
            PressureCalibrationBridge.isTrustedSender(
                uid = null,
                packageName = PressureCalibrationBridge.SYSTEMUI_PACKAGE,
                selfUid = 10999,
            ),
        )
        // 系统没给出身份时是 -1（清单注册的接收者可能如此）——同样不可信，靠 provider 主通道兜住。
        assertFalse(
            PressureCalibrationBridge.isTrustedSender(
                uid = -1,
                packageName = PressureCalibrationBridge.SYSTEMUI_PACKAGE,
                selfUid = 10999,
            ),
        )
    }

    /**
     * provider 通道（主通道）的判定：拿 `Binder.getCallingUid()` 反问系统该 uid 装了哪些包 ——
     * 这条一定拿得到，所以它不依赖广播那套可能缺失的发送方信息。
     */
    @Test
    fun `the provider trusts only the uid that owns systemui`() {
        assertTrue(
            PressureCalibrationBridge.isTrustedUid(
                uid = systemUiUid,
                uidPackages = listOf(PressureCalibrationBridge.SYSTEMUI_PACKAGE),
                selfUid = 10999,
            ),
        )
        assertFalse(
            PressureCalibrationBridge.isTrustedUid(
                uid = otherAppUid,
                uidPackages = listOf("com.evil.app"),
                selfUid = 10999,
            ),
        )
        // 查不到包（uid 已消失 / 查询失败）也不信任
        assertFalse(PressureCalibrationBridge.isTrustedUid(uid = otherAppUid, uidPackages = null, selfUid = 10999))
        // system 与自己照旧信任
        assertTrue(PressureCalibrationBridge.isTrustedUid(uid = 1000, uidPackages = null, selfUid = 10999))
        assertTrue(PressureCalibrationBridge.isTrustedUid(uid = 10999, uidPackages = null, selfUid = 10999))
    }

    @Test
    fun `only the five known stages are accepted`() {
        val known = listOf(
            PressureCalibrationState.Stage.STARTED,
            PressureCalibrationState.Stage.SAMPLE,
            PressureCalibrationState.Stage.RESULT,
            PressureCalibrationState.Stage.TIMEOUT,
            PressureCalibrationState.Stage.UNSUPPORTED,
        )
        known.forEach { assertTrue(it, PressureCalibrationBridge.isKnownStage(it)) }
        assertFalse(PressureCalibrationBridge.isKnownStage("bogus"))
        assertFalse(PressureCalibrationBridge.isKnownStage(""))
        assertFalse(PressureCalibrationBridge.isKnownStage(null))
    }
}
