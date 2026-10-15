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
}
