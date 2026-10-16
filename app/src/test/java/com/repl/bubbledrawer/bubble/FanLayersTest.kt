package com.repl.bubbledrawer.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            outerTiles = 6, innerTiles = 0, innerRadiusPx = base, outerRadiusPx = null,
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
                    outerTiles = tiles, innerTiles = 0, innerRadiusPx = base, outerRadiusPx = null,
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

    // --------------------------------------------------------------- 双层半径（用户 2026-10-16）

    /** 一个常见设备状态：内圈默认 260dp、外圈自动。 */
    private fun doubleRings(
        innerPx: Float = base,
        outerPx: Float? = null,
        outerTiles: Int = 7,
        innerTiles: Int = 6,
        iconPx: Float = icon,
    ) = FanLayout.rings(
        outerTiles = outerTiles, innerTiles = innerTiles,
        innerRadiusPx = innerPx, outerRadiusPx = outerPx,
        iconPx = iconPx, ringGapPx = gap, maxRadiusPx = maxRadius(iconPx), safeDegrees = 0,
    )

    @Test
    fun theRadiusSettingMovesBothRings() {
        // **用户 2026-10-16 报的 bug**：双层时半径滑条"根本控制不了"。
        // 旧几何把用户设置当成"下限"再和一圈自己的弦长要求取大，于是 180→284dp 整段纹丝不动。
        // 现在的规矩：设置就是半径本身。
        val small = 200f * 3.25f
        val medium = 260f * 3.25f
        val large = 340f * 3.25f
        val rings = listOf(small, medium, large).map { doubleRings(innerPx = it) }

        assertEquals("内圈半径就是设置本身", small, rings[0][1].radiusPx, 0.001f)
        assertEquals(medium, rings[1][1].radiusPx, 0.001f)
        assertEquals(large, rings[2][1].radiusPx, 0.001f)
        // 滑条一动两圈都要跟着动（外圈自动时 = 内圈 + 一圈图标 + 间隙）
        assertTrue(rings[1][0].radiusPx > rings[0][0].radiusPx)
        assertTrue(rings[2][0].radiusPx > rings[1][0].radiusPx)
    }
    @Test
    fun theAutomaticOuterRingFollowsTheInnerEverywhere() {
        // 自动外圈 = 内圈 + 一圈图标 + 间隙，**整条滑条上都成立**。
        //
        // 用户 2026-10-16 第二轮：「即使不启用单独条件，外圈半径也要跟随内圈缩放，不是完全不动」——
        // 中间那版给自动外圈留了一条"外圈自己的弦长下限"（862px），于是内圈在 180–213dp 之间拖动时
        // 外圈原地不动。弦长下限因此整个删掉：跟着设置缩放比"理想间距"重要。
        //
        // 上限只到 300dp：再往右是屏幕装不下（`maxRadius` 夹外圈），间距会被吃掉，那是物理限制。
        for (dp in listOf(180, 200, 232, 260, 300)) {
            val inner = dp * 3.25f
            val rings = doubleRings(innerPx = inner)
            assertEquals("inner=$dp", inner, rings[1].radiusPx, 0.001f)
            assertEquals("inner=$dp", icon + gap, rings[0].radiusPx - rings[1].radiusPx, 0.5f)
        }
        // 内圈怎么缩放，外圈就跟着缩放
        assertTrue(doubleRings(innerPx = 200f * 3.25f)[0].radiusPx < doubleRings(innerPx = 260f * 3.25f)[0].radiusPx)
    }

    @Test
    fun theAutomaticOuterRingSitsOneIconOutsideTheInner() {
        val rings = doubleRings()
        val outer = rings[0]
        val inner = rings[1]
        // 默认这一档：外圈 = 内圈 + 一格
        assertEquals(icon + gap, outer.radiusPx - inner.radiusPx, 0.5f)
        // 内圈不许被"自动撑开"顶走 —— 它必须还是用户设的那个数。
        assertEquals(base, inner.radiusPx, 0.001f)
    }

    @Test
    fun anExplicitOuterRadiusIsHonouredExactly() {
        val wanted = 320f * 3.25f
        val rings = doubleRings(outerPx = wanted)
        assertEquals(wanted, rings[0].radiusPx, 0.001f)
        assertEquals(base, rings[1].radiusPx, 0.001f)
    }

    @Test
    fun anOuterRadiusBelowTheInnerSnapsBackToTheInner() {
        // 用户 2026-10-16：「滑条滑到了比内圈半径更小的会自动弹回内圈半径」。
        val inner = 300f * 3.25f
        val rings = doubleRings(innerPx = inner, outerPx = 200f * 3.25f)
        assertEquals("外圈回到内圈半径", inner, rings[0].radiusPx, 0.001f)
        assertEquals("内圈不受影响，还是他设的那个", inner, rings[1].radiusPx, 0.001f)
        // 0 / 负数同样是"没设"，走自动那条
        val auto = doubleRings(innerPx = inner, outerPx = 0f)
        assertTrue(auto[0].radiusPx > inner)
    }

    @Test
    fun theScreenCapNeverInvertsTheRings() {
        // 拖到最右：外圈会被"图标扫到 90° 还在屏幕内"夹住；内圈跟着一起停在边界上，绝不跑到外面去。
        val rings = doubleRings(innerPx = 360f * 3.25f, outerPx = 360f * 3.25f)
        assertTrue(rings[0].radiusPx <= maxRadius() + 0.001f)
        assertTrue("内圈不能比外圈还远", rings[1].radiusPx <= rings[0].radiusPx + 0.001f)
        assertTrue(rings[1].radiusPx >= icon)
    }

    @Test
    fun doubleRingNeverLeavesTheScreen() {
        // 64dp 图标 + 最小半径：几何想要更大，也只能停在"图标扫到 90° 还在屏幕内"。
        val bigIcon = 64f * 3.25f
        val rings = doubleRings(
            innerPx = 180f * 3.25f,
            outerTiles = 8,
            innerTiles = 7,
            iconPx = bigIcon,
        )
        assertTrue(rings[0].radiusPx <= maxRadius(bigIcon) + 0.001f)
        assertTrue(rings[0].radiusPx + bigIcon / 2f <= screen + 0.001f)
    }

    @Test
    fun doubleRingUsesTheInnerCountForTheInnerStep() {
        val rings = FanLayout.rings(
            outerTiles = 8, innerTiles = 7, innerRadiusPx = base, outerRadiusPx = null,
            iconPx = icon, ringGapPx = gap, maxRadiusPx = maxRadius(), safeDegrees = 3,
        )
        // 外层 8 枚、内层 7 枚：两圈步长不同（这正是半径要分开算的原因）。
        assertEquals((90f - 6f) / 8f, rings[0].stepDeg, 0.001f)
        assertEquals((90f - 6f) / 7f, rings[1].stepDeg, 0.001f)
        // safeDegrees 下第一枚不在 0°：内圈第一枚 = 半格 + safe
        assertEquals(rings[1].stepDeg * 0.5f + 3f, rings[1].angleOf(0, 3), 0.001f)
    }
}
