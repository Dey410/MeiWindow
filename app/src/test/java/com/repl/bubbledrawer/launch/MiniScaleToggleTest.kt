package com.repl.bubbledrawer.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迷你小窗「放大 / 还原」的几何决策（`MiniScaleToggle.kt`）。
 *
 * 数字取这台机器（1220×2656 @3.25）：迷你缩放 0.42 时一个 1105×2400 的 task 显示成 464×1008，
 * 放进右上角（`left = 1220 - 464 - 15`、`top = 130`），和 ROM 自己摆迷你窗口的位置一致
 * （`MiuiFreeformModeAnimation.startEnterMiniAnimForFixedRotation:3943`）。
 */
class MiniScaleToggleTest {

    private companion object {
        const val SCREEN_W = 1220
        const val SCREEN_H = 2656
        const val TASK_W = 1105
        const val TASK_H = 2400
        const val MINI_SCALE = 0.42f
        const val MINI_W = 464
        const val MINI_H = 1008
        const val MINI_TOP = 130
        const val RIGHT_MINI_LEFT = SCREEN_W - MINI_W - 15
        const val LEFT_MINI_LEFT = 15
        const val MARGIN = 8
    }

    @Test
    fun aRightParkedMiniGrowsTowardsTheRightEdge() {
        val decision = decideMiniScale(
            taskWidth = TASK_W,
            taskHeight = TASK_H,
            currentScale = MINI_SCALE,
            visualLeft = RIGHT_MINI_LEFT,
            visualTop = MINI_TOP,
            screenWidth = SCREEN_W,
            screenHeight = SCREEN_H,
            normalMiniScale = MINI_SCALE,
            margin = MARGIN,
        )

        assertEquals(true, decision?.enlarged)
        assertEquals(1.5f * MINI_SCALE, decision!!.scale, 1e-4f)
        val enlargedW = Math.round(TASK_W * decision.scale)
        // 锚右边缘：右边缘不动，宽度差从左边让出来。
        assertEquals(RIGHT_MINI_LEFT + MINI_W - enlargedW, decision.left)
        assertEquals(MINI_TOP, decision.top)
    }

    @Test
    fun aLeftParkedMiniGrowsTowardsTheLeftEdge() {
        val decision = decideMiniScale(
            taskWidth = TASK_W,
            taskHeight = TASK_H,
            currentScale = MINI_SCALE,
            visualLeft = LEFT_MINI_LEFT,
            visualTop = MINI_TOP,
            screenWidth = SCREEN_W,
            screenHeight = SCREEN_H,
            normalMiniScale = MINI_SCALE,
            margin = MARGIN,
        )

        assertEquals(true, decision?.enlarged)
        // 锚左边缘：左边缘不动。
        assertEquals(LEFT_MINI_LEFT, decision!!.left)
        assertEquals(MINI_TOP, decision.top)
    }

    @Test
    fun anEnlargedMiniShrinksBackToTheNormalSizeAtTheSameCorner() {
        val enlargedScale = MINI_SCALE * 1.5f
        val enlargedW = Math.round(TASK_W * enlargedScale)
        val enlargedLeft = RIGHT_MINI_LEFT + MINI_W - enlargedW

        val decision = decideMiniScale(
            taskWidth = TASK_W,
            taskHeight = TASK_H,
            currentScale = enlargedScale,
            visualLeft = enlargedLeft,
            visualTop = MINI_TOP,
            screenWidth = SCREEN_W,
            screenHeight = SCREEN_H,
            normalMiniScale = MINI_SCALE,
            margin = MARGIN,
        )

        assertEquals(false, decision?.enlarged)
        assertEquals(MINI_SCALE, decision!!.scale, 1e-4f)
        assertEquals(RIGHT_MINI_LEFT, decision.left)
        assertEquals(MINI_TOP, decision.top)
    }

