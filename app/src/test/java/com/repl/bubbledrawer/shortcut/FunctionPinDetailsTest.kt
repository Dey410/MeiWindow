package com.repl.bubbledrawer.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已选功能」详情的编解码。
 *
 * 这份详情存在的唯一理由是跨进程：面板（SystemUI）自己那份枚举结果里未必有用户选的那条，光有键
 * 就画不出来（`mapNotNull` 会把它静默丢掉 →「设置页里选好了，扇形面板里什么都没有」）。
 * 所以字段必须原样往返，尤其是启动 Intent —— 它是面板唯一能用的启动手段。
 */
class FunctionPinDetailsTest {

    private fun item(
        key: String,
        label: String,
        sub: String? = null,
        intent: String? = null,
    ): ShortcutItem {
        val pkg = key.substringBefore('#')
        return ShortcutItem(
            id = key.substringAfter('#'),
            packageName = pkg,
            label = label,
            subLabel = sub,
            type = ShortcutType.APP_SHORTCUT,
            intentUri = intent,
        )
    }

    @Test
    fun roundTripKeepsEveryField() {
        val items = listOf(
            item("com.tencent.mm#wechat_scan", "扫一扫", "微信", "intent:#Intent;action=VIEW;end"),
            item("com.eg.android.AlipayGphone#alipay_pay", "付款码", "支付宝"),
        )
        val decoded = FunctionPinDetails.decode(FunctionPinDetails.encode(items))
        assertEquals(2, decoded.size)
        assertEquals("扫一扫", decoded[0].label)
        assertEquals("微信", decoded[0].subLabel)
        assertEquals("intent:#Intent;action=VIEW;end", decoded[0].intentUri)
        assertEquals(items[0].pinKey, decoded[0].pinKey)
        assertNull("没有 Intent 就是没有，不能变成空串", decoded[1].intentUri)
    }

    @Test
    fun tileTypeAndTintSurviveTheOldFourFieldFormat() {
        // 磁贴不能退化回 APP_SHORTCUT：扇形会拿它去 launchShortcut，而磁贴没有 Intent。
        val tile = ShortcutItem(
            id = "com.example/.TileService",
            packageName = "com.example",
            label = "示例磁贴",
            subLabel = "示例",
            type = ShortcutType.QS_TILE,
            tintColor = 0xFF3366CC.toInt(),
        )
        val decoded = FunctionPinDetails.decode(FunctionPinDetails.encode(listOf(tile)))[0]
        assertEquals(ShortcutType.QS_TILE, decoded.type)
        assertEquals(0xFF3366CC.toInt(), decoded.tintColor)

        // 上一代只有四段：key、label、subLabel、intentUri —— 仍要能读，并按快捷方式处理。
        val legacy = FunctionPinDetails.decode("com.x#foo\t旧功能\t宿主\tintent:#Intent;end")
        assertEquals(1, legacy.size)
        assertEquals(ShortcutType.APP_SHORTCUT, legacy[0].type)
        assertNull(legacy[0].tintColor)
    }

    @Test
    fun separatorsInsideValuesSurvive() {
        // 标签里带制表符/换行/反斜杠：不转义就会撕成两条记录、字段错位（功能名是应用给的，什么都可能有）。
        val nasty = item("com.x#1", "带\t制表\n换行\\反斜杠", "宿\t主", "intent:#Intent;S.x=1;end")
        val decoded = FunctionPinDetails.decode(FunctionPinDetails.encode(listOf(nasty)))
        assertEquals(1, decoded.size)
        assertEquals("带\t制表\n换行\\反斜杠", decoded[0].label)
        assertEquals("宿\t主", decoded[0].subLabel)
        assertEquals("com.x#1", decoded[0].pinKey)
    }

    @Test
    fun garbageAndEmptyInputDecodeToNothing() {
        assertEquals(emptyList<ShortcutItem>(), FunctionPinDetails.decode(null))
        assertEquals(emptyList<ShortcutItem>(), FunctionPinDetails.decode(""))
        assertEquals(emptyList<ShortcutItem>(), FunctionPinDetails.decode("   "))
        assertEquals(emptyList<ShortcutItem>(), FunctionPinDetails.decode("\n\n"))
        // 超长载荷整体拒收（共享组里可能被灌）。
        assertEquals(emptyList<ShortcutItem>(), FunctionPinDetails.decode("a".repeat(FunctionPinDetails.MAX_CHARS + 1)))
    }

    @Test
    fun placeholderIsUsableWhenTheKeyCannotBeResolved() {
        val ph = FunctionPinDetails.placeholder("com.tencent.mm#wechat_scan", "微信")
        assertEquals("com.tencent.mm", ph.packageName)
        assertEquals("wechat_scan", ph.id)
        assertEquals("com.tencent.mm#wechat_scan", ph.pinKey)
        assertEquals("微信", ph.label)

        // 拿不到应用名时用 id 当标题，绝不能是空标题（空标题在网格里看起来就是"没有"）。
        val bare = FunctionPinDetails.placeholder("com.x#do_thing")
        assertEquals("do_thing", bare.label)
        assertEquals("com.x", bare.packageName)
    }

    @Test
    fun encodeStopsAtThePayloadCap() {
        val many = (0 until 2000).map { item("com.example.app$it#id$it", "功能$it", "应用$it") }
        val encoded = FunctionPinDetails.encode(many)
        assertTrue("载荷不能无上限", encoded.length <= FunctionPinDetails.MAX_CHARS)
        val decoded = FunctionPinDetails.decode(encoded)
        assertTrue(decoded.isNotEmpty())
        assertTrue("截断必须从尾部丢，顺序不变", decoded.size < many.size)
        assertEquals(many[0].pinKey, decoded[0].pinKey)
    }
}
