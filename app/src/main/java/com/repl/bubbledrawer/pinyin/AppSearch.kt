package com.repl.bubbledrawer.pinyin

/**
 * 应用搜索的匹配优先级与排序，纯函数（见 `AppSearchTest`）。
 *
 * 面板/收藏页（`PinManageModel.displayedCells`）与「选择应用」页共用这一份 —— 同一个应用名，
 * 在哪一页搜都该给同样的结果和同样的顺序；两边各写一套的结果是「面板搜得到、设置页搜不到」
 * 这类只会在真机上被发现的偏差。排序规则本来就是从面板那边搬过来的，行为未变。
 */
object AppSearch {

    /** 名称前缀：`微` → 微信。 */
    const val NAME_PREFIX = 1

    /** 拼音或首字母前缀：`wei` / `wx` → 微信。 */
    const val PINYIN_PREFIX = 2

    /** 名称 / 拼音 / 首字母包含：`信` → 微信、企业微信。 */
    const val CONTAINS = 3

    /** 只有包名包含：`alipay` → 支付宝。 */
    const val PACKAGE_CONTAINS = 4

    /** 不匹配。 */
    const val NO_MATCH = 0

    /**
     * 匹配优先级，数字越小越靠前；[NO_MATCH] = 不匹配。
     *
     * 调用方给的是原文查询串，这里不 trim（面板那边已经在外面 trim 过，行为保持不变）。
     */
    fun rank(app: BubbleApp, query: String): Int {
        if (app.label.startsWith(query, ignoreCase = true)) return NAME_PREFIX
        if (app.sortKey.startsWith(query, ignoreCase = true) ||
            app.initials.startsWith(query, ignoreCase = true)
        ) {
            return PINYIN_PREFIX
        }
        if (app.label.contains(query, ignoreCase = true) ||
            app.sortKey.contains(query, ignoreCase = true) ||
            app.initials.contains(query, ignoreCase = true)
        ) {
            return CONTAINS
        }
        if (app.packageName.contains(query, ignoreCase = true)) return PACKAGE_CONTAINS
        return NO_MATCH
    }

    /**
     * 过滤 + 排序。查询串为空白时**原样返回**（调用方给什么顺序就是什么顺序，通常是
     * `AppSortKey.DEFAULT`）；否则按 [rank] 升序，同档再按「名称短的优先 → 用得多的优先 →
     * 拼音键」——这一串平手规则是实地调出来的：同档里 `微信` 必须排在 `微信读书` 前面。
     */
    fun filter(apps: List<BubbleApp>, query: String): List<BubbleApp> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps
            .mapNotNull { app ->
                val rank = rank(app, q)
                if (rank == NO_MATCH) null else app to rank
            }
            .sortedWith(
                compareBy<Pair<BubbleApp, Int>> { it.second }
                    .thenBy { it.first.label.length }
                    .thenByDescending { it.first.usageCount }
                    .thenBy { it.first.sortKey },
            )
            .map { it.first }
    }
}