    @Test
    fun theWindowNeverLeavesTheScreen() {
        val decision = decideMiniScale(
            taskWidth = TASK_W,
            taskHeight = TASK_H,
            currentScale = MINI_SCALE,
            // 迷你窗口被拖到右下角，放大后必须整体收回屏幕内。
            visualLeft = SCREEN_W - MINI_W,
            visualTop = SCREEN_H - MINI_H,
            screenWidth = SCREEN_W,
            screenHeight = SCREEN_H,
            normalMiniScale = MINI_SCALE,
            margin = MARGIN,
        )!!

        val w = Math.round(TASK_W * decision.scale)
        val h = Math.round(TASK_H * decision.scale)
        assertTrue("left=${decision.left}", decision.left >= MARGIN)
        assertTrue("right=${decision.left + w}", decision.left + w <= SCREEN_W - MARGIN)
        assertTrue("top=${decision.top}", decision.top >= MARGIN)
        assertTrue("bottom=${decision.top + h}", decision.top + h <= SCREEN_H - MARGIN)
    }

    @Test
    fun theScaleIsClampedToSomethingUsable() {
        val decision = decideMiniScale(
            taskWidth = TASK_W,
            taskHeight = TASK_H,
            currentScale = 0.9f,
            visualLeft = 100,
            visualTop = 100,
            screenWidth = SCREEN_W,
            screenHeight = SCREEN_H,
            normalMiniScale = 0.9f,
            margin = MARGIN,
        )!!

        // 0.9 × 1.5 = 1.35 会盖住整个屏幕 —— 夹到 0.95。
        assertEquals(0.95f, decision.scale, 1e-4f)
        assertEquals(true, decision.enlarged)
    }

    @Test
    fun unusableInputDoesNothing() {
        assertNull(
            decideMiniScale(
                taskWidth = 0,
                taskHeight = TASK_H,
                currentScale = MINI_SCALE,
                visualLeft = 0,
                visualTop = 0,
                screenWidth = SCREEN_W,
                screenHeight = SCREEN_H,
                normalMiniScale = MINI_SCALE,
            ),
        )
        assertNull(
            decideMiniScale(
                taskWidth = TASK_W,
                taskHeight = TASK_H,
                currentScale = MINI_SCALE,
                visualLeft = 0,
                visualTop = 0,
                screenWidth = SCREEN_W,
                screenHeight = SCREEN_H,
                normalMiniScale = 0f,
            ),
        )
        assertNull(
            decideMiniScale(
                taskWidth = TASK_W,
                taskHeight = TASK_H,
                currentScale = MINI_SCALE,
                visualLeft = 0,
                visualTop = 0,
                screenWidth = SCREEN_W,
                screenHeight = SCREEN_H,
                normalMiniScale = Float.NaN,
            ),
        )
    }

    @Test
    fun aScaleOfZeroFallsBackToTheNormalMiniScale() {
        // ROM 在动画中间被问到时 scale 可能还是 0：按「正常迷你」算，别把窗口缩成 0。
        val decision = decideMiniScale(
            taskWidth = TASK_W,
            taskHeight = TASK_H,
            currentScale = 0f,
            visualLeft = RIGHT_MINI_LEFT,
            visualTop = MINI_TOP,
            screenWidth = SCREEN_W,
            screenHeight = SCREEN_H,
            normalMiniScale = MINI_SCALE,
            margin = MARGIN,
        )!!

        assertEquals(true, decision.enlarged)
        assertEquals(1.5f * MINI_SCALE, decision.scale, 1e-4f)
    }

    @Test
    fun enlargedMeansMoreThanFivePercentAboveTheNormalMiniScale() {
        assertTrue(isMiniScaleEnlarged(0.63f, 0.42f))
        assertFalse(isMiniScaleEnlarged(0.42f, 0.42f))
        // ROM 自己算出来的 scale 会有取整误差（±1px），不该被当成「已放大」。
        assertFalse(isMiniScaleEnlarged(0.43f, 0.42f))
        assertTrue(isMiniScaleEnlarged(0.45f, 0.42f))
        assertFalse(isMiniScaleEnlarged(0.42f, 0f))
        assertFalse(isMiniScaleEnlarged(Float.NaN, 0.42f))
    }
}
