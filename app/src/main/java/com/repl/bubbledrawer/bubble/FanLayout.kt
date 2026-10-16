package com.repl.bubbledrawer.bubble

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 扇形「分层」的几何：一层 / 两层时每页各放几个应用、tile 落在哪一圈、每圈的半径与角度。
 *
 * 需求原文（用户 2026-10-14）：「内层有5个应用，则外层应有7个应用，计算方式为内层5个应用+内层有的
 * 更多按钮再+1。外层无需更多应用入口」——
 *
 *  - **内层** = [iconCount] 个应用 + 「更多」，共 `iconCount + 1` 枚 tile；
 *  - **外层** = `iconCount + 2` 个应用，**没有**「更多」（唯一的「更多」在内层）；
 *  - 于是内层 5 个应用时外层 7 个应用（`5 + 1 + 1`）。
 *
 * 纯逻辑、无 Android 依赖：半径/角度的推导是这套东西里最容易算错的部分（三角函数 + 屏幕装不装得下
 * 的钳位），抽出来才能用单测钉住 —— `GestureAppLauncher` 里那份 1:1 移植的布局代码不适合再塞分支。
 *
 * 为什么两圈半径要算而不是直接取「中径」：外圈 tile 数是内圈 + 1，同样的 90° 扇形里角度步长更小，
 * 半径不够时相邻图标会**叠在一起**。半径必须同时满足「自己这一圈不叠」和「两圈之间留得下图标 + 间隙」，
 * 而这两件事都随用户设置的图标大小变化（32–64dp）。
 */
object FanLayout {

    const val LAYERS_SINGLE = 1
    const val LAYERS_DOUBLE = 2

    /**
     * 外层比内层多几个应用。
     *
     * 2 = 「更多」在内层占掉的那一格 + 用户要的那 1 个。别改成 1：那会变成内层 5 应用 → 外层 6 应用，
     * 与「内层5个应用则外层应有7个应用」不符。
     */
    const val OUTER_EXTRA_APPS = 2

    /** 单层：这一圈装 [iconCount] 个应用。双层：外层装 [iconCount] + [OUTER_EXTRA_APPS] 个。 */
    fun outerApps(iconCount: Int, layers: Int): Int =
        if (isDouble(layers)) iconCount + OUTER_EXTRA_APPS else iconCount

    /** 单层没有内圈（0）；双层内圈装 [iconCount] 个应用。 */
    fun innerApps(iconCount: Int, layers: Int): Int =
        if (isDouble(layers)) iconCount else 0

    /** 每页应用数（不含「更多」）：单层 `iconCount`，双层 `2 * iconCount + 2`。 */
    fun appsPerPage(iconCount: Int, layers: Int): Int = outerApps(iconCount, layers) + innerApps(iconCount, layers)

    /** 每页 tile 数：应用 + 一枚「更多」。这就是分页的容量（见 [FanPagination]）。 */
    fun tilesPerPage(iconCount: Int, layers: Int): Int = appsPerPage(iconCount, layers) + 1

    /** 外圈 tile 数。**单层时「更多」也在这一圈**（原来就只有一圈）。 */
    fun outerTiles(iconCount: Int, layers: Int): Int =
        if (isDouble(layers)) outerApps(iconCount, layers) else outerApps(iconCount, layers) + 1

    /** 内圈 tile 数：应用 + 「更多」。单层为 0。 */
    fun innerTiles(iconCount: Int, layers: Int): Int =
        if (isDouble(layers)) innerApps(iconCount, layers) + 1 else 0

    /**
     * 这一页的内圈装几个应用：**前 [iconCount] 个**。
     *
     * 用户 2026-10-15 改的规则：「内圈显示 1-5 个应用，外圈显示第 6 个+的应用」（原来是反的：外层
     * 拿前几个）。内层 5 个、外层 7 个这个数量关系没变，变的是谁在前。
     */
    fun innerAppsOnPage(appsOnPage: Int, iconCount: Int, layers: Int): Int =
        if (isDouble(layers)) minOf(appsOnPage, iconCount) else 0

