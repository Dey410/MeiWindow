package com.repl.bubbledrawer.contactbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一个联系人只占一条 —— 用户反馈「联系人条里可能出现几个一样的联系人图标」。
 *
 * 成因（设备日志里的真实标题）：群消息的标题是 `群名：发送者`
 * （`StarVoyager Ideal：ksm`、`YuKongA | Chat：Who care` …），而通知带的大图标是**发送者的头像** ——
 * 同一个人在两三个群里说话，条上就是两三张一模一样的脸。会话的身份（标题）认不出人，头像才能。
 */
class ConversationDedupeTest {

    // ------------------------------------------------------------------ 指纹

    @Test
    fun theFingerprintIsThreeLevelsPerPixelAndIgnoresAlpha() {
        val opaque = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val translucent = intArrayOf(0x00000000, 0x80FFFFFF.toInt())

        assertEquals(6, avatarPrint(opaque, side = 2).length)
        assertEquals("同一个像素的两种 alpha 是同一个人", avatarPrint(opaque, side = 2), avatarPrint(translucent, side = 2))
        assertEquals("000", avatarPrint(intArrayOf(0xFF000000.toInt()), side = 1))
        assertEquals("fff", avatarPrint(intArrayOf(0xFFFFFFFF.toInt()), side = 1))
    }

    @Test
    fun theSamePictureReEncodedStillMatches() {
        val original = fingerprint(picture(seed = 7))

        // 重新编码/重采样：一部分通道差一个量化级 —— 正是 16x16 指纹无法逐位相等的原因。
        assertTrue("同一张脸的两次编码必须认出来", printsMatch(original, perturb(original, everyNth = 7, delta = 1)))

        // 反相 = 另一张图（每个通道都差了一大截），不能被当成同一个人。
        val inverted = picture(seed = 7).map { it xor 0x00FFFFFF }.toIntArray()
        assertFalse(printsMatch(original, fingerprint(inverted)))
    }

    @Test
    fun missingOrMismatchedFingerprintsNeverMatch() {
        val print = fingerprint(picture(seed = 1))
        assertFalse("没有头像就不能认人（否则所有没头像的人会并成一条）", printsMatch(null, print))
        assertFalse(printsMatch(print, null))
        assertFalse(printsMatch(null, null))
        assertFalse(printsMatch("", ""))
        assertFalse("长度不同 = 不是同一张图", printsMatch("0o1", "0o1o"))
    }

    // ------------------------------------------------------------------ 合并

    @Test
    fun theSamePersonInSeveralGroupsBecomesOneEntry() {
        val ksm = fingerprint(picture(seed = 42))
        val items = listOf(
            conversation("StarVoyager Ideal：ksm", print = ksm, postTime = 300),
            conversation("geymelody：ksm", print = ksm, postTime = 100),
            conversation("YuKongA | Chat：ksm", print = ksm, postTime = 200),
        )

        val people = dedupeByPerson(items)

        assertEquals("同一个人只留一条", 1, people.size)
        assertEquals("留下的应该是最近说话的那个会话", "StarVoyager Ideal：ksm", people[0].title)
    }

    @Test
    fun differentPeopleAreAllKept() {
        val items = listOf(
            conversation("A：ksm", print = fingerprint(picture(seed = 1)), postTime = 3),
            conversation("A：宵月ワトラ", print = fingerprint(picture(seed = 2)), postTime = 2),
            conversation("B：平沢 憂", print = fingerprint(picture(seed = 3)), postTime = 1),
        )

        assertEquals(3, dedupeByPerson(items).size)
    }

