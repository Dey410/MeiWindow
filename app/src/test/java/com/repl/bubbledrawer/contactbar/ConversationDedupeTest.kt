package com.repl.bubbledrawer.contactbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一个**会话**只占一条，群通知显示**群名**（用户 2026-10-12：「群通知应该用群名啊」）。
 *
 * 成因（设备日志里的真实标题）：群消息的标题是 `群名：发送者`
 * （`StarVoyager Ideal：ksm`、`YuKongA | Chat：Perlinkca` …）—— 同一个群里两个人说话就是两条
 * 标题不同的通知，而名字列只有 45dp，两条都截成 `YuKongA | Chat`，看起来就是"重复的联系人"。
 * 会话的名字是**前半段**（群名），人的名字是后半段；条上按会话名去重、按会话名显示。
 */
class ConversationDedupeTest {

    // ------------------------------------------------------------------ 会话名

    @Test
    fun theConversationNameIsThePartBeforeTheSender() {
        // 设备日志里的真实标题（Telegram 群）：群名在前、人在后。
        assertEquals("StarVoyager Ideal", conversationName("StarVoyager Ideal：ksm"))
        assertEquals("YuKongA | Chat", conversationName("YuKongA | Chat：Perlinkca"))
        assertEquals("HyperMusicCover", conversationName("HyperMusicCover：puhbu"))
        assertEquals("YuKongA | Chat", conversationName("YuKongA | Chat: Who care"))
        // 1:1 会话没有分隔符：整个标题就是名字。
        assertEquals("张三", conversationName("张三"))
        // 多个分隔符时取第一段（群名里本身带冒号的情况很少，但取第一段最接近 ROM 的语义）。
        assertEquals("A", conversationName("A：B：C"))
    }

    @Test
    fun aSeparatorWithoutANameInFrontIsNotASeparator() {
        // 分隔符后面没人名时（`群名：`），前半段仍然就是会话名。
        assertEquals("群名", conversationName("群名："))
        // 分隔符前面没有人名 → 整个标题就是名字。
        assertEquals("：x", conversationName("：x"))
        assertEquals("", conversationName(""))
    }

    // ------------------------------------------------------------------ 去重

    @Test
    fun twoSendersInOneGroupAreOneEntryLabelledWithTheGroup() {
        // **用户 2026-10-12 的复现**：同一个群里两个人说话 → 两条 `YuKongA | Chat：…`，
        // 名字列截断后一模一样，看着像重复。它们本来就是同一个会话 → 只留一条，显示群名。
        val items = listOf(
            conversation("YuKongA | Chat：Perlinkca", label = "YuKongA | Chat", postTime = 2),
            conversation("YuKongA | Chat：Yife Playte", label = "YuKongA | Chat", postTime = 1),
        )

        val kept = dedupeByConversation(items)
        assertEquals("同一个群只能占一条", 1, kept.size)
        assertEquals("YuKongA | Chat", kept[0].displayLabel)
        assertEquals("留下的应该是最近说话的那条", "YuKongA | Chat：Perlinkca", kept[0].title)
    }

    @Test
    fun theSamePersonInTwoGroupsStaysTwoEntries() {
        // 反向：同一个人在两个群里说话 —— 那是两个会话，两条都要留（各显示自己的群名）。
        val items = listOf(
            conversation("StarVoyager Ideal：ksm", label = "StarVoyager Ideal", postTime = 5),
            conversation("geymelody：ksm", label = "geymelody", postTime = 9),
        )

        val kept = dedupeByConversation(items)
        assertEquals(2, kept.size)
        assertEquals(listOf("geymelody", "StarVoyager Ideal"), kept.map { it.displayLabel })
    }

    @Test
    fun conversationsWithoutALabelFallBackToTheirTitle() {
        // 没有 MessagingStyle/群名的通知（1:1、系统消息）：名字就是标题，按标题去重。
        val items = listOf(
            conversation("系统通知", label = null, postTime = 3),
            conversation("系统通知", label = null, postTime = 2),
            conversation("另一个会话", label = null, postTime = 1),
        )

        val kept = dedupeByConversation(items)
        assertEquals(2, kept.size)
        assertEquals(listOf("系统通知", "另一个会话"), kept.map { it.displayLabel })
    }

    @Test
    fun theNewestEntryWinsAndTheLiveDotSurvivesATie() {
        val older = conversation("群一：甲", label = "群一", postTime = 10, live = true)
        val newer = conversation("群一：乙", label = "群一", postTime = 20, live = false)
        assertEquals("群一：乙", dedupeByConversation(listOf(older, newer))[0].title)

        // 同一毫秒的两条：留还在通知栏里的那条，它才带红点。
        val dead = conversation("群一：甲", label = "群一", postTime = 20, live = false)
        val alive = conversation("群一：乙", label = "群一", postTime = 20, live = true)
        val kept = dedupeByConversation(listOf(dead, alive))
        assertEquals(1, kept.size)
        assertTrue("留下的必须带红点", kept[0].live)
    }

