package com.repl.bubbledrawer.launch

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 迷你小窗长按菜单里的「静音 / 取消静音」（2026-10-18）。
 *
 * 手段有两套，和 HyperFreeformX 一致：
 *
 *  1. HyperOS 私有接口 `AudioManager.getPlayerVolume(packageName)` /
 *     `setPlayerVolume(config, volume)`：按**应用**改播放音量，音量条位置保留，能变 0 也能还原；
 *  2. 兜底 `AppOpsManager` 的 `OP_PLAY_AUDIO`（28）：给它下次出声时上锁。
 *
 * **应用没在出声时按静音**（用户 2026-10-18 报的第二个问题）：这时第 1 条没有
 * `AudioPlaybackConfiguration` 可用，第 2 条又不一定有权限（`MANAGE_APP_OPS_MODES` 通常只给 Settings），
 * 于是那一次点击什么都不会发生。所以「静音」先记成**意图**（[MiniMuteState]），并注册一次
 * `registerAudioPlaybackCallback`：等这个应用真的开始播声音，回调里立刻把它的播放音量压到 0。
 *
 * **图标显示什么**：现场读系统事实（音量为 0 / AppOps 被忽略），再用 [MiniMuteState] 把"还没机会
 * 生效的意图"合并进去。这样别处把声音开回来时图标会自己变（上一版就是因为缓存了一个
 * `mutedStateByPkg` 才卡住的）。
 */
object MiniAppAudioHelper {
    private const val TAG = "MiniAppAudioHelper"

    /** `AppOpsManager.OP_PLAY_AUDIO`。 */
    private const val OP_PLAY_AUDIO = 28

    /** `OP_PLAY_AUDIO` 的字符串形式（给 `checkOpNoThrow(String, int, String)` 用）。 */
    private const val OPSTR_PLAY_AUDIO = "android:play_audio"

    /** 待还原音量的持久化文件：SystemUI 重启后还要把上个进程欠的音量账还上。 */
    private const val STORE_NAME = "bubbledrawer_mini_mute"

    /** 持久化键前缀，值是"静音前的播放音量"。 */
    private const val STORE_PREFIX = "restore."

    /** 「静音 / 未静音」的意图与事实，见 [MiniMuteState]。 */
    private val muteState = MiniMuteState()

    /** 应用 key（`包名@uid`）→ 我们静音之前把播放音量改成了什么，取消静音时还原它。 */
    private val savedVolumeByApp = ConcurrentHashMap<String, Float>()

    /**
     * 「已经欠下、但还没还」的播放音量：应用 key → 静音前的值。
     *
     * 为什么需要它（2026-10-23，用户报「小窗长按静音后手机按键无法调节音量」）：
     * `setPlayerVolume` 只对**活跃的**播放通道生效。窗口关掉（或 SystemUI 重启）时应用要是没在出声，
     * 调它等于什么都没做，可 `mMusicVolumeMap` 里那条 0 已经写进去了 —— 应用以后每次出声都是 0，
     * 音量键调的是媒体流音量、乘出来还是 0，表现就是"按键怎么按都没声"。
     * 所以这种情况不丢这个值，先记在这里，由 [playbackCallback] 在它下次出声时补上。
     */
    private val pendingRestore = ConcurrentHashMap<String, Float>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 停止待生效静音用的播放回调；只有存在待生效请求时才注册。 */
    private var watchRegistered = false
    private var watchContext: Context? = null

