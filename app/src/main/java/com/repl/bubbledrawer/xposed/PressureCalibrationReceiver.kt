package com.repl.bubbledrawer.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.repl.bubbledrawer.bubble.PressureCalibration

/**
 * 接收 SystemUI 侧校准会话的进度与结果，交给 [SettingsStore] 落盘（本地镜像 + LSPosed 远程组）。
 *
 * 与 [PinSyncReceiver] 同一个形状：导出的接收者 + 发送方校验 + 「把系统那边的事实写进 App 的
 * 真相」。区别只在负载 —— 收藏是一整份列表，这里是几个标量，所以状态迁移用纯函数
 * [com.repl.bubbledrawer.bubble.PressureCalibrationState.applyEvent] 表达，接收者只负责搬运。
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
        val stage = intent.getStringExtra(PressureCalibrationBridge.EXTRA_STAGE)
        if (!PressureCalibrationBridge.isKnownStage(stage)) {
            Log.w(TAG, "PRESSURE_CALIBRATION_REJECTED unknown stage=$stage")
            return
        }
        SettingsStore.applyPressureCalibrationEvent(
            context = context.applicationContext,
            stage = stage!!,
            attempts = intent.getIntExtra(PressureCalibrationBridge.EXTRA_ATTEMPTS, 0),
            delta = intent.getFloatExtra(PressureCalibrationBridge.EXTRA_DELTA, 0f),
            valid = intent.getBooleanExtra(PressureCalibrationBridge.EXTRA_VALID, false),
            threshold = intent.getFloatExtra(PressureCalibrationBridge.EXTRA_THRESHOLD, 0f),
            validCount = intent.getIntExtra(PressureCalibrationBridge.EXTRA_VALID_COUNT, 0),
        )
        // 参考实现里「第 n/5 次」这类提示由页面轮询 prefs 得到（FanRuntime.reportPressureCalibration
        // → ContentProvider.call）；这里 App 就是被通知的一方，写完本地文件后 Compose 的
        // SettingsStore.observe 会自己推一次，页面不需要轮询。
        Log.i(
            TAG,
            "PRESSURE_CALIBRATION_APPLIED stage=$stage attempts=${intent.getIntExtra(PressureCalibrationBridge.EXTRA_ATTEMPTS, 0)}" +
                "/${PressureCalibration.REQUIRED_ATTEMPTS}",
        )
    }

    private companion object {
        const val TAG = "BubbleDrawer"
    }
}
