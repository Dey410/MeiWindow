package com.repl.bubbledrawer.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 应用图标取色 —— 移植自 AppMarket 的 `ui/util/ImageColors.kt`（`dominantImageColor`）。
 *
 * 原实现是给首页封面图取「主色 + 统一明度」的，逻辑本身与图片内容无关，所以这里原样搬到
 * 图标位图上：磁贴底色用它，图标再叠在上面。
 *
 * 规则（与 AppMarket 逐条一致）：
 *  1. 24×24 采样（576 个点），整图缩一次，别逐像素读原图；
 *  2. 跳过 alpha < 128、最亮通道 < 24（近黑）、最暗通道 > 240（近白）的像素；
 *  3. RGB 各取高 4 位做桶，取像素最多的桶求平均色；
 *  4. 转 HSL 后把 L 固定到 0.45（保留色相/饱和度）再转回 RGB —— 每个磁贴底色都是中等明度，
 *     不会有的刺眼、有的发黑。
 *
 * 为什么不用 AndroidX `Palette`：它要额外依赖、默认按「视觉显著性」而不是像素数选色，且
 * 明度不统一；这里要的是「同一套规则、跨进程结果一致」，所以纯算术最省事，也能在 JVM 单测里
 * 直接跑（[dominantFromPixels] 不碰 Android 类）。
 */
object IconColors {

    /** 采样网格边长：AppMarket 用的就是 24（576 个像素，够稳且几乎不花时间）。 */
    const val SAMPLE_SIZE = 24

    /** 统一后的 HSL 明度（AppMarket `normalizeLightness` 里的 0.45）。 */
    private const val TARGET_LIGHTNESS = 0.45f

    /**
     * 位图版：先缩到 [SAMPLE_SIZE]²，再走纯函数。
     *
     * 缩放必须用 `FILTER_BITMAP_FLAG`：最近邻采样会让小图标里的细线条整片丢失，主色跟着偏。
     * 返回 ARGB（alpha 恒为 FF）；取不到可用颜色返回 null（调用方回退主题色）。
     */
    fun dominant(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val size = SAMPLE_SIZE
        val sample = runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Canvas(sample).drawBitmap(bitmap, null, Rect(0, 0, size, size), paint)
        val pixels = IntArray(size * size)
        val readOk = runCatching { sample.getPixels(pixels, 0, size, 0, 0, size, size) }.isSuccess
        if (!sample.isRecycled) sample.recycle()
        if (!readOk) return null
        return dominantFromPixels(pixels)
    }

    /**
     * 纯函数：从 ARGB 像素里挑主色并统一明度。空/全透明/全近黑/全近白都返回 null。
     *
     * 桶的 key 与 AppMarket 相同：`(r and 0xF0) shl 4 or (g and 0xF0) or (b shr 4)`。
     * 注意 `shl 4` 之后 r 的高 4 位落在 bit 8..11，g 落在 bit 4..7，b 的高 4 位落在 bit 0..3。
     */
    fun dominantFromPixels(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val buckets = HashMap<Int, Bucket>()
        for (argb in pixels) {
            val alpha = (argb ushr 24) and 0xFF
            val red = (argb ushr 16) and 0xFF
            val green = (argb ushr 8) and 0xFF
            val blue = argb and 0xFF
            if (alpha < 128) continue
            val maxChannel = max(red, max(green, blue))
            val minChannel = min(red, min(green, blue))
            if (maxChannel < 24 || minChannel > 240) continue
            val key = ((red and 0xF0) shl 4) or (green and 0xF0) or (blue shr 4)
            buckets.getOrPut(key) { Bucket() }.add(red, green, blue)
        }
        val bucket = buckets.values.maxByOrNull { it.count } ?: return null
        val average = floatArrayOf(
            bucket.red / bucket.count.toFloat() / 255f,
            bucket.green / bucket.count.toFloat() / 255f,
            bucket.blue / bucket.count.toFloat() / 255f,
        )
        return normalizeLightness(average).toArgb()
    }

    /**
     * HSL 明度固定到 [TARGET_LIGHTNESS]，色相/饱和度保持不变。
     *
     * 用 HSL 而不是「按比例缩放 RGB」：后者会连饱和度一起压下去，深色图标取出来会发灰。
     */
    private fun normalizeLightness(rgb: FloatArray): FloatArray {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val delta = maxChannel - minChannel
        val lightness = (maxChannel + minChannel) / 2f
        val saturation = if (delta == 0f) {
            0f
        } else {
            delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-6f)
        }
        val hue = when {
            delta == 0f -> 0f
            maxChannel == r -> 60f * (((g - b) / delta) % 6f)
            maxChannel == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val chroma = (1f - abs(2f * TARGET_LIGHTNESS - 1f)) * saturation
        val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val (r1, g1, b1) = when (hue) {
            in 0f..<60f -> Triple(chroma, x, 0f)
            in 60f..<120f -> Triple(x, chroma, 0f)
            in 120f..<180f -> Triple(0f, chroma, x)
            in 180f..<240f -> Triple(0f, x, chroma)
            in 240f..<300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val match = TARGET_LIGHTNESS - chroma / 2f
        return floatArrayOf(
            (r1 + match).coerceIn(0f, 1f),
            (g1 + match).coerceIn(0f, 1f),
            (b1 + match).coerceIn(0f, 1f),
        )
    }

    private fun FloatArray.toArgb(): Int {
        val red = (this[0] * 255f).roundToInt().coerceIn(0, 255)
        val green = (this[1] * 255f).roundToInt().coerceIn(0, 255)
        val blue = (this[2] * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private class Bucket {
        var count = 0
        var red = 0
        var green = 0
        var blue = 0

        fun add(r: Int, g: Int, b: Int) {
            count++
            red += r
            green += g
            blue += b
        }
    }
}
