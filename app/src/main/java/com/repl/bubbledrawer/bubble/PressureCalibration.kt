package com.repl.bubbledrawer.bubble

/**
 * 气压重按的**阈值校准**纯逻辑（无 Android 依赖，便于单测）。
 *
 * 为什么需要校准：气压重按靠的是「按压玻璃 → 机身容积变小 → 气压计读数出现正向尖峰」
 * （Boyle 定律），而这个尖峰的幅度取决于机身密封程度、气压计灵敏度、手指用力，**逐台不同**。
 * 参考实现（`com.oxohang.fanfreeform` 的「按压手势」）就是让用户实按 5 次，用实测样本算阈值：
 *
 *  - `FanRuntime.recordPressureCalibrationAttempt(delta)` —— 每次按压记一个「正向气压峰值」；
 *  - `FanRuntime.finishPressureCalibration()` —— 第 5 次后调用 `PressureGesturePolicy.calibrate`；
 *  - `PressureGesturePolicy.calibrate(rawDeltas)` —— 丢掉非正/非有限样本，**有效样本 < 3 个即失败**，
 *    否则排序后取中位数 `median`，阈值 = `(最小值 + median) / 2`。
 *
 * 取「最小值与中位数的中点」而不是中位数本身，是为了让阈值**低于最弱的一次真实按压**：用户按得
 * 最轻的那次也一定能触发，同时仍明显高于静止噪声（±0.05 hPa）。这套规则原样照搬 —— 唯一改动是
 * 加了一层 [MIN_THRESHOLD_HPA]..[MAX_THRESHOLD_HPA] 的夹取，避免一次抖动（走路、开关门、电梯）
 * 把阈值算到 0.02 或 8 hPa 这种必然误触/必然失灵的值域里去。
 */
object PressureCalibration {

    /** 一次校准要完成的按压次数（参考实现固定 5 次：`KEY_PRESSURE_CALIBRATION_ATTEMPTS` 初值 5）。 */
    const val REQUIRED_ATTEMPTS = 5

    /** 有效样本下限：少于 3 个样本中位数没有意义（参考实现同值）。 */
    const val MIN_VALID_SAMPLES = 3

    /** 校准阈值下限：再低就会踩到气压计噪声。 */
    const val MIN_THRESHOLD_HPA = 0.15f

    /** 校准阈值上限：超过这个值只能是「按不动」，等同于功能失效。 */
    const val MAX_THRESHOLD_HPA = 4.0f

    /**
     * 按压分段门限：一次「按压」从 delta 越过它开始、跌回它的 [PRESS_RELEASE_FACTOR] 以下结束。
     *
     * 取 0.08 hPa 是因为静止时气压计帧间噪声约 ±0.02~0.05 hPa —— 门限必须高于噪声，否则一段
     * 停留不动的手势会被切成几十次「按压」；而 0.08 又远低于任何一次真实重按（实测 0.3~1.5 hPa），
     * 所以不会漏记轻按。
     */
    const val PRESS_GATE_HPA = 0.08f

    /** 门限的回落比例：与 [FanPressureDetector] 的翻页判定（`releaseThreshold = threshold * 0.4`）保持一致。 */
    const val PRESS_RELEASE_FACTOR = 0.4f

    /** 校准会话超时（与参考实现 `FanRuntime.schedulePressureCalibrationTimeout` 的 120000L 一致）。 */
    const val SESSION_TIMEOUT_MS = 120_000L

    /** 校准会话的最终结果（存 prefs 的 int，App 侧与 SystemUI 侧共用）。 */
    object Outcome {
        /** 从未校准过（用灵敏度预设的阈值）。 */
        const val NONE = 0

        /** 上一次校准成功，[PressureCalibrationState.calibratedHpa] 可用。 */
        const val OK = 1

        /** 有效样本不足 [MIN_VALID_SAMPLES] 个（气压没有升高 / 按得太轻）。 */
        const val FEW_SAMPLES = 2

        /** 超时未按满 [REQUIRED_ATTEMPTS] 次。 */
        const val TIMEOUT = 3

        /** 本机没有气压计。 */
        const val UNSUPPORTED = 4
    }

    /**
     * @param valid 是否算出了一组可用阈值
     * @param thresholdHpa 阈值（[valid] 为 false 时无意义）
     * @param validCount 参与计算的有效样本数
     * @param clamped 原始计算结果被 [MIN_THRESHOLD_HPA]/[MAX_THRESHOLD_HPA] 夹取过（诊断用）
     */
    data class Result(
        val valid: Boolean,
        val thresholdHpa: Float,
        val validCount: Int,
        val clamped: Boolean = false,
    )

    /**
     * 参考实现 `PressureGesturePolicy.calibrate` 的逐行移植：过滤 → 排序 → 中位数 →
     * `(min + median) / 2`，再夹到可用区间。
     */
    fun calibrate(rawDeltas: List<Float>): Result {
        val valid = rawDeltas.filter { it.isFinite() && it > 0f }.sorted()
        if (valid.size < MIN_VALID_SAMPLES) {
            return Result(valid = false, thresholdHpa = 0f, validCount = valid.size)
        }
        val middle = valid.size / 2
        val median = if (valid.size % 2 == 0) {
            (valid[middle - 1] + valid[middle]) / 2f
        } else {
            valid[middle]
        }
        val raw = (valid.first() + median) / 2f
        if (!raw.isFinite() || raw <= 0f) {
            return Result(valid = false, thresholdHpa = 0f, validCount = valid.size)
        }
        val threshold = raw.coerceIn(MIN_THRESHOLD_HPA, MAX_THRESHOLD_HPA)
        return Result(
            valid = true,
            thresholdHpa = threshold,
            validCount = valid.size,
            clamped = threshold != raw,
        )
    }

    /** 按满 [REQUIRED_ATTEMPTS] 次就结束会话（有效样本会被单独计数，无效的按压同样消耗次数）。 */
    fun shouldFinish(attempts: Int): Boolean = attempts >= REQUIRED_ATTEMPTS

    /**
     * 实际生效的阈值：**校准值优先**，没有校准过才回退到灵敏度预设。
     *
     * 与参考实现的差别：那边把 `pressureCalibrated` 也当作手势总开关的一部分（默认 true），校准
     * 失败/清除会让重按彻底失效；这里不阻断功能 —— 没校准就用预设，校准过就用专属值，用户永远
     * 有一条可用的路径（预设的存在正是为了这个）。
     */
    fun effectiveThreshold(calibrated: Boolean, calibratedHpa: Float, presetHpa: Float): Float =
        if (calibrated && calibratedHpa.isFinite() && calibratedHpa > 0f) calibratedHpa else presetHpa
}
