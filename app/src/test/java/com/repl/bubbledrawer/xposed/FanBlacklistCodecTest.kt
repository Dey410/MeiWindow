package com.repl.bubbledrawer.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势黑名单的落盘格式（用户 2026-10-18：「在启用角落手势下面加一个手势黑名单」）。
 *
 * 存换行分隔的包名而不是 `StringSet`：远程组是按 key 逐个镜像的（`RemoteBridge.syncLocal` 里
 * 那串 `putInt`/`putBoolean`），StringSet 在那套协议里没有对应类型。往返必须**无损且稳定**：
 * 顺序、空白、重复项都不能让"存进去"和"读出来"不是同一份。
 */
class FanBlacklistCodecTest {

    @Test
    fun roundTripKeepsExactlyTheSelectedPackages() {
        val selected = setOf("com.tencent.mm", "tv.danmaku.bili", "com.miui.home")
        val decoded = decodeFanBlacklist(encodeFanBlacklist(selected))
        assertEquals(selected, decoded)
    }

    @Test
    fun theEncodingIsStableSoRepeatedWritesDoNotChurn() {
        // 顺序稳定（排序）才能让"内容没变"的两次写入产生同一个字符串 —— 远程组是按值比较的。
        assertEquals(
            encodeFanBlacklist(setOf("b.pkg", "a.pkg", "c.pkg")),
            encodeFanBlacklist(setOf("c.pkg", "b.pkg", "a.pkg")),
        )
        assertEquals("a.pkg\nb.pkg\nc.pkg", encodeFanBlacklist(setOf("c.pkg", "a.pkg", "b.pkg")))
    }

    @Test
    fun blankEntriesAreDropped() {
        assertEquals("a.pkg", encodeFanBlacklist(setOf("a.pkg", "", "   ")))
        assertEquals(setOf("a.pkg", "b.pkg"), decodeFanBlacklist("a.pkg\n\n  \nb.pkg\n"))
    }

    @Test
    fun theDefaultIsAnEmptyList() {
        assertTrue(decodeFanBlacklist(RemotePrefs.DEFAULT_FAN_BLACKLIST).isEmpty())
        assertTrue(decodeFanBlacklist(null).isEmpty())
        assertEquals("", encodeFanBlacklist(emptySet()))
    }
}
