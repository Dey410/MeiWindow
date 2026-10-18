package com.repl.bubbledrawer.launch

import android.app.AppOpsManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 迷你小窗长按菜单里的「静音 / 取消静音」（2026-10-18）。
 *
 * 两套手段并用，和 HyperFreeformX 的做法一致：
 *
 *  1. HyperOS 私有接口 `AudioManager.getPlayerVolume(packageName)` /
 *     `setPlayerVolume(config, volume)`：按**应用**改播放音量，音量条位置保留，能变 0 也能还原；
 *  2. 兜底 `AppOpsManager` 的 `OP_PLAY_AUDIO`（28）：应用此刻没有活跃播放时（比如视频是暂停的），
 *     第 1 条无从下手，这一条至少能让它下次出声时被系统拦掉。
 *
 * **判断「现在是不是静音」，永远现场问系统，绝不缓存。**
 * 之前这里有一张 `mutedStateByPkg` 表，点一次静音就永远返回「静音」——用户用音量键、音量面板或
 * 应用自己的按钮把声音开回来之后，菜单图标还停在「取消静音」上（2026-10-18 用户报的第三个问题）。
 * 现在只保留「我们按过静音」这个事实用来还原音量（[savedVolumeByApp]），显示与否则现场读：
 * 播放音量为 0、或者 AppOps 被我们（或用户）设成 IGNORED，才算静音。
 */
object MiniAppAudioHelper {
    private const val TAG = "MiniAppAudioHelper"

    /** `AppOpsManager.OP_PLAY_AUDIO`。 */
    private const val OP_PLAY_AUDIO = 28

    /** 应用 key（`包名@uid`）→ 我们静音之前把播放音量改成了什么，取消静音时还原它。 */
    private val savedVolumeByApp = ConcurrentHashMap<String, Float>()

    private fun appKey(packageName: String, uid: Int): String = "$packageName@$uid"

    /**
     * 这个应用此刻是不是静音。
     *
     * 两条证据任意一条成立即可：播放音量为 0，或 `OP_PLAY_AUDIO` 被忽略。读不到（ROM 没有这些接口）
     * 就当没静音——宁可少显示一个「已静音」，也不要卡在一个假状态里。
     */
    fun isAppMuted(context: Context, packageName: String, uid: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val volume = getPlayerVolume(audioManager, packageName)
            if (volume != null && volume <= 0.001f) return true
        }
        return appOpsMode(context, packageName, uid) == AppOpsManager.MODE_IGNORED
    }

    /**
     * 切换静音。返回 true 表示至少有一半手段生效（播放音量或 AppOps）。
     *
     * @param mute true = 静音
     */
    fun setAppMuted(context: Context, packageName: String, uid: Int, mute: Boolean): Boolean {
        val key = appKey(packageName, uid)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var volumeApplied = false

        if (audioManager != null) {
            val configs = activeConfigs(audioManager, uid)
            if (mute) {
                val current = getPlayerVolume(audioManager, packageName)
                if (current != null && current > 0.001f && !savedVolumeByApp.containsKey(key)) {
                    savedVolumeByApp[key] = current
                }
                volumeApplied = configs.any { setPlayerVolume(audioManager, it, 0f) }
            } else {
                val restore = savedVolumeByApp.remove(key) ?: 1f
                volumeApplied = configs.any { setPlayerVolume(audioManager, it, restore) }
            }
        }

        val appOpsApplied = setAppOpsAudioMode(context, packageName, uid, mute)
        val success = volumeApplied || appOpsApplied
        Log.i(
            TAG,
            "setAppMuted pkg=$packageName uid=$uid mute=$mute " +
                "volume=$volumeApplied appOps=$appOpsApplied success=$success",
        )
        return success
    }

    /**
     * 迷你窗口关掉了：把我们替它做的静音**还回去**。
     *
     * 为什么必须还（源码依据见 [setAppMuted] 的两套手段）：静音是这个窗口的属性，窗口都没了，用户
     * 再从桌面打开同一个应用时却一声不出、而且没有入口解除 —— 那是个死状态。只还原**我们改过**的
     * 那个应用（[savedVolumeByApp] 里有记录才动），用户自己用音量键/面板静的音一概不碰。
     */
    fun release(context: Context, packageName: String, uid: Int) {
        if (packageName.isEmpty()) return
        val restore = savedVolumeByApp.remove(appKey(packageName, uid)) ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var volumeRestored = false
        if (audioManager != null) {
            volumeRestored = activeConfigs(audioManager, uid).any { setPlayerVolume(audioManager, it, restore) }
        }
        val appOpsCleared = setAppOpsAudioMode(context, packageName, uid, mute = false)
        Log.i(
            TAG,
            "release pkg=$packageName uid=$uid restore=$restore " +
                "volume=$volumeRestored appOps=$appOpsCleared",
        )
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

    private fun getClientUid(config: AudioPlaybackConfiguration): Int = runCatching {
        val method = config.javaClass.getMethod("getClientUid")
        method.isAccessible = true
        (method.invoke(config) as? Number)?.toInt() ?: -1
    }.getOrDefault(-1)

    /**
     * 读 `OP_PLAY_AUDIO` 的当前模式；读不到返回 [AppOpsManager.MODE_DEFAULT]（= 「不知道」，
     * 调用方按「没静音」处理）。
     */
    private fun appOpsMode(context: Context, packageName: String, uid: Int): Int {
        if (uid <= 0) return AppOpsManager.MODE_DEFAULT
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return AppOpsManager.MODE_DEFAULT
        return runCatching {
            // 先试按包名的三参版本（Android 11+ 的 checkOpNoThrow(String, int, String)）。
            val byName = runCatching {
                appOps.javaClass.getMethod(
                    "checkOpNoThrow",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ).invoke(appOps, "android:play_audio", uid, packageName)
            }.getOrNull()
            val value = byName ?: runCatching {
                appOps.javaClass.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ).invoke(appOps, OP_PLAY_AUDIO, uid, packageName)
            }.getOrNull()
            (value as? Number)?.toInt() ?: AppOpsManager.MODE_DEFAULT
        }.getOrDefault(AppOpsManager.MODE_DEFAULT)
    }

    /** 兜底：把 `OP_PLAY_AUDIO` 设成 IGNORED / ALLOWED（没有权限时返回 false，不算错）。 */
    private fun setAppOpsAudioMode(context: Context, packageName: String, uid: Int, mute: Boolean): Boolean {
        if (uid <= 0) return false
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (mute) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
        return runCatching {
            val setMode = runCatching {
                appOps.javaClass.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            if (setMode != null) {
                setMode.invoke(appOps, OP_PLAY_AUDIO, uid, packageName, mode)
            } else {
                appOps.javaClass.getMethod(
                    "setUidMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(appOps, OP_PLAY_AUDIO, uid, mode)
            }
            true
        }.onFailure {
            Log.w(TAG, "setAppOpsAudioMode failed pkg=$packageName uid=$uid mute=$mute", it)
        }.getOrDefault(false)
    }
}