    @Test
    fun conversationsWithoutAnAvatarOnlyCollapseOnTheSameTitle() {
        // 没有头像的条目画的是应用图标 —— 认头像会把所有这样的人并成一个，所以它们只按标题去重。
        val items = listOf(
            conversation("系统通知", print = null, postTime = 3),
            conversation("系统通知", print = null, postTime = 2),
            conversation("另一个会话", print = null, postTime = 1),
            conversation("有头像的人", print = fingerprint(picture(seed = 9)), postTime = 0),
        )

        val kept = dedupeByPerson(items)
        assertEquals(3, kept.size)
        assertEquals(listOf("系统通知", "另一个会话", "有头像的人"), kept.map { it.title })
    }

    @Test
    fun theNewestEntryWinsAndTheLiveDotSurvivesATie() {
        val print = fingerprint(picture(seed = 5))
        val older = conversation("群一：同一个人", print = print, postTime = 10, live = true)
        val newer = conversation("群二：同一个人", print = print, postTime = 20, live = false)

        assertEquals("群二：同一个人", dedupeByPerson(listOf(older, newer))[0].title)

        // 同一毫秒的两条：留还在通知栏里的那条，它才带红点。
        val dead = conversation("群一：同一个人", print = print, postTime = 20, live = false)
        val alive = conversation("群二：同一个人", print = print, postTime = 20, live = true)
        val kept = dedupeByPerson(listOf(dead, alive))
        assertEquals(1, kept.size)
        assertTrue("留下的必须带红点", kept[0].live)
    }

    @Test
    fun theListStaysSortedNewestFirst() {
        val items = listOf(
            conversation("老会话", print = null, postTime = 1),
            conversation("新会话", print = null, postTime = 9),
            conversation("中间", print = null, postTime = 5),
        )

        assertEquals(listOf("新会话", "中间", "老会话"), dedupeByPerson(items).map { it.title })
    }

    // ------------------------------------------------------------------ 通知事件的过滤

    @Test
    fun onlyChatNotificationsAreWorthARebuild() {
        // 通知 key 的真实形状（AOSP `Notification.getKey`）：userId|pkg|id|tag|uid。
        assertEquals(
            "com.tencent.mm",
            imPackageOfKey("0|com.tencent.mm|42|chat|10123"),
        )
        assertEquals(
            "org.telegram.messenger",
            imPackageOfKey("0|org.telegram.messenger|null|1234567|10123"),
        )
        // 音乐播放器的进度通知一秒一条：跟联系人条无关，不该触发全量重建。
        assertEquals(null, imPackageOfKey("0|com.netease.cloudmusic|1001|null|10200"))
        assertEquals(null, imPackageOfKey("0|android|1|null|1000"))
        assertEquals(null, imPackageOfKey(""))
        assertEquals(null, imPackageOfKey("garbage"))
    }

    // ------------------------------------------------------------------ helpers

    private fun conversation(
        title: String,
        print: String?,
        postTime: Long,
        live: Boolean = false,
    ) = Conversation(
        pkg = "com.tencent.mm",
        key = "$title|$postTime",
        title = title,
        icon = null,
        pendingIntent = null,
        postTime = postTime,
        live = live,
        avatarPrint = print,
    )

    /** 一张「头像」：确定性的四通道图案，`seed` 不同就是不同的人。 */
    private fun picture(seed: Int): IntArray = IntArray(AVATAR_PRINT_SIDE * AVATAR_PRINT_SIDE) { index ->
        val r = (index * 7 + seed * 31) % 256
        val g = (index * 13 + seed * 17) % 256
        val b = (index * 29 + seed * 11) % 256
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun fingerprint(pixels: IntArray): String = avatarPrint(pixels, AVATAR_PRINT_SIDE)

    /** 每隔 [everyNth] 个通道偏移 [delta] 个量化级 —— 模拟重新编码后的同一张图。 */
    private fun perturb(print: String, everyNth: Int, delta: Int): String = print.mapIndexed { index, char ->
        if (index % everyNth != 0) {
            char
        } else {
            val level = Character.digit(char, 16).coerceAtLeast(0)
            "0123456789abcdef"[(level + delta).coerceIn(0, 15)]
        }
    }.joinToString("")
}
