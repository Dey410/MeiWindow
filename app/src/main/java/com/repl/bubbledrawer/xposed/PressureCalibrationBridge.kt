package com.repl.bubbledrawer.xposed

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.repl.bubbledrawer.bubble.PressureCalibrationState

/**
 * 「气压重按校准」的跨进程协议（SystemUI → App）。
 *
 * 为什么必须跨进程：hook 进程拿到的远程组是**只读镜像**（`FanHost` 里那段注释记着实测结果：
 * `edit()` 抛 `UnsupportedOperationException: Read only implementation`），所以采集到的按压样本
 * 只能交给 App 进程去落盘 —— 与收藏（[PinSyncReceiver]）同一个约束。反过来 App → SystemUI 的
 * 「开始/取消校准」走 prefs（App 写得动，SystemUI 的监听器收得到），两个方向各用各自能用的通道。
 *
 * **两条投递通道**（2026-10-13 用户报「按满 5 次后页面仍停在取消校准」之后补的）：
 *
 *  1. [METHOD_EVENT] 的 **ContentProvider.call**（主通道）：同步、有回执，发送方当场就知道 App
 *     受理没有；调用方身份用 `Binder.getCallingUid()` 判定，**不依赖** `sentFromUid` /
 *     `sentFromPackage` —— 那两个在清单注册的接收者上并不保证有值，一旦拿不到，广播通道就会
 *     静默地把每一条事件都拒掉（页面于是永远停在「校准中」）。参考实现
 *     （`com.oxohang.fanfreeform` 的 `FanRuntime.reportPressureCalibration` → `ConfigProvider`）
 *     用的正是这条路。
 *  2. **显式广播**（兜底）：App 进程被杀 / provider 一时不可用时仍能送达，清单接收者会被拉起来。
 *
 * 协议收在这里是为了让「发」与「收」引同一份常量；[isTrustedSender] / [isTrustedUid] 做成纯函数，
 * 好让「谁能改我的校准状态」这件事有单测兜着（两条通道都是 exported 的，不认发送方就等于把重按
 * 阈值交给了任何一个 App）。
 */
object PressureCalibrationBridge {

    const val ACTION = "com.repl.bubbledrawer.action.PRESSURE_CALIBRATION"

    /** 主通道的 provider（App 侧 `PressureCalibrationProvider`）。 */
    const val AUTHORITY = "com.repl.bubbledrawer.calibration"
    const val METHOD_EVENT = "event"

    const val EXTRA_STAGE = "stage"
    const val EXTRA_ATTEMPTS = "attempts"
    const val EXTRA_DELTA = "delta"
    const val EXTRA_VALID = "valid"
    const val EXTRA_THRESHOLD = "threshold"
    const val EXTRA_VALID_COUNT = "valid_count"

    /** provider 的回执：App 确实受理了这次事件（广播没有返回值，只有这条通道能确认）。 */
    const val EXTRA_APPLIED = "applied"

    /** SystemUI 的包名（这块 ROM 上它不是 uid 1000，实测 u0_a231 —— 与 [PinSyncReceiver] 同一条）。 */
    const val SYSTEMUI_PACKAGE = "com.android.systemui"

    fun uri(): Uri = Uri.parse("content://$AUTHORITY/event")

    /**
     * 一次校准事件的全部载荷。两个通道共用同一份字段定义（[toBundle] / [toIntent]），
     * 免得「广播里叫 valid_count、provider 里叫 validCount」这种歪法各写一遍。
     */
    data class Event(
        val stage: String,
        val attempts: Int = 0,
        val delta: Float = 0f,
        val valid: Boolean = false,
        val threshold: Float = 0f,
        val validCount: Int = 0,
    ) {
        fun toBundle(): Bundle = Bundle().apply {
            putString(EXTRA_STAGE, stage)
            putInt(EXTRA_ATTEMPTS, attempts)
            putFloat(EXTRA_DELTA, delta)
            putBoolean(EXTRA_VALID, valid)
            putFloat(EXTRA_THRESHOLD, threshold)
            putInt(EXTRA_VALID_COUNT, validCount)
        }

        fun toIntent(): Intent = Intent(ACTION).putExtras(toBundle())

        companion object {
            fun fromBundle(bundle: Bundle?): Event? {
                val stage = bundle?.getString(EXTRA_STAGE) ?: return null
                return Event(
                    stage = stage,
                    attempts = bundle.getInt(EXTRA_ATTEMPTS, 0),
                    delta = bundle.getFloat(EXTRA_DELTA, 0f),
                    valid = bundle.getBoolean(EXTRA_VALID, false),
                    threshold = bundle.getFloat(EXTRA_THRESHOLD, 0f),
                    validCount = bundle.getInt(EXTRA_VALID_COUNT, 0),
                )
            }

            fun fromIntent(intent: Intent): Event? = fromBundle(intent.extras)
        }
    }

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
     * 广播通道的发送方可信吗：system（uid 1000）、本 App 自己，或 SystemUI 包。
     *
     * [uid] 为 null/-1 表示系统没给出（清单注册的接收者可能拿不到发送方信息）—— 那就**不可信**：
     * 校准值决定重按灵不灵，宁可丢掉这条事件（还有 provider 主通道 + 一次自动重投），也不能让
     * 任意 App 写进来。
     *
     * [selfUid] 只是为了让这条规则能单测（JVM 单测里 `Process.myUid()` 是抛异常的桩）。
     */
    fun isTrustedSender(uid: Int?, packageName: String?, selfUid: Int = Process.myUid()): Boolean {
        if (uid == null || uid < 0) return false
        if (uid == Process.SYSTEM_UID || uid == selfUid) return true
        return packageName == SYSTEMUI_PACKAGE
    }

    /**
     * provider 通道的发起方可信吗：拿 `Binder.getCallingUid()` 反问系统「这个 uid 装了哪些包」，
     * 而不是相信载荷里写的任何东西。[uidPackages] 为 null 表示查不到该 uid 的包。
     */
    fun isTrustedUid(uid: Int, uidPackages: List<String>?, selfUid: Int = Process.myUid()): Boolean {
        if (uid < 0) return false
        if (uid == Process.SYSTEM_UID || uid == selfUid) return true
        return uidPackages?.contains(SYSTEMUI_PACKAGE) == true
    }
}
