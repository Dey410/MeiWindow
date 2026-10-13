package com.repl.bubbledrawer.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校准算法照抄参考实现（`PressureGesturePolicy.calibrate`），所以这里的每个期望值都能手算复核：
 * 阈值 = (最小样本 + 中位数) / 2，再夹到 [0.15, 4.0] hPa。
 */
class PressureCalibrationTest {

    private val eps = 1e-4f

    @Test
    fun `median of five samples then midpoint with the smallest`() {
        val result = PressureCalibration.calibrate(listOf(1.8f, 1.0f, 1.4f, 1.2f, 1.6f))
        assertTrue(result.valid)
        assertEquals(5, result.validCount)
        // 排序后 [1.0, 1.2, 1.4, 1.6, 1.8] → 中位数 1.4 → (1.0 + 1.4) / 2
        assertEquals(1.2f, result.thresholdHpa, eps)
        assertFalse(result.clamped)
    }

    @Test
    fun `even sample count averages the two middle values`() {
        val result = PressureCalibration.calibrate(listOf(0.7f, 0.4f, 0.6f, 0.5f))
        assertTrue(result.valid)
        assertEquals(4, result.validCount)
        // 中位数 (0.5 + 0.6) / 2 = 0.55 → (0.4 + 0.55) / 2
        assertEquals(0.475f, result.thresholdHpa, eps)
    }

    @Test
    fun `non positive and non finite samples are discarded before counting`() {
        val result = PressureCalibration.calibrate(
            listOf(0.5f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 0f, 0.7f, 0.9f),
        )
        assertTrue(result.valid)
        assertEquals(3, result.validCount)
        // [0.5, 0.7, 0.9] → 中位数 0.7 → (0.5 + 0.7) / 2
        assertEquals(0.6f, result.thresholdHpa, eps)
    }

    @Test
    fun `fewer than three valid samples is a failure`() {
        val two = PressureCalibration.calibrate(listOf(0.5f, 0.6f))
        assertFalse(two.valid)
        assertEquals(2, two.validCount)
        assertEquals(0f, two.thresholdHpa, eps)

        val none = PressureCalibration.calibrate(emptyList())
        assertFalse(none.valid)
        assertEquals(0, none.validCount)

        // 三个样本的边界是有效的
        val three = PressureCalibration.calibrate(listOf(0.5f, 0.6f, 0.7f))
        assertTrue(three.valid)
    }

    @Test
    fun `a too small threshold is clamped up to the usable floor`() {
        val result = PressureCalibration.calibrate(listOf(0.02f, 0.03f, 0.04f))
        assertTrue(result.valid)
        assertTrue(result.clamped)
        // 原始值 (0.02 + 0.03) / 2 = 0.025 —— 低于噪声，必须抬到下限
        assertEquals(PressureCalibration.MIN_THRESHOLD_HPA, result.thresholdHpa, eps)
    }

    @Test
    fun `an absurdly large threshold is clamped down`() {
        val result = PressureCalibration.calibrate(listOf(3.0f, 6.0f, 9.0f))
        assertTrue(result.valid)
        assertTrue(result.clamped)
        assertEquals(PressureCalibration.MAX_THRESHOLD_HPA, result.thresholdHpa, eps)
    }

    @Test
    fun `the session finishes exactly at the fifth press`() {
        assertFalse(PressureCalibration.shouldFinish(0))
        assertFalse(PressureCalibration.shouldFinish(4))
        assertTrue(PressureCalibration.shouldFinish(5))
        assertTrue(PressureCalibration.shouldFinish(6))
        assertEquals(5, PressureCalibration.REQUIRED_ATTEMPTS)
    }

    /**
     * 有效样本下限：越过 0.08 的分段门限**不算**样本（那只是"切出一次按压"）。没有这一层，
     * 五次刚过门限的轻按也会算「校准成功」，`(min+median)/2` 把阈值钉到 0.2 以下 —— 噪声就能翻页。
     */
    @Test
    fun `only a solid enough press counts as a sample`() {
        assertFalse(PressureCalibration.isUsableSample(0.08f))
        assertFalse(PressureCalibration.isUsableSample(0.19f))
        assertTrue(PressureCalibration.isUsableSample(PressureCalibration.MIN_SAMPLE_HPA))
        assertTrue(PressureCalibration.isUsableSample(0.42f))
        assertFalse(PressureCalibration.isUsableSample(Float.NaN))
        assertFalse(PressureCalibration.isUsableSample(-1f))
    }

    @Test
    fun `samples that only just clear the gate cannot drag the threshold into the noise floor`() {
        // 五次「刚过门限」的按压：在旧的 valid = delta > 0 规则下全算有效，阈值被夹到 0.15；
        // 有了下限它们一个都不是样本，于是退回「有效样本不足」，用户仍用灵敏度预设。
        val gateOnly = listOf(0.08f, 0.09f, 0.10f, 0.11f, 0.12f)
            .filter { PressureCalibration.isUsableSample(it) }
        assertTrue(gateOnly.isEmpty())
        assertFalse(PressureCalibration.calibrate(gateOnly).valid)
        // 真按下去的样本仍然算得出阈值
        val real = listOf(0.31f, 0.42f, 0.55f, 0.38f, 0.61f)
            .filter { PressureCalibration.isUsableSample(it) }
        assertEquals(5, real.size)
        assertTrue(PressureCalibration.calibrate(real).thresholdHpa >= PressureCalibration.MIN_SAMPLE_HPA)
    }

    @Test
    fun `the calibrated threshold wins over the sensitivity preset`() {
        assertEquals(
            0.55f,
            PressureCalibration.effectiveThreshold(calibrated = true, calibratedHpa = 0.55f, presetHpa = 1.2f),
            eps,
        )
        assertEquals(
            1.2f,
            PressureCalibration.effectiveThreshold(calibrated = false, calibratedHpa = 0.55f, presetHpa = 1.2f),
            eps,
        )
        // 已校准但值不可用（旧版本留下的 0 / NaN）→ 仍然回退到预设，绝不返回一个不能用的阈值
        assertEquals(
            1.2f,
            PressureCalibration.effectiveThreshold(calibrated = true, calibratedHpa = 0f, presetHpa = 1.2f),
            eps,
        )
        assertEquals(
            0.8f,
            PressureCalibration.effectiveThreshold(calibrated = true, calibratedHpa = Float.NaN, presetHpa = 0.8f),
            eps,
        )
    }
}
