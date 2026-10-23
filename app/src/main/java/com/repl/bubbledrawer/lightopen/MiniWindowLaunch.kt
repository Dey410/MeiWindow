package com.repl.bubbledrawer.lightopen

/**
 * 「这次启动是不是 ROM 自己的下拉小窗 / 迷你窗路径」的纯逻辑判定。
 *
 * 背景（用户 2026-10-23）：打开「竖屏点击通知以小窗打开」后，有概率把系统原有的
 * **下拉通知以小窗打开** 弄坏 —— 下拉延迟或打不开。原因是下拉小窗由 MIUI 的拖拽控制器
 * → `AppMiniWindowManagerImpl.launchMiniWindowActivity` 完成，它有自己的动画与任务标志；
 * 我们给「点击通知」准备的小窗 bundle（`NEW_TASK|MULTIPLE_TASK`、`setFreeformAnimation(false)`）
 * 一旦被它取走，就会盖掉 ROM 自己的 options。
 *
 * 这里只做字符串分类，方便单测；真正的栈扫描在 `LightOpenSystemUi.isRomMiniWindowLaunch()`。
 */
object MiniWindowLaunch {

    /**
     * 出现这些片段就认为是 ROM 的小窗/拖拽路径。
     *
     * - `MiniWindow` / `MiniWin` / `launchMiniWindow`：`AppMiniWindowManagerImpl` 一类；
     * - `Drag`：通知拖拽控制器（`MiuiNotificationDragController` 等），大小写敏感，
     *   正常的点击栈里不会出现。
     */
    private val MARKERS = listOf(
        "MiniWindow",
        "miniWindow",
        "MiniWin",
        "Drag",
        "dragDown",
        "DragDown",
        "launchMiniWindow",
    )

    fun isMiniWindowFrame(className: String): Boolean = MARKERS.any { className.contains(it) }

    fun isMiniWindowStack(stack: Array<StackTraceElement>): Boolean =
        stack.any { isMiniWindowFrame(it.className) }
}
