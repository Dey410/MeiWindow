package com.repl.bubbledrawer.bubble

import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlin.math.asin
import kotlin.math.min

/**
 * 多开应用在扇形里的规则：同一个包的几个实例（主空间 / 应用双开 user 999 / 其它用户）合成**一枚** tile，
 * 直接松手打开默认实例，停留（或重按、长按）再弹出 1 / 2 让用户选。
 *
 * 纯函数、不碰 View，规则全在这里、有单测（`FanInstancesTest`）：
 *  - [merge]：收藏与推荐里的同包实例怎么合、默认是哪个；
 *  - [bubbleAngles]：选择气泡摆在哪（别出屏幕、别互相压住）。
 */
object FanInstances {

    /** 应用双开（MIUI XSpace）固定的用户 id。 */
    const val XSPACE_USER_ID = 999

    /**
     * 实例编号顺序：主空间（user 0）是 1，应用双开（999）是 2，其它用户（工作资料等）按 id 排在后面。
     * 用户心里的「1 / 2」就是「原来那个 / 分身」，所以 999 必须排在 10、11 这类工作资料前面。
     */
    fun numberRank(userId: Int): Long = when (userId) {
        0 -> 0L
        XSPACE_USER_ID -> 1L
        else -> 2L + userId
    }

    /** 机器上装了不止一个实例的包 → 它的全部实例（按 [numberRank] 排好）。只装了一份的包不在里面。 */
    fun instancesByPackage(all: List<BubbleApp>): Map<String, List<BubbleApp>> =
        all.groupBy { it.packageName }
            .filterValues { list -> list.distinctBy { it.userId }.size >= 2 }
            .mapValues { (_, list) -> list.distinctBy { it.userId }.sortedBy { numberRank(it.userId) } }

    data class Merged(
        val pinned: List<GestureAppLauncher.AdapterItem>,
        val recommended: List<GestureAppLauncher.AdapterItem>,
    )

    /**
     * 把多开应用合成一枚 tile。
     *
     * 默认实例 = **先出现的那个**（用户 2026-10-28 定的「跟收藏顺序走」）：
     *  - 收藏：只收藏了分身就默认分身；两个都收藏了，合到靠前的位置，默认是先收藏（排在前面）的那个；
     *  - 推荐：排名高的那个实例是默认；
     *  - 收藏里已经有这个包了，推荐里同包的实例（不管哪个）整个跳过 —— 收藏那枚 tile 里就能选到它。
     *
     * 已选功能（快捷方式）、「更多」、单开应用原样保留，顺序不动。
     *
     * @param instancesOf [instancesByPackage] 的结果。默认实例万一不在里面（应用列表还是旧的），补进去，
     *   保证弹出的选项里一定有「直接松手会打开的那个」。
     */
    fun merge(
        pinned: List<GestureAppLauncher.AdapterItem>,
        recommended: List<GestureAppLauncher.AdapterItem>,
        instancesOf: Map<String, List<BubbleApp>>,
    ): Merged {
        if (instancesOf.isEmpty()) return Merged(pinned, recommended)
        val seen = HashSet<String>()
        fun fold(items: List<GestureAppLauncher.AdapterItem>): List<GestureAppLauncher.AdapterItem> {
            val out = ArrayList<GestureAppLauncher.AdapterItem>(items.size)
            for (item in items) {
                if (item !is GestureAppLauncher.AdapterItem.AppItem) {
                    out.add(item)
                    continue
                }
                val pkg = item.app.packageName
                val instances = instancesOf[pkg]
                if (instances == null) {
                    out.add(item)
                    continue
                }
                if (!seen.add(pkg)) continue
                out.add(GestureAppLauncher.AdapterItem.AppItem(item.app, withDefault(instances, item.app)))
            }
            return out
        }
        val mergedPinned = fold(pinned)
        return Merged(mergedPinned, fold(recommended))
    }

    private fun withDefault(instances: List<BubbleApp>, app: BubbleApp): List<BubbleApp> =
        if (instances.any { it.userId == app.userId }) {
            instances
        } else {
            (instances + app).sortedBy { numberRank(it.userId) }
        }

    /**
     * 选择气泡的角度（度）。角度与 [GestureAppLauncher] 同一套极坐标：圆心在扇形所在的屏幕角，
     * 0° = 贴着侧边竖直向上，90° = 贴着底边水平朝屏幕里。所有气泡在同一个半径 [radiusPx] 上。
     *
     * 以 tile 的角度为中心，相邻气泡圆心相距一个气泡直径 + [gapPx]（按弧长算）。靠近侧边 / 底边时整组
     * 平移，保证每个气泡离两条屏幕边都至少 `气泡半径 + edgeMarginPx`；整组都塞不下时压缩间距。
     */
    fun bubbleAngles(
        tileAngleDeg: Float,
        count: Int,
        radiusPx: Float,
        bubbleRadiusPx: Float,
        gapPx: Float,
        edgeMarginPx: Float,
    ): FloatArray {
        if (count <= 0 || radiusPx <= 0f) return FloatArray(0)
        val stepDeg = Math.toDegrees(((2f * bubbleRadiusPx + gapPx) / radiusPx).toDouble()).toFloat()
        val margin = Math.toDegrees(asin(min(1.0, ((bubbleRadiusPx + edgeMarginPx) / radiusPx).toDouble()))).toFloat()
        val lo = margin
        val hi = 90f - margin
        if (count == 1 || hi <= lo) return FloatArray(count) { tileAngleDeg.coerceIn(minOf(lo, hi), maxOf(lo, hi)) }
        val step = min(stepDeg, (hi - lo) / (count - 1))
        val span = step * (count - 1)
        // 不用 coerceIn：整组正好塞满时 `hi - span` 会因浮点误差比 lo 小一丁点，空区间直接抛异常
        // （单测 tooManyBubblesCompressToFit 抓到的）—— 在 SystemUI 里那就是崩溃。
        val start = maxOf(lo, minOf(tileAngleDeg - span / 2f, hi - span))
        return FloatArray(count) { i -> start + step * i }
    }
}
