package com.repl.bubbledrawer.pin

import com.repl.bubbledrawer.pinyin.BubbleApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分区折叠（用户 2026-10-14）：「超过 20 个的默认不显示，在 20 个下面加一个展开按钮点击才能显示」。
 *
 * 规则落在 [Sections.visible] 上（纯函数），推荐区与收藏区各算各的 —— 一页被 A–Z 字母分成的那些段
 * **永远不折叠**：那是全量列表，折起来等于应用不见了。
 */
class SectionsVisibleTest {

    private fun app(name: String) = BubbleApp("pkg.$name", name, 0, usageCount = 1)

    private fun pins(count: Int): List<Cell> =
        listOf<Cell>(Cell.PinHead) + (1..count).map { Cell.Pin(app("pin$it")) }

    private fun recommends(count: Int): List<Cell> =
        listOf<Cell>(Cell.Label(Sections.RECOMMEND_SECTION)) +
            (1..count).map { Cell.App(app("rec$it"), Sections.RECOMMEND_SECTION) }

    private fun expandCells(cells: List<Cell>) = cells.filterIsInstance<Cell.Expand>()

    @Test
    fun underTheCapNothingIsHidden() {
        val cells = pins(20) + recommends(20) + listOf(Cell.Label("A"), Cell.App(app("a1"), "A"))
        val visible = Sections.visible(cells, pinnedExpanded = false, recommendExpanded = false)
        assertEquals(cells, visible)
        assertTrue(expandCells(visible).isEmpty())
    }

    @Test
    fun oneOverTheCapShowsTheButton() {
        val cells = pins(21) + recommends(21)
        val visible = Sections.visible(cells, pinnedExpanded = false, recommendExpanded = false)
        // 收藏 20 个 + 一枚展开；推荐 20 个 + 一枚展开
        assertEquals(20, visible.filterIsInstance<Cell.Pin>().size)
        assertEquals(20, visible.filterIsInstance<Cell.App>().size)
        val expands = expandCells(visible)
        assertEquals(2, expands.size)
        assertEquals(Sections.PIN_SECTION, expands[0].section)
        assertEquals(1, expands[0].remaining)
        assertFalse(expands[0].expanded)
        assertEquals(Sections.RECOMMEND_SECTION, expands[1].section)
        assertEquals(1, expands[1].remaining)
    }

    @Test
    fun expandButtonSitsRightAfterTheLastVisibleOne() {
        val cells = recommends(25)
        val visible = Sections.visible(cells, pinnedExpanded = false, recommendExpanded = false)
        // [0] = 「推荐」小标题，[1..20] = 前 20 个应用，[21] = 展开按钮（用户要的「在 20 个下面」）
        assertTrue(visible[0] is Cell.Label)
        assertEquals(20, visible.subList(1, 21).count { it is Cell.App })
        val expand = visible[21]
        assertTrue(expand is Cell.Expand)
        assertEquals(5, (expand as Cell.Expand).remaining)
        assertEquals(22, visible.size)
        // 展开按钮之前不该漏出第 21 个应用
        assertFalse(visible.any { it is Cell.App && it.app.packageName == "pkg.rec21" })
    }

    @Test
    fun expandedShowsEverythingAndOffersCollapse() {
        val cells = recommends(25)
        val visible = Sections.visible(cells, pinnedExpanded = false, recommendExpanded = true)
        assertEquals(25, visible.filterIsInstance<Cell.App>().size)
        val expand = expandCells(visible).single()
        assertTrue(expand.expanded)
        assertEquals(0, expand.remaining)
        // 全部铺开时按钮在最后一项之后
        assertTrue(visible.last() is Cell.Expand)
    }

    @Test
    fun pinsExpandKeepsTheHeadRowFirst() {
        val cells = pins(30)
        val visible = Sections.visible(cells, pinnedExpanded = false, recommendExpanded = false)
        assertEquals(Cell.PinHead, visible.first())
        assertEquals(20, visible.filterIsInstance<Cell.Pin>().size)
        assertEquals(Cell.PinHead, visible[0])
        assertTrue(visible[1] is Cell.Pin)
        val expand = expandCells(visible).single()
        assertEquals(10, expand.remaining)
        assertEquals("pkg.pin1", (visible[1] as Cell.Pin).app.packageName)
        assertEquals("pkg.pin20", (visible[20] as Cell.Pin).app.packageName)
    }

    @Test
    fun expandingPinsDoesNotExpandRecommends() {
        val cells = pins(30) + recommends(30)
        val visible = Sections.visible(cells, pinnedExpanded = true, recommendExpanded = false)
        assertEquals(30, visible.filterIsInstance<Cell.Pin>().size)
        assertEquals(20, visible.filterIsInstance<Cell.App>().size)
    }

    @Test
    fun letterSectionsAreNeverCapped() {
        val letter = (1..30).map { Cell.App(app("a$it"), "A") }
        val cells = listOf(Cell.Label("A")) + letter
        val visible = Sections.visible(cells, pinnedExpanded = false, recommendExpanded = false)
        assertEquals(cells, visible)
        assertTrue(expandCells(visible).isEmpty())
    }

    @Test
    fun theCapIsTwenty() {
        assertEquals(20, Sections.VISIBLE_MAX)
    }

    @Test
    fun emptyCellsStayEmpty() {
        assertTrue(Sections.visible(emptyList(), false, false).isEmpty())
    }
}