    /** 待还原音量的持久化，见 [ensureStore]。 */
    private var store: SharedPreferences? = null
    private var storeLoaded = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val ctx = watchContext ?: return
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val pending = muteState.pendingKeys()
            if (pending.isNotEmpty()) {
                val pendingUids = pending.mapNotNull { key -> uidOfKey(key)?.let { it to key } }
                for (config in configs) {
                    // 回调给的本来就是"活跃"的那批播放（`isActive()` 不是公开 API，这里不需要再筛）。
                    val uid = getClientUid(config)
                    if (uid <= 0) continue
                    val key = pendingUids.firstOrNull { it.first == uid }?.second ?: continue
                    if (setPlayerVolume(audioManager, config, 0f)) {
                        Log.i(TAG, "pending mute applied key=$key uid=$uid volume=0")
                    }
                }
            }
            // 窗口关掉时没在出声的应用，等它这次出声把音量还回去。
            applyPendingRestores(audioManager, configs)
            if (muteState.pendingKeys().isEmpty() && pendingRestore.isEmpty()) {
                stopWatch(audioManager)
            }
        }
    }

    private fun appKey(packageName: String, uid: Int): String = "$packageName@$uid"

    private fun uidOfKey(key: String): Int? = key.substringAfterLast('@', "").toIntOrNull()

    /**
     * 控制器启动时调一次：把上个进程（SystemUI 重启前）没还完的音量捞回来。
     *
     * 没有这一步，静音值就只活在内存里：进程一重启，`mMusicVolumeMap` 里那条 0 还在，
     * 我们却再也不知道它原来是多大 —— 应用就永久哑了，音量键也救不回来。
     */
    fun attach(context: Context) {
        if (ensureStore(context) == null) return
        if (pendingRestore.isEmpty()) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        applyPendingRestores(audioManager)
        if (pendingRestore.isNotEmpty()) startWatch(context, audioManager)
    }

    /** 读一次持久化的"待还原音量"，只在第一次调用时从磁盘加载。 */
    private fun ensureStore(context: Context): SharedPreferences? {
        if (storeLoaded) return store
        val ctx = context.applicationContext ?: context
        val sp = runCatching { ctx.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE) }.getOrNull()
        store = sp
        storeLoaded = true
        sp?.all?.forEach { (key, value) ->
            if (!key.startsWith(STORE_PREFIX)) return@forEach
            val volume = (value as? Number)?.toFloat() ?: return@forEach
            pendingRestore[key.removePrefix(STORE_PREFIX)] = volume
        }
        return sp
    }

    /** 记一笔"欠着的音量"（窗口关了/进程重启后还要还）。 */
    private fun savePendingRestore(key: String, volume: Float) {
        pendingRestore[key] = volume
        store?.edit()?.putFloat(STORE_PREFIX + key, volume)?.apply()
    }

    private fun clearPendingRestore(key: String) {
        pendingRestore.remove(key)
        store?.edit()?.remove(STORE_PREFIX + key)?.apply()
    }

    /**
     * 把 [pendingRestore] 里的账还掉。`configs` 为 null 时现场查每个 uid 的活跃播放。
     *
     * @return 是否至少还掉了一笔
     */
    private fun applyPendingRestores(
        audioManager: AudioManager,
        configs: List<AudioPlaybackConfiguration>? = null,
    ): Boolean {
        if (pendingRestore.isEmpty()) return false
        var appliedAny = false
        for ((key, volume) in pendingRestore.entries.toList()) {
            val uid = uidOfKey(key) ?: continue
            val targets = configs?.filter { getClientUid(it) == uid } ?: activeConfigs(audioManager, uid)
            if (targets.isEmpty()) continue
            if (targets.any { setPlayerVolume(audioManager, it, volume) }) {
                clearPendingRestore(key)
                appliedAny = true
            }
        }
        return appliedAny
    }

    /**
     * 把窗口静音过的应用音量还回去。
     *
     * 应用此刻没在出声时不能直接丢值 —— `setPlayerVolume` 对它不生效，map 里的 0 会留下来，
     * 所以改成 [pendingRestore]，等它下次出声再补（见 [playbackCallback]）。
     *
     * @param fallback 没有记录时的还原值：点「取消静音」时用 1.0（满音量）。
     * @return true = 已经还原（或现场音量本来就不是 0），false = 记了待还原
     */
    private fun restoreVolume(
        context: Context,
        audioManager: AudioManager?,
        packageName: String,
        uid: Int,
        key: String,
        fallback: Float,
    ): Boolean {
        val restore = savedVolumeByApp.remove(key) ?: fallback
        if (audioManager != null) {
            // 用户/应用自己已经把声音开回来了：以现场为准，别拿旧值盖回去。
            val live = getPlayerVolume(audioManager, packageName)
            if (live != null && live > 0.001f) {
                clearPendingRestore(key)
                return true
            }
            val configs = activeConfigs(audioManager, uid)
            if (configs.isNotEmpty() && configs.any { setPlayerVolume(audioManager, it, restore) }) {
                clearPendingRestore(key)
                return true
            }
            savePendingRestore(key, restore)
            startWatch(context, audioManager)
            return false
        }
        savePendingRestore(key, restore)
        return false
    }

    /**
     * 这个应用此刻该显示成「已静音」吗？
     *
     * 会顺手推进 [MiniMuteState]（意图 → 事实的迁移），所以既要在点击后调用，也要在菜单显示期间
     * 定时调用（`FlymeMiniMenuOverlay` 每 400ms 一次）。
     */
    fun isAppMuted(context: Context, packageName: String, uid: Int): Boolean {
        ensureStore(context)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val liveMuted = isLiveMuted(context, audioManager, packageName, uid)
        val key = appKey(packageName, uid)
        val shown = muteState.observe(key, liveMuted)
        if (!shown && !liveMuted && savedVolumeByApp.remove(key) != null) {
            // 别处（音量面板/应用自己）已经把声音开回来了：我们记的"静音前音量"作废，
            // 否则关窗口时 release 会拿旧值把用户刚调好的音量盖回去。
            clearPendingRestore(key)
        }
        if (muteState.pendingKeys().isEmpty()) stopWatch(audioManager)
        return shown
    }

    /**
     * 切换静音。
     *
     * @param mute true = 静音。应用没在出声时这一步只是"记下意图"，等它出声由播放回调补刀。
     * @return true 表示这次点击有效（已经压到 0、AppOps 上了锁，或登记了待生效的意图）
     */
    fun setAppMuted(context: Context, packageName: String, uid: Int, mute: Boolean): Boolean {
        ensureStore(context)
        val key = appKey(packageName, uid)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var volumeApplied = false

        if (audioManager != null) {
            if (mute) {
                // 用户又按了静音：之前"还没还回去"的那笔账作废，以这次为准。
                clearPendingRestore(key)
                val current = getPlayerVolume(audioManager, packageName)
                if (current != null && current > 0.001f) {
                    savedVolumeByApp.putIfAbsent(key, current)
                    // 同时落盘：SystemUI 重启后内存里的这份会没，靠它把这笔账还上（见 ensureStore）。
                    store?.edit()?.putFloat(STORE_PREFIX + key, current)?.apply()
                }
                volumeApplied = activeConfigs(audioManager, uid).any { setPlayerVolume(audioManager, it, 0f) }
            } else {
                volumeApplied = restoreVolume(context, audioManager, packageName, uid, key, fallback = 1f)
            }
        }

        val appOpsApplied = setAppOpsAudioMode(context, packageName, uid, mute)

        if (mute) {
            muteState.requestMute(key)
            // 没压到正在播的那条通道 = 应用此刻没声音：等它出声再压（见 playbackCallback）。
            if (audioManager != null && !volumeApplied) {
                startWatch(context, audioManager)
            }
        } else {
            muteState.requestUnmute(key)
            stopWatchIfIdle(audioManager)
        }

        val pending = muteState.hasPendingMute(key)
        val success = volumeApplied || appOpsApplied || pending
        Log.i(
            TAG,
            "setAppMuted pkg=$packageName uid=$uid mute=$mute volume=$volumeApplied " +
                "appOps=$appOpsApplied pending=$pending success=$success",
        )
        return success
    }

    /**
     * 迷你窗口关掉了：把我们替它做的静音**还回去**。
     *
     * 静音是这个窗口的属性，窗口没了就没人能解除它了 —— 否则会留下"从桌面打开同一个应用一声不
     * 出、也没有入口恢复"的死状态。只还原**我们改过**的那个应用，用户自己用音量键/面板静的音一概不碰。
     */
    fun release(context: Context, packageName: String, uid: Int) {
        if (packageName.isEmpty()) return
        ensureStore(context)
        val key = appKey(packageName, uid)
        muteState.forget(key)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var volumeRestored = false
        val restore = savedVolumeByApp[key]
        if (restore != null) {
            // 应用没在出声时这一笔还不掉，restoreVolume 会把它记成待还原。
            volumeRestored = restoreVolume(context, audioManager, packageName, uid, key, fallback = restore)
        }
        val appOpsCleared = setAppOpsAudioMode(context, packageName, uid, mute = false)
        stopWatchIfIdle(audioManager)
        Log.i(
            TAG,
            "release pkg=$packageName uid=$uid restore=$restore " +
                "volume=$volumeRestored appOps=$appOpsCleared",
        )
    }

    // ---------------------------------------------------------------------------------------
    // 待生效静音的播放回调
    // ---------------------------------------------------------------------------------------

    private fun startWatch(context: Context, audioManager: AudioManager) {
        if (watchRegistered) return
        watchContext = context.applicationContext ?: context
        runCatching { audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler) }
            .onSuccess {
                watchRegistered = true
                Log.i(TAG, "playback watch registered (waiting for the app to make a sound)")
            }
            .onFailure { Log.w(TAG, "registerAudioPlaybackCallback failed", it) }
    }

    private fun stopWatch(audioManager: AudioManager?) {
        if (!watchRegistered) return
        watchRegistered = false
        runCatching { audioManager?.unregisterAudioPlaybackCallback(playbackCallback) }
        Log.i(TAG, "playback watch unregistered")
    }

    private fun stopWatchIfIdle(audioManager: AudioManager?) {
        if (muteState.pendingKeys().isEmpty() && pendingRestore.isEmpty()) stopWatch(audioManager)
    }

    // ---------------------------------------------------------------------------------------
    // 系统事实
    // ---------------------------------------------------------------------------------------

    private fun isLiveMuted(
        context: Context,
        audioManager: AudioManager?,
        packageName: String,
        uid: Int,
    ): Boolean {
        if (audioManager != null) {
            val volume = getPlayerVolume(audioManager, packageName)
            if (volume != null && volume <= 0.001f) return true
        }
        return appOpsMode(context, packageName, uid) == AppOpsManager.MODE_IGNORED
    }

    /** 读隐藏接口 `AudioManager.getPlayerVolume(String): float`（没有就返回 null）。 */
    fun getPlayerVolume(audioManager: AudioManager, packageName: String): Float? = runCatching {
        val method = audioManager.javaClass.getMethod("getPlayerVolume", String::class.java)
        method.isAccessible = true
        (method.invoke(audioManager, packageName) as? Number)?.toFloat()
    }.getOrNull()

    /** 写隐藏接口 `AudioManager.setPlayerVolume(AudioPlaybackConfiguration, float)`。 */
    fun setPlayerVolume(audioManager: AudioManager, config: AudioPlaybackConfiguration, volume: Float): Boolean =
        runCatching {
            val method = audioManager.javaClass.getMethod(
                "setPlayerVolume",
                AudioPlaybackConfiguration::class.java,
                java.lang.Float.TYPE,
            )
            method.isAccessible = true
            method.invoke(audioManager, config, volume)
            true
        }.onFailure {
            Log.w(TAG, "setPlayerVolume failed", it)
        }.getOrDefault(false)

    /** 这个 uid 当前所有活跃播放（`AudioPlaybackConfiguration.getClientUid` 是隐藏接口）。 */
    private fun activeConfigs(audioManager: AudioManager, uid: Int): List<AudioPlaybackConfiguration> {
        if (uid <= 0) return emptyList()
        return runCatching {
            audioManager.activePlaybackConfigurations.filter { getClientUid(it) == uid }
        }.getOrDefault(emptyList())
    }

    private fun getClientUid(config: AudioPlaybackConfiguration): Int {
        runCatching {
            val method = config.javaClass.getMethod("getClientUid")
            method.isAccessible = true
            (method.invoke(config) as? Number)?.toInt()
        }.getOrNull()?.let { return it }
        // 某些版本没有这个 getter，退到字段（AOSP 的 AudioPlaybackConfiguration 一直是 mClientUid）。
        return runCatching {
            config.javaClass.getDeclaredField("mClientUid").apply { isAccessible = true }.getInt(config)
        }.getOrDefault(-1)
    }

    /**
     * 读 `OP_PLAY_AUDIO` 的当前模式；读不到返回 [AppOpsManager.MODE_DEFAULT]（= 「不知道」，
     * 调用方按「没静音」处理）。
     */
    private fun appOpsMode(context: Context, packageName: String, uid: Int): Int {
        if (uid <= 0) return AppOpsManager.MODE_DEFAULT
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return AppOpsManager.MODE_DEFAULT
        // 先试按名字的三参版本（Android 11+），再退到按 op 号的老版本。
        val byName = runCatching {
            appOps.javaClass.getMethod(
                "checkOpNoThrow",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
            ).invoke(appOps, OPSTR_PLAY_AUDIO, uid, packageName)
        }.getOrNull()
        val value = byName ?: runCatching {
            appOps.javaClass.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            ).invoke(appOps, OP_PLAY_AUDIO, uid, packageName)
        }.getOrNull()
        return (value as? Number)?.toInt() ?: AppOpsManager.MODE_DEFAULT
    }

    /**
     * 兜底：把 `OP_PLAY_AUDIO` 设成 IGNORED / ALLOWED。
     *
     * 两个签名**各自独立尝试**：4 参 `setMode(int, int, String, int)` 在部分版本里只剩
     * `@SystemApi`，调用会抛 SecurityException —— 以前那种"第一个查到就当成功"的写法一旦抛异常
     * 就直接返回 false，后面的 `setUidMode` 根本没跑过。
     */
    private fun setAppOpsAudioMode(context: Context, packageName: String, uid: Int, mute: Boolean): Boolean {
        if (uid <= 0) return false
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (mute) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED

        val viaSetMode = runCatching {
            AppOpsManager::class.java.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(appOps, OP_PLAY_AUDIO, uid, packageName, mode)
            true
        }.onFailure {
            Log.w(TAG, "AppOps setMode failed pkg=$packageName uid=$uid", it)
        }.getOrDefault(false)
        if (viaSetMode) return true

        return runCatching {
            AppOpsManager::class.java.getMethod(
                "setUidMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(appOps, OP_PLAY_AUDIO, uid, mode)
            true
        }.onFailure {
            Log.w(TAG, "AppOps setUidMode failed pkg=$packageName uid=$uid", it)
        }.getOrDefault(false)
    }
}
