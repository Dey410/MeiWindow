package com.repl.bubbledrawer.launch

import kotlin.math.roundToInt

/**
 * 迷你小窗「放大 / 还原」的几何决策 —— 纯逻辑，不碰 ROM，方便单测
 * （见 `FlymeFreeformController.toggleMiniWindowScale` / `FlymeMiniMenuOverlay` 的放大按钮）。
 *
 * 坐标约定（源码 `out/WMShell_src/…/multitasking/common/taskmanager/MiuiFreeformModeTaskInfo`）：
 *
 *  - `getBounds()` = baseAnimTarget 的「未缩放 task bounds 摆在视觉位置」：
 *    宽高是**没缩放**的，left/top 是窗口此刻显示在屏幕上的左上角
 *    （`getCurrentBounds:476-478` → `MultiTaskingAnimTarget.getCurrentBounds:88-92`）；
 *  - `getScaledBounds()` = 上面那个 rect 原地放大（`MultiTaskingCommonUtils.scaleBounds:194-200`
 *    先 `Rect.scale` 再 `offsetTo` 回原来的 left/top），所以它才是我们眼睛看到的矩形；
 *  - 迷你缩放本身来自 `MiuiMultiWindowUtils.getMiniFreeformScale`，ROM 每次进迷你都重新算一遍
 *    （`MiuiFreeformModeAnimation:3941/4083/4613/5863`），所以「正常迷你大小」不用记，随时能问。
 *
 * 「放大」等于把 scale 乘 [MINI_ENLARGE_RATIO]：窗口的 base bounds 不变，只改缩放，ROM 那边
 * `MiuiFreeformModeAnimation.applyFreeformToMiniAnimation` 正是这么做的（只下发 scale + bounds）。
 */

/** 放大到正常迷你尺寸的几倍。1.5 与 Flyme 的「放大」观感接近，且不会盖满屏幕。 */
internal const val MINI_ENLARGE_RATIO = 1.5f

/** 大于「正常迷你缩放」这个比例就算放大状态（ROM 自己算出来的 scale 会有 ±1px 的取整误差）。 */
private const val MINI_ENLARGED_THRESHOLD = 1.05f

/** 缩放的安全区间：太小看不清、太大就成了普通小窗。 */
private const val MINI_SCALE_MIN = 0.15f
private const val MINI_SCALE_MAX = 0.95f

/** 一次「放大 / 还原」的结果：目标缩放 + 目标位置（未缩放的 task bounds 摆在哪儿）。 */
internal data class MiniScaleDecision(
    val scale: Float,
    val left: Int,
    val top: Int,
    val enlarged: Boolean,
)

/** 当前缩放算不算「已放大」。 */
internal fun isMiniScaleEnlarged(currentScale: Float, normalMiniScale: Float): Boolean =
    normalMiniScale > 0f &&
        currentScale.isFinite() &&
        currentScale > normalMiniScale * MINI_ENLARGED_THRESHOLD

/**
 * 算出「放大 / 还原」的下一状态，返回 null 表示这次点击不该动窗口（参数不可用）。
 *
 * 位置规则：窗口靠哪边就往哪边「长」—— 右半屏的锚右边缘、左半屏的锚左边缘（迷你窗口通常就停在
 * 上方的左右角），纵向保持 top 不动，两头都夹在屏幕内。这样放大时窗口不会突然跳到屏幕中间。
 *
 * @param taskWidth/taskHeight 未缩放的 task bounds（[MiniScaleDecision.left]/[top] 也是这套坐标）
 * @param currentScale 当前 scale（`MiuiFreeformModeTaskInfo.getFreeformScale()`）
 * @param visualLeft/visualTop 当前**视觉**左上角（`getScaledBounds().left/top`）
 * @param normalMiniScale ROM 认为的迷你缩放（`MiuiMultiWindowUtils.getMiniFreeformScale`）
 * @param margin 离屏幕边缘至少留多少 px
 * @param ratio 放大倍数（测试用，默认 [MINI_ENLARGE_RATIO]）
 */
internal fun decideMiniScale(
    taskWidth: Int,
    taskHeight: Int,
    currentScale: Float,
    visualLeft: Int,
    visualTop: Int,
    screenWidth: Int,
    screenHeight: Int,
    normalMiniScale: Float,
    margin: Int = 0,
    ratio: Float = MINI_ENLARGE_RATIO,
): MiniScaleDecision? {
    if (taskWidth <= 0 || taskHeight <= 0) return null
    if (screenWidth <= 0 || screenHeight <= 0) return null
    if (!normalMiniScale.isFinite() || normalMiniScale <= 0f) return null

    // ROM 偶尔在动画中间被问（scale 还是 0 或 NaN）：这种时候按「正常迷你」处理，别把窗口缩没。
    val nowScale = currentScale.takeIf { it.isFinite() && it > 0f } ?: normalMiniScale
    val enlargedNow = isMiniScaleEnlarged(nowScale, normalMiniScale)

    val target = (if (enlargedNow) normalMiniScale else normalMiniScale * ratio)
        .coerceIn(MINI_SCALE_MIN, MINI_SCALE_MAX)
    if (!target.isFinite() || target <= 0f) return null

    val nowW = (taskWidth * nowScale).roundToInt()
    val nextW = (taskWidth * target).roundToInt()
    val nextH = (taskHeight * target).roundToInt()

    val maxLeft = (screenWidth - nextW - margin).coerceAtLeast(margin)
    val maxTop = (screenHeight - nextH - margin).coerceAtLeast(margin)

    // 靠右半屏 → 锚右边缘（放大时向左长），靠左半屏 → 锚左边缘。
    val anchoredLeft = if (visualLeft + nowW / 2 > screenWidth / 2) {
        visualLeft + nowW - nextW
    } else {
        visualLeft
    }

    return MiniScaleDecision(
        scale = target,
        left = anchoredLeft.coerceIn(margin, maxLeft),
        top = visualTop.coerceIn(margin, maxTop),
        enlarged = !enlargedNow,
    )
}
