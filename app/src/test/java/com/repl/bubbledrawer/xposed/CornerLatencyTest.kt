package com.repl.bubbledrawer.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角落手势时延统计 —— 用户反馈「边角屏蔽手势时灵时不灵」要能被量化：
 * 单看最近一笔会被运气误导，p90/max 与超阈值计数才是判据。
 *
 * 真实数据（2026-10-12 用户诊断日志）：正常一笔 `lat=2`，被看门狗挡住的那笔 `lat=15`。
 */
class CornerLatencyTest {

    @Test
    fun aNormalStrokeIsNotSlow() {
        val stats = LatencyStats()

        assertFalse("2ms 是设备实测的正常值", stats.record(2))
        assertEquals(1, stats.count)
        assertEquals(2, stats.max)
        assertEquals(0, stats.slow)
    }

    @Test
    fun theThresholdIsEightMilliseconds() {
        val stats = LatencyStats()

        assertFalse("正好 8ms 还不算慢", stats.record(LatencyStats.SLOW_MS))
        assertTrue("9ms 就已经可能被 launcher 抢先", stats.record(LatencyStats.SLOW_MS + 1))
        assertEquals(2, stats.count)
        assertEquals(1, stats.slow)
        assertEquals(LatencyStats.SLOW_MS + 1, stats.max)
    }

    @Test
    fun percentilesDescribeTheDistribution() {
        val stats = LatencyStats()
        // 19 笔 2ms + 1 笔 15ms：正是"时灵时不灵"的形状。
        repeat(19) { stats.record(2) }
        stats.record(15)

        assertEquals(2, stats.percentile(50))
        assertEquals(2, stats.percentile(90))
        assertEquals(15, stats.percentile(100))
        assertEquals(1, stats.slow)
        assertEquals("n=20 p50=2 p90=2 max=15 slow(>8ms)=1", stats.summary())
    }

    @Test
    fun anEmptyStatsObjectAnswersZero() {
        val stats = LatencyStats()

        assertEquals(0, stats.count)
        assertEquals(0, stats.percentile(50))
        assertEquals(0, stats.max)
        assertEquals("n=0 p50=0 p90=0 max=0 slow(>8ms)=0", stats.summary())
    }

    @Test
    fun theRingKeepsTheNewestSamplesButRemembersTheWorst() {
        val stats = LatencyStats(capacity = 4)
        stats.record(2)
        stats.record(3)
        stats.record(4)
        stats.record(5)
        stats.record(30) // 顶掉最早那个 2ms

        assertEquals("环形只保留最近 4 笔", 4, stats.count)
        assertEquals("最大值不随覆盖清零（它才是「有没有被抢过」的证据）", 30, stats.max)
        assertEquals(1, stats.slow)
        assertEquals("最早那笔 2ms 已经被顶掉，最小剩下 3ms", 3, stats.percentile(0))
    }

    @Test
    fun theReportCadenceIsEveryNStrokes() {
        val stats = LatencyStats()
        repeat(19) { stats.record(2) }

        assertFalse("不到 20 笔不打汇总", stats.dueForReport(20))
        stats.record(2)
        assertTrue("第 20 笔打一次", stats.dueForReport(20))
        assertFalse("刚打过就不再打", stats.dueForReport(20))
        repeat(20) { stats.record(2) }
        assertTrue("再攒 20 笔又打一次", stats.dueForReport(20))
    }

    @Test
    fun resetClearsEverythingForANewSession() {
        val stats = LatencyStats()
        repeat(3) { stats.record(20) }

        stats.reset()

        assertEquals(0, stats.count)
        assertEquals(0, stats.max)
        assertEquals(0, stats.slow)
        assertEquals("n=0 p50=0 p90=0 max=0 slow(>8ms)=0", stats.summary())
    }
}
