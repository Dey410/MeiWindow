package com.repl.bubbledrawer.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 校准事件的**兜底通道**（主通道是 [PressureCalibrationProvider] 的 `call()`）。
 *
 * 保留它的理由：App 进程被杀/被冻结、provider 一时不可用时，显式广播仍然能把 App 拉起来。
 * 与 [PinSyncReceiver] 同一个形状：导出的接收者 + 发送方校验 + 「把系统那边的事实写进 App 的真相」。
 *
 * 注意这条通道的**固有弱点**（也是这次加 provider 的原因）：广播没有回执，而 `sentFromUid` /
 * `sentFromPackage` 在清单注册的接收者上不保证有值 —— 拿不到就一律拒绝，于是 SystemUI 以为送到了、
 * App 其实什么也没收到。所以发送侧是「provider 优先 + 广播兜底 + 一次重投」。
 */
class PressureCalibrationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PressureCalibrationBridge.ACTION) return
        val uid = runCatching { sentFromUid }.getOrNull()
        val pkg = runCatching { sentFromPackage }.getOrNull()
        if (!PressureCalibrationBridge.isTrustedSender(uid, pkg)) {
            Log.w(TAG, "PRESSURE_CALIBRATION_REJECTED sender uid=$uid pkg=$pkg")
            return
        }
        val event = PressureCalibrationBridge.Event.fromIntent(intent)
        if (event == null || !PressureCalibrationBridge.isKnownStage(event.stage)) {
            Log.w(TAG, "PRESSURE_CALIBRATION_REJECTED unknown stage=${event?.stage}")
            return
        }
        SettingsStore.applyPressureCalibrationEvent(
            context = context.applicationContext,
            stage = event.stage,
            attempts = event.attempts,
            delta = event.delta,
            valid = event.valid,
            threshold = event.threshold,
            validCount = event.validCount,
            via = "broadcast",
        )
        // 参考实现里「第 n/5 次」这类提示由页面轮询 prefs 得到（FanRuntime.reportPressureCalibration
        // → ContentProvider.call）；这里 App 就是被通知的一方，写完本地文件后 Compose 的
        // SettingsStore.observe 会自己推一次，页面不需要轮询。
        Log.i(TAG, "PRESSURE_CALIBRATION_APPLIED via=broadcast stage=${event.stage}")
    }

    private companion object {
        const val TAG = "BubbleDrawer"
    }
}
