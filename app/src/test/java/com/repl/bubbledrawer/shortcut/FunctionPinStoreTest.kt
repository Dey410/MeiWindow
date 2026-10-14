package com.repl.bubbledrawer.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已选功能」的持久化格式。
 *
 * 这一层出错的表现都是**静默丢数据**：一条键被分隔符撕成两条（点选完看起来没选上）、
 * 一次复制粘贴式的重复写入把自己顶掉、或者一个超长载荷灌进广播。所以这些边界要钉住。
 */
class FunctionPinStoreTest {

    private val wechatScan = "com.tencent.mm#wechat_scan"
    private val alipayPay = "com.eg.android.AlipayGphone#alipay_pay"

    @Test
    fun roundTripKeepsOrder() {
        val pins = listOf(wechatScan, alipayPay)
        assertEquals(pins, FunctionPinStore.decode(FunctionPinStore.encode(pins)))
    }

    @Test
    fun emptyListIsAValidValue() {
        assertEquals("", FunctionPinStore.encode(emptyList()))
        assertEquals(emptyList<String>(), FunctionPinStore.decode(""))
        assertEquals(emptyList<String>(), FunctionPinStore.decode(null))
        // 「一个都不选」必须能落盘并且读得回来，否则用户取消最后一个收藏时会回魂。
        assertTrue(FunctionPinStore.isPlausible(""))
    }

    @Test
    fun duplicatesAndBlanksAreDropped() {
        val raw = FunctionPinStore.encode(listOf(wechatScan, "  ", wechatScan, alipayPay))
        assertEquals("$wechatScan;$alipayPay", raw)

        assertEquals(listOf(wechatScan, alipayPay), FunctionPinStore.decode(" $wechatScan ;; $alipayPay "))
    }

    @Test
    fun oversizeEntriesAreDroppedInsteadOfTruncated() {
        val tooLong = "com.x#" + "a".repeat(600)
        val encoded = FunctionPinStore.encode(listOf(tooLong, wechatScan))
        assertEquals(wechatScan, encoded)
        assertFalse(FunctionPinStore.isPlausible(encoded + ";" + tooLong))
    }

    @Test
    fun absurdPayloadsAreRejected() {
        assertFalse(FunctionPinStore.isPlausible("a".repeat(40_000)))
        assertFalse(FunctionPinStore.isPlausible((0 until 600).joinToString(";") { "com.p$it#id" }))
        assertTrue(FunctionPinStore.isPlausible(wechatScan))
    }

    /**
     * encode 造得出的，接收端必须收得下。
     *
     * 曾经这里是不对称的：encode 允许 512 条 × 512 字符（≈262KB），而 isPlausible 在 32KB 处
     * 整条拒收 —— 面板显示「已选」，落盘时被对面丢干净。现在 encode 自己按同一套上限截断。
     */
    @Test
    fun whateverEncodeProducesPassesTheReceiverCheck() {
        val huge = (0 until 400).map { "com.example.app$it#" + "x".repeat(120) }
        val encoded = FunctionPinStore.encode(huge)
        assertTrue("encode 的输出必须能通过 isPlausible", FunctionPinStore.isPlausible(encoded))
        // 截断是从尾部丢条目，前面的顺序保持不变。
        assertEquals(encoded.split(';').first(), huge.first())
    }

    @Test
    fun shortcutIdCannotBreakTheSeparator() {
        // id 是应用自己声明的字符串：里面出现 ';' 会把一条键撕成两条，于是"点选完没选上"。
        // ShortcutItem.pinKey 生成时就替换掉了，两端用的是同一个 getter，所以对应关系不变。
        val nasty = ShortcutItem(
            id = "weird;id",
            packageName = "com.example",
            label = "坏的",
            type = ShortcutType.APP_SHORTCUT,
        )
        val key = nasty.pinKey
        assertEquals("com.example#weird_id", key)
        assertEquals(listOf(key), FunctionPinStore.decode(FunctionPinStore.encode(listOf(key))))
    }

    @Test
    fun desktopShortcutWithoutPackageStillHasAStableKey() {
        val item = ShortcutItem(
            id = "42",
            packageName = "",
            label = "快捷方式",
            type = ShortcutType.DESKTOP_PINNED,
        )
        assertEquals("#42", item.pinKey)
    }
}