    /**
     * 这一页前几枚 tile 归**内圈**（内圈 = 它的应用 + 末尾那枚「更多」）。
     *
     * 双层时恒 ≥ 1（哪怕整页只有一枚「更多」，它也在内圈）；单层返回 0 = 整页一圈，走移植版那套。
     */
    fun innerTileCount(appsOnPage: Int, iconCount: Int, layers: Int): Int =
        if (isDouble(layers)) innerAppsOnPage(appsOnPage, iconCount, layers) + 1 else 0

    /**
     * 把一页的 item 重排成「**内圈在前、外圈在后**」：[iconCount] 个应用 + 「更多」在内圈，剩下的
     * 应用在外圈。双层时「更多」因此会从页尾挪到页中间（内圈的最后一格）。
     *
     * 单层、找不到「更多」、或页内应用本来就装得进内圈时原样返回 —— 后两种情况下重排等于没重排
     * （内圈就是整页），不必动它。
     */
    fun <T> ringOrder(page: List<T>, iconCount: Int, layers: Int, isMore: (T) -> Boolean): List<T> {
        if (!isDouble(layers)) return page
        val moreIndex = page.indexOfLast(isMore)
        if (moreIndex < 0) return page
        val apps = page.filterIndexed { index, _ -> index != moreIndex }
        if (apps.size <= iconCount) return page
        return apps.take(iconCount) + page[moreIndex] + apps.drop(iconCount)
    }

    fun isDouble(layers: Int): Boolean = layers >= LAYERS_DOUBLE

    /** 一圈的几何。角度公式与 [GestureAppLauncher.angleFor] 同一套（`i` 从 0 数）。 */
    data class Ring(
        /** 这一圈的 tile 数（决定角度步长）。 */
        val tiles: Int,
        /** 这一圈的中心半径（px）。 */
        val radiusPx: Float,
        /** 相邻两个 tile 的角度步长（度）。 */
        val stepDeg: Float,
        /** 命中判定的角度半宽（度）—— 与移植版 `aimAt` 的 `90f / n * 0.5f` 等价。 */
        val angleHalfDeg: Float,
    ) {
        fun angleOf(indexInRing: Int, safeDegrees: Int): Float =
            if (safeDegrees == 0) {
                stepDeg * (indexInRing + 1f)
            } else {
                indexInRing * stepDeg + stepDeg * 0.5f + safeDegrees
            }
    }

