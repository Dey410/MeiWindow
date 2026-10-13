package com.repl.bubbledrawer.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按压分段：门限 0.08 hPa 越过即开始，跌到 0.032 hPa（门限 × 0.4）以下即结束。
 */
class PressurePressTrackerTest {

    private val eps = 1e-4f

    @Test
    fun `a full press reports its peak when the delta falls back`() {
        val tracker = PressurePressTracker()
        assertNull(tracker.onDelta(0.01f))
        assertNull(tracker.onDelta(0.05f))
        assertNull(tracker.onDelta(0.12f)) // 越过门限：开始
        assertTrue(tracker.isPressed)
        assertNull(tracker.onDelta(0.50f)) // 峰值
        assertNull(tracker.onDelta(0.30f))
        assertEquals(0.50f, tracker.onDelta(0.02f)!!, eps) // 回落：结算
        assertFalse(tracker.isPressed)
    }

    @Test
    fun `the peak is the maximum, not the last sample`() {
        val tracker = PressurePressTracker()
        tracker.onDelta(0.10f)
        tracker.onDelta(1.40f)
        tracker.onDelta(0.90f)
        tracker.onDelta(0.60f)
        assertEquals(1.40f, tracker.onDelta(0f)!!, eps)
    }

    @Test
    fun `noise below the gate never starts a press`() {
        val tracker = PressurePressTracker()
        repeat(50) { assertNull(tracker.onDelta(0.07f)) }
        assertFalse(tracker.isPressed)
        assertNull(tracker.flush())
    }

    @Test
    fun `holding the press reports nothing until the stream ends, then flushes once`() {
        val tracker = PressurePressTracker()
        tracker.onDelta(0.20f)
        repeat(30) { assertNull(tracker.onDelta(0.90f)) } // 一直按着
        assertTrue(tracker.isPressed)
        assertEquals(0.90f, tracker.flush()!!, eps)
        // flush 只结算一次
        assertNull(tracker.flush())
    }

    @Test
    fun `two presses inside one stroke are counted separately`() {
        val tracker = PressurePressTracker()
        tracker.onDelta(0.20f)
        tracker.onDelta(0.45f)
        assertEquals(0.45f, tracker.onDelta(0.01f)!!, eps)
        tracker.onDelta(0.30f)
        tracker.onDelta(0.80f)
        assertEquals(0.80f, tracker.onDelta(0.02f)!!, eps)
    }

    @Test
    fun `non finite samples are ignored and do not disturb an ongoing press`() {
        val tracker = PressurePressTracker()
        tracker.onDelta(0.30f)
        assertNull(tracker.onDelta(Float.NaN))
        assertNull(tracker.onDelta(Float.NEGATIVE_INFINITY))
        assertTrue(tracker.isPressed)
        assertEquals(0.30f, tracker.flush()!!, eps)
    }

    @Test
    fun `reset drops the ongoing press without reporting it`() {
        val tracker = PressurePressTracker()
        tracker.onDelta(0.60f)
        tracker.reset()
        assertFalse(tracker.isPressed)
        assertNull(tracker.flush())
    }
}
