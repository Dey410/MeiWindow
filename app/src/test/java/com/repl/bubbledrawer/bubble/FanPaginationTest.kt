package com.repl.bubbledrawer.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FanPaginationTest {

    /**
     * 跑的是**生产实现**（[FanPagination.pages]）。
     *
     * 这份测试以前自带一份镜像实现，生产代码改了它不会跟着改 —— 加分层容量时正好撞上：容量从
     * 「maxApps 个应用 + 更多」变成「[FanLayout.tilesPerPage]」，镜像那份就会悄悄继续绿着。
     */
    private fun paginate(
        pinnedCount: Int,
        recommendCount: Int,
        maxApps: Int,
        autoFillRecommend: Boolean,
        layers: Int = FanLayout.LAYERS_SINGLE,
    ): List<List<String>> {
        val pinned = (1..pinnedCount).map { "pinned_$it" }
        val rec = (1..recommendCount).map { "rec_$it" }
        return FanPagination.pages(
            pinned = pinned,
            recommended = rec,
            capacity = FanLayout.tilesPerPage(maxApps, layers),
            autoFillRecommend = autoFillRecommend,
            more = { "More" },
        )
    }

    @Test
    fun testTwelvePinnedWithRecommendations() {
        val pages = paginate(pinnedCount = 12, recommendCount = 8, maxApps = 5, autoFillRecommend = false)
        // 12 pinned + 8 rec = 20 total apps -> 4 pages of 5
        assertEquals(4, pages.size)
        // Page 0: pinned 1..5
        assertEquals(listOf("pinned_1", "pinned_2", "pinned_3", "pinned_4", "pinned_5", "More"), pages[0])
        // Page 1: pinned 6..10
        assertEquals(listOf("pinned_6", "pinned_7", "pinned_8", "pinned_9", "pinned_10", "More"), pages[1])
        // Page 2: pinned 11..12 + rec 1..3 (收藏没了就显示推荐的)
        assertEquals(listOf("pinned_11", "pinned_12", "rec_1", "rec_2", "rec_3", "More"), pages[2])
        // Page 3: rec 4..8
        assertEquals(listOf("rec_4", "rec_5", "rec_6", "rec_7", "rec_8", "More"), pages[3])
    }

    @Test
    fun testTwoPinnedWithoutAutoFill() {
        val pages = paginate(pinnedCount = 2, recommendCount = 7, maxApps = 5, autoFillRecommend = false)
        // Page 0 has only the 2 pinned apps + More
        assertEquals(listOf("pinned_1", "pinned_2", "More"), pages[0])
        // Page 1 has recommendations 1..5 + More
        assertEquals(listOf("rec_1", "rec_2", "rec_3", "rec_4", "rec_5", "More"), pages[1])
        // Page 2 has recommendations 6..7 + More
        assertEquals(listOf("rec_6", "rec_7", "More"), pages[2])
    }

    @Test
    fun testTwoPinnedWithAutoFill() {
        val pages = paginate(pinnedCount = 2, recommendCount = 7, maxApps = 5, autoFillRecommend = true)
        // Page 0 is filled to 5 items using recommendations
        assertEquals(listOf("pinned_1", "pinned_2", "rec_1", "rec_2", "rec_3", "More"), pages[0])
        // Page 1 has rec 4..7 + More
        assertEquals(listOf("rec_4", "rec_5", "rec_6", "rec_7", "More"), pages[1])
    }

    @Test
    fun testEmptyPinnedAndRecommendations() {
        // 什么都没有时也要留一页「更多」—— 它是唯一的出口
        val pages = FanPagination.pages(
            pinned = emptyList<String>(),
            recommended = emptyList(),
            capacity = FanLayout.tilesPerPage(5, FanLayout.LAYERS_DOUBLE),
            autoFillRecommend = false,
            more = { "More" },
        )
        assertEquals(listOf(listOf("More")), pages)
    }

    @Test
    fun testDoubleLayerFitsMoreAppsPerPage() {
        // 内层 5 应用 + 外层 7 应用 = 12 应用 + 更多 = 13 枚 tile 一页
        val pages = paginate(pinnedCount = 12, recommendCount = 8, maxApps = 5, autoFillRecommend = false, layers = FanLayout.LAYERS_DOUBLE)
        // 20 个应用原来要 4 页（每页 5 个），双层后只要 2 页
        assertEquals(2, pages.size)
        assertEquals(
            (1..12).map { "pinned_$it" } + "More",
            pages[0],
        )
        // 第 2 页：8 个推荐 + 更多
        assertEquals((1..8).map { "rec_$it" } + "More", pages[1])
    }

    @Test
    fun testDoubleLayerPageSplitsInnerFirst() {
        // 满页 13 枚 tile：**前 6 枚（5 应用 + 「更多」）在内圈**，剩下 7 枚应用在外圈。
        val pages = paginate(pinnedCount = 20, recommendCount = 0, maxApps = 5, autoFillRecommend = true, layers = FanLayout.LAYERS_DOUBLE)
        assertEquals(13, pages[0].size)
        assertEquals(6, FanLayout.innerTileCount(12, 5, FanLayout.LAYERS_DOUBLE))
        val ordered = FanLayout.ringOrder(pages[0], 5, FanLayout.LAYERS_DOUBLE) { it == "More" }
        assertEquals((1..5).map { "pinned_$it" } + "More", ordered.take(6))
        assertEquals((6..12).map { "pinned_$it" }, ordered.drop(6))
    }

    @Test
    fun testTopUpFillsTheLastPage() {
        // 用户 2026-10-15：最后一页不够就用后面的推荐补齐，哪怕为此要多要几条推荐。
        val perPage = FanLayout.tilesPerPage(5, FanLayout.LAYERS_DOUBLE) - 1
        // 没有收藏：把推荐补成整数页
        assertEquals(48, FanPagination.topUpRequest(base = 39, pinnedCount = 0, perPage = perPage, autoFillRecommend = false))
        // 收藏 12 个 + 没开自动填充：首屏只放收藏，推荐自己成页 → 也要整数页
        assertEquals(48, FanPagination.topUpRequest(base = 39, pinnedCount = 12, perPage = perPage, autoFillRecommend = false))
        // 收藏 13 个：首屏塞不下，收藏与推荐同一个池子 → 把 (13 + 推荐) 补到整数页
        assertEquals(47, FanPagination.topUpRequest(base = 39, pinnedCount = 13, perPage = perPage, autoFillRecommend = false))
        // 开了自动填充：收藏 2 个也要一起算进页里
        assertEquals(46, FanPagination.topUpRequest(base = 39, pinnedCount = 2, perPage = perPage, autoFillRecommend = true))
        // 单层（每页 5 个应用）：15 本来就是整数页，不用多要
        assertEquals(15, FanPagination.topUpRequest(base = 15, pinnedCount = 0, perPage = 5, autoFillRecommend = false))
        // 基数不是整数页时往上补：16 → 20
        assertEquals(20, FanPagination.topUpRequest(base = 16, pinnedCount = 0, perPage = 5, autoFillRecommend = false))
        // 退化输入不当机
        assertEquals(39, FanPagination.topUpRequest(39, 0, 0, false))
        assertEquals(0, FanPagination.topUpRequest(0, 0, 12, false))
    }

    @Test
    fun testToppedUpPoolMakesEveryPageFull() {
        // 补过之后，只要机器上的应用够，每一页都是满的（末页也不例外）。
        val capacity = FanLayout.tilesPerPage(5, FanLayout.LAYERS_DOUBLE)
        val perPage = capacity - 1
        val request = FanPagination.topUpRequest(base = 39, pinnedCount = 0, perPage = perPage, autoFillRecommend = false)
        val rec = (1..request).map { "rec_$it" }
        val pages = FanPagination.pages(
            pinned = emptyList<String>(),
            recommended = rec,
            capacity = capacity,
            autoFillRecommend = false,
            more = { "More" },
        )
        assertEquals(4, pages.size)
        pages.forEach { assertEquals(capacity, it.size) }
    }

    @Test
    fun testPressureThresholdLogic() {
        var triggered = 0
        var isHeavyPressed = false
        val threshold = 1.2f
        val releaseThreshold = threshold * 0.4f

        fun onSample(delta: Float) {
            if (delta >= threshold) {
                if (!isHeavyPressed) {
                    isHeavyPressed = true
                    triggered++
                }
            } else if (delta < releaseThreshold) {
                isHeavyPressed = false
            }
        }

        // Light touch, moving around
        onSample(0.1f)
        onSample(0.3f)
        onSample(0.5f)
        assertEquals(0, triggered)

        // Heavy press spikes
        onSample(1.25f)
        assertEquals(1, triggered)

        // Sustained high pressure does not repeat trigger
        onSample(1.5f)
        onSample(1.8f)
        assertEquals(1, triggered)

        // Partial release above release threshold
        onSample(0.7f)
        assertEquals(1, triggered)

        // Full release below release threshold
        onSample(0.2f)
        assertFalse(isHeavyPressed)

        // Second heavy press triggers next page!
        onSample(1.3f)
        assertEquals(2, triggered)
        assertTrue(isHeavyPressed)
    }
}
