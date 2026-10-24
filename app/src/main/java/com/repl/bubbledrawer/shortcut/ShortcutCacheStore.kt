package com.repl.bubbledrawer.shortcut

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 在应用进程持久化 SystemUI 上报的完整快捷方式缓存。
 *
 * **只在内容真的变了的时候才通知监听者**。这不是省事，是断环：管理页监听缓存 → 刷新时又会
 * 请求 SystemUI 重扫（见 [ShortcutRepository.loadAppShortcuts]）→ 上报回来又存一次，如果这里
 * 每次都回调，两边就成了一个永不停止的循环：全量扫描 + 几百个图标 PNG 编码 + 分片 Binder
 * 传输，每轮一秒不到，直到设置页关掉为止。
 */
object ShortcutCacheStore {

    private const val TAG = "ShortcutCacheStore"
    private const val CACHE_FILE = "systemui_shortcuts.json"

    private val lock = Any()

    @Volatile
    private var memoryCache: List<ShortcutItem>? = null

    /** 上一次落盘内容（按 pinKey 顺序比较，避免纯格式差异触发通知）。 */
    private var lastKeys: List<String>? = null

    /** 上一次内容变化的时间（毫秒，System.currentTimeMillis）。 */
    @Volatile
    private var savedAtMillis: Long = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 保存来自 SystemUI 的快捷方式数据。
     *
     * 空载荷默认忽略：一次「扫到 0 条」（开机未解锁、LauncherApps 暂时不可用）不该把上一次
     * 的好数据抹掉。但**完整**的空清单（[allowEmpty]，由 SystemUI 明确标注）必须落下去，
     * 否则卸载掉的应用会永远留在「已选」里。
     */
    fun save(context: Context, json: String, allowEmpty: Boolean = false) {
        if (json.isBlank()) return
        val items = ShortcutBridge.deserializeShortcuts(json)
        if (items.isEmpty() && !allowEmpty) {
            Log.w(TAG, "SHORTCUT_CACHE_IGNORED unparseable/empty jsonLen=${json.length}")
            return
        }
        val keys = items.map { it.pinKey }
        synchronized(lock) {
            if (keys == lastKeys) {
                // 内容一样：只把「新鲜度」往前推，不写文件、不通知 —— 通知一次就是一轮重扫。
                savedAtMillis = System.currentTimeMillis()
                Log.i(TAG, "SHORTCUT_CACHE_UNCHANGED items=${items.size}")
                return
            }
            lastKeys = keys
            memoryCache = if (items.isEmpty()) null else items
            savedAtMillis = System.currentTimeMillis()
            runCatching {
                if (items.isEmpty()) {
                    File(context.filesDir, CACHE_FILE).delete()
                } else {
                    File(context.filesDir, CACHE_FILE).writeText(json)
                }
            }.onFailure {
                Log.w(TAG, "Failed writing shortcuts cache file", it)
            }
        }
        Log.i(TAG, "SHORTCUT_CACHE_SAVED items=${items.size}")
        listeners.forEach {
            runCatching { it.invoke() }
        }
    }

    /**
     * 读取已缓存的快捷方式。
     */
    fun load(context: Context): List<ShortcutItem>? {
        memoryCache?.let { return it }
        return runCatching {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists()) {
                val json = file.readText()
                val items = ShortcutBridge.deserializeShortcuts(json)
                if (items.isNotEmpty()) {
                    synchronized(lock) {
                        memoryCache = items
                        if (lastKeys == null) {
                            lastKeys = items.map { it.pinKey }
                        }
                        if (savedAtMillis == 0L) savedAtMillis = file.lastModified()
                    }
                    items
                } else null
            } else null
        }.getOrNull()
    }

    /** 缓存多久没更新了（毫秒）。从没存过 = [Long.MAX_VALUE]。 */
    fun ageMillis(context: Context): Long {
        if (memoryCache == null) load(context)
        val at = savedAtMillis
        if (at <= 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }
}
