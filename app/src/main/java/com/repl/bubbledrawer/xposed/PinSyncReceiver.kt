package com.repl.bubbledrawer.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.shortcut.FunctionPinStore

/**
 * Broadcast-based persistence handoff for the two user-curated lists edited inside SystemUI
 * (收藏应用 = pins, 已选功能 = shortcut keys).
 *
 * This is the ONLY leg of that handoff. It used to have a `startService` twin, which was
 * removed because it was not a security boundary at all: a service's `onStartCommand` runs in
 * a plain Handler message, so `Binder.getCallingUid()` there reports the process's OWN uid and
 * the caller check admitted everybody (any app could post `pins=""` and wipe both lists). It
 * was also pointless as a fallback — cross-app background `startService` is forbidden since
 * Android 8 (`BackgroundServiceStartNotAllowedException`). An explicit broadcast to a manifest
 * receiver cold-starts the module app even when it is idle or stopped.
 */
class PinSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!isFromSystemUi(this, context)) {
            // Was not verified at all before 2026-10-09 (any app could rewrite the pins);
            // the payload is harmless but the list is the user's, so check the sender.
            // uid + packages are logged so a device log can tell "identity unavailable"
            // (uid=-1) apart from "somebody else" — only the latter is an attack.
            Log.w(TAG, "PIN_SYNC_BROADCAST_REJECTED sender=${sentFromUidOrNull()} pkg=${sentFromPackageOrNull()}")
            return
        }
        val appContext = context.applicationContext

        val raw = intent.getStringExtra(EXTRA_PINS)
        if (raw != null) {
            if (!isPlausiblePinsValue(raw)) {
                Log.w(TAG, "PIN_SYNC_BROADCAST_REJECTED bad payload len=${raw.length}")
            } else {
                val rev = intent.getLongExtra(EXTRA_REV, 0L)
                if (rev > 0L) {
                    // Keep the SENDER's stamp: this is the panel's edit, not a new one (see
                    // SettingsStore.applyPushedPins).
                    SettingsStore.applyPushedPins(appContext, raw, rev)
                } else {
                    SettingsStore.pinBackend(appContext).write(raw)
                }
                Log.i(
                    TAG,
                    "PIN_SYNC_BROADCAST_APPLIED pins=${PinCodec.decode(raw).size} len=${raw.length} rev=$rev",
                )
            }
        }

        // 同一个交接通道也送「已选功能」（快捷方式收藏）：两个列表都由面板编辑，没必要开第二条路。
        val functions = intent.getStringExtra(EXTRA_FUNCTIONS)
        if (functions != null) {
            if (!isPlausibleFunctionsValue(functions)) {
                Log.w(TAG, "FUNCTION_SYNC_BROADCAST_REJECTED bad payload len=${functions.length}")
            } else {
                val rev = intent.getLongExtra(EXTRA_FUNCTIONS_REV, 0L)
                if (rev > 0L) {
                    SettingsStore.applyPushedFunctions(appContext, functions, rev)
                } else {
                    SettingsStore.functionBackend(appContext).write(functions)
                }
                Log.i(
                    TAG,
                    "FUNCTION_SYNC_BROADCAST_APPLIED functions=${FunctionPinStore.decode(functions).size} " +
                        "len=${functions.length} rev=$rev",
                )
            }
        }
    }

    private fun sentFromUidOrNull(): Int? = runCatching { sentFromUid }.getOrNull()

    private fun sentFromPackageOrNull(): String? = runCatching { sentFromPackage }.getOrNull()

    /**
     * True only for the SystemUI-side sender.
     *
     * Three ways in, because the two sender fields are not equally reliable on a manifest
     * receiver: the package NAME may be missing while the uid is present, so the uid is also
     * resolved through PackageManager (the module's own uid is accepted for self-refresh).
     * Fails closed: with no identity at all the edit is dropped and logged.
     */
    private fun isFromSystemUi(receiver: BroadcastReceiver, context: Context): Boolean = runCatching {
        val uid = receiver.sentFromUid
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return@runCatching true
        if (receiver.sentFromPackage == SYSTEMUI_PACKAGE) return@runCatching true
        context.packageManager.getPackagesForUid(uid)?.contains(SYSTEMUI_PACKAGE) == true
    }.getOrDefault(false)

    companion object {
        const val TAG = "BubbleDrawer"
        const val ACTION = "com.repl.bubbledrawer.action.SYNC_PINS"
        const val ACTION_TO_SYSTEMUI = "com.repl.bubbledrawer.action.SYNC_PINS_TO_SYSTEMUI"
        const val EXTRA_PINS = "pins"

        /** 「已选功能」（快捷方式收藏）走同一条交接通道，只是换了一组 extra。 */
        const val EXTRA_FUNCTIONS = "functions"

        /** 功能收藏的戳，语义同 [EXTRA_REV]。 */
        const val EXTRA_FUNCTIONS_REV = "functions_rev"

        /**
         * Freshness stamp travelling with [EXTRA_PINS] — see [com.repl.bubbledrawer.data.PinsSync].
         * Absent/0 means an older module build sent it.
         */
        const val EXTRA_REV = "pins_rev"

        /** On this ROM SystemUI is NOT uid 1000 (verified: u0_a231, `packages.list`). */
        const val SYSTEMUI_PACKAGE = "com.android.systemui"

        /** "pkg#user;…" for ~100 pins is < 5 KB; 10 KB is a generous cap. */
        private const val MAX_VALUE_LEN = 10_000

        /**
         * Grammar check for the encoded pin string: `pkg#user;pkg#user;…` (PinCodec
         * format). Empty = "no pins", which is legal (manage mode removes everything).
         */
        fun isPlausiblePinsValue(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return true
            if (trimmed.length > MAX_VALUE_LEN) return false
            return trimmed.split(';').filter { it.isNotBlank() }.all { entry ->
                entry.length <= 512 &&
                    entry.substringBefore('#').let { it.isNotEmpty() && it.none { c -> c == '#' || c == ';' } }
            }
        }

        /** 功能收藏的语法检查见 [FunctionPinStore.isPlausible]（键是 `pkg#id`，id 不受包名规则约束）。 */
        fun isPlausibleFunctionsValue(raw: String): Boolean = FunctionPinStore.isPlausible(raw)
    }
}
