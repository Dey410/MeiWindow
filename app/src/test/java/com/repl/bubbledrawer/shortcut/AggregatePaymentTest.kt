package com.repl.bubbledrawer.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 支付入口的启动 URI 选择。
 *
 * 2026-10-24 真机 bug 的根因：`LauncherApps` 返回的 `ShortcutInfo.getIntents()` 恒为 null
 * （系统 CLONE_REMOVE_FOR_LAUNCHER 把 intent 剥掉了），旧代码这时退到了应用主界面 Intent
 * —— 于是「微信扫一扫/收付款」全都只是把微信首页拉起来，看着像点了没反应。这里锁住：
 * 只要有 ShortcutInfo，就绝不能拿主界面 Intent 顶上。
 */
class AggregatePaymentTest {

    @Test
    fun shortcutWithoutVisibleIntentKeepsNull() {
        assertNull(
            "有快捷方式时拿不到 intent 也不能退主界面 Intent",
            AggregatePayment.entryIntentUri(
                shortcutUri = null,
                appLaunchUri = "intent:#Intent;component=com.tencent.mm/.ui.LauncherUI;end",
                hasShortcut = true,
            ),
        )
    }

    @Test
    fun shortcutIntentWins() {
        assertEquals(
            "intent:#Intent;action=com.tencent.mm.ui.ShortCutDispatchAction;end",
            AggregatePayment.entryIntentUri(
                shortcutUri = "intent:#Intent;action=com.tencent.mm.ui.ShortCutDispatchAction;end",
                appLaunchUri = "intent:#Intent;component=com.tencent.mm/.ui.LauncherUI;end",
                hasShortcut = true,
            ),
        )
    }

    @Test
    fun missingShortcutFallsBackToAppLaunchIntent() {
        assertEquals(
            "intent:#Intent;component=com.tencent.mm/.ui.LauncherUI;end",
            AggregatePayment.entryIntentUri(
                shortcutUri = null,
                appLaunchUri = "intent:#Intent;component=com.tencent.mm/.ui.LauncherUI;end",
                hasShortcut = false,
            ),
        )
    }

    @Test
    fun nothingAvailableStaysNull() {
        assertNull(
            AggregatePayment.entryIntentUri(
                shortcutUri = null,
                appLaunchUri = null,
                hasShortcut = false,
            ),
        )
    }
}
