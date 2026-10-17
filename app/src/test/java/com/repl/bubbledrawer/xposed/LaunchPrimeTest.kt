package com.repl.bubbledrawer.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「下一次打开这个应用请给小窗」的一次性口令（[RemotePrefs.KEY_LAUNCH_PRIME]）。
 *
 * 它是 `GateLaunchStrategy` 的第二通道：Intent 上的标记若被安全中心的拦截框重造时丢掉，
 * 用户点「允许」之后那一次启动只带着这份口令。所以两条要求必须钉死：
 *
 *  1. 编解码严格往返（包名 + 截止时间，缺一不可）；
 *  2. 任何半截值/旧格式都当成"没有口令"（宁可退回全屏，也不能把别人的启动塞进小窗）。
 */
class LaunchPrimeTest {

    @Test
    fun `round trips package and deadline`() {
        val encoded = RemotePrefs.encodeLaunchPrime("com.tencent.mm", 987_654_321L)
        assertEquals("com.tencent.mm|987654321", encoded)
        val decoded = RemotePrefs.decodeLaunchPrime(encoded)
        assertEquals("com.tencent.mm" to 987_654_321L, decoded)
    }

    @Test
    fun `empty or malformed values are ignored`() {
        assertNull(RemotePrefs.decodeLaunchPrime(null))
        assertNull(RemotePrefs.decodeLaunchPrime(""))
        assertNull(RemotePrefs.decodeLaunchPrime("com.tencent.mm"))       // 没有截止时间
        assertNull(RemotePrefs.decodeLaunchPrime("com.tencent.mm|"))      // 截止时间为空
        assertNull(RemotePrefs.decodeLaunchPrime("|123"))                 // 没有包名
        assertNull(RemotePrefs.decodeLaunchPrime("com.tencent.mm|abc"))   // 截止时间不是数字
        assertNull(RemotePrefs.decodeLaunchPrime("com.tencent.mm|0"))     // 0 = 作废
        assertNull(RemotePrefs.decodeLaunchPrime("com.tencent.mm|-5"))    // 负数同理
    }

    @Test
    fun `the ttl covers the confirmation dialog`() {
        // 口令必须活到用户读完「xx 想要打开 xx」并点「允许」—— 实测这段通常在 1~5 秒。
        assertEquals(true, RemotePrefs.LAUNCH_PRIME_TTL_MS >= 5_000L)
        // 但也不能长到"下一次从别处打开同一个应用"都还在窗口里。
        assertEquals(true, RemotePrefs.LAUNCH_PRIME_TTL_MS <= 10_000L)
    }
}
