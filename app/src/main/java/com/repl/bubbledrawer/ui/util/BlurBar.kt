package com.repl.bubbledrawer.ui.util

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏渐进模糊（Progressive Blur）统一参数，三个数字都对齐 HyperMusicCover
 * （`ui/util/BlurUtils.kt`，同一套 miuix-blur 0.9.4-rc01）。
 *
 * **`GradientCurve` 决定模糊带看起来有多高，不要凭手感调小。**
 * 库里 `curve` 不是「渐变形状」而是**模糊半径的衰减指数**，公式就在 AGSL 着色器里
 * （`buildProgressiveBlurShader`，从 aar 反编译核对）：
 *
 *     raw       = clamp((p - band.x) / (band.y - band.x), 0, 1)   // 沿渐变方向的归一化位置，0 在栏顶
 *     intensity = 1 - pow(smoothstep(raw), curve)                 // smoothstep(x) = x²(3-2x)
 *     radius    = maxRadius * intensity
 *
 * 也就是：curve 越大，半径越「扛住」到底部才掉。按栏高的比例算半径系数：
 *
 *     raw       0.50    0.75    0.90
 *     curve=10  1.00    0.82    0.25     ← 参考实现（栏顶到 ~80% 高度都是满强度）
 *     curve=2   0.75    0.29    0.06     ← 2026-10 那次改动，栏底一半就糊不动了，看着「矮一截」
 *
 * 柔和的尾巴不是靠 alpha 淡出做的：半径掉到 0 时着色器直接返回**未模糊**的 backdrop
 * （`if (radius <= 0.0)` 那条分支，且输出 alpha 恒为 1），所以只要 curve 够大，栏底
 * 自然收干净、没有硬分割线。见 [TopBarBlurRamp] 与 `TopBarBlurTest`。
 */
object TopBarBlurConfig {
    /** 模糊半径（dp），模糊最强处的强度 */
    const val BlurRadius: Float = 15f

    /**
     * 渐变曲线指数：1 = 库默认（线性衰减）；>1 让半径更靠后才衰减。
     * 10 = 参考实现的值，改小之前先看类注释里的那张表。
     */
    const val GradientCurve: Float = 10f

    /**
     * 顶栏 surface 的混合透明度（0~1），越大栏越实。
     * 注意它交给库里的 blend（`BlurDefaults.blurColors`）生效，**不是**盖一层 background：
     * 库会用与半径同一条 band + curve 的权重把颜色混进去（`buildBlendModeShader` 的
     * `rampWeight`），所以颜色和模糊一起在栏底收到 0，不会在栏底留下一道色边。
     */
    const val SurfaceAlpha: Float = 0.3f

    /**
     * 滚动渐显距离（dp）：内容下滑该距离内，模糊从透明渐显到完整。
     * 0 = 顶栏常驻完整模糊（推荐，各页面顶部即可见模糊）。
     */
    val ScrollFadeDistance: Dp = 0.dp
}

@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** true when [backdrop] is actually usable; call once at the top of a screen. */
fun blurActive(backdrop: LayerBackdrop?): Boolean = backdrop != null

/** Bar background color for the current mode: transparent lets the blur show through. */
@Composable
fun barColor(active: Boolean): Color =
    if (active) Color.Transparent else MiuixTheme.colorScheme.surface

/**
 * Wrap a TopAppBar / PanelBar so the bar itself blurs what the content layer recorded.
 *
 * 只有这一层，**不要**再叠 `drawWithContent` + `DstIn` 的 alpha 遮罩，也不要再盖一层
 * 渐变 background：
 *  - 模糊着色器输出的 alpha 恒为 1，渐变靠半径衰减完成，再乘一次 alpha 等于把模糊带
 *    从中间就切淡，视觉上直接变矮（2026-10 的回归就是这么来的）；
 *  - 颜色走 `colors = BlurDefaults.blurColors(...)`，库自己会按同一条曲线收掉，
 *    手动盖 background 会和它叠成两层、且收不到底。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean = true,
    scrollBehavior: ScrollBehavior? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val blurActive = blurEnabled && backdrop != null
    val scrollFadePx = with(LocalDensity.current) { TopBarBlurConfig.ScrollFadeDistance.toPx() }
    Box(modifier = modifier) {
        if (blurActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (scrollFadePx > 0f) {
                            scrollBehavior?.state
                                ?.let { (-it.contentOffset / scrollFadePx).coerceIn(0f, 1f) }
                                ?: 1f
                        } else {
                            1f
                        }
                    }
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = TopBarBlurConfig.GradientCurve),
                        blurRadius = TopBarBlurConfig.BlurRadius,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = MiuixTheme.colorScheme.surface
                                        .copy(TopBarBlurConfig.SurfaceAlpha),
                                ),
                            ),
                        ),
                    ),
            )
        }
        content()
    }
}

/**
 * The content container of a page: registers its draw output as the backdrop the bar
 * samples.
 */
@Composable
fun Modifier.pageBackdrop(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) this.layerBackdrop(backdrop) else this
