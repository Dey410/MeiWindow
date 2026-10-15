package com.repl.bubbledrawer.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.repl.bubbledrawer.data.AppIconCache
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.xposed.RemotePrefs

/**
 * 圆形裁切的应用图标。
 *
 * 先吃 [AppIconCache] 的同步命中（列表滚动时绝大多数行都是命中，直接画），没命中再异步画一张
 * **同样像素尺寸** 的位图 —— 缓存键含像素尺寸，所以设置页行内的 36dp 小图与扇形/网格里的大图
 * 各缓存各的，不会互相顶掉。
 *
 * 原来是 `PinManageScreen` 里的私有实现（面板与网格在用），设置页的「选择应用」也要同一份，
 * 于是挪到这里，行为一字未改。
 *
 * @param cloneBadge 分身角标开关，取值来自当前设置（[RemotePrefs.DEFAULT_CLONE_BADGE] 兜底）；
 *   它进缓存键：同一个 package#user 在开关两侧是两张不同的图。
 */
@Composable
fun AppIcon(
    app: BubbleApp,
    size: Dp,
    modifier: Modifier = Modifier,
    cloneBadge: Boolean = RemotePrefs.DEFAULT_CLONE_BADGE,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }

    var imageBitmap by remember(app.packageName, app.userId, px, cloneBadge) {
        mutableStateOf(AppIconCache.get(app.packageName, app.userId, px, cloneBadge))
    }

    if (imageBitmap == null) {
        LaunchedEffect(app.packageName, app.userId, px, cloneBadge) {
            val loaded = AppIconCache.loadOrGet(context, app, px, cloneBadge)
            if (loaded != null) {
                imageBitmap = loaded
            }
        }
    }

    val currentBmp = imageBitmap
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp,
            contentDescription = app.label,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        // 占位块保持同尺寸：图标加载完成时行高不会跳一下。
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    }
}
