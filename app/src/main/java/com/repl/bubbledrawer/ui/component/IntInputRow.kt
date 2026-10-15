package com.repl.bubbledrawer.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 数字设置项：整行可点，弹出输入框手输一个整数（[min]–[max]）。
 *
 * 用户 2026-10-14：「推荐应用数量显示个数从 4-20 个的滑块开放为用户手动输入」——滑块只能给档位，
 * 用户要的是想要几就填几。所以这一项不再是 [top.yukonga.miuix.kmp.preference.SliderPreference]，
 * 而是「行 + 输入弹窗」。
 *
 * 几个刻意的选择：
 *  - 弹窗用 miuix `WindowDialog`（窗口级，不要求页面有 Scaffold 弹窗宿主）——miuix 的选型规则：
 *    跨页面/与页面 Scaffold 无关的弹窗用 `Window*`，`Overlay*` 才需要 Scaffold 祖先。
 *  - **不用 `if (show)` 把弹窗从组合里摘掉**：miuix 的显示/隐藏动画挂在 `show` 上，自己提前
 *    return 会连退出动画一起切掉（弹窗"啪"地消失）。输入状态因此放在弹窗内容里 —— 内容随弹窗
 *    出现/消失进出组合，每次打开天然是一份新的。
 *  - 按钮只有「取消 / 确认」两个，走 [DialogActions]：左右排列、各自全宽。
 *  - 值非法时（清空、超出范围）**确认按钮置灰**并给一行红字说明，而不是自动钳位后照样提交 ——
 *    自动钳位会让人以为"我填的 99 生效了"，其实被改成了 50。
 */
@Composable
fun IntInputRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onCommit: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    /** 行尾显示的值（与旁边的滑块行同一套写法：`@Composable`，好让调用点能直接 `stringResource`）。 */
    valueText: @Composable (Int) -> String = { it.toString() },
) {
    var show by remember { mutableStateOf(false) }

    ArrowPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        // 弹窗开着时整行保持按下态（MIUI 惯例，见 docs/ui-guidelines.md 的「弹 Dialog 的入口行」）。
        holdDownState = show,
        onClick = { show = true },
        endActions = {
            Text(
                text = valueText(value),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                } else {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                },
            )
        },
    )

    WindowDialog(
        show = show,
        title = title,
        summary = stringResource(R.string.int_input_range, min, max),
        onDismissRequest = { show = false },
        content = {
            // 打开时以当前值为初始文本：用户改的是"这一项"，不是从空白开始打字。
            var text by remember(value) { mutableStateOf(value.toString()) }
            val parsed = text.toIntOrNull()
            val valid = parsed != null && parsed in min..max
            val focusRequester = remember { FocusRequester() }
            val confirm = {
                parsed?.takeIf { it in min..max }?.let {
                    onCommit(it)
                    show = false
                }
                Unit
            }

            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = text,
                    // 只收数字，并且按上限的位数截断：50 的上限连"500"都打不出来，非法值没法输入。
                    onValueChange = { raw -> text = raw.filter { it.isDigit() }.take(max.toString().length) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    label = stringResource(R.string.int_input_field_hint),
                    // 标题上方已经写着这一项叫什么了，输入框里再挂一遍同样的字纯属重复：
                    // 让它当占位符 —— 清空时露出"数量"，平时不占地方。
                    useLabelAsPlaceholder = true,
                    enabled = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                // 空的时候不红：上面那句「可输入 4–50 的整数」已经说明范围了，占位符也还在。
                if (text.isNotEmpty() && !valid) {
                    Text(
                        text = stringResource(R.string.int_input_invalid, min, max),
                        modifier = Modifier.padding(top = 8.dp),
                        color = StatusColors.danger(),
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                Spacer(Modifier.height(16.dp))
                DialogActions(
                    actions = listOf(
                        DialogAction(
                            text = stringResource(R.string.dialog_cancel),
                            onClick = { show = false },
                        ),
                        DialogAction(
                            text = stringResource(R.string.dialog_confirm),
                            onClick = confirm,
                            primary = true,
                            enabled = valid,
                        ),
                    ),
                )
            }

            // 打开即聚焦：这一行的意义就是"马上打字"，还要再点一下输入框才算开始就太钝了。
            // 放在弹窗内容里而不是行上：内容是在弹窗真正出现时才组合的，那一刻 FocusRequester
            // 才挂在节点上（在行上请求会撞"还没 attach"）。
            LaunchedEffect(Unit) {
                runCatching { focusRequester.requestFocus() }
            }
        },
    )
}
