package com.repl.bubbledrawer.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * SystemUI 与 App 进程之间的快捷方式数据桥接。
 *
 * SystemUI 拥有系统级 ACCESS_SHORTCUTS 特权，能通过 LauncherApps.getShortcuts(...)
 * 读出最全的动态/静态/固定快捷方式。读出后通过 ContentResolver.call() 上报给 App 的
 * [ShortcutProvider] 落盘，供管理页与设置页使用。
 */
object ShortcutBridge {

    private const val TAG = "ShortcutBridge"

    const val AUTHORITY = "com.repl.bubbledrawer.shortcuts"

    /** lazy：这个对象里的纯逻辑（[planChunks]/[nextScanId]）要能在 JVM 单测里被碰到。 */
    val CONTENT_URI: Uri by lazy { Uri.parse("content://$AUTHORITY") }

    const val METHOD_REPORT = "report_shortcuts"
    const val EXTRA_JSON = "shortcuts_json"
    const val EXTRA_APPLIED = "applied"
    const val EXTRA_SCAN_ID = "scan_id"
    const val EXTRA_CHUNK_INDEX = "chunk_index"
    const val EXTRA_CHUNK_TOTAL = "chunk_total"

    /**
     * 「这次上报是一份**完整的**清单」——包括清单为空的情况。
     *
     * 没有它就分不清「扫到 0 条」（某个应用被卸载了，缓存该清）和「这次没有上报」（开机未解锁、
     * LauncherApps 暂时不可用，缓存必须留住）。少了这个区分，卸载应用后它的功能会永远留在
     * 「已选」里，而刷新又永远清不掉。
     */
    const val EXTRA_COMPLETE = "complete"

    /** 单次上报的载荷上限（字符数，Base64 是 ASCII 所以≈字节）：再多就说明对方在灌垃圾。 */
    const val MAX_PAYLOAD_CHARS = 4_000_000

    /** App 端触发 SystemUI 重新扫描快捷方式的广播 */
    const val ACTION_REQUEST_REFRESH = "com.repl.bubbledrawer.action.REQUEST_SHORTCUTS"

    /** 图标边长上限（px）：160 在面板/管理页的 50dp 图标上已经看不出差别，却省一半内存。 */
    const val ICON_MAX_PX = 160

    /**
     * 单次 Binder 事务的**字节**预算。
     *
     * 这里曾经是「每 25 条一批」，理由是「彻底消除 TransactionTooLargeException 隐患」——
     * 但 25 个 160px 的 PNG 图标 Base64 之后可以轻松超过 Binder 的 1MB 总缓冲，分片**照样爆**，
     * 而且是抛异常后被 runCatching 吞掉：App 永远收不到列表，功能页看起来就是「空的」。
     * 按字节切才是真的上限（每个事务 ~200KB，留足余量给 Bundle 与 Binder 自身的开销）。
     */
    const val CHUNK_BYTE_BUDGET = 200_000

    /**
     * 把一个大小为 [sizes] 的列表按 [budget] 切成若干段（返回每段的 index 区间）。
     *
     * 纯整数逻辑，和 JSON/图标尺寸无关 —— 分片这东西出错的代价是「静默丢数据」，
     * 所以它必须是不用设备就能测的那部分。
     */
    fun planChunks(sizes: List<Int>, budget: Int = CHUNK_BYTE_BUDGET): List<IntRange> {
        if (sizes.isEmpty()) return emptyList()
        val safeBudget = budget.coerceAtLeast(1)
        val out = mutableListOf<IntRange>()
        var start = 0
        var bytes = 0
        for (i in sizes.indices) {
            val size = sizes[i].coerceAtLeast(0)
            // 一段至少要装一条：单条超过预算时让它独占一段（Binder 仍可能拒绝，
            // 但那是这条数据本身的问题，不该把后面的条目一起拖死）。
            if (i > start && bytes + size > safeBudget) {
                out += start until i
                start = i
                bytes = 0
            }
            bytes += size
        }
        out += start until sizes.size
        return out
    }

    /**
     * 将快捷方式列表序列化为 JSON 字符串。
     * 图标压缩为小尺寸 PNG Base64，兼顾清晰度与 Binder 体积。
     * 注意：不序列化 appIconBitmap，由接收端根据 packageName 借助 PackageManager 本地解析，杜绝 Binder 超限。
     */
    fun serializeShortcuts(items: List<ShortcutItem>): String {
        val array = JSONArray()
        for (item in items) array.put(serializeShortcut(item))
        return array.toString()
    }

