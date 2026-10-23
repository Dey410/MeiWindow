package com.repl.bubbledrawer.lightopen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 熔断器的纯逻辑测试（假时钟）。背景见 [FreeformStormGuard] 类注释：
 * 酷安在小窗里自我重启死循环，31 秒弹了上百个小窗（用户 2026-10-23）。
 */
class FreeformStormGuardTest {

    private class FakeClock {
        var now = 0L
        fun tick(ms: Long) {
            now += ms
        }
    }

    private fun guard(clock: FakeClock): FreeformStormGuard = FreeformStormGuard { clock.now }

    /** 正常人手速：2 秒内开不出 5 个小窗 —— 不熔断。 */
    @Test
    fun sparseWindowCreationsNeverTripTheBreaker() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(4) {
            assertFalse(guard.record("com.coolapk.market"))
            clock.tick(600)
        }
        assertFalse(guard.isSuppressing("com.coolapk.market"))
    }

    /** 风暴：2 秒内第 5 个小窗触发熔断，冷却立即生效。 */
    @Test
    fun fiveWindowsInTwoSecondsTripTheBreaker() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(4) {
            assertFalse(guard.record("com.coolapk.market"))
            clock.tick(300)
        }
        assertTrue(guard.record("com.coolapk.market"))
        assertTrue(guard.isSuppressing("com.coolapk.market"))
    }

    /** 冷却期内的事件既不累计也不续期（否则死循环自己能把冷却续到天荒地老），到期自动解除。 */
    @Test
    fun cooldownIgnoresEventsAndExpiresOnItsOwn() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(300)
        }
        assertTrue(guard.isSuppressing("com.coolapk.market"))
        // 冷却期内死循环还在跑：不该把这些算作新事件。
        repeat(20) {
            assertFalse(guard.record("com.coolapk.market"))
            clock.tick(300)
        }
        // 冷却从触发时刻起算，走到期后必然解除。
        clock.tick(FreeformStormGuard.DEFAULT_COOLDOWN_MS)
        assertFalse(guard.isSuppressing("com.coolapk.market"))
    }

    /** 冷却结束后很快又起风暴：冷却放大（15s → 60s），防止"风暴↔间歇"反复横跳。 */
    @Test
    fun quickRetriggerEscalatesTheCooldown() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(300)
        }
        assertTrue(guard.isSuppressing("com.coolapk.market"))
        // 走完 15s 冷却，紧接着又一轮风暴（还在 retriggerMs 窗口里）。
        clock.tick(FreeformStormGuard.DEFAULT_COOLDOWN_MS + 1)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(300)
        }
        assertTrue(guard.isSuppressing("com.coolapk.market"))
        // 放大后的 60s 冷却：过 30s 仍未解除。
        clock.tick(30_000)
        assertTrue(guard.isSuppressing("com.coolapk.market"))
        clock.tick(30_000 + 1)
        assertFalse(guard.isSuppressing("com.coolapk.market"))
    }

    /** 冷却有上限（120s），不会无限翻倍。 */
    @Test
    fun cooldownIsCapped() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(100)
        }
        var guardMs = FreeformStormGuard.DEFAULT_COOLDOWN_MS
        // 连续几轮"冷却一结束就再起风暴"，把冷却推到上限。
        repeat(6) { round ->
            clock.tick(guardMs + 1)
            repeat(5) {
                guard.record("com.coolapk.market")
                clock.tick(100)
            }
            guardMs = (guardMs * FreeformStormGuard.DEFAULT_ESCALATION_FACTOR)
                .coerceAtMost(FreeformStormGuard.DEFAULT_MAX_COOLDOWN_MS)
            assertTrue("round=$round", guard.isSuppressing("com.coolapk.market"))
        }
        // 再触发一轮，时长仍是上限值（不是 480s）：再走 120s 必然解除。
        clock.tick(guardMs + 1)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(100)
        }
        clock.tick(FreeformStormGuard.DEFAULT_MAX_COOLDOWN_MS + 1)
        assertFalse(guard.isSuppressing("com.coolapk.market"))
    }

    /** 安静过一段（retriggerMs 之外）再起风暴：冷却回到基础值，不背历史包袱。 */
    @Test
    fun quietPeriodResetsToBaseCooldown() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(300)
        }
        assertTrue(guard.isSuppressing("com.coolapk.market"))
        // 冷却 + 一段安静期之后再来一轮风暴 —— 应当只冷却基础时长。
        clock.tick(FreeformStormGuard.DEFAULT_COOLDOWN_MS + FreeformStormGuard.DEFAULT_RETRIGGER_MS + 1)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(300)
        }
        assertTrue(guard.isSuppressing("com.coolapk.market"))
        clock.tick(FreeformStormGuard.DEFAULT_COOLDOWN_MS + 1)
        assertFalse(guard.isSuppressing("com.coolapk.market"))
    }

    /** 各包独立：一个应用风暴不影响另一个应用的小窗。 */
    @Test
    fun packagesAreIndependent() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(5) {
            guard.record("com.coolapk.market")
            clock.tick(300)
        }
        assertTrue(guard.isSuppressing("com.coolapk.market"))
        assertFalse(guard.isSuppressing("com.tencent.mobileqq"))
        assertFalse(guard.record("com.tencent.mobileqq"))
    }

    /** 滑动窗口：两秒**前**的事件不算数（4 个 + 隔 3 秒再 4 个 = 都不触发）。 */
    @Test
    fun oldEventsSlideOutOfTheWindow() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(4) {
            assertFalse(guard.record("com.coolapk.market"))
            clock.tick(300)
        }
        clock.tick(FreeformStormGuard.DEFAULT_WINDOW_MS + 1_000)
        repeat(4) {
            assertFalse(guard.record("com.coolapk.market"))
            clock.tick(300)
        }
        assertFalse(guard.isSuppressing("com.coolapk.market"))
    }

    /** 空包名不进统计（钩子解析不出包名时宁可不熔断，也不能误伤全局）。 */
    @Test
    fun emptyPackageNameIsIgnored() {
        val clock = FakeClock()
        val guard = guard(clock)
        repeat(10) {
            assertFalse(guard.record(""))
            clock.tick(50)
        }
        assertFalse(guard.isSuppressing(""))
    }
}
