package com.repl.bubbledrawer.contactbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知标题闸门（用户 2026-10-18：「检查一下有没有破坏原功能」的那次改动）。
 *
 * 原版闸门是"标题非空且不是应用名" —— 它挡掉的是系统话术（微信「你的微信已在电脑上登录」的标题
 * 就是 `微信`）。改成"标题是应用名时从正文 `发送者：消息` 里取前半段"之后，**取不到就必须丢弃**：
 * 正文是消息内容，拿它当名字会造出假联系人 —— 这正是闸门当初存在的理由。
 */
class NotificationTitleTest {

    private val ignored = setOf("下线通知", "微信支付", "服务通知")

    // ------------------------------------------------------------------ 正常标题

    @Test
    fun aNormalTitleIsTakenAsIs() {
        assertEquals("张三", resolveConversationTitle("张三", "你好", ignored))
        // 群通知的标题形状（`群名：发送者`）在这里不做拆分 —— 会话名由 `conversationName` 负责。
        assertEquals("StarVoyager Ideal：ksm", resolveConversationTitle("StarVoyager Ideal：ksm", "", ignored))
        assertEquals("张三", resolveConversationTitle("  张三  ", "", ignored))
    }

    @Test
    fun theKnownSystemTitlesAreRejected() {
        // 用户实测过的形状：这两个标题都不是人。
        assertNull(resolveConversationTitle("下线通知", "您的QQ已在其他设备登录", ignored))
        assertNull(resolveConversationTitle("微信支付", "收款 0.01 元", ignored))
        assertNull(resolveConversationTitle("服务通知", "", ignored))
    }

    // ------------------------------------------------------------------ 标题是应用名

    @Test
    fun theSenderIsTakenFromTheBodyWhenTheTitleIsTheAppName() {
        assertEquals("张三", resolveConversationTitle("QQ", "张三：在吗", ignored))
        assertEquals("张三", resolveConversationTitle("微信", "张三: 在吗", ignored))
        assertEquals("李四", resolveConversationTitle("TIM", "李四：收到", ignored))
    }

    @Test
    fun aBodyWithoutASenderIsNotAConversation() {
        // 这次改动最容易犯的错：把正文当名字。
        assertNull(resolveConversationTitle("微信", "你好呀", ignored))
        assertNull(resolveConversationTitle("QQ", "", ignored))
        assertNull(resolveConversationTitle("", "", ignored))
        // 冒号在开头 = 前面没有人名。
        assertNull(resolveConversationTitle("QQ", "：你好", ignored))
    }

    @Test
    fun systemChatterInTheBodyIsNotASender() {
        // 应用名标题 + 系统话术：正文里也有冒号，但前半段不是人。
        assertNull(resolveConversationTitle("微信", "你的微信已在电脑上登录：点击查看", ignored))
        assertNull(resolveConversationTitle("QQ", "登录提醒：新设备登录", ignored))
        assertNull(resolveConversationTitle("TIM", "版本更新：点击升级", ignored))
        assertNull(resolveConversationTitle("微信", "验证码：123456", ignored))
        assertNull(resolveConversationTitle("QQ", "3 条新消息：点击查看", ignored))
    }

    @Test
    fun anEmptyTitleWithASenderInTheBodyStillCounts() {
        assertEquals("张三", resolveConversationTitle("", "张三：在吗", ignored))
        assertEquals("张三", resolveConversationTitle("   ", "张三：在吗", ignored))
    }

    @Test
    fun aSentenceIsNotAName() {
        val long = "这是很长很长的一句话被当成了名字所以必须拒绝掉才对"
        assertTrue(long.length > 24)
        assertNull(resolveConversationTitle("QQ", "$long：你好", ignored))
    }

    @Test
    fun theExtractedSenderStillHasToPassTheGates() {
        // 正文里取出来的"人"正好是黑名单/应用名 → 仍然不是一条会话。
        assertNull(resolveConversationTitle("QQ", "微信支付：收款", ignored))
        assertNull(resolveConversationTitle("微信", "QQ：在吗", ignored))
        // 取出来的人名正常 → 通过。
        assertEquals("张三", resolveConversationTitle("微信", "张三：在吗", ignored))
    }

    @Test
    fun theAppNameTitlesAreExactlyTheThreeKnownOnes() {
        assertEquals(setOf("QQ", "微信", "TIM"), APP_NAME_TITLES)
    }
}
