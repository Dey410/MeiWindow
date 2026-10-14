package com.repl.bubbledrawer.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扇形（转盘）里那几枚功能 tile 的取值规则。
 *
 * 用户 2026-10-14 报「功能不显示在**扇形面板**（重按翻页的那个转盘）里」—— 那是与应用图标同排的
 * 转盘 tile，不是「更多」浮层。它的构建跑在手势中间的主线程上，所以规则必须一眼看得完：
 * 顺序跟用户点选；有已枚举到的就用它（带图标与 ShortcutInfo），否则用 App 发布的详情，再否则占位。
 */
class FunctionFanItemsTest {

    private fun detail(key: String, label: String, intent: String? = null) = ShortcutItem(
        id = key.substringAfter('#'),
        packageName = key.substringBefore('#'),
        label = label,
        subLabel = "宿主",
        type = ShortcutType.APP_SHORTCUT,
        intentUri = intent,
    )

    @Test
    fun orderFollowsTheUserSelection() {
        val keys = listOf("com.b#two", "com.a#one")
        val items = FunctionFanItems.resolve(keys, details = emptyList(), cached = { null })
        assertEquals(keys, items.map { it.pinKey })
    }

    @Test
    fun anEnumeratedEntryWinsBecauseItCarriesTheIconAndShortcutInfo() {
        val key = "com.tencent.mm#wechat_scan"
        val live = detail(key, "扫一扫（枚举）")
        val fromApp = detail(key, "扫一扫（详情）")

        val items = FunctionFanItems.resolve(listOf(key), listOf(fromApp)) { if (it == key) live else null }
        assertEquals(1, items.size)
        assertEquals("扫一扫（枚举）", items[0].label)
    }

    @Test
    fun theHostProvidedDetailsAreTheSecondChoice() {
        val key = "org.telegram.messenger#group42"
        val fromApp = detail(key, "群聊", intent = "intent:#Intent;action=VIEW;end")
        val items = FunctionFanItems.resolve(listOf(key), listOf(fromApp), cached = { null })
        assertEquals("群聊", items[0].label)
        assertEquals("intent:#Intent;action=VIEW;end", items[0].intentUri)
    }

    @Test
    fun anUnresolvableKeyStillGetsATileInsteadOfVanishing() {
        val items = FunctionFanItems.resolve(listOf("com.gone#thing"), emptyList()) { null }
        assertEquals(1, items.size)
        assertEquals("com.gone#thing", items[0].pinKey)
        assertEquals("thing", items[0].label)
        assertEquals("com.gone", items[0].packageName)
        assertNull("占位项没有启动方式，但至少看得见", items[0].intentUri)
    }

    @Test
    fun nothingPinnedMeansNoTilesAndNoLookups() {
        var lookups = 0
        val items = FunctionFanItems.resolve(emptyList(), emptyList()) { lookups++; null }
        assertEquals(emptyList<ShortcutItem>(), items)
        assertEquals("没选功能时不许去查枚举结果（这是手势中间的主线程）", 0, lookups)
    }

    @Test
    fun duplicatesInTheSelectionDoNotDuplicateTiles() {
        // 键是唯一的（存储层去重过），但万一重复了也不该出现两枚一样的 tile —— 扇形位置很贵。
        val key = "com.a#one"
        val items = FunctionFanItems.resolve(listOf(key, key), listOf(detail(key, "A")), cached = { null })
        assertEquals(2, items.size)
        assertEquals(listOf(key, key), items.map { it.pinKey })
    }
}