    @Test
    fun theListStaysSortedNewestFirst() {
        val items = listOf(
            conversation("老会话", label = null, postTime = 1),
            conversation("新会话", label = null, postTime = 9),
            conversation("中间", label = null, postTime = 5),
        )

        assertEquals(listOf("新会话", "中间", "老会话"), dedupeByConversation(items).map { it.title })
    }

    // ------------------------------------------------------- 未读数不是会话名的一部分（QQ）

    @Test
    fun theUnreadCountQqHangsOffTheTitleIsNotPartOfTheName() {
        // **用户 2026-10-16 的复现**：`CONTACT_BAR_LIST` 里同一个 QQ 会话占了三行 ——
        // `通信12301潘宇锋 | … | 通信12301潘宇锋(2条新消息) | 通信12301潘宇锋(3条新消息)`。
        assertEquals("通信12301潘宇锋", conversationName("通信12301潘宇锋(2条新消息)"))
        assertEquals("通信12301潘宇锋", conversationName("通信12301潘宇锋(3条新消息)"))
        // 括号的几种写法都可能出现（QQ 版本 / ROM 不同），数字位数也不固定。
        assertEquals("通信12301潘宇锋", conversationName("通信12301潘宇锋（12条新消息）"))
        assertEquals("通信12301潘宇锋", conversationName("通信12301潘宇锋[2条新消息]"))
        assertEquals("通信12301潘宇锋", conversationName("通信12301潘宇锋【2条新消息】"))
        // 后缀跟着发送者（`群名：发送者(2条新消息)`）时也要去掉（取前半段之后再去一次）。
        assertEquals("群名", conversationName("群名：发送者(2条新消息)"))
        // 反向：后缀夹在分隔符之前（`群名(2条新消息)：发送者`）。
        assertEquals("群名", conversationName("群名(2条新消息)：发送者"))
    }

    @Test
    fun aTitleThatOnlyLooksLikeAnUnreadCountIsLeftAlone() {
        // 括号里没有「条新消息」的都不是未读数 —— 群名自带括号很常见，别误伤。
        assertEquals("电院23级(2023)", conversationName("电院23级(2023)"))
        assertEquals("奶茶群(2个)", conversationName("奶茶群(2个)"))
        assertEquals("版本(2条消息)", conversationName("版本(2条消息)"))
        // 整个标题就是后缀时不能剪成空名字（条上宁可显示这串东西，也不能没有名字）。
        assertEquals("(2条新消息)", normalizeConversationName("(2条新消息)"))
    }

    @Test
    fun theSameQqChatCountedThreeTimesIsOneEntry() {
        // 设备日志的三条（同一会话，未读数不同）→ 一条，显示去掉后缀的会话名。
        val items = listOf(
            conversationFromTitle("通信12301潘宇锋(3条新消息)", postTime = 30),
            conversationFromTitle("通信12301潘宇锋(2条新消息)", postTime = 20),
            conversationFromTitle("通信12301潘宇锋", postTime = 10),
        )

        val kept = dedupeByConversation(items)
        assertEquals("同一个会话只能占一条", 1, kept.size)
        assertEquals("通信12301潘宇锋", kept[0].displayLabel)
        assertEquals("留下的应该是最新的那条", "通信12301潘宇锋(3条新消息)", kept[0].title)
    }

    // ------------------------------------------------------------------ 通知事件的过滤

    @Test
    fun onlyChatNotificationsAreWorthARebuild() {
        // 通知 key 的真实形状（AOSP `Notification.getKey`）：userId|pkg|id|tag|uid。
        assertEquals("com.tencent.mm", imPackageOfKey("0|com.tencent.mm|42|chat|10123"))
        assertEquals("org.telegram.messenger", imPackageOfKey("0|org.telegram.messenger|null|1234567|10123"))
        // 音乐播放器的进度通知一秒一条：跟联系人条无关，不该触发全量重建。
        assertEquals(null, imPackageOfKey("0|com.netease.cloudmusic|1001|null|10200"))
        assertEquals(null, imPackageOfKey("0|android|1|null|1000"))
        assertEquals(null, imPackageOfKey(""))
        assertEquals(null, imPackageOfKey("garbage"))
    }

    // ------------------------------------------------------------------ helpers

    /** 通知里的那条会话（[label] 就是 `Conversation` 构造时的会话名）。 */
    private fun conversation(
        title: String,
        label: String?,
        postTime: Long,
        live: Boolean = false,
    ) = Conversation(
        pkg = "org.telegram.messenger",
        key = "$title|$postTime",
        title = title,
        icon = null,
        pendingIntent = null,
        postTime = postTime,
        live = live,
        label = label,
    )

    /**
     * 生产里 `toConversation` 的算法：会话名 = [conversationName]（MessagingStyle /
     * `EXTRA_CONVERSATION_TITLE` 拿不到时的退路，QQ 与 Telegram 都走这一条）。
     */
    private fun conversationFromTitle(title: String, postTime: Long, live: Boolean = false) =
        conversation(title, label = conversationName(title), postTime = postTime, live = live)
}
