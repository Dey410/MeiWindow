package com.repl.bubbledrawer.contactbar

/**
 * 通知标题闸门：一条通知算不算"一个会话"，以及条上那个人叫什么。
 *
 * 原版（Flyme `C2831I.m8903m()`）的判断是"标题非空且不是应用名"——聊天通知的标题就是发送者，
 * 系统提示（`下线通知` / `微信支付`）不适合当联系人。移植时照抄了这条闸门，并且实测它是对的：
 * 微信「你的微信已在电脑上登录」这类通知的标题就是 `微信`。
 *
 * 但**有一类聊天通知的标题真的只有应用名**（`QQ` / `微信` / `TIM`），发送者在正文里，
 * 形状是 `发送者：消息`（用户 2026-10-18 让补上这一档）。所以这里分两种处理：
 *
 *  - 标题是一个正常的标题 → 直接用（仍要过黑名单）；
 *  - 标题是应用名或空 → 只从正文的 `发送者：消息` 里取**前半段**；取不到就**丢弃**这条通知。
 *
 * 为什么"取不到就丢弃"，而不是把正文当名字（这是同一个改动里必须守住的一条）：正文是消息内容，
 * 拿它当联系人就是"你的微信已在电脑上登录"变成一条叫这个名字的会话 —— 那正是这条闸门当初
 * 要挡掉的东西。宁可少一个头像，也不要条上多一个假人。
 *
 * 纯算术，见 `NotificationTitleTest`。
 */

/** 标题就是应用名的那几个 —— 单独一档处理，不再和 [IGNORED_TITLES] 混在一起。 */
val APP_NAME_TITLES = setOf("QQ", "微信", "TIM")

/**
 * 明显不是人名的字眼：应用名标题的正文大多是系统提示（登录提醒、版本更新、验证码…），
 * 而它们的分隔符也长成 `xxx：yyy`。命中任何一条就认为"这不是发送者"。
 */
private val SYSTEM_CHATTER = listOf("通知", "提醒", "新消息", "登录", "验证", "更新", "版本", "系统")

/** 人名/群名的上限：正常昵称远不到这个长度，超了基本是把一句话当成了名字。 */
private const val MAX_NAME_LENGTH = 24

/**
 * 标题的最终判定。返回 **null = 这条通知不是一条会话**（调用方必须丢弃它），
 * 否则返回条上要显示的那个名字。
 *
 * @param rawTitle 通知的 `EXTRA_TITLE`
 * @param text     通知的 `EXTRA_TEXT`（只在标题是应用名/空时才会被读）
 * @param ignored  已知的系统提示标题（`RecentConversations.IGNORED_TITLES`）
 */
fun resolveConversationTitle(
    rawTitle: String,
    text: String,
    ignored: Set<String> = emptySet(),
): String? {
    val title = rawTitle.trim()
    if (title.isNotEmpty() && title !in APP_NAME_TITLES) {
        return title.takeIf { it !in ignored }
    }
    val sender = senderFromText(text) ?: return null
    return sender.takeIf { it !in ignored && it !in APP_NAME_TITLES }
}

/** 正文 `发送者：消息` 的前半段；不是那个形状（或明显是系统话术）时返回 null。 */
private fun senderFromText(text: String): String? {
    val body = text.trim()
    if (body.isEmpty()) return null
    val index = body.indexOfAny(charArrayOf(':', '：'))
    if (index <= 0) return null
    val candidate = body.substring(0, index).trim()
    if (candidate.isEmpty() || candidate.length > MAX_NAME_LENGTH) return null
    if (SYSTEM_CHATTER.any { candidate.contains(it) }) return null
    return candidate
}
