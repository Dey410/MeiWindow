package com.repl.bubbledrawer.bubble

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlin.math.hypot

/**
 * 多开应用的「1 / 2」选择气泡（用户 2026-10-28）。
 *
 * 状态、绘制、命中都在这里；什么时候弹、手指事件怎么路由由 [GestureAppLauncher] 决定。
 *
 * **画在 launcher 的 canvas 上，不是子 View**：launcher 的子 View 下标就是 adapter 的下标，命中、
 * 展开 / 收起动画、翻页全靠这一点，往里插别的 View 会全乱。
 *
 * 气泡里的图标用的是缓存图标的**副本**（`constantState.newDrawable().mutate()`）：缓存里那张同时挂在
 * tile 的 ImageView 上，直接改它的 bounds / alpha 会把 tile 一起改花。
 */
internal class InstanceChooser(private val resources: Resources, private val invalidate: () -> Unit) {

    /** 手指此刻在选择区域的哪一块。决定松手时做什么（见 [GestureAppLauncher] 的 finishChooser）。 */
    enum class Zone {
        /** 还在原来那枚 tile 上：松手 = 默认实例。 */
        ANCHOR,
        /** 在某个气泡上：松手 = 那个实例。 */
        BUBBLE,
        /** tile 与气泡之间的过道：松手什么都不开（扇形留着，可以再点）。 */
        GAP,
    }

    var anchorIndex = -1
        private set
    var item: GestureAppLauncher.AdapterItem.AppItem? = null
        private set
    var selected = -1
        private set
    var zone = Zone.ANCHOR
    /** 长按打开的（扇形已经松手、靠点选）：此时触摸直接落在 launcher 上，而不是经 SPY 转发来。 */
    var tapMode = false
        private set

    val isOpen: Boolean get() = anchorIndex != -1

    private var anchorX = 0f
    private var anchorY = 0f
    private var bubbleRadius = 0f
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var icons: List<Drawable?> = emptyList()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x40, 0xFF, 0xFF, 0xFF) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xEE, 0xFF, 0xFF, 0xFF) }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xFF, 0x1F, 0x1F, 0x1F)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tmpBounds = Rect()

    fun open(
        index: Int,
        appItem: GestureAppLauncher.AdapterItem.AppItem,
        anchorCenterX: Float,
        anchorCenterY: Float,
        centersX: FloatArray,
        centersY: FloatArray,
        radiusPx: Float,
        iconOf: (BubbleApp) -> Drawable?,
        byTap: Boolean,
    ) {
        anchorIndex = index
        item = appItem
        anchorX = anchorCenterX
        anchorY = anchorCenterY
        xs = centersX
        ys = centersY
        bubbleRadius = radiusPx
        tapMode = byTap
        selected = -1
        zone = Zone.ANCHOR
        icons = appItem.instances.map { app -> iconOf(app)?.let(::copyOf) }
        ringPaint.strokeWidth = radiusPx * 0.08f
        numberPaint.textSize = radiusPx * 0.5f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MotionSpec.INSTANCE_OPEN_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun close() {
        animator?.cancel()
        animator = null
        anchorIndex = -1
        item = null
        selected = -1
        tapMode = false
        icons = emptyList()
        progress = 0f
        invalidate()
    }

    /** @return 是否换了一个气泡（宿主据此振一下）。 */
    fun select(index: Int): Boolean {
        if (index == selected) return false
        selected = index
        invalidate()
        return index != -1
    }

    /** 手指下的气泡（命中半径放宽到 1.35 倍 —— 划动中的手指不精确）。-1 = 不在任何气泡上。 */
    fun hit(x: Float, y: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in xs.indices) {
            val d = hypot(x - xs[i], y - ys[i])
            if (d <= bubbleRadius * 1.35f && d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    /** 手指是否还在「tile → 气泡」的过道里（离任一气泡不远，或贴着 tile 到某个气泡的连线）。 */
    fun inCorridor(x: Float, y: Float): Boolean {
        for (i in xs.indices) {
            if (hypot(x - xs[i], y - ys[i]) <= bubbleRadius * 2.2f) return true
            if (distanceToSegment(x, y, anchorX, anchorY, xs[i], ys[i]) <= bubbleRadius * 1.3f) return true
        }
        return false
    }

    fun draw(canvas: Canvas) {
        if (!isOpen || progress <= 0f) return
        val alpha = (255 * progress).toInt().coerceIn(0, 255)
        for (i in xs.indices) {
            val chosen = i == selected
            val r = bubbleRadius * (0.6f + 0.4f * progress) * (if (chosen) 1.15f else 1f)
            val cx = xs[i]
            val cy = ys[i]
            backdropPaint.alpha = (0x40 * progress).toInt()
            canvas.drawCircle(cx, cy, r, backdropPaint)
            icons.getOrNull(i)?.let { icon ->
                val half = (r * 0.82f).toInt()
                icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
                icon.alpha = alpha
                icon.draw(canvas)
            }
            if (chosen) {
                ringPaint.alpha = alpha
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
            // 编号角标（右上；分身角标在右下，不打架）
            val bx = cx + r * 0.7f
            val by = cy - r * 0.7f
            val br = r * 0.36f
            badgePaint.alpha = (0xEE * progress).toInt()
            canvas.drawCircle(bx, by, br, badgePaint)
            numberPaint.alpha = alpha
            val label = (i + 1).toString()
            numberPaint.getTextBounds(label, 0, label.length, tmpBounds)
            canvas.drawText(label, bx, by + tmpBounds.height() / 2f, numberPaint)
        }
    }

    private fun copyOf(d: Drawable): Drawable? =
        runCatching { d.constantState?.newDrawable(resources)?.mutate() }.getOrNull()

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
