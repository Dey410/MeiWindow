package com.repl.bubbledrawer.bubble

/**
 * 扇形翻页：把「收藏（应用 + 已选功能）」与「推荐」切成风车的一页页，每页尾部挂一枚「更多」。
 *
 * 从 [BubbleDockController.buildPages] 抽出来的**纯函数**：分页规则是用户可感知的行为（第几页出现
 * 什么），而它原来只存在于宿主代码里，测试里得再抄一份镜像实现 —— 抄的那份不会随生产代码一起改，
 * 迟早各说各话（这次加分层容量就是这么暴露的）。现在 `FanPaginationTest` 直接测这一份。
 */
object FanPagination {

    /**
     * @param capacity 每页最多几枚 tile（**含**「更多」）。分层后一个 tile 位就是一个图标位，
     *   所以容量直接由 [FanLayout.tilesPerPage] 给。
     * @param autoFillRecommend 关了就用推荐把「收藏不满一页」的首屏填满，开了就只放收藏。
     * @param more 「更多」tile 的构造（泛型是为了单测能用字符串跑同一份实现）。
     *
     * 顺序（与移植版一致，不许改）：
     *  1. 收藏（应用在前，已选功能在后）
     *  2. 推荐（`AppPredictor` 给的顺序）
     * 没有任何内容时给一页只有「更多」的 —— 那是唯一的入口，空着就没有出口了。
     */
    fun <T> pages(
        pinned: List<T>,
        recommended: List<T>,
        capacity: Int,
        autoFillRecommend: Boolean,
        more: () -> T,
    ): List<List<T>> {
        val perPage = (capacity - 1).coerceAtLeast(1)
        val out = ArrayList<List<T>>()

        if (pinned.isEmpty() && recommended.isEmpty()) {
            out.add(listOf(more()))
            return out
        }

        if (pinned.isEmpty()) {
            var idx = 0
            while (idx < recommended.size) {
                val chunk = recommended.subList(idx, minOf(idx + perPage, recommended.size))
                out.add(chunk + more())
                idx += perPage
            }
            return out
        }

        if (!autoFillRecommend && pinned.size <= perPage) {
            // 首屏只放收藏，不拿推荐来凑
            out.add(pinned + more())
            var rIdx = 0
            while (rIdx < recommended.size) {
                val chunk = recommended.subList(rIdx, minOf(rIdx + perPage, recommended.size))
                out.add(chunk + more())
                rIdx += perPage
            }
            return out
        }

        // 收藏 + 推荐合成一个池子，按容量切
        val pool = ArrayList<T>(pinned.size + recommended.size)
        pool.addAll(pinned)
        pool.addAll(recommended)

        var idx = 0
        while (idx < pool.size) {
            val chunk = pool.subList(idx, minOf(idx + perPage, pool.size))
            out.add(chunk + more())
            idx += perPage
        }
        return out
    }

    /**
     * 「最后一页也要铺满」要跟预测器要多少条推荐。
     *
     * 用户 2026-10-15：「如果翻到最后一页数量不够就用后续的推荐补齐（即使没开那么多推荐…）
     * 如果用户没开推荐就不动」。做法是把推荐数（或与收藏相加的总数）补到 [perPage] 的整数倍：
     * 多出来的那几条排在推荐序的后面，正好就是"后面的推荐"。
     *
     * 取不到那么多（机器上没那么多应用）就少给几条，最后一页短一点 —— 尽力而为。
     *
     * @param base 基数（原来的取数上限：容量 ×3，至少 15）
     * @param pinnedCount 收藏（含已选功能）条数
     * @param perPage 每页应用数（容量 − 1）
     * @param autoFillRecommend 关掉时首屏只放收藏、推荐自己成页，所以只要把**推荐**补成整数页
     */
    fun topUpRequest(base: Int, pinnedCount: Int, perPage: Int, autoFillRecommend: Boolean): Int {
        if (perPage <= 0 || base <= 0) return base
        val recommendOwnsPages = !autoFillRecommend && pinnedCount in 1..perPage
        val wanted = if (recommendOwnsPages) {
            roundUp(base, perPage)
        } else {
            roundUp(pinnedCount + base, perPage) - pinnedCount
        }
        return wanted.coerceAtLeast(base)
    }

    /** 向上取整到 [perPage] 的整数倍。 */
    private fun roundUp(value: Int, perPage: Int): Int =
        ((value + perPage - 1) / perPage) * perPage
}
