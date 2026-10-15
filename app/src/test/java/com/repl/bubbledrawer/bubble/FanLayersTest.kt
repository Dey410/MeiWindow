package com.repl.bubbledrawer.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 扇形分层的数量规则与双环几何。
 *
 * 数量规则是用户点名的（2026-10-14）：「内层有5个应用，则外层应有7个应用，计算方式为内层5个应用+
 * 内层有的更多按钮再+1。外层无需更多应用入口」—— 所以这里的期望值不是抄代码，是抄需求。
 */
class FanLayersTest {

    // 设备实测密度 3.25（1220 × 2656）：44dp 图标 = 143px，8dp 圈间隙 = 26px，260dp 半径 = 845px。
    private val icon = 143f
    private val gap = 26f
    private val base = 845f
    private val screen = 1220f

    private fun maxRadius(iconPx: Float = icon) = screen - iconPx / 2f

    @Test
    fun singleLayerKeepsTodaysCapacity() {
        // 单层 = 现状：一圈 iconCount 个应用 + 一枚「更多」。
        assertEquals(5, FanLayout.appsPerPage(5, FanLayout.LAYERS_SINGLE))
        assertEquals(6, FanLayout.tilesPerPage(5, FanLayout.LAYERS_SINGLE))
        assertEquals(6, FanLayout.outerTiles(5, FanLayout.LAYERS_SINGLE))
        assertEquals(0, FanLayout.innerTiles(5, FanLayout.LAYERS_SINGLE))
        assertEquals(0, FanLayout.innerTileCount(5, 5, FanLayout.LAYERS_SINGLE))
    }

    @Test
    fun doubleLayerOuterIsInnerPlusTwo() {
        // 内层 5 个应用 → 外层 7 个应用（用户给的那个例子）。
        assertEquals(7, FanLayout.outerApps(5, FanLayout.LAYERS_DOUBLE))
        assertEquals(5, FanLayout.innerApps(5, FanLayout.LAYERS_DOUBLE))
        // 内层 = 5 个应用 + 「更多」；外层没有「更多」。
        assertEquals(6, FanLayout.innerTiles(5, FanLayout.LAYERS_DOUBLE))
        assertEquals(7, FanLayout.outerTiles(5, FanLayout.LAYERS_DOUBLE))
        assertEquals(12, FanLayout.appsPerPage(5, FanLayout.LAYERS_DOUBLE))
        assertEquals(13, FanLayout.tilesPerPage(5, FanLayout.LAYERS_DOUBLE))
        // 6 个应用的那一档同理
        assertEquals(8, FanLayout.outerApps(6, FanLayout.LAYERS_DOUBLE))
        assertEquals(14, FanLayout.appsPerPage(6, FanLayout.LAYERS_DOUBLE))
        assertEquals(15, FanLayout.tilesPerPage(6, FanLayout.LAYERS_DOUBLE))
    }

    @Test
    fun firstAppsGoToTheInnerRing() {
        // 用户 2026-10-15：内圈显示 1–5 个应用，外圈显示第 6 个起的。
        assertEquals(5, FanLayout.innerAppsOnPage(appsOnPage = 12, iconCount = 5, layers = FanLayout.LAYERS_DOUBLE))
        // 不满内圈就全在内圈（外圈空着）
        assertEquals(3, FanLayout.innerAppsOnPage(appsOnPage = 3, iconCount = 5, layers = FanLayout.LAYERS_DOUBLE))
        // 空页只剩一枚「更多」，它也在内圈
        assertEquals(0, FanLayout.innerAppsOnPage(appsOnPage = 0, iconCount = 5, layers = FanLayout.LAYERS_DOUBLE))
        assertEquals(1, FanLayout.innerTileCount(appsOnPage = 0, iconCount = 5, layers = FanLayout.LAYERS_DOUBLE))
    }

