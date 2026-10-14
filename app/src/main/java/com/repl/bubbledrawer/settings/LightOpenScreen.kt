package com.repl.bubbledrawer.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.lightopen.LightOpen
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.barColor
import com.repl.bubbledrawer.ui.util.pageBackdrop
import com.repl.bubbledrawer.ui.util.pageContentPadding
import com.repl.bubbledrawer.ui.util.pageScroll
import com.repl.bubbledrawer.ui.util.rememberBlurBackdrop
import com.repl.bubbledrawer.xposed.RemotePrefs
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「轻量打开」设置页：六个开关 + 应用间跳转的名单。
 *
 * 每个开关背后是一处系统钩子，判定逻辑全在 [LightOpen]（纯函数、有单测）；这页只负责写值。
 * 两项在 SystemUI 进程里生效（通知 / 控制中心），三项在 system_server 里生效（分享 / 文本操作 /
 * 应用间跳转）—— 后者要重启手机，页尾的说明里写清楚了。
 */
@Composable
fun LightOpenSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    onPickApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val enabled = snap.lightOpenEnabled
    val targets = remember(snap.lightOpenJumpList) { LightOpen.decodeTargets(snap.lightOpenJumpList) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.light_open_title),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "lead") { LeadSpacer() }

            groupedCardItems(
                keyPrefix = "master",
                items = listOf(
                    CardItem("enable") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_enable),
                            summary = stringResource(R.string.light_open_enable_summary),
                            checked = enabled,
                            onCheckedChange = actions::setLightOpenEnabled,
                        )
                    },
                ),
            )

            item(key = "notification-title") {
                SmallTitle(text = stringResource(R.string.light_open_section_notification))
            }
            groupedCardItems(
                keyPrefix = "notification",
                items = listOf(
                    CardItem("portrait") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_notif_portrait),
                            summary = stringResource(R.string.light_open_notif_portrait_summary),
                            checked = snap.lightOpenNotifPortrait,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenNotifPortrait,
                        )
                    },
                    CardItem("landscape") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_notif_landscape),
                            summary = stringResource(R.string.light_open_notif_landscape_summary),
                            checked = snap.lightOpenNotifLandscape,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenNotifLandscape,
                        )
                    },
                ),
            )

            item(key = "control-center-title") {
                SmallTitle(text = stringResource(R.string.light_open_section_control_center))
            }
            groupedCardItems(
                keyPrefix = "control-center",
                items = listOf(
                    CardItem("tile") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_control_center),
                            summary = stringResource(R.string.light_open_control_center_summary),
                            checked = snap.lightOpenControlCenter,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenControlCenter,
                        )
                    },
                ),
            )

            item(key = "jump-title") {
                SmallTitle(text = stringResource(R.string.light_open_section_jump))
            }
            groupedCardItems(
                keyPrefix = "jump",
                items = buildList {
                    add(CardItem("share") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_share),
                            summary = stringResource(R.string.light_open_share_summary),
                            checked = snap.lightOpenShare,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenShare,
                        )
                    })
                    add(CardItem("text") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_text),
                            summary = stringResource(R.string.light_open_text_summary),
                            checked = snap.lightOpenText,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenText,
                        )
                    })
                    add(CardItem("app-jump") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_jump),
                            summary = stringResource(R.string.light_open_jump_summary),
                            checked = snap.lightOpenJump,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenJump,
                        )
                    })
                    add(CardItem("mode") {
                        DropdownRow(
                            title = stringResource(R.string.light_open_jump_mode),
                            items = listOf(
                                stringResource(R.string.light_open_jump_mode_white),
                                stringResource(R.string.light_open_jump_mode_black),
                            ),
                            selectedIndex = snap.lightOpenJumpMode,
                            enabled = enabled && snap.lightOpenJump,
                            onSelect = actions::setLightOpenJumpMode,
                        )
                    })
                    add(CardItem("pick") {
                        ArrowRow(
                            title = stringResource(R.string.light_open_jump_pick),
                            summary = if (targets.isEmpty()) {
                                stringResource(R.string.light_open_jump_pick_empty)
                            } else {
                                stringResource(R.string.light_open_jump_pick_count, targets.size)
                            },
                            enabled = enabled && snap.lightOpenJump,
                            onClick = onPickApps,
                        )
                    })
                },
            )

            item(key = "hint") { HintRow(stringResource(R.string.light_open_hint)) }
            item(key = "defaults") {
                HintRow(
                    "黑名单模式：名单外的一切跨应用跳转都会进小窗；名单内的应用强制全屏（用来压掉系统" +
                        "原生的轻量打开）。白名单模式：只有名单内的目标应用会进小窗。",
                )
            }

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * 应用选择页：按「被打开的应用」勾选。列表来自 [AppRepository]（与扇形/收藏同一份枚举），
 * 勾选直接写回整份名单 —— 编码与清洗都在 [LightOpen.encodeTargets] 里，UI 不做半套。
 */
@Composable
fun LightOpenAppsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snap = state.snapshot
    val selected = remember(snap.lightOpenJumpList) { LightOpen.decodeTargets(snap.lightOpenJumpList) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val apps by produceState(initialValue = emptyList<BubbleApp>()) {
        value = runCatching { AppRepository(context).all().toList() }
            .getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.light_open_apps_title),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "lead") { LeadSpacer() }
            item(key = "count") {
                SmallTitle(text = stringResource(R.string.light_open_apps_selected, selected.size))
            }
            if (apps.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.light_open_apps_loading),
                        modifier = Modifier.pageScroll(scrollBehavior),
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            groupedCardItems(
                keyPrefix = "app",
                items = apps.map { app ->
                    CardItem(app.packageName) {
                        SwitchRow(
                            title = app.label,
                            summary = app.packageName,
                            checked = app.packageName in selected,
                            onCheckedChange = { on ->
                                val next = if (on) {
                                    selected + app.packageName
                                } else {
                                    selected - app.packageName
                                }
                                actions.setLightOpenJumpList(next)
                            },
                        )
                    }
                },
            )
            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}
