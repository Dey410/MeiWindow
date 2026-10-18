package com.repl.bubbledrawer.pin

import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 2026-10-18：「在扇形面板打开的更多应用页或主页的收藏应用管理，当点击管理再搜索应用时，
 * 搜索到的应用无法正常点击收藏或取消收藏」。
 *
 * 面板与主页共用同一套 `PinManageScreen` + [PinManageModel]，所以先在这里把模型那一半钉住：
 * **搜索结果里的格子走的就是 `model.togglePin`**，它必须当场生效（图标与 ★ 区同时变）。
 * 模型若是好的，问题就只可能出在界面层（点击被谁吃掉 / 搜索结果没画成可点的格子）。
 */
class PinManageModelSearchManageTest {

    private val wechat = BubbleApp("com.tencent.mm", "微信", sortKey = "WEIXIN", initials = "WX")
    private val alipay = BubbleApp("com.eg.android.AlipayGphone", "支付宝", sortKey = "ZHIFUBAO", initials = "ZFB")

    /** 与 [PinManageModelPinTest] 同一套注入方式：`apps` 是 Compose 状态字段。 */
    private fun injectApps(model: PinManageModel, apps: List<BubbleApp>) {
        val field = PinManageModel::class.java.declaredFields.first { it.name.startsWith("apps") }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as androidx.compose.runtime.MutableState<kotlinx.collections.immutable.ImmutableList<BubbleApp>>
        state.value = persistentListOf(*apps.toTypedArray())
    }

    private fun modelOf(stored: () -> List<PinnedRef>, write: (List<PinnedRef>) -> Unit): PinManageModel =
        PinManageModel(
            loadApps = { persistentListOf(wechat, alipay) },
            pinsOf = stored,
            onOrderChange = write,
            recommendConfig = { false to 0 },
        ).also { injectApps(it, listOf(wechat, alipay)) }

    @Test
    fun aSearchResultCanBePinnedWhileManaging() {
        var pins: List<PinnedRef> = emptyList()
        val model = modelOf({ pins }, { pins = it })

        model.toggleManageMode()          // 点「管理」
        model.startSearch()               // 再搜索
        model.searchQuery = "微"

        val cell = model.displayedCells.filterIsInstance<Cell.App>()
            .first { it.app.packageName == wechat.packageName }
        assertFalse("搜索结果里还没收藏", model.isPinned(cell.app))

        model.togglePin(cell.app)         // 界面上就是这一下

        assertTrue("点下去必须当场收藏", model.isPinned(wechat))
        assertEquals(listOf(PinnedRef(wechat.packageName, wechat.userId)), pins)
        // 搜索列表还在（不会因为收藏把结果清掉），而且这一格仍然是可点的 App 格子。
        assertTrue(
            "搜索结果必须还在",
            model.displayedCells.filterIsInstance<Cell.App>().any { it.app.packageName == wechat.packageName },
        )
    }

    @Test
    fun aSearchResultCanBeUnpinnedWhileManaging() {
        var pins: List<PinnedRef> = listOf(PinnedRef(wechat.packageName, wechat.userId))
        val model = modelOf({ pins }, { pins = it })

        model.toggleManageMode()
        model.startSearch()
        model.searchQuery = "微"

        val cell = model.displayedCells.filterIsInstance<Cell.App>()
            .first { it.app.packageName == wechat.packageName }
        assertTrue("已经收藏的那条要显示成可取消", model.isPinned(cell.app))

        model.togglePin(cell.app)

        assertFalse("点下去必须当场取消收藏", model.isPinned(wechat))
        assertTrue("存储里也删掉了", pins.none { it.packageName == wechat.packageName })
    }
}
