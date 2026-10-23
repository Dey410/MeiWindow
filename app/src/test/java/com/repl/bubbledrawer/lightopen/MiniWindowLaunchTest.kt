package com.repl.bubbledrawer.lightopen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「下拉小窗 / 迷你窗」路径识别（`MiniWindowLaunch.kt`）。
 *
 * 用户 2026-10-23：打开「竖屏点击通知以小窗打开」后，有概率把系统原有的下拉小窗弄坏。
 * 这个识别用来保证：拖拽/迷你窗的启动绝不走我们的通知点击规则。
 */
class MiniWindowLaunchTest {

    private fun stack(vararg names: String) =
        Array(names.size) { StackTraceElement(names[it], "method", "File", 1) }

    @Test
    fun notificationDragControllerIsMiniWindow() {
        assertTrue(
            MiniWindowLaunch.isMiniWindowStack(
                stack(
                    "com.android.systemui.statusbar.notification.MiuiNotificationDragController",
                    "android.view.ViewGroup.dispatchTouchEvent",
                ),
            ),
        )
    }

    @Test
    fun appMiniWindowManagerIsMiniWindow() {
        assertTrue(
            MiniWindowLaunch.isMiniWindowStack(
                stack("com.miui.systemui.AppMiniWindowManagerImpl\$launchMiniWindowActivity\$1", "android.os.Handler"),
            ),
        )
        assertTrue(MiniWindowLaunch.isMiniWindowStack(stack("com.android.systemui.statusbar.phone.FooBar", "x.MiniWindowHelper")))
    }

    @Test
    fun normalNotificationClickIsNotMiniWindow() {
        assertFalse(
            MiniWindowLaunch.isMiniWindowStack(
                stack(
                    "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter",
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                    "android.view.View.performClick",
                ),
            ),
        )
    }

    @Test
    fun emptyStackIsNotMiniWindow() {
        assertFalse(MiniWindowLaunch.isMiniWindowStack(emptyArray()))
        assertFalse(MiniWindowLaunch.isMiniWindowFrame(""))
    }
}
