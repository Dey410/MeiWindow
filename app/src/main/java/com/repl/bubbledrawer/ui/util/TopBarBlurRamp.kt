package com.repl.bubbledrawer.ui.util

import kotlin.math.pow

/**
 * miuix-blur 0.9.4-rc01 里渐进模糊的两条权重曲线，逐行抄自库里的 AGSL 着色器
 * （`ShadersKt.buildProgressiveBlurShader` 与 `ShadersKt.buildBlendModeShader`）。
 *
 * 抄出来的目的只有一个：让「模糊带有多高」这件事能被单测盯住，而不是只能靠眼睛判断。
 * 库里的实现是运行时着色器，App 侧改不了它，能改的只有 [TopBarBlurConfig] 那几个
 * 常数 —— 这里算出改完之后长什么样。
 *
 * [fraction] 是沿渐变方向的归一化位置：0 = 栏顶（模糊最强端），1 = 栏底。
 */
object TopBarBlurRamp {

    /** 模糊半径系数：`radius = maxRadius * blurRadiusFactor(fraction, curve)`。 */
    fun blurRadiusFactor(fraction: Float, curve: Float): Float =
        1f - smoothstep(fraction).pow(curve)

    /**
     * 混色的权重：库把 `blurColors` 按这个权重混进模糊结果
     * （`mix(src, tinted, rampWeight)`），用的是同一条 band + curve，
     * 所以颜色和模糊同进同退，栏底不会剩一道色边。
     */
    fun tintWeight(fraction: Float, curve: Float): Float =
        smoothstep(1f - smoothstep(fraction).pow(curve))

    /** 着色器里的 `x²(3-2x)`。 */
    fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
