package com.repl.bubbledrawer.xposed

import android.content.SharedPreferences
import com.repl.bubbledrawer.lightopen.LightOpenSettings

/**
 * 【轻量打开】配置缓存：把远程 prefs 里的六个开关读成一份不可变对象，并在变化时刷新。
 *
 * 两个理由不让 hook 直接每次读 prefs：
 *  1. 判定跑在**启动路径**上（system_server 的 startActivity、SystemUI 的通知点击），
 *     一次启动读 9 个键 + 一次名单解析（字符串 split）是白白的开销；
 *  2. prefs 监听器在 SharedPreferences 里是**弱引用**，所以监听必须挂在一个我们长期持有的对象上
 *     —— 这个类的实例由各自的 installer 持有（[LightOpenGate] / [LightOpenSystemUi]）。
 */
class LightOpenWatcher(private val prefs: SharedPreferences) {

    @Volatile
    private var cached: LightOpenSettings = read()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 名单与六个开关都在这几个键里；其它键（贴边、扇形……）变化不重建。
        if (key == null || key.startsWith(LIGHT_OPEN_KEY_PREFIX)) {
            cached = read()
        }
    }

    init {
        runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }
    }

    fun current(): LightOpenSettings = cached

    /** 重新读一次（installer 在装载时调用一次，确保热重载后拿到最新值）。 */
    fun refresh(): LightOpenSettings = read().also { cached = it }

    private fun read(): LightOpenSettings =
        RemotePrefs.lightOpenSettings(RemotePrefs.read(prefs))

    private companion object {
        const val LIGHT_OPEN_KEY_PREFIX = "light_open_"
    }
}
