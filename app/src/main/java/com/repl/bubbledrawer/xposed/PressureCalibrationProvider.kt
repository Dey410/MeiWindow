package com.repl.bubbledrawer.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

/**
 * 气压校准的**主投递通道**：SystemUI（hook 进程写不了远程组）把每次按压、最终结果、超时通过
 * `ContentResolver.call()` 送到这里，再由 [SettingsStore] 落盘（本地镜像 + LSPosed 远程组）。
 *
 * 为什么不用广播当主通道（2026-10-13 用户报「按满 5 次后页面仍停在取消校准」）：
 *  - 广播**没有回执**，SystemUI 无法知道 App 到底收到没有；
 *  - 广播的发送方身份靠 `sentFromUid` / `sentFromPackage`，而这两个在**清单注册**的接收者上不保证
 *    有值 —— 拿不到时那条严格校验就会把每一条事件都拒掉（本地日志在 App 进程里，用户在设备上看不到，
 *    症状就是「校准永远不结束」）。
 *
 * provider 调用没有这两个问题：`Binder.getCallingUid()` 一定拿得到（uid → 装了哪些包反问
 * PackageManager），返回的 Bundle 就是回执。参考实现（`com.oxohang.fanfreeform` 的
 * `FanRuntime.reportPressureCalibration` → `ConfigProvider`）用的也正是这条路；广播保留为兜底。
 *
 * 安全：`android:exported="true"` 是跨应用调用的前提，所以入口做 uid 校验（只认 system / 自己 /
 * SystemUI 包）。事件内容只能改校准状态，读不到任何用户数据。
 */
class PressureCalibrationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PressureCalibrationBridge.METHOD_EVENT) return null
        val app = context?.applicationContext ?: return null
        val uid = Binder.getCallingUid()
        val packages = runCatching { app.packageManager.getPackagesForUid(uid)?.toList() }.getOrNull()
        if (!PressureCalibrationBridge.isTrustedUid(uid, packages)) {
            Log.w(TAG, "PRESSURE_CALIBRATION_CALL_REJECTED uid=$uid packages=$packages")
            return null
        }
        val event = PressureCalibrationBridge.Event.fromBundle(extras)
        if (event == null || !PressureCalibrationBridge.isKnownStage(event.stage)) {
            Log.w(TAG, "PRESSURE_CALIBRATION_CALL_REJECTED bad payload stage=${event?.stage}")
            return null
        }
        SettingsStore.applyPressureCalibrationEvent(
            context = app,
            stage = event.stage,
            attempts = event.attempts,
            delta = event.delta,
            valid = event.valid,
            threshold = event.threshold,
            validCount = event.validCount,
            via = "provider",
        )
        // 唯一的回执：非 null 且 applied=true 才代表「App 受理了」，SystemUI 据此决定要不要重投。
        return Bundle().apply { putBoolean(PressureCalibrationBridge.EXTRA_APPLIED, true) }
    }

    // 这条通道只用 call()：其余 CRUD 一律不实现（返回空/0），避免多出没必要的读写面。
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "BubbleDrawer"
    }
}
