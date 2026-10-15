package com.repl.bubbledrawer.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 搜索栏外壳：miuix 的 `SearchBar` 只差一个「末端动作」的位置，这里给出来。
 *
 * 搜索页的顶栏在搜索态下整个换成它（见 `PinManageActivity` 与「选择应用」页）：
 * 调用方传 `InputField`，[outsideEndAction] 放取消按钮，并自己补状态栏 inset
 * （`MiuixSearchBar` 是普通 Row，不吃窗口 inset；`TopAppBar` 是自己吃的）。
 */
@Composable
fun MiuixSearchBar(
    inputField: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = DpSize(12.dp, 0.dp),
    expanded: Boolean = true,
    outsideEndAction: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = insideMargin.height, horizontal = insideMargin.width),
        ) {
            inputField()
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
            exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it }),
        ) {
            outsideEndAction?.invoke()
        }
    }
}

/**
 * 圆形灰底 Close 图标用于取消搜索。
 */
@Composable
fun SearchCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(end = 12.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Close,
            contentDescription = stringResource(R.string.search_cancel),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
    }
}
