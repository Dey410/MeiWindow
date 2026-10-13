package com.repl.bubbledrawer.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校准状态机：事件到达顺序决定页面看到什么，这里把每种顺序都钉死。 */
class PressureCalibrationStateTest {

    private val eps = 1e-4f

    private fun running() = PressureCalibrationState(
        active = true,
        calibrated = true,
        calibratedHpa = 1.05f,
        validCount = 3,
        lastDeltaHpa = 0.42f,
        lastValid = true,
        outcome = PressureCalibration.Outcome.OK,
    )

    @Test
    fun `starting a new session resets the progress but keeps the previous result`() {
        val next = PressureCalibrationState.applyEvent(running(), PressureCalibrationState.Stage.STARTED)
        assertTrue(next.active)
        assertEquals(0, next.attempts)
        assertEquals(0f, next.lastDeltaHpa, eps)
        assertFalse(next.lastValid)
        assertEquals(PressureCalibration.Outcome.NONE, next.outcome)
        // 关键：重新校准期间旧阈值必须继续可用
        assertTrue(next.calibrated)
        assertEquals(1.05f, next.calibratedHpa, eps)
        // 样本数也不清：它是「上一次成功校准的结果」，页面就靠它说「已校准：N 个有效样本」。
        // 清了就会出现「已校准：0 个有效样本」这种自相矛盾的话（点开始再点取消就能看到）。
        assertEquals(3, next.validCount)
    }

    @Test
    fun `starting and then cancelling a recalibration still shows the last result with its sample count`() {
        val calibrated = PressureCalibrationState.applyEvent(
            PressureCalibrationState(),
            PressureCalibrationState.Stage.RESULT,
            valid = true,
            threshold = 0.365f,
            validCount = 5,
        )
        val restarted = PressureCalibrationState.applyEvent(calibrated, PressureCalibrationState.Stage.STARTED)
        val cancelled = restarted.copy(active = false) // SettingsStore.cancelPressureCalibration 只写这一个字段
        assertTrue(cancelled.calibrated)
        assertEquals(0.365f, cancelled.calibratedHpa, eps)
        assertEquals(5, cancelled.validCount)
    }

    @Test
    fun `a sample updates the progress while the session is running`() {
        val next = PressureCalibrationState.applyEvent(
            running(),
            PressureCalibrationState.Stage.SAMPLE,
            attempts = 3,
            delta = 0.63f,
            valid = true,
            validCount = 2,
        )
        assertTrue(next.active)
        assertEquals(3, next.attempts)
        assertEquals(2, next.validCount)
        assertEquals(0.63f, next.lastDeltaHpa, eps)
        assertTrue(next.lastValid)
    }

    @Test
    fun `a sample arriving after the session ended is dropped`() {
        val finished = PressureCalibrationState.applyEvent(
            running(),
            PressureCalibrationState.Stage.RESULT,
            valid = true,
            threshold = 0.42f,
            validCount = 5,
        )
        val late = PressureCalibrationState.applyEvent(
            finished,
            PressureCalibrationState.Stage.SAMPLE,
            attempts = 2,
            delta = 0.3f,
            valid = true,
            validCount = 2,
        )
        assertEquals(finished, late)
        assertFalse(late.active)
    }

    @Test
    fun `a valid result stores the threshold and ends the session`() {
        val next = PressureCalibrationState.applyEvent(
            running(),
            PressureCalibrationState.Stage.RESULT,
            valid = true,
            threshold = 0.3721f,
            validCount = 5,
        )
        assertFalse(next.active)
        assertTrue(next.calibrated)
        assertEquals(0.3721f, next.calibratedHpa, eps)
        assertEquals(5, next.validCount)
        assertEquals(PressureCalibration.Outcome.OK, next.outcome)
    }

    @Test
    fun `a failed result keeps the previous threshold and only reports the failure`() {
        val next = PressureCalibrationState.applyEvent(
            running(),
            PressureCalibrationState.Stage.RESULT,
            valid = false,
            threshold = 0f,
            validCount = 1,
        )
        assertFalse(next.active)
        assertTrue(next.calibrated)
        assertEquals(1.05f, next.calibratedHpa, eps)
        assertEquals(PressureCalibration.Outcome.FEW_SAMPLES, next.outcome)
    }

    @Test
    fun `a failed result with nothing calibrated before stays uncalibrated`() {
        val next = PressureCalibrationState.applyEvent(
            PressureCalibrationState(),
            PressureCalibrationState.Stage.RESULT,
            valid = false,
        )
        assertFalse(next.active)
        assertFalse(next.calibrated)
        assertEquals(0f, next.calibratedHpa, eps)
        assertEquals(PressureCalibration.Outcome.FEW_SAMPLES, next.outcome)
    }

    @Test
    fun `timeout and unsupported only end the session`() {
        val timedOut = PressureCalibrationState.applyEvent(running(), PressureCalibrationState.Stage.TIMEOUT)
        assertFalse(timedOut.active)
        assertTrue(timedOut.calibrated)
        assertEquals(PressureCalibration.Outcome.TIMEOUT, timedOut.outcome)

        val unsupported = PressureCalibrationState.applyEvent(running(), PressureCalibrationState.Stage.UNSUPPORTED)
        assertFalse(unsupported.active)
        assertEquals(PressureCalibration.Outcome.UNSUPPORTED, unsupported.outcome)
        assertEquals(1.05f, unsupported.calibratedHpa, eps)
    }

    @Test
    fun `an unknown stage changes nothing`() {
        val state = running()
        assertEquals(state, PressureCalibrationState.applyEvent(state, "something_new"))
    }

    @Test
    fun `a whole five press session ends calibrated at the computed threshold`() {
        var state = PressureCalibrationState.applyEvent(
            PressureCalibrationState(),
            PressureCalibrationState.Stage.STARTED,
        )
        val samples = listOf(0.31f, 0.42f, 0.55f, 0.38f, 0.61f)
        samples.forEachIndexed { index, delta ->
            state = PressureCalibrationState.applyEvent(
                state,
                PressureCalibrationState.Stage.SAMPLE,
                attempts = index + 1,
                delta = delta,
                valid = true,
                validCount = index + 1,
            )
        }
        assertEquals(5, state.attempts)
        assertTrue(state.active)

        val result = PressureCalibration.calibrate(samples)
        state = PressureCalibrationState.applyEvent(
            state,
            PressureCalibrationState.Stage.RESULT,
            valid = result.valid,
            threshold = result.thresholdHpa,
            validCount = result.validCount,
        )
        // 排序后 [0.31, 0.38, 0.42, 0.55, 0.61] → 中位数 0.42 → (0.31 + 0.42) / 2
        assertEquals(0.365f, state.calibratedHpa, eps)
        assertFalse(state.active)
        assertEquals(PressureCalibration.Outcome.OK, state.outcome)
    }
}