    @Test
    fun ringOrderPutsTheMoreTileInside() {
        val apps = (1..12).map { "app$it" }
        val page = apps + "More"
        val ordered = FanLayout.ringOrder(page, iconCount = 5, layers = FanLayout.LAYERS_DOUBLE) { it == "More" }
        // 内圈 = app1..app5 + More；外圈 = app6..app12
        assertEquals(
            listOf("app1", "app2", "app3", "app4", "app5", "More", "app6", "app7", "app8", "app9", "app10", "app11", "app12"),
            ordered,
        )
        assertEquals(6, FanLayout.innerTileCount(ordered.count { it != "More" }, 5, FanLayout.LAYERS_DOUBLE))
    }

    @Test
    fun ringOrderLeavesShortPagesAlone() {
        // 装得进内圈：顺序不动（内圈就是整页，「更多」仍在末尾）
        val short = listOf("app1", "app2", "app3", "More")
        assertEquals(short, FanLayout.ringOrder(short, 5, FanLayout.LAYERS_DOUBLE) { it == "More" })
        // 单层：永远不动
        val full = (1..12).map { "app$it" } + "More"
        assertEquals(full, FanLayout.ringOrder(full, 5, FanLayout.LAYERS_SINGLE) { it == "More" })
    }

    @Test
    fun sixAppsOnTheLastPageSplitFiveAndOne() {
        // 用户报的那个 bug：最后一页 6 个应用 —— 旧规则会按页退化成单圈（半径也跟着变），
        // 看起来就像"跑到内圈"。现在恒按满页几何，6 个应用 = 内圈 5 个 + 外圈 1 个。
        val page = (1..6).map { "app$it" } + "More"
        val ordered = FanLayout.ringOrder(page, 5, FanLayout.LAYERS_DOUBLE) { it == "More" }
        assertEquals(listOf("app1", "app2", "app3", "app4", "app5", "More", "app6"), ordered)
        assertEquals(6, FanLayout.innerTileCount(6, 5, FanLayout.LAYERS_DOUBLE))
        assertEquals(13, FanLayout.tilesPerPage(5, FanLayout.LAYERS_DOUBLE))
    }

    @Test
    fun singleRingRadiusIsExactlyTheSetting() {
        val rings = FanLayout.rings(
            outerTiles = 6, innerTiles = 0, baseRadiusPx = base,
            iconPx = icon, ringGapPx = gap, maxRadiusPx = maxRadius(), safeDegrees = 0,
        )
        assertEquals(1, rings.size)
        // 用户调过的半径不许被我们"优化"掉。
        assertEquals(base, rings[0].radiusPx, 0.001f)
        assertEquals(90f / 7f, rings[0].stepDeg, 0.001f)
    }

    @Test
    fun singleRingMatchesThePortedAngleFormula() {
        for (safe in listOf(0, 3)) {
            for (tiles in listOf(5, 6, 7, 8)) {
                val ring = FanLayout.rings(
                    outerTiles = tiles, innerTiles = 0, baseRadiusPx = base,
                    iconPx = icon, ringGapPx = gap, maxRadiusPx = maxRadius(), safeDegrees = safe,
                ).first()
                val step = if (safe == 0) 90.0f / (tiles + 1) else (90f - safe * 2f) / tiles
                for (i in 0 until tiles) {
                    val ported = if (safe == 0) {
                        (90.0f / (tiles + 1)) * (i + 1f)
                    } else {
                        i * step + step * 0.5f + safe
                    }
                    assertEquals("safe=$safe tiles=$tiles i=$i", ported, ring.angleOf(i, safe), 0.001f)
                }
                // 命中半宽与移植版 aimAt 的 `90f / n * 0.5f` 一致
                assertEquals(45f / tiles, ring.angleHalfDeg, 0.001f)
            }
        }
    }

