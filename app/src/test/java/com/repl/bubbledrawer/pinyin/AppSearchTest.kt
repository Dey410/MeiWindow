package com.repl.bubbledrawer.pinyin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app-search rules that both the pin panel and 轻量打开's 选择应用 page run on.
 *
 * They used to live inline in `PinManageModel`; they are pinned here because a second caller
 * now depends on them: two lists that answer the same query differently is the kind of
 * divergence that only shows up on a device.
 */
class AppSearchTest {

    private class FakePinyinCompare : HanziToPinyin.Compare {
        private val map: HashMap<Char, String> = HashMap<Char, String>().let { seed ->
            for (i in PinyinTables.BOUNDARIES.indices) {
                seed[PinyinTables.BOUNDARIES[i]] = PinyinTables.PINYINS[i].lowercase()
            }
            seed['微'] = "wei"; seed['信'] = "xin"; seed['支'] = "zhi"; seed['付'] = "fu"
            seed['宝'] = "bao"; seed['美'] = "mei"; seed['团'] = "tuan"; seed['企'] = "qi"
            seed['业'] = "ye"; seed
        }

        override fun compare(a: String, b: String): Int {
            val ka = a.singleOrNull()?.let { map[it] } ?: a
            val kb = b.singleOrNull()?.let { map[it] } ?: b
            return ka.compareTo(kb)
        }
    }

    @Before
    fun setUp() {
        HanziToPinyin.collatorOverride = FakePinyinCompare()
    }

    @After
    fun tearDown() {
        HanziToPinyin.collatorOverride = null
    }

    private val weixin = BubbleApp("com.tencent.mm", "微信", sortKey = "WEIXIN", initials = "WX")
    private val qyWeixin = BubbleApp("com.tencent.wework", "企业微信", sortKey = "QIYEWEIXIN", initials = "QYWX")
    private val alipay = BubbleApp("com.eg.android.AlipayGphone", "支付宝", sortKey = "ZHIFUBAO", initials = "ZFB")
    private val weishi = BubbleApp("com.tencent.weishi", "短视频", sortKey = "DUANSHIPIN", initials = "DSP")
    private val meituan = BubbleApp("com.sankuai.meituan", "美团", sortKey = "MEITUAN", initials = "MT")

    private val all = listOf(qyWeixin, weishi, alipay, weixin, meituan)

    @Test fun blankQueryKeepsTheCallersOrder() {
        // The picker shows the repository's own order (usage desc, then pinyin) when nothing
        // is typed; re-sorting there would fight the repository.
        assertEquals(all, AppSearch.filter(all, "   "))
        assertEquals(all, AppSearch.filter(all, ""))
    }

    @Test fun rankCoversTheFourTiers() {
        assertEquals(AppSearch.NAME_PREFIX, AppSearch.rank(weixin, "微"))
        assertEquals(AppSearch.CONTAINS, AppSearch.rank(qyWeixin, "微"))
        assertEquals(AppSearch.PINYIN_PREFIX, AppSearch.rank(weixin, "wei"))
        assertEquals(AppSearch.CONTAINS, AppSearch.rank(qyWeixin, "wei"))
        assertEquals(AppSearch.PACKAGE_CONTAINS, AppSearch.rank(weishi, "wei"))
        assertEquals(AppSearch.NO_MATCH, AppSearch.rank(meituan, "微"))
    }

    @Test fun initialsMatchLikePinyin() {
        assertEquals(AppSearch.PINYIN_PREFIX, AppSearch.rank(weixin, "wx"))
        assertEquals(AppSearch.PINYIN_PREFIX, AppSearch.rank(alipay, "zfb"))
        assertEquals(AppSearch.PINYIN_PREFIX, AppSearch.rank(meituan, "MT"))
    }

    @Test fun chineseNameAndPackageNameBothFindTheApp() {
        assertEquals(listOf(weixin), AppSearch.filter(all, "微信").take(1))
        assertEquals(listOf(alipay), AppSearch.filter(all, "alipay"))
    }

    @Test fun resultsAreOrderedByTierThenByShorterName() {
        val result = AppSearch.filter(all, "wei")
        // 拼音前缀（微信）→ 包含（企业微信）→ 只剩包名（短视频）
        assertEquals(listOf("com.tencent.mm", "com.tencent.wework", "com.tencent.weishi"), result.map { it.packageName })
    }

    @Test fun shorterNameWinsInsideOneTier() {
        // 同一档里「微信」必须排在「微信读书」前面 —— 平手规则就是为这个调的。
        val short = BubbleApp("com.a", "微", sortKey = "WEI", initials = "W")
        val long = BubbleApp("com.b", "微读", sortKey = "WEIDU", initials = "WD")
        assertEquals(
            listOf("com.a", "com.b"),
            AppSearch.filter(listOf(long, short), "微").map { it.packageName },
        )
    }

    @Test fun noMatchIsAnEmptyList() {
        assertTrue(AppSearch.filter(all, "nonexistent").isEmpty())
    }

    @Test fun everyQueryLetterFindsSomethingForAKnownApp() {
        // 「打字打一半」的常见情形：wx / weix / weixin 都该命中微信。
        for (q in listOf("w", "wx", "wei", "weix", "weixin")) {
            assertTrue("query=$q found nothing", AppSearch.filter(all, q).contains(weixin))
        }
    }
}
