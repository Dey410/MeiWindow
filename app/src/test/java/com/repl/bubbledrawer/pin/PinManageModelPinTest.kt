package com.repl.bubbledrawer.pin

import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点一下收藏 / 取消收藏，必须**当场**在列表里生效。
 *
 * 用户 2026-10-12 的报告：「收藏或取消收藏应用有概率停在原地，只改变加号减号图标，
 * 需要收藏或取消收藏其他应用才会刷新」。
 *
 * 两个成因都在 [PinManageModel]：
 *  1. 图标读 `isPinned`（缓存），位置读 `cells` —— 两者若不是同一份快照，就会"图标变了、位置没变"；
 *  2. 写入之后又回头和解读一次：值与戳是两个 key，撕裂的那一瞬间读回来的是旧值，于是刚点的那一下
 *     被旧值盖掉，要等下一次操作才补上。
 *
 * 这里把「写入成功、但紧接着的读拿到旧值」这个顺序直接摆出来 —— 点下去就必须生效，与那次读无关。
 */
class PinManageModelPinTest {

    private val wechat = BubbleApp("com.tencent.mm", "微信", sortKey = "WEIXIN", initials = "WX")

    /** 现有测试的做法：`apps` 是 Compose 状态字段，往它背后的 [androidx.compose.runtime.MutableState] 里塞值。 */
    private fun injectApps(model: PinManageModel, apps: List<BubbleApp>) {
        val field = PinManageModel::class.java.declaredFields.first { it.name.startsWith("apps") }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as androidx.compose.runtime.MutableState<kotlinx.collections.immutable.ImmutableList<BubbleApp>>
        state.value = persistentListOf(*apps.toTypedArray())
    }

    @Test
    fun pinningTakesEffectEvenWhenTheReadRightAfterIsStale() {
        var stored: List<PinnedRef> = emptyList()
        var readIsStale = false
        val model = PinManageModel(
            loadApps = { persistentListOf(wechat) },
            // 写完之后的那次读拿到旧值：值与戳是两个 key，逐 key 镜像时会撕裂（真实设备就是这么发生的）。
            pinsOf = { if (readIsStale) emptyList() else stored },
            onOrderChange = { order ->
                stored = order
                readIsStale = true
            },
            recommendConfig = { false to 0 },
        )
        injectApps(model, listOf(wechat))

        model.togglePin(wechat)

        assertTrue(
            "刚收藏的项必须当场进 ★ 区（不能等下一次操作）",
            model.cells.any { it is Cell.Pin && it.app.packageName == wechat.packageName },
        )
        assertTrue("图标与位置必须来自同一份快照", model.isPinned(wechat))
    }

    @Test
    fun unpinningTakesEffectEvenWhenTheReadRightAfterIsStale() {
        var stored: List<PinnedRef> = listOf(PinnedRef(wechat.packageName, 0))
        var readIsStale = false
        val model = PinManageModel(
            loadApps = { persistentListOf(wechat) },
            // 撕裂时读回来的仍是"已收藏"，于是刚取消的那一下看起来没生效。
            pinsOf = { if (readIsStale) listOf(PinnedRef(wechat.packageName, 0)) else stored },
            onOrderChange = { order ->
                stored = order
                readIsStale = true
            },
            recommendConfig = { false to 0 },
        )
        injectApps(model, listOf(wechat))
        model.refreshPins()

        model.togglePin(wechat)

        assertFalse(
            "取消收藏必须当场从 ★ 区消失",
            model.cells.any { it is Cell.Pin && it.app.packageName == wechat.packageName },
        )
        assertFalse("图标也要同时变回加号", model.isPinned(wechat))
        assertTrue("但这一项仍在（写下去的确实是空列表）", stored.isEmpty())
    }
}
