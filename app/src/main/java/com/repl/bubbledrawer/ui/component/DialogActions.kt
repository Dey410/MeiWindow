package com.repl.bubbledrawer.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 弹窗里的一条操作（一个按钮）。
 *
 * @param primary 主要动作（确认/保存），用 miuix 的 `textButtonColorsPrimary()`；其余用默认的次要色。
 */
data class DialogAction(
    val text: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 弹窗按钮的排布规则（用户 2026-10-14）：
 *
 *  - **两个**选项（取消 + 确认这种）→ 左右排列，两个都全宽（各占一半，合起来铺满弹窗）；
 *  - **其它数量**（一个、三个及以上）→ 纵向排列，每个都自己占满整行。
 *
 * 别在调用点手搓 Row/Column：这条规则是用户点名要的排版，散在各处就会各写各的（三个按钮横排、
 * 两个按钮竖排这种"看起来差不多"的错法，MIUI 里一眼就不对）。
 */
@Composable
fun DialogActions(
    actions: List<DialogAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    if (actions.size == 2) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                TextButton(
                    text = action.text,
                    onClick = action.onClick,
                    modifier = Modifier.weight(1f),
                    enabled = action.enabled,
                    colors = if (action.primary) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                TextButton(
                    text = action.text,
                    onClick = action.onClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = action.enabled,
                    colors = if (action.primary) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                )
            }
        }
    }
}
