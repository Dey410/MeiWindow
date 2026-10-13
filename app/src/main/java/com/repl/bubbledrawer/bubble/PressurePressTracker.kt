package com.repl.bubbledrawer.bubble

/**
 * 把连续的气压差值（相对本次手势基线）切成一次次的「按压」，并给出每一次的峰值。
 *
 * 为什么需要它：参考实现的校准是「点一次算一次」—— 手指 DOWN 时记基线、UP 时结算峰值
 * （`FanRuntime.handlePressureCalibrationMotion`）：它的触发圆分布在整块屏幕上，每次按压天然是
 * 一次独立触摸。本模块的重按只存在于**扇形展开期间**，而划出扇形本身就是一条持续按住的手势
 * （DOWN 在屏幕下角 → 滑出扇形 → 手指仍按着）。所以「一次按压」不能再用 DOWN/UP 划分，只能用
 * 气压曲线本身划分：越过门限即开始、跌回门限的 [PressureCalibration.PRESS_RELEASE_FACTOR] 以下
 * 即结束。这样一次划出里连续重按 5 次也能全部记上，用户不必来回划 5 次。
 *
 * 纯逻辑、无 Android 依赖：`OnSensorChanged` 的线程与主线程都会调它，但内部只做标量比较。
 */
class PressurePressTracker(
    private val gateHpa: Float = PressureCalibration.PRESS_GATE_HPA,
    private val releaseFactor: Float = PressureCalibration.PRESS_RELEASE_FACTOR,
) {
    /** 当前是否处在一次按压之中（峰值还在累积）。 */
    var isPressed: Boolean = false
        private set

    private var peak = 0f

    fun reset() {
        isPressed = false
        peak = 0f
    }

    /**
     * 喂一帧差值。
     *
     * @return 这一次按压**刚刚结束**时它的峰值（hPa）；还在按着或没越过门限时为 null
     */
    fun onDelta(delta: Float): Float? {
        if (!delta.isFinite()) return null
        if (!isPressed) {
            if (delta < gateHpa) return null
            isPressed = true
            peak = delta
            return null
        }
        if (delta > peak) peak = delta
        if (delta <= gateHpa * releaseFactor) {
            isPressed = false
            val done = peak
            peak = 0f
            return done
        }
        return null
    }

    /**
     * 手势结束（手指抬起 / SPY 流被系统掐断）：把还按着的那一次结算掉。
     *
     * 没有这一手，「按住不放再抬手」的按压永远等不到回落，会被整段丢掉 —— 而抬手正是最常见的
     * 收尾方式。
     */
    fun flush(): Float? {
        if (!isPressed) return null
        isPressed = false
        val done = peak
        peak = 0f
        return done
    }
}