    @Test
    fun doubleRingKeepsICsApartAtTheDefaultRadius() {
        val rings = FanLayout.rings(
            outerTiles = 7, innerTiles = 6, baseRadiusPx = base,
            iconPx = icon, ringGapPx = gap, maxRadiusPx = maxRadius(), safeDegrees = 0,
        )
        assertEquals(2, rings.size)
        val outer = rings[0]
        val inner = rings[1]
        // 两圈之间空得下「一个图标 + 间隙」，否则内外圈的命中带会互咬。
        assertTrue(
            "outer-inner=${outer.radiusPx - inner.radiusPx}",
            outer.radiusPx - inner.radiusPx >= icon + gap - 0.001f,
        )
        // 每一圈自己也不许叠：相邻圆心距 ≥ 图标 + 间隙
        assertTrue(chord(outer) >= icon + gap - 0.001f)
        assertTrue(chord(inner) >= icon + gap - 0.001f)
        // 内圈在里、外圈在外
        assertTrue(inner.radiusPx < outer.radiusPx)
    }

    @Test
    fun doubleRingExpandsTheRadiusWhenTheSettingIsTooSmall() {
        // 用户把半径设成 180dp（最小的那一档）还要开双层：几何上装不下，只能把外圈撑开。
        val small = 180f * 3.25f
        val rings = FanLayout.rings(
            outerTiles = 7, innerTiles = 6, baseRadiusPx = small,
            iconPx = icon, ringGapPx = gap, maxRadiusPx = maxRadius(), safeDegrees = 0,
        )
        assertTrue("radius must grow past the setting", rings[0].radiusPx > small)
        assertTrue(chord(rings[0]) >= icon + gap - 0.001f)
        assertTrue(chord(rings[1]) >= icon + gap - 0.001f)
        // 但内圈仍然要在外圈里面
        assertTrue(rings[1].radiusPx > 0f)
    }

    @Test
    fun doubleRingNeverLeavesTheScreen() {
        // 64dp 图标 + 最小半径：几何想要更大，也只能停在"图标扫到 90° 还在屏幕内"。
        val bigIcon = 64f * 3.25f
        val rings = FanLayout.rings(
            outerTiles = 8, innerTiles = 7, baseRadiusPx = 180f * 3.25f,
            iconPx = bigIcon, ringGapPx = gap, maxRadiusPx = maxRadius(bigIcon), safeDegrees = 0,
        )
        assertTrue(rings[0].radiusPx <= maxRadius(bigIcon) + 0.001f)
        assertTrue(rings[0].radiusPx + bigIcon / 2f <= screen + 0.001f)
    }

    @Test
    fun doubleRingUsesTheInnerCountForTheInnerStep() {
        val rings = FanLayout.rings(
            outerTiles = 8, innerTiles = 7, baseRadiusPx = base,
            iconPx = icon, ringGapPx = gap, maxRadiusPx = maxRadius(), safeDegrees = 3,
        )
        // 外层 8 枚、内层 7 枚：两圈步长不同（这正是半径要分开算的原因）。
        assertEquals((90f - 6f) / 8f, rings[0].stepDeg, 0.001f)
        assertEquals((90f - 6f) / 7f, rings[1].stepDeg, 0.001f)
        // safeDegrees 下第一枚不在 0°：内圈第一枚 = 半格 + safe
        assertEquals(rings[1].stepDeg * 0.5f + 3f, rings[1].angleOf(0, 3), 0.001f)
    }

    @Test
    fun chordRadiusIsTheInverseOfTheChordFormula() {
        val step = 90f / 7f
        val r = FanLayout.chordRadius(180f, step)
        assertEquals(180f, 2f * r * kotlin.math.sin(Math.toRadians(step / 2.0)).toFloat(), 0.01f)
        // 步长退化时不除零
        assertEquals(180f, FanLayout.chordRadius(180f, 0f), 0.001f)
    }

    /** 相邻两个 tile 的圆心距。 */
    private fun chord(ring: FanLayout.Ring): Float =
        2f * ring.radiusPx * abs(kotlin.math.sin(Math.toRadians(ring.stepDeg / 2.0)).toFloat())
}
