package com.repl.bubbledrawer.bubble

import com.repl.bubbledrawer.bubble.GestureAppLauncher.AdapterItem
import com.repl.bubbledrawer.pinyin.BubbleApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多开应用合成一枚 tile 的规则。默认实例「跟收藏顺序走」是用户 2026-10-28 定的：
 * 收藏里先放哪个就默认哪个，推荐里排名高的那个是默认。
 */
class FanInstancesTest {

    private fun app(pkg: String, user: Int = 0) = BubbleApp(pkg, pkg, userId = user)
    private fun item(pkg: String, user: Int = 0) = AdapterItem.AppItem(app(pkg, user))

    private val all = listOf(
        app("wechat"), app("wechat", 999),
        app("qq"), app("qq", 999),
        app("maps"),
    )
    private val grouped = FanInstances.instancesByPackage(all)

    private fun AdapterItem.key(): String = when (this) {
        is AdapterItem.AppItem -> "${app.packageName}#${app.userId}" + if (isMulti) "*${instances.size}" else ""
        is AdapterItem.FunctionItem -> "fn"
        AdapterItem.More -> "more"
    }

    @Test
    fun onlyPackagesWithSeveralInstancesAreGrouped() {
        assertEquals(setOf("wechat", "qq"), grouped.keys)
        assertEquals(listOf(0, 999), grouped.getValue("wechat").map { it.userId })
    }

    @Test
    fun numberingPutsTheCloneSecondEvenWithAWorkProfile() {
        val grouped = FanInstances.instancesByPackage(listOf(app("mail", 10), app("mail", 999), app("mail")))
        assertEquals(listOf(0, 999, 10), grouped.getValue("mail").map { it.userId })
    }

    @Test
    fun pinnedCloneOnlyMakesTheCloneTheDefault() {
        val merged = FanInstances.merge(listOf(item("wechat", 999)), emptyList(), grouped)
        assertEquals(listOf("wechat#999*2"), merged.pinned.map { it.key() })
    }

    @Test
    fun bothInstancesPinnedMergeAtTheFirstPositionWithTheFirstPinnedAsDefault() {
        val pinned = listOf(item("maps"), item("wechat", 999), item("qq"), item("wechat"))
        val merged = FanInstances.merge(pinned, emptyList(), grouped)
        assertEquals(listOf("maps#0", "wechat#999*2", "qq#0*2"), merged.pinned.map { it.key() })
    }

    @Test
    fun recommendationsUseTheHigherRankedInstanceAndSkipPinnedPackages() {
        val pinned = listOf(item("wechat"))
        val recommended = listOf(item("wechat", 999), item("qq", 999), item("maps"), item("qq"))
        val merged = FanInstances.merge(pinned, recommended, grouped)
        assertEquals(listOf("wechat#0*2"), merged.pinned.map { it.key() })
        assertEquals(listOf("qq#999*2", "maps#0"), merged.recommended.map { it.key() })
    }

    @Test
    fun singleInstanceAppsAndNonAppTilesAreUntouched() {
        val pinned = listOf(item("maps"), AdapterItem.More)
        val merged = FanInstances.merge(pinned, listOf(item("maps")), emptyMap())
        assertEquals(pinned, merged.pinned)
        assertEquals(listOf("maps#0"), merged.recommended.map { it.key() })
    }

    @Test
    fun aDefaultMissingFromTheAppListIsStillOffered() {
        // 应用列表是旧的（开机锁定时枚举的），里面只有主空间那份；收藏的是分身。
        val stale = mapOf("wechat" to listOf(app("wechat"), app("wechat", 10)))
        val merged = FanInstances.merge(listOf(item("wechat", 999)), emptyList(), stale)
        val tile = merged.pinned.single() as AdapterItem.AppItem
        assertEquals(999, tile.app.userId)
        assertEquals(listOf(0, 999, 10), tile.instances.map { it.userId })
    }

    // ---------------- bubble placement ----------------

    private val radius = 900f
    private val bubble = 60f

    private fun assertInsideScreenEdges(angles: FloatArray) {
        val margin = Math.toDegrees(kotlin.math.asin(((bubble + 12f) / radius).toDouble())).toFloat()
        angles.forEach { assertTrue("angle $it too close to an edge", it >= margin - 0.01f && it <= 90f - margin + 0.01f) }
    }

    @Test
    fun bubblesCenterOnTheTileAndDoNotOverlap() {
        val angles = FanInstances.bubbleAngles(45f, 2, radius, bubble, gapPx = 20f, edgeMarginPx = 12f)
        assertEquals(45f, (angles[0] + angles[1]) / 2f, 0.01f)
        val arc = Math.toRadians((angles[1] - angles[0]).toDouble()) * radius
        assertTrue("bubbles overlap: arc=$arc", arc >= 2 * bubble + 20f - 0.5f)
    }

    @Test
    fun bubblesNearAScreenEdgeShiftInsteadOfLeavingTheScreen() {
        val nearSide = FanInstances.bubbleAngles(3f, 2, radius, bubble, gapPx = 20f, edgeMarginPx = 12f)
        val nearBottom = FanInstances.bubbleAngles(88f, 3, radius, bubble, gapPx = 20f, edgeMarginPx = 12f)
        assertInsideScreenEdges(nearSide)
        assertInsideScreenEdges(nearBottom)
        assertTrue(nearSide[0] < nearSide[1])
    }

    @Test
    fun tooManyBubblesCompressToFit() {
        val angles = FanInstances.bubbleAngles(45f, 12, 300f, bubble, gapPx = 20f, edgeMarginPx = 12f)
        assertEquals(12, angles.size)
        val margin = Math.toDegrees(kotlin.math.asin(((bubble + 12f) / 300f).toDouble())).toFloat()
        assertTrue(angles.first() >= margin - 0.01f && angles.last() <= 90f - margin + 0.01f)
    }
}