    /** 单条序列化 —— 分片要按“这一条多大”来算，所以它单独存在。 */
    fun serializeShortcut(item: ShortcutItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("pkg", item.packageName)
        put("label", item.label)
        put("subLabel", item.subLabel ?: "")
        put("type", item.type.name)
        put("intentUri", item.intentUri ?: "")
        put("isEnabled", item.isEnabled)

        val bmp = item.iconBitmap
        if (bmp != null && !bmp.isRecycled) {
            val stream = ByteArrayOutputStream()
            val scaled = if (bmp.width > ICON_MAX_PX || bmp.height > ICON_MAX_PX) {
                val scale = ICON_MAX_PX.toFloat() / maxOf(bmp.width, bmp.height)
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, w, h, true)
            } else {
                bmp
            }
            scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            put("iconBmp", base64)
        }
    }

    /**
     * 反序列化 JSON 字符串为 [ShortcutItem] 列表。
     */
    fun deserializeShortcuts(json: String): List<ShortcutItem> {
        val list = mutableListOf<ShortcutItem>()
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val pkg = obj.optString("pkg")
                val label = obj.optString("label")
                val subLabel = obj.optString("subLabel").ifBlank { null }
                val typeName = obj.optString("type", ShortcutType.APP_SHORTCUT.name)
                // 旧版缓存里可能还有已删除的磁贴条目：直接跳过，不能把它们当快捷方式画出来。
                if (typeName == "QS_TILE") continue
                val type = runCatching { ShortcutType.valueOf(typeName) }.getOrDefault(ShortcutType.APP_SHORTCUT)
                val intentUri = obj.optString("intentUri").ifBlank { null }
                val isEnabled = obj.optBoolean("isEnabled", true)

                val iconBase64 = obj.optString("iconBmp").ifBlank { null }
                val iconBmp = iconBase64?.let {
                    val bytes = Base64.decode(it, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                list.add(
                    ShortcutItem(
                        id = id,
                        packageName = pkg,
                        label = label,
                        subLabel = subLabel,
                        type = type,
                        intentUri = intentUri,
                        iconBitmap = iconBmp,
                        appIconBitmap = null,
                        isEnabled = isEnabled,
                    )
                )
            }
        }.onFailure {
            Log.w(TAG, "Failed deserializing shortcuts JSON", it)
        }
        return list
    }

    /** 一次上报的结果 —— 调用方（SystemUI）要能说清「送到了没有」。 */
    enum class ReportResult { SENT, EMPTY, UNCHANGED, FAILED, INCOMPLETE }

    /**
     * SystemUI 进程向 App 端上报读取到的全量 shortcuts。
     *
     * 按**字节预算**分片（见 [CHUNK_BYTE_BUDGET]），每片带同一个 `scan_id`，接收端按它隔离
     * 两次扫描的分片；**每片都要回执**（`applied=true`），任何一片没落地就整体算失败 ——
     * 否则 App 那边会留下一份「半张列表」，比没有还糟。
     *
     * [items] 为空也是一次**有效**上报（[EXTRA_COMPLETE]）：接收端据此清掉缓存里那些已经不存在的
     * 应用的快捷方式。调用方负责判断「空」到底是扫描结果还是枚举失败（见 ShortcutRepository）。
     */
    fun reportToApp(context: Context, items: List<ShortcutItem>, scanId: Long = nextScanId()): Boolean {
        if (items.isEmpty()) {
            val extras = Bundle().apply {
                putString(EXTRA_JSON, "[]")
                putBoolean(EXTRA_COMPLETE, true)
                putLong(EXTRA_SCAN_ID, scanId)
            }
            val ack = runCatching {
                context.contentResolver.call(CONTENT_URI, METHOD_REPORT, null, extras)
            }.getOrNull()
            Log.i(TAG, "SHORTCUT_REPORT_EMPTY ack=${ack?.getBoolean(EXTRA_APPLIED) == true}")
            return ack?.getBoolean(EXTRA_APPLIED) == true
        }
        val objects = items.map { serializeShortcut(it) }
        val sizes = objects.map { it.toString().length } // Base64 是纯 ASCII：字符数 ≈ 字节数
        val chunks = planChunks(sizes)
        var delivered = 0
        for ((index, range) in chunks.withIndex()) {
            val array = JSONArray()
            for (i in range) array.put(objects[i])
            val extras = Bundle().apply {
                putString(EXTRA_JSON, array.toString())
                putInt(EXTRA_CHUNK_INDEX, index)
                putInt(EXTRA_CHUNK_TOTAL, chunks.size)
                putLong(EXTRA_SCAN_ID, scanId)
            }
            val ack = runCatching {
                context.contentResolver.call(CONTENT_URI, METHOD_REPORT, null, extras)
            }.onFailure {
                Log.w(TAG, "Failed reporting shortcuts chunk ${index + 1}/${chunks.size}", it)
            }.getOrNull()
            if (ack?.getBoolean(EXTRA_APPLIED) == true) delivered++
            else Log.w(TAG, "SHORTCUT_REPORT_UNAPPROVED chunk=${index + 1}/${chunks.size} bytes=${range.sumOf { sizes[it] }}")
        }
        Log.i(
            TAG,
            "SHORTCUT_REPORT total=${items.size} chunks=${chunks.size} delivered=$delivered " +
                "maxChunkBytes=${chunks.maxOfOrNull { r -> r.sumOf { sizes[it] } } ?: 0}",
        )
        return delivered == chunks.size
    }

    private val scanIds = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 分片会话的标识。
     *
     * 曾经直接用墙上时钟：同一毫秒内的两次扫描会共用一个会话，接收端把 A 的第 1 片和 B 的第 2 片
     * 拼成一份并不存在的清单。这里用「单调时钟 + 10 位序号」（[now] 可注入，纯逻辑才测得了）。
     */
    fun nextScanId(now: Long = android.os.SystemClock.uptimeMillis()): Long =
        (now shl 10) or (scanIds.incrementAndGet() and 0x3FF)

    /**
     * App 端通知 SystemUI 重新扫描并上报。
     */
    fun requestRefresh(context: Context) {
        val intent = Intent(ACTION_REQUEST_REFRESH).apply {
            setPackage("com.android.systemui")
        }
        runCatching { com.repl.bubbledrawer.xposed.PinSyncReceiver.sendBroadcastWithIdentity(context, intent) }
    }

    fun isTrustedUid(uid: Int, packages: List<String>?): Boolean {
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return true
        return packages?.contains("com.android.systemui") == true
    }
}
