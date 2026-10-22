package com.repl.bubbledrawer.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 磁贴取色的纯逻辑：与 AppMarket `dominantImageColor` 同一套规则，出错会直接反映成「磁贴底色
 * 发黑/发白/随机」，所以这里把每条过滤和明度归一化都钉住。
 */
class IconColorsTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun redOf(color: Int) = (color ushr 16) and 0xFF
    private fun greenOf(color: Int) = (color ushr 8) and 0xFF
    private fun blueOf(color: Int) = color and 0xFF

    /** HSL 的 L：归一化后必须是 0.45（容差给舍入）。 */
    private fun lightnessOf(color: Int): Float {
        val r = redOf(color) / 255f
        val g = greenOf(color) / 255f
        val b = blueOf(color) / 255f
        return (maxOf(r, g, b) + minOf(r, g, b)) / 2f
    }

    @Test
    fun majorityBucketWins() {
        // 60% 红、40% 蓝：主色必须是红，而不是「平均成紫」。
        val pixels = IntArray(10) { index ->
            if (index < 6) argb(255, 220, 30, 30) else argb(255, 20, 20, 220)
        }
        val color = IconColors.dominantFromPixels(pixels)!!
        assertTrue("red should dominate: ${redOf(color)}", redOf(color) > 150)
        assertTrue(blueOf(color) < 80)
    }

    @Test
    fun transparentNearBlackAndNearWhiteAreIgnored() {
        val pixels = intArrayOf(
            argb(0, 255, 0, 0),      // 全透明
            argb(255, 2, 2, 2),       // 近黑
            argb(255, 250, 250, 250), // 近白
            argb(255, 30, 140, 220),  // 唯一有效：蓝
        )
        val color = IconColors.dominantFromPixels(pixels)!!
        assertTrue(blueOf(color) > redOf(color))
        assertEquals(0.45f, lightnessOf(color), 0.02f)
    }

    @Test
    fun allInvalidPixelsReturnNull() {
        assertNull(IconColors.dominantFromPixels(IntArray(0)))
        assertNull(
            IconColors.dominantFromPixels(
                intArrayOf(argb(0, 255, 0, 0), argb(255, 0, 0, 0), argb(255, 255, 255, 255)),
            ),
        )
    }

    @Test
    fun pureRedIsDarkenedToTargetLightness() {
        val color = IconColors.dominantFromPixels(IntArray(4) { argb(255, 255, 0, 0) })!!
        assertTrue(redOf(color) > 220)
        assertTrue(greenOf(color) <= 2)
        assertTrue(blueOf(color) <= 2)
        // 0.45 明度的纯红 ≈ 230/255。
        assertTrue(abs(redOf(color) - 230) <= 2)
    }

    @Test
    fun grayIsAChromaticAndStillNormalized() {
        val color = IconColors.dominantFromPixels(IntArray(4) { argb(255, 128, 128, 128) })!!
        val r = redOf(color)
        assertEquals(r, greenOf(color))
        assertEquals(r, blueOf(color))
        // 0.45 明度的灰 ≈ 115/255。
        assertTrue(abs(r - 115) <= 2)
    }

    @Test
    fun saturatedBlueKeepsHue() {
        val color = IconColors.dominantFromPixels(IntArray(4) { argb(255, 0, 80, 255) })!!
        assertTrue(blueOf(color) > greenOf(color))
        assertTrue(greenOf(color) > redOf(color))
        assertEquals(0.45f, lightnessOf(color), 0.02f)
    }
}
