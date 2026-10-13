package com.repl.bubbledrawer.xposed

import android.content.Intent
import android.os.Process
import com.repl.bubbledrawer.bubble.PressureCalibrationState

/**
 * 「气压重按校准」的跨进程协议（SystemUI → App）。
 *
 * 为什么必须是广播而不是写 prefs：hook 进程拿到的远程组是**只读镜像**
 * （`FanHost` 里那段注释记着实测结果：`edit()` 抛 `UnsupportedOperationException: Read only
 * implementation`），所以采集到的按压样本只能交给 App 进程去落盘 —— 与收藏（[PinSyncReceiver]）
 * 完全同一条路。反过来 App → SystemUI 的「开始/取消校准」走 prefs（App 写得动，SystemUI 的
 * 监听器收得到），两个方向各用各自能用的那条通道。
 *
 * 协议本身收在这里，是为了让「发」和「收」两侧引同一份常量；[isTrustedSender] 也做成纯函数，
 * 好让「谁能改我的校准状态」这件事有单测兜着（接收者是 exported 的，不认发送方就等于把校准
 * 阈值交给了任何一个 App）。
 */
object PressureCalibrationBridge {

    const val ACTION = "com.repl.bubbledrawer.action.PRESSURE_CALIBRATION"

    const val EXTRA_STAGE = "stage"
    const val EXTRA_ATTEMPTS = "attempts"
    const val EXTRA_DELTA = "delta"
    const val EXTRA_VALID = "valid"
    const val EXTRA_THRESHOLD = "threshold"
    const val EXTRA_VALID_COUNT = "valid_count"

    /** SystemUI 的包名（这块 ROM 上它不是 uid 1000，实测 u0_a231 —— 与 [PinSyncReceiver] 同一条）。 */
    const val SYSTEMUI_PACKAGE = "com.android.systemui"

    fun intent(
        stage: String,
        attempts: Int = 0,
        delta: Float = 0f,
        valid: Boolean = false,
        threshold: Float = 0f,
        validCount: Int = 0,
    ): Intent = Intent(ACTION)
        .putExtra(EXTRA_STAGE, stage)
        .putExtra(EXTRA_ATTEMPTS, attempts)
        .putExtra(EXTRA_DELTA, delta)
        .putExtra(EXTRA_VALID, valid)
        .putExtra(EXTRA_THRESHOLD, threshold)
        .putExtra(EXTRA_VALID_COUNT, validCount)

    /** 已知阶段；未知的一律丢弃（跨版本混装时宁可不动，也不要瞎写 prefs）。 */
    fun isKnownStage(stage: String?): Boolean = when (stage) {
        PressureCalibrationState.Stage.STARTED,
        PressureCalibrationState.Stage.SAMPLE,
        PressureCalibrationState.Stage.RESULT,
        PressureCalibrationState.Stage.TIMEOUT,
        PressureCalibrationState.Stage.UNSUPPORTED,
        -> true

        else -> false
    }

    /**
     * 发送方可信吗：system（uid 1000）、本 App 自己，或 SystemUI 包。
     *
     * [uid] 为 null 表示系统没给出（老系统/无法判定）—— 那就**不可信**：校准值决定重按灵不灵，
     * 宁可丢掉一条合法事件（页面上再按一次「开始校准」即可），也不能让任意 App 写进来。
     *
     * [selfUid] 只是为了让这条规则能单测（JVM 单测里 `Process.myUid()` 是抛异常的桩）。
     */
    fun isTrustedSender(uid: Int?, packageName: String?, selfUid: Int = Process.myUid()): Boolean {
        if (uid == null) return false
        if (uid == Process.SYSTEM_UID || uid == selfUid) return true
        return packageName == SYSTEMUI_PACKAGE
    }
}
