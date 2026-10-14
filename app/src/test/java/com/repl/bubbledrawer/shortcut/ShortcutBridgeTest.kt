package com.repl.bubbledrawer.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上报分片的切分规则。
 *
 * 这里替换掉的旧规则是「每 25 条一批」，注释还写着「彻底消除 TransactionTooLargeException 隐患」
 * —— 可 Binder 的总缓冲是 1MB，25 个 160px 的 PNG 图标 Base64 之后随手就超，分片照样爆；而爆了
 * 只会被 runCatching 吞掉，App 那边看到的就是「功能页是空的」。所以上限必须按**字节**算，
 * 而分片算术是这件事里唯一不用设备就能测的部分。
 */
class ShortcutBridgeTest {

    @Test
    fun emptyInputHasNoChunks() {
        assertEquals(emptyList<IntRange>(), ShortcutBridge.planChunks(emptyList(), 100))
    }

    @Test
    fun coversEveryIndexExactlyOnceInOrder() {
        val sizes = listOf(10, 20, 30, 40, 50, 60, 70)
        val chunks = ShortcutBridge.planChunks(sizes, 100)
        assertEquals(sizes.indices.toList(), chunks.flatMap { it.toList() })
        for (i in 1 until chunks.size) {
            assertTrue("分片必须连续", chunks[i].first == chunks[i - 1].last + 1)
        }
    }

    @Test
    fun respectsTheBudgetWhenItemsAreSmall() {
        val sizes = List(10) { 30 } // 10 × 30B，预算 100B → 每片最多 3 条
        val chunks = ShortcutBridge.planChunks(sizes, 100)
        assertEquals(4, chunks.size)
        for (chunk in chunks) {
            assertTrue("每片都不能超预算", chunk.sumOf { sizes[it] } <= 100)
        }
    }

    @Test
    fun oneOversizedItemGetsItsOwnChunkInsteadOfKillingTheRest() {
        val sizes = listOf(50, 500, 50)
        val chunks = ShortcutBridge.planChunks(sizes, 100)
        assertEquals(3, chunks.size)
        assertEquals(1, chunks[1].first)
        assertEquals(1, chunks[1].last)
        // 后面的条目仍然照常上报：一条超大图标不该拖死整张清单。
        assertEquals(listOf(0, 1, 2), chunks.flatMap { it.toList() })
    }

    @Test
    fun exactBudgetBoundaryDoesNotSplit() {
        val sizes = listOf(50, 50, 50)
        val chunks = ShortcutBridge.planChunks(sizes, 100)
        assertEquals(2, chunks.size)
        assertEquals(50 + 50, chunks[0].sumOf { sizes[it] })
    }

    @Test
    fun degenerateBudgetStillTerminates() {
        val sizes = List(5) { 10 }
        val chunks = ShortcutBridge.planChunks(sizes, 0)
        assertEquals(5, chunks.size)
        assertEquals(sizes.indices.toList(), chunks.flatMap { it.toList() })
    }

    @Test
    fun scanIdsAreUniqueEvenInsideTheSameMillisecond() {
        val ids = List(1000) { ShortcutBridge.nextScanId(now = 1_000L) }
        assertEquals("同一毫秒内的两次扫描不能共用一个分片会话", 1000, ids.toSet().size)
        // 时钟前进后不会与同一序号的历史会话撞上。
        assertTrue(ids.none { it == ShortcutBridge.nextScanId(now = 1_001L) })
    }
}
