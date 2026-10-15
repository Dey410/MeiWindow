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
     * 这一页前几枚 tile 归**外圈**。
     *
     * 满页时就是 `outerApps`；页尾不满（最后一页、或「收藏不足且没开自动填充」的第一页）时可能
     * 连外圈都填不满，那时返回 **0 = 整页按单圈画**：一圈 2 枚应用 + 一枚孤零零的「更多」挂在内圈
     * 比现在难看，而原来单层的样子本来就支持任意枚数的环。
     */
    fun outerSplit(tiles: Int, iconCount: Int, layers: Int): Int {
        if (!isDouble(layers)) return 0
        val outer = outerApps(iconCount, layers)
        return if (tiles > outer) outer else 0
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
     * @param baseRadiusPx 用户设置的半径 —— **它就是外圈半径**（单层时不再改动）。
     * @param iconPx 图标边长（px）。相邻图标的圆心距至少要 [iconPx] + [ringGapPx] 才不会叠。
     * @param ringGapPx 两圈之间的径向间隙。必须 ≥ 触摸 slop，否则内外圈的命中带会互相咬住
     *   （`radiusMax = r + icon/2 + slop`）。
     * @param maxRadiusPx 外圈半径上限（屏幕装得下）。几何需要更大时到此为止，宁可略微叠一点也不能
     *   把图标推出屏幕。
     */
    fun rings(
        outerTiles: Int,
        innerTiles: Int,
        baseRadiusPx: Float,
        iconPx: Float,
        ringGapPx: Float,
        maxRadiusPx: Float,
        safeDegrees: Int,
    ): List<Ring> {
        if (outerTiles <= 0) return emptyList()
        val stepOuter = stepDeg(outerTiles, safeDegrees)
        val outer: Float
        val inner: Float
        if (innerTiles <= 0) {
            // 单层：半径就是用户设的那个，不做任何"自动撑开" —— 他调过的半径不能被我们改掉。
            outer = baseRadiusPx
            inner = 0f
        } else {
            val stepInner = stepDeg(innerTiles, safeDegrees)
            val need = iconPx + ringGapPx
            // 外圈自己要够宽，还得给内圈留出「内圈所需半径 + 一圈图标 + 间隙」。
            val wanted = max(
                max(baseRadiusPx, chordRadius(need, stepOuter)),
                chordRadius(need, stepInner) + need,
            )
            outer = min(wanted, maxRadiusPx)
            // 半径的下限只在小到荒谬的屏（比一个图标还窄）上会碰到：内圈退到离轴心一个图标远处，
            // 两圈命中带可能相交 —— 那时整页本来也画不出两层，不再为它加分支。
            inner = max(outer - need, iconPx)
        }
        val list = ArrayList<Ring>(2)
        list.add(Ring(tiles = outerTiles, radiusPx = outer, stepDeg = stepOuter, angleHalfDeg = 45f / outerTiles))
        if (innerTiles > 0) {
            val stepInner = stepDeg(innerTiles, safeDegrees)
            list.add(Ring(tiles = innerTiles, radiusPx = inner, stepDeg = stepInner, angleHalfDeg = 45f / innerTiles))
        }
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
