package com.repl.bubbledrawer.pin

import androidx.compose.runtime.Immutable
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Flat cell model for the pin-manager grid.
 *
 * The original renders BOTH areas as 4-column grids:
 *  - ★ area: AppDragLayout → GridLayoutManager(context, 4) (AppDragLayout.java:185),
 *    one item per app, drag-sortable;
 *  - letter area: rows grouped 4-per-line via C2875i subList chunks (:561/:615) with
 *    section labels — identical visual result to one grid with span=1 cells and
 *    full-span label rows (LightWeightOpenSettings' SpanSizeLookup idiom :193-198).
 *
 * The list is an [ImmutableList] produced HERE (docs/ui-guidelines.md "强跳过友好的状态
 * 形状"): converting at the UI layer would leave every intermediate composable unskippable.
 */
@Immutable
sealed interface Cell {
    data object PinHead : Cell                 // 已添加 + 长按拖动图标以排序 (app_settings_item_head)
    data object EmptyHint : Cell               // 暂无已添加应用 (click_add_tip, only while pins empty)
    data class Label(val text: String) : Cell  // ★/推荐/A..Z/# group header
    data class Pin(val app: BubbleApp) : Cell  // pinned slot, drag-reorderable
    /** @param section owning group label — lazy-grid keys need it (recommend + letter
     *  groups overlap by design, so pkg alone is not unique). */
    data class App(val app: BubbleApp, val section: String) : Cell  // regular grid cell

    /**
     * 「展开全部 / 收起」行（用户 2026-10-14）：某一区超过 [Sections.VISIBLE_MAX] 个时，先只铺
     * [Sections.VISIBLE_MAX] 个，这一行垫在第 N 个下面，点开才把剩下的放出来。
     *
     * @param remaining 还藏着几个（展开状态下为 0）
     * @param expanded 当前是不是展开态（决定按钮写「展开全部」还是「收起」）
     */
    data class Expand(val section: String, val remaining: Int, val expanded: Boolean) : Cell
}

private fun MutableList<Cell>.addApp(app: BubbleApp, section: String) = add(Cell.App(app, section))

object Sections {

    /**
     * 没给出数量时的推荐条数。与 [com.repl.bubbledrawer.xposed.RemotePrefs.DEFAULT_RECOMMEND_COUNT] 一致
     * （默认 12），别让"没有设置时"和"默认设置"显示成两样。
     */
    const val RECOMMEND_TOP_N = 12

    /**
     * 一屏最多铺多少个（推荐 / 收藏应用各自算）。
     *
     * 用户 2026-10-14：「超过20个的默认不显示，在20个下面加一个展开按钮点击才能显示」。定 20 是因为
     * 面板是浮窗，20 个（4 列 × 5 行）已经占掉大半屏，再多就把 A–Z 列表挤没了 —— 而列表才是这一页的
     * 主体。
     */
    const val VISIBLE_MAX = 20

    /** 收藏区（★）的分区名 —— [Cell.Expand.section] 用它区分"展开的是哪一段"。 */
    const val PIN_SECTION = "★"

    /** 推荐区分区名，与 [Cell.Label] 的文案一致（原来就是硬编码的"推荐"）。 */
    const val RECOMMEND_SECTION = "推荐"

    fun build(
        all: List<BubbleApp>,
        pins: List<PinnedRef>,
        recommend: Boolean,
        recommendCount: Int = RECOMMEND_TOP_N,
        recommendedApps: List<BubbleApp> = emptyList(),
    ): ImmutableList<Cell> {
        val pinnedKeys = pins.map { "${it.packageName}#${it.userId}" }.toSet()
        val out = ArrayList<Cell>()

        out.add(Cell.PinHead)
        if (pins.isEmpty()) {
            // SlideLaunchAppSettings:1034 — click_add_tip VISIBLE(0) only while empty
            out.add(Cell.EmptyHint)
        } else {
            // no "★" text row in the original: the ★ area is AppDragLayout right below
            // the 已添加 head; ★ exists only as the index-bar current marker (:326)
            for (ref in pins) {
                all.firstOrNull { it.packageName == ref.packageName && it.userId == ref.userId }
                    ?.let { out.add(Cell.Pin(it)) }
            }
        }

        if (recommend && recommendCount > 0) {
            val rec = if (recommendedApps.isNotEmpty()) {
                recommendedApps.filter { "${it.packageName}#${it.userId}" !in pinnedKeys }.take(recommendCount)
            } else {
                all.filter { it.usageCount > 0 && "${it.packageName}#${it.userId}" !in pinnedKeys }
                    .sortedByDescending { it.usageCount }
                    .take(recommendCount)
            }
            if (rec.isNotEmpty()) {
                out.add(Cell.Label(RECOMMEND_SECTION))
                rec.forEach { out.addApp(it, RECOMMEND_SECTION) }
            }
        }

        val groups = LinkedHashMap<String, MutableList<BubbleApp>>()
        for (app in all) {
            if ("${app.packageName}#${app.userId}" in pinnedKeys) continue
            groups.getOrPut(AppSortKey.groupOf(app.sortKey)) { ArrayList() }.add(app)
        }
        for (k in groups.keys.sortedWith(AppSortKey.SECTION_LETTER)) {
            out.add(Cell.Label(k))
            groups.getValue(k).sortedWith(AppSortKey.DEFAULT).forEach { out.addApp(it, k) }
        }
        return out.toImmutableList()
    }

    /** Group labels present (for the index bar), in display order. */
    fun labelsOf(cells: List<Cell>): List<String> = cells.filterIsInstance<Cell.Label>().map { it.text }

    /**
     * 铺到界面上的那一份：[build] 给什么就有什么，但**推荐区与收藏区各最多 [VISIBLE_MAX] 个**，
     * 超出的部分折在 [Cell.Expand] 后面。
     *
     * 为什么不把上限做进 [build]：搜索走的是另一条路（搜索结果不折叠 —— 用户搜的就是要找的那个，
     * 折起来等于没搜到），而 [build] 是两边共用的；另外折叠只是显示层的事，改它不该重跑推荐（那是
     * 一次 IO + 预测）。
     *
     * 只折「收藏**应用**」（[Cell.Pin]）：需求说的是"应用页的收藏应用管理"，而顶部收藏区在功能页
     * 装的是「已选功能」（`model.pinnedFunctions`，本函数管不着），把它一起折了会让人以为功能丢了。
     *
     * @param pinnedExpanded 收藏区是否已展开
     * @param recommendExpanded 推荐区是否已展开
     */
    fun visible(
        cells: List<Cell>,
        pinnedExpanded: Boolean,
        recommendExpanded: Boolean,
        max: Int = VISIBLE_MAX,
    ): List<Cell> {
        if (cells.isEmpty()) return cells
        val out = ArrayList<Cell>(cells.size + 2)

        // 1) 收藏区：开头到第一个 Label 之前（PinHead / EmptyHint / Pin…）
        val headEnd = cells.indexOfFirst { it is Cell.Label }.let { if (it < 0) cells.size else it }
        out.addAll(cap(cells.subList(0, headEnd), PIN_SECTION, max, pinnedExpanded) { it is Cell.Pin })

        // 2) 其余按 Label 分段；只有「推荐」段折叠
        var i = headEnd
        while (i < cells.size) {
            val cell = cells[i]
            if (cell !is Cell.Label) {
                out.add(cell)
                i++
                continue
            }
            var end = i + 1
            while (end < cells.size && cells[end] !is Cell.Label) end++
            out.add(cell)
            val body = cells.subList(i + 1, end)
            if (cell.text == RECOMMEND_SECTION) {
                out.addAll(cap(body, RECOMMEND_SECTION, max, recommendExpanded) { it is Cell.App })
            } else {
                out.addAll(body)
            }
            i = end
        }
        return out
    }

    /**
     * 折叠一段：[items] 不超过 [max] 就原样返回；超过则只留前 [max]（或全部，展开时），
     * 并在**第一个 item 的位置之后**补一枚 [Cell.Expand]（收藏区的 PinHead 在 items 之前，别打乱它）。
     */
    private fun cap(
        block: List<Cell>,
        section: String,
        max: Int,
        expanded: Boolean,
        isItem: (Cell) -> Boolean,
    ): List<Cell> {
        val items = block.filter(isItem)
        if (items.size <= max) return block
        val shown = if (expanded) items.size else max
        val out = ArrayList<Cell>(shown + 3)
        var placed = false
        for (cell in block) {
            if (isItem(cell)) {
                if (!placed) {
                    placed = true
                    out.addAll(items.take(shown))
                }
                continue
            }
            out.add(cell)
        }
        if (!placed) out.addAll(items.take(shown))
        out.add(Cell.Expand(section = section, remaining = items.size - shown, expanded = expanded))
        return out
    }
}