    /**
     * 算出这一页要画的圈。[innerTiles] ≤ 0 表示单层（只返回外圈）。
     *
     * 传 **满页时**的 tile 数（[outerTiles]/[innerTiles] 按设置算），不要传"这一页实际有几枚" ——
     * 半径只由设置决定，翻页时图标才不会忽远忽近（用户 2026-10-15 报的「最后一页的图标跑到内圈」
     * 就是旧写法按页算、末页退化成单圈造成的）。
     *
     * ## 半径的语义（用户 2026-10-16：「开双层根本控制不了半径」之后改的）
     *
     * **设置就是半径本身，谁也不许把它悄悄改掉**：
     *  - 单层：那一圈 = [innerRadiusPx]；
     *  - 双层：**内圈 = [innerRadiusPx]**；外圈 = [outerRadiusPx]（用户单独指定，**比内圈小时按内圈算**），
     *    没指定（null）时自动 = `max(内圈 + 一圈图标 + 间隙, 外圈自己的弦长下限)`。
     *
     * 旧写法把「这一圈自己的弦长要求」也算进用户设置里（`max(设置, 弦长, 内圈弦长 + 间隙)`），
     * 后果是双层时半径被钉死在 284dp 一带：设置从 180 拖到 284 完全没反应，只有 284→360 那一小段
     * 有变化 —— 那正是「根本控制不了半径」。弦长从此只用来决定**自动**外圈要撑到哪，挤不挤由用户
     * 自己调（单层一直就是这个规矩）。
     *
     * @param innerRadiusPx 用户设置的半径 = 内圈（单层时就是唯一那一圈）。
     * @param outerRadiusPx 外圈半径（用户单独设定）；null = 自动跟着内圈走。
     * @param iconPx 图标边长（px）。相邻图标的圆心距至少要 [iconPx] + [ringGapPx] 才不会叠。
     * @param ringGapPx 两圈之间的径向间隙。必须 ≥ 触摸 slop，否则内外圈的命中带会互相咬住
     *   （`radiusMax = r + icon/2 + slop`）。
     * @param maxRadiusPx 外圈半径上限（屏幕装得下）。几何需要更大时到此为止，宁可略微叠一点也不能
     *   把图标推出屏幕。
     */
    fun rings(
        outerTiles: Int,
        innerTiles: Int,
        innerRadiusPx: Float,
        outerRadiusPx: Float?,
        iconPx: Float,
        ringGapPx: Float,
        maxRadiusPx: Float,
        safeDegrees: Int,
    ): List<Ring> {
        if (outerTiles <= 0) return emptyList()
        val stepOuter = stepDeg(outerTiles, safeDegrees)
        val list = ArrayList<Ring>(2)
        if (innerTiles <= 0) {
            // 单层：半径就是用户设的那个，不做任何"自动撑开" —— 他调过的半径不能被我们改掉。
            list.add(Ring(tiles = outerTiles, radiusPx = innerRadiusPx, stepDeg = stepOuter, angleHalfDeg = 45f / outerTiles))
            return list
        }
        val need = iconPx + ringGapPx
        val stepInner = stepDeg(innerTiles, safeDegrees)
        val wantedOuter = outerRadiusPx?.takeIf { it > 0f }
            // 用户单独设的：**不小于内圈**（比内圈小的自动回到内圈半径，用户 2026-10-16 的规则）。
            ?.let { max(it, innerRadiusPx) }
            // 自动：内圈 + 一圈图标 + 径向间隙（两圈刚好不叠），再和"外圈自己不挤"的弦长下限取大。
            ?: max(innerRadiusPx + need, chordRadius(need, stepOuter))
        val outer = wantedOuter.coerceAtMost(maxRadiusPx)
        // 内圈就是用户设的那个；只有外圈被屏幕夹到它里面去时才跟着降下来 —— 两圈不能颠倒，
        // 但**也不替用户维持间距**：他把外圈拉得贴着内圈，就照他说的那样贴着（命中带会略微重叠，
        // 挑中的是内圈那一枚）。间距是"自动外圈"那条公式的职责。
        val inner = innerRadiusPx.coerceIn(iconPx, max(outer, iconPx))
        list.add(Ring(tiles = outerTiles, radiusPx = outer, stepDeg = stepOuter, angleHalfDeg = 45f / outerTiles))
        list.add(Ring(tiles = innerTiles, radiusPx = inner, stepDeg = stepInner, angleHalfDeg = 45f / innerTiles))
        return list
    }

    /** 90° 扇形里 [tiles] 枚 tile 的角度步长：`angleFor` 的两支都在这儿。 */
    fun stepDeg(tiles: Int, safeDegrees: Int): Float {
        if (tiles <= 0) return 0f
        return if (safeDegrees == 0) 90f / (tiles + 1) else (90f - safeDegrees * 2f) / tiles
    }

    /** 弦长 [chordPx] 对应多大的半径（相邻圆心距 = 2R·sin(步长/2)）。 */
    fun chordRadius(chordPx: Float, stepDeg: Float): Float {
        if (stepDeg <= 0f) return chordPx
        return chordPx / (2f * sin(Math.toRadians(stepDeg / 2.0)).toFloat())
    }
}
