package com.repl.bubbledrawer.xposed

import android.content.SharedPreferences
import android.os.SystemClock
import com.repl.bubbledrawer.lightopen.LightOpenSettings

/**
 * 【轻量打开】配置缓存：把远程 prefs 里的六个开关读成一份不可变对象，并在变化时刷新。
 *
 * 两个理由不让 hook 直接每次读 prefs：
 *  1. 判定跑在**启动路径**上（system_server 的 startActivity、SystemUI 的通知点击），
 *     一次启动读 9 个键 + 一次名单解析（字符串 split）是白白的开销；
 *  2. prefs 监听器在 SharedPreferences 里是**弱引用**，所以监听必须挂在一个我们长期持有的对象上
 *     —— 这个类的实例由各自的 installer 持有（[LightOpenGate] / [LightOpenSystemUi]）。
 *
 * ## 为什么还要"超时重读"（真机 2026-10-14 的"重启后失效"）
 *
 * 现象：改完设置（热重载装钩子）时六个开关都能生效，**一重启就全部失效**。原因是这两个进程
 * （`system_server` 与 `com.android.systemui`）都启动得极早，LSPosed 的远程 prefs 镜像那时
 * 还没同步过来 —— 首次 `read()` 读到的是"全 false"的默认值，而 `OnSharedPreferenceChangeListener`
 * 只在**后续有写入**时才回调，于是这份"全 false"的快照会一直用下去。
 *
 * 处理：判定入口调用 [current] 时，如果距上次真实读取超过 [REREAD_INTERVAL_MS]，就重读一次。
 * 调用点是"用户点通知/长按磁贴/发起跳转"这种低频事件，代价可以忽略；好处是自愈 ——
 * 镜像晚到也好、监听器没回调也好，最多 2 秒后就会用上真实配置。
 */
class LightOpenWatcher(private val prefs: SharedPreferences) {

    @Volatile
    private var cached: LightOpenSettings = read()

    @Volatile
    private var lastReadUptime: Long = SystemClock.uptimeMillis()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 名单与六个开关都在这几个键里；其它键（贴边、扇形……）变化不重建。
        if (key == null || key.startsWith(LIGHT_OPEN_KEY_PREFIX)) {
            cached = read()
            lastReadUptime = SystemClock.uptimeMillis()
        }
    }

    init {
        runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }
    }

    fun current(): LightOpenSettings {
        val now = SystemClock.uptimeMillis()
        if (!cached.enabled || now - lastReadUptime >= REREAD_INTERVAL_MS) {
            lastReadUptime = now
            cached = read()
        }
        return cached
    }

    /** 重新读一次（installer 在装载时调用一次，确保热重载后拿到最新值）。 */
    fun refresh(): LightOpenSettings = read().also {
        cached = it
        lastReadUptime = SystemClock.uptimeMillis()
    }

    private fun read(): LightOpenSettings =
        RemotePrefs.lightOpenSettings(RemotePrefs.read(prefs))

    private companion object {
        const val LIGHT_OPEN_KEY_PREFIX = "light_open_"

        /** 最长用 2 秒前的快照；见类注释里的"重启后失效"。 */
        const val REREAD_INTERVAL_MS = 2_000L
    }
}
