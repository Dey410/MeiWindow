package com.repl.bubbledrawer.shortcut

import android.content.Context
import android.content.SharedPreferences
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PrefsKeyBackend
import com.repl.bubbledrawer.xposed.SettingsStore

/**
 * 「已选功能」（快捷方式收藏）的存储。
 *
 * 两件必须一起成立的事：
 *  1. **面板与设置页看到的是同一份列表**。面板跑在 SystemUI（hook 进程），它的远程组是
 *     *只读镜像*，写不进去；所以 hook 侧的值由 [com.repl.bubbledrawer.xposed.FanHost] 持有
 *     （会话覆盖 + SystemUI 本地副本 + 远程镜像和解），写操作经广播/服务交给 App 进程落盘
 *     —— 与「收藏应用」([com.repl.bubbledrawer.data.PinStore]) 完全同一套机制。
 *  2. **值与戳分开存**：和收藏应用一样，LSPosed 的远程组是逐 key 镜像的，读的一方必须用
 *     [com.repl.bubbledrawer.data.PinsSync] 和解，否则一次丢手就会让两份列表永久分叉。
 *
 * 这里只负责**编码**（可测的纯逻辑）与 App 侧的读写入口；hook 侧的读写在 FanHost。
 */
object FunctionPinStore {

    const val KEY_PINNED_FUNCTIONS = "pinned_functions"
    const val PREFS_KEY_REV = "pinned_functions_rev"

    /** 一条 `pkg#id` 的上限；正常远小于此（包名 ≤ 200 + id ≤ 200）。 */
    private const val MAX_KEY_LEN = 512

    /** 整个列表的上限：收藏应用不限条数（扇形只取前 6），功能页同理不设条数上限，只挡住异常值。 */
    private const val MAX_RAW_LEN = 32_000

    private const val MAX_ENTRIES = 512

    /**
     * 把收藏键拼成持久化字符串；空列表 = 「一个都不选」，是合法值。
     *
     * 这里遵守与 [isPlausible] **同一套**上限：encode 造得出、接收端却拒绝的载荷是一个陷阱
     * （面板显示"已选"，落盘时整条被丢掉），所以长度超限时从尾部丢条目，直到放得下。
     */
    fun encode(list: List<String>): String {
        val keys = list
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_KEY_LEN }
            .distinct()
            .take(MAX_ENTRIES)
        val out = StringBuilder()
        for (key in keys) {
            val extra = if (out.isEmpty()) key.length else key.length + 1
            if (out.length + extra > MAX_RAW_LEN) break
            if (out.isNotEmpty()) out.append(';')
            out.append(key)
        }
        return out.toString()
    }

    /** 解析持久化字符串；容忍空值、多余空白与重复项。 */
    fun decode(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return text.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_KEY_LEN }
            .distinct()
            .take(MAX_ENTRIES)
    }

    /**
     * 广播/服务收到的载荷是否可以落盘。收藏键是 `pkg#id`（[ShortcutItem.pinKey]，id 里的 `;`
     * 已在生成时替换掉），所以这里只查长度与分隔符，不做包名校验 —— 桌面快捷方式的 id 是
     * 数据库号，功能名称也可能来自第三方应用。
     */
    fun isPlausible(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return true
        if (text.length > MAX_RAW_LEN) return false
        val entries = text.split(';')
        if (entries.size > MAX_ENTRIES) return false
        return entries.all { it.isNotBlank() && it.length <= MAX_KEY_LEN }
    }

    /** 任意 prefs 上的后端（App 侧 = 本地镜像 + 远程组；hook 侧 = SystemUI 本地副本 / 远程镜像）。 */
    fun backend(prefs: SharedPreferences): PinBackend =
        PrefsKeyBackend(prefs, KEY_PINNED_FUNCTIONS, PREFS_KEY_REV)

    // ---------------- App 进程入口 ----------------

    /** App 侧读：与收藏应用同一条和解路径（远端更新就采纳，本地更新就推回去）。 */
    fun getPins(context: Context): List<String> =
        decode(SettingsStore.functionBackend(context).read())

    /** App 侧写：本地镜像 + 远程组（+ 通知 SystemUI 的面板），与收藏应用同一套。 */
    fun setPins(context: Context, pins: List<String>) {
        SettingsStore.functionBackend(context).write(encode(pins))
    }
}
