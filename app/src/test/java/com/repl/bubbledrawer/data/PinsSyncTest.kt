package com.repl.bubbledrawer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "which 收藏 list wins" decision — the part of the app/SystemUI pin split that can be
 * proven without a device (see PinsSync and PinBackend.readRev).
 */
class PinsSyncTest {

    private val a = "com.a#0"
    private val b = "com.a#0;com.b#0"

    @Test
    fun bothEmptyIsNoPins() {
        val d = PinsSync.decide("", 0, "", 0)
        assertEquals(PinsSync.Source.NONE, d.source)
        assertEquals("", d.value)
        assertFalse(d.pushBack)
    }

    @Test
    fun newerRemoteReplacesStaleLocal() {
        val d = PinsSync.decide(a, 100, b, 200)
        assertEquals(PinsSync.Source.REMOTE, d.source)
        assertEquals(b, d.value)
        assertEquals(200, d.rev)
        assertTrue("loser must be rewritten so the two converge", d.pushBack)
    }

    @Test
    fun newerLocalIsKeptAndPushedBack() {
        // The panel edited pins, the handoff to the app never landed: local is newer.
        val d = PinsSync.decide(b, 300, a, 200)
        assertEquals(PinsSync.Source.LOCAL, d.source)
        assertEquals(b, d.value)
        assertTrue(d.pushBack)
    }

    @Test
    fun legacyUnstampedSidesFallBackToTheSharedBus() {
        // Pre-stamp data: both rev 0 and different → remote (the shared bus) wins, once.
        val d = PinsSync.decide(a, 0, b, 0)
        assertEquals(PinsSync.Source.REMOTE, d.source)
        assertEquals(b, d.value)
        assertTrue(d.pushBack)
    }

    @Test
    fun identicalValuesNeedNoWork() {
        val d = PinsSync.decide(b, 42, b, 42)
        assertEquals(b, d.value)
        assertFalse(d.pushBack)
    }

    @Test
    fun oneSidedValuesPropagate() {
        val remoteOnly = PinsSync.decide("", 0, b, 7)
        assertEquals(PinsSync.Source.REMOTE, remoteOnly.source)
        assertEquals(7, remoteOnly.rev)
        assertTrue(remoteOnly.pushBack)

        val localOnly = PinsSync.decide(b, 7, "", 0)
        assertEquals(PinsSync.Source.LOCAL, localOnly.source)
        assertEquals(7, localOnly.rev)
        assertTrue(localOnly.pushBack)
    }

    @Test
    fun clearingAllPinsIsAValueToo() {
        // "unpin everything" in the app must reach the panel. The stamp is what makes the
        // difference: an empty STAMPED list is an edit, an empty unstamped one is a cold store.
        val cleared = PinsSync.decide("", 500, b, 400)
        assertEquals(PinsSync.Source.LOCAL, cleared.source)
        assertEquals("", cleared.value)
        assertEquals(500, cleared.rev)
        assertTrue(cleared.pushBack)

        // …and the legacy (unstamped) empty side is only "never filled".
        val coldStore = PinsSync.decide("", 0, b, 0)
        assertEquals(PinsSync.Source.REMOTE, coldStore.source)
        assertEquals(b, coldStore.value)
    }

    // ------------------------------------------------------------------ 逐 key 回调（拉锯的现场）

    /**
     * 撕裂的一对：远程共享组是逐 key 镜像的，所以这边可能先看到**新值 + 旧戳**。
     * 这一刻绝不能"把我的旧值推回去"——那正是 2026-10-12 用户报的「收藏自动反复添加/移除」。
     * 规则：戳不小于本地就采纳（值先到、戳后到没关系，戳到了自然收敛）。
     */
    @Test
    fun theTornPairAdoptsAndNeverFights() {
        // 用户刚加了一项（值 = b），戳还没镜像过来（remoteRev == localRev）。
        assertTrue(PinsSync.shouldAdoptOnKeyEvent(remoteRev = 100, localRev = 100))
        // 戳更新：照常采纳。
        assertTrue(PinsSync.shouldAdoptOnKeyEvent(remoteRev = 200, localRev = 100))
        // 远端确实更旧：什么都不做（不采纳、也**不回推** —— 回推就是拉锯）。
        assertFalse(PinsSync.shouldAdoptOnKeyEvent(remoteRev = 50, localRev = 100))
    }

    @Test
    fun theOscillationStopsAfterTwoEvents() {
        // 模拟：本地旧值旧戳 (a,100) ← 事件1 新值旧戳 (b,100) ← 事件2 新值新戳 (b,110)。
        var localValue = a
        var localRev = 100L
        for ((value, rev) in listOf(b to 100L, b to 110L)) {
            if (PinsSync.shouldAdoptOnKeyEvent(rev, localRev)) {
                localValue = value
                localRev = rev
            }
        }
        assertEquals("拉锯最多两次事件就收敛，而且第二次之后再也不会有回推", b, localValue)
        assertEquals(110L, localRev)
    }

    /**
     * 对面主动推过来的一份：旧于本地的直接丢，绝不回推。
     * （真正"我的落后了"的场景由对面的读路径修复，那里两个 key 都已落定。）
     */
    @Test
    fun aPushWithAnOlderStampIsDroppedQuietly() {
        assertFalse(PinsSync.shouldApplyPush(rev = 50, localRev = 100))
        assertTrue(PinsSync.shouldApplyPush(rev = 100, localRev = 100))
        assertTrue(PinsSync.shouldApplyPush(rev = 200, localRev = 100))
        // rev 缺失 = 更老模块版本的投递，只能收下。
        assertTrue(PinsSync.shouldApplyPush(rev = 0, localRev = 100))
    }
}
