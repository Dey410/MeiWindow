package com.repl.bubbledrawer.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.bubble.FanLayout
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.IntInputRow
import com.repl.bubbledrawer.ui.component.StatusCard
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
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * Main settings screen with categorized feature navigation entries and redesigned status card.
 */
@Composable
fun MainSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onNavigateToTrigger: () -> Unit,
    onNavigateToFan: () -> Unit,
    onNavigateToMorePanel: () -> Unit,
    onNavigateToExperimental: () -> Unit,
    onNavigateToLightOpen: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToGestureBlacklist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.settings_title),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    // 重启系统界面：模块的 hook 全在 SystemUI 进程里，热重载只重建模块自己的运行时，
                    // 想从干净进程复现问题/让改动生效，按这个比重启手机快（需要 root，见 SystemUiRestart）。
                    actions = {
                        IconButton(onClick = actions::restartSystemUi) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.restart_systemui),
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

            // --- Redesigned Status Card (KernelSU / HyperMusicCover / ghostlock-app style) ---
            item(key = "status-card") {
                StatusCard(
                    connected = state.connected,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }

            // --- Global Toggles ---
            item(key = "global-title") {
                SmallTitle(text = stringResource(R.string.global_section_title))
            }
            groupedCardItems(
                keyPrefix = "global",
                items = buildList {
                    add(CardItem("enable") {
                        SwitchRow(
                            title = stringResource(R.string.enable_service),
                            summary = stringResource(R.string.enable_service_summary),
                            checked = snap.enabled,
                            onCheckedChange = actions::setEnabled,
                        )
                    })
                    // 用户 2026-10-18：「在启用角落手势下面加一个手势黑名单和横屏时需滑动两次才打开的开关」。
                    add(CardItem("gesture_blacklist") {
                        ArrowRow(
                            title = stringResource(R.string.gesture_blacklist),
                            summary = if (snap.fanBlacklist.isEmpty()) {
                                stringResource(R.string.gesture_blacklist_empty)
                            } else {
                                stringResource(R.string.gesture_blacklist_count, snap.fanBlacklist.size)
                            },
                            enabled = snap.enabled,
                            onClick = onNavigateToGestureBlacklist,
                        )
                    })
                    add(CardItem("landscape_two_swipes") {
                        SwitchRow(
                            title = stringResource(R.string.fan_landscape_two_swipes),
                            summary = stringResource(R.string.fan_landscape_two_swipes_summary),
                            checked = snap.fanLandscapeTwoSwipes,
                            enabled = snap.enabled,
                            onCheckedChange = actions::setFanLandscapeTwoSwipes,
                        )
                    })
                    add(CardItem("freeform") {                        SwitchRow(
                            title = stringResource(R.string.freeform_toggle),
                            summary = stringResource(R.string.freeform_hint),
                            checked = snap.freeform,
                            enabled = snap.enabled,
                            onCheckedChange = actions::setFreeform,
                        )
                    })
                },
            )

            // --- Categorized Feature Entries ---
            item(key = "categories-title") {
                SmallTitle(text = stringResource(R.string.category_section_title))
            }
            groupedCardItems(
                keyPrefix = "categories",
                items = listOf(
                    CardItem("trigger") {
                        ArrowRow(
                            title = stringResource(R.string.category_trigger),
                            summary = stringResource(R.string.category_trigger_summary),
                            onClick = onNavigateToTrigger,
                        )
                    },
                    CardItem("fan") {
                        ArrowRow(
                            title = stringResource(R.string.category_fan),
                            summary = stringResource(R.string.category_fan_summary),
                            onClick = onNavigateToFan,
                        )
                    },
                    CardItem("panel") {
                        ArrowRow(
                            title = stringResource(R.string.category_panel),
                            summary = stringResource(R.string.category_panel_summary),
                            onClick = onNavigateToMorePanel,
                        )
                    },
                    CardItem("manage") {
                        ArrowRow(
                            title = stringResource(R.string.category_manage),
                            summary = stringResource(R.string.category_manage_summary),
                            onClick = { actions.openManage(context) },
                        )
                    },
                    CardItem("experimental") {
                        ArrowRow(
                            title = stringResource(R.string.category_experimental),
                            summary = stringResource(R.string.category_experimental_summary),
                            onClick = onNavigateToExperimental,
                        )
                    },
                    CardItem("light-open") {
                        ArrowRow(
                            title = stringResource(R.string.category_light_open),
                            summary = stringResource(R.string.category_light_open_summary),
                            onClick = onNavigateToLightOpen,
                        )
                    },
                    CardItem("diagnosis") {
                        ArrowRow(
                            title = stringResource(R.string.category_diagnosis),
                            summary = stringResource(R.string.category_diagnosis_summary),
                            onClick = onNavigateToDiagnostics,
                        )
                    },
                ),
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * Trigger gesture and zone settings subpage.
 * Note: 触发区默认半径滑条已去除，仅保留底边/侧边范围设置与预览。
 */
@Composable
fun TriggerSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    onPreviewTriggerZone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_trigger),
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
                keyPrefix = "trigger_pos",
                items = listOf(
                    CardItem("pos") {
                        DropdownRow(
                            title = stringResource(R.string.trigger_pos),
                            items = listOf(
                                stringResource(R.string.pos_both),
                                stringResource(R.string.pos_left),
                                stringResource(R.string.pos_right),
                            ),
                            selectedIndex = state.triggerPos,
                            enabled = snap.enabled,
                            onSelect = actions::setTriggerPos,
                        )
                    },
                ),
            )

            groupedCardItems(
                keyPrefix = "zone",
                items = buildList {
                    add(CardItem("bottom") {
                        IntSliderRow(
                            title = stringResource(R.string.range_bottom),
                            summary = stringResource(R.string.range_bottom_summary),
                            enabled = snap.enabled,
                            value = snap.bottomDp,
                            min = RemotePrefsMinDp, max = RemotePrefsMaxDp,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = { actions.setBottomDp(it) },
                        )
                    })
                    add(CardItem("edge") {
                        IntSliderRow(
                            title = stringResource(R.string.range_edge),
                            summary = stringResource(R.string.range_edge_summary),
                            enabled = snap.enabled,
                            value = snap.edgeDp,
                            min = RemotePrefsMinDp, max = RemotePrefsMaxDp,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = { actions.setEdgeDp(it) },
                        )
                    })
                    add(CardItem("preview") {
                        ArrowRow(
                            title = stringResource(R.string.preview_trigger_zone),
                            enabled = snap.enabled,
                            onClick = onPreviewTriggerZone,
                        )
                    })
                    add(CardItem("hint") { HintRow(stringResource(R.string.range_hint)) })
                },
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * Fan dock appearance settings subpage (icon count and radius).
 */
@Composable
fun FanSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_fan),
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
                keyPrefix = "fan",
                items = buildList {
                    // 双层才谈得上"内圈 / 外圈"：单层时下面与外圈有关的两项一律**置灰**
                    // （藏起来会让人以为功能没做，用户 2026-10-16）。
                    val isDoubleLayer = snap.fanLayers == RemotePrefs.FAN_LAYERS_DOUBLE
                    add(CardItem("layers") {
                        DropdownRow(
                            title = stringResource(R.string.fan_layers),
                            summary = stringResource(R.string.fan_layers_summary),
                            items = listOf(
                                stringResource(R.string.fan_layers_single),
                                stringResource(R.string.fan_layers_double),
                            ),
                            selectedIndex = if (snap.fanLayers == RemotePrefs.FAN_LAYERS_DOUBLE) 1 else 0,
                            onSelect = {
                                actions.setFanLayers(
                                    if (it == 1) RemotePrefs.FAN_LAYERS_DOUBLE else RemotePrefs.FAN_LAYERS_SINGLE,
                                )
                            },
                        )
                    })
                    add(CardItem("icon_count") {
                        DropdownRow(
                            title = stringResource(R.string.fan_icon_count),
                            // 双层时这一项管的是内层：把外层会跟着变成几个直接写在描述里，
                            // 否则「内层 5 个」看不出整页到底放得下多少。
                            summary = if (isDoubleLayer) {
                                stringResource(
                                    R.string.fan_icon_count_summary_double,
                                    snap.fanIconCount,
                                    FanLayout.outerApps(snap.fanIconCount, snap.fanLayers),
                                )
                            } else {
                                null
                            },
                            items = listOf(
                                stringResource(R.string.fan_icon_count_5),
                                stringResource(R.string.fan_icon_count_6),
                            ),
                            selectedIndex = if (snap.fanIconCount == 5) 0 else 1,
                            onSelect = { actions.setFanIconCount(if (it == 0) 5 else 6) },
                        )
                    })
                    add(CardItem("radius") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.fan_radius),
                            // 双层时这一项是**内圈**半径（用户 2026-10-16 起）：外圈要么跟在内圈外面一格，
                            // 要么由下面那个开关单独设定 —— 不写清楚会让人以为"半径没生效"。
                            summary = if (isDoubleLayer) {
                                stringResource(R.string.fan_radius_summary_double)
                            } else {
                                null
                            },
                            value = snap.fanRadiusDp,
                            defaultValue = RemotePrefs.DEFAULT_FAN_RADIUS_DP,
                            min = RemotePrefsFanRadiusMin,
                            max = RemotePrefsFanRadiusMax,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = actions::setFanRadiusDp,
                        )
                    })
                    add(CardItem("outer_radius_enabled") {
                        SwitchRow(
                            title = stringResource(R.string.fan_outer_radius_enabled),
                            summary = stringResource(R.string.fan_outer_radius_enabled_summary),
                            checked = snap.fanOuterRadiusEnabled,
                            // 单层没有"外圈"这回事：**置灰**而不是藏起来 —— 藏起来会让人以为这个功能
                            // 没做（用户 2026-10-16）。
                            enabled = isDoubleLayer,
                            onCheckedChange = actions::setFanOuterRadiusEnabled,
                        )
                    })
                    // 滑条同样常驻、置灰：开关没开（或单层）时它不生效，但位置固定，不会让下面的行跳来跳去。
                    // 下限就是**内圈半径** —— 滑到更小时滑条自己停在那一格（用户 2026-10-16 的规则）。
                    add(CardItem("outer_radius") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.fan_outer_radius),
                            summary = stringResource(R.string.fan_outer_radius_summary),
                            value = snap.fanOuterRadiusDp,
                            defaultValue = RemotePrefs.DEFAULT_FAN_OUTER_RADIUS_DP,
                            min = maxOf(RemotePrefsFanRadiusMin, snap.fanRadiusDp),
                            max = RemotePrefsFanRadiusMax,
                            enabled = isDoubleLayer && snap.fanOuterRadiusEnabled,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = actions::setFanOuterRadiusDp,
                        )
                    })
                    add(CardItem("show_vibrate") {
                        SwitchRow(
                            title = stringResource(R.string.fan_show_vibrate),
                            summary = stringResource(R.string.fan_show_vibrate_summary),
                            checked = snap.fanShowVibrate,
                            onCheckedChange = actions::setFanShowVibrate,
                        )
                    })
                    add(CardItem("auto_retract") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.fan_auto_retract),
                            summary = stringResource(R.string.fan_auto_retract_summary),
                            value = snap.fanAutoRetractSec,
                            defaultValue = RemotePrefs.DEFAULT_FAN_AUTO_RETRACT_SEC,
                            min = RemotePrefsFanAutoRetractMin,
                            max = RemotePrefsFanAutoRetractMax,
                            valueText = {
                                // 用户 2026-10-18：滑到最左（0 秒）= **松手直接收起**，不再表示"永不收起"。
                                if (it <= 0) {
                                    stringResource(R.string.fan_auto_retract_release)
                                } else {
                                    stringResource(R.string.sec_value, it)
                                }
                            },
                            onCommit = actions::setFanAutoRetractSec,
                        )
                    })
                    add(CardItem("double_tap_dismiss") {
                        SwitchRow(
                            title = stringResource(R.string.fan_double_tap_dismiss),
                            summary = stringResource(R.string.fan_double_tap_dismiss_summary),
                            checked = snap.fanDoubleTapDismiss,
                            onCheckedChange = actions::setFanDoubleTapDismiss,
                        )
                    })
                    add(CardItem("icon_size") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.fan_icon_size),
                            summary = stringResource(R.string.fan_icon_size_summary),
                            value = snap.fanIconDp,
                            defaultValue = RemotePrefs.DEFAULT_FAN_ICON_DP,
                            min = RemotePrefsFanIconMin,
                            max = RemotePrefsFanIconMax,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = actions::setFanIconDp,
                        )
                    })
                    add(CardItem("icon_shape") {
                        DropdownRow(
                            title = stringResource(R.string.fan_icon_shape),
                            summary = stringResource(R.string.fan_icon_shape_summary),
                            items = listOf(
                                stringResource(R.string.fan_icon_shape_circle),
                                stringResource(R.string.fan_icon_shape_rounded),
                            ),
                            selectedIndex = snap.fanIconShape.coerceIn(0, 1),
                            onSelect = actions::setFanIconShape,
                        )
                    })
                    add(CardItem("clone_badge") {
                        SwitchRow(
                            title = stringResource(R.string.fan_clone_badge),
                            summary = stringResource(R.string.fan_clone_badge_summary),
                            checked = snap.cloneBadge,
                            onCheckedChange = actions::setCloneBadge,
                        )
                    })
                    add(CardItem("auto_fill") {
                        SwitchRow(
                            title = stringResource(R.string.fan_auto_fill_recommend),
                            summary = stringResource(R.string.fan_auto_fill_recommend_summary),
                            checked = snap.fanAutoFillRecommend,
                            onCheckedChange = actions::setFanAutoFillRecommend,
                        )
                    })
                    add(CardItem("pressure_page_turn") {
                        SwitchRow(
                            title = stringResource(R.string.fan_pressure_page_turn),
                            summary = stringResource(R.string.fan_pressure_page_turn_summary),
                            checked = snap.fanPressurePageTurn,
                            onCheckedChange = actions::setFanPressurePageTurn,
                        )
                    })
                    add(CardItem("pressure_sensitivity") {
                        DropdownRow(
                            title = stringResource(R.string.fan_pressure_sensitivity),
                            // 校准过以后预设不再生效（阈值以校准值为准，见 RemotePrefs.pressureThresholdFor），
                            // 与其让用户改一个不起作用的下拉，不如把它灰掉并说清怎么回到预设。
                            summary = if (snap.fanPressureCalibrated && snap.fanPressureCalibratedHpa > 0f) {
                                stringResource(R.string.fan_pressure_sensitivity_summary_calibrated, snap.fanPressureCalibratedHpa)
                            } else {
                                null
                            },
                            items = listOf(
                                stringResource(R.string.fan_pressure_sensitivity_standard),
                                stringResource(R.string.fan_pressure_sensitivity_sensitive),
                                stringResource(R.string.fan_pressure_sensitivity_heavy),
                            ),
                            selectedIndex = snap.fanPressureSensitivity.coerceIn(0, 2),
                            enabled = snap.fanPressurePageTurn && !snap.fanPressureCalibrated,
                            onSelect = actions::setFanPressureSensitivity,
                        )
                    })
                    add(CardItem("pressure_calibrate") {
                        ArrowRow(
                            title = stringResource(
                                when {
                                    snap.fanPressureCalibrationActive -> R.string.fan_pressure_calibration_cancel
                                    snap.fanPressureCalibrated -> R.string.fan_pressure_calibration_restart
                                    else -> R.string.fan_pressure_calibration_start
                                },
                            ),
                            summary = pressureCalibrationSummary(snap),
                            // 会话进行中必须一直可点（用来取消）：扇形的采集不受「翻页总开关」约束，
                            // 中途把翻页关掉时如果这里跟着灰掉，用户就只能干等 120s 超时。
                            enabled = snap.fanPressureCalibrationActive || snap.fanPressurePageTurn,
                            onClick = {
                                if (snap.fanPressureCalibrationActive) {
                                    actions.cancelPressureCalibration()
                                } else {
                                    actions.startPressureCalibration()
                                }
                            },
                        )
                    })
                    add(CardItem("pressure_calibrate_clear") {
                        ArrowRow(
                            title = stringResource(R.string.fan_pressure_calibration_clear),
                            summary = stringResource(R.string.fan_pressure_calibration_clear_summary),
                            enabled = snap.fanPressurePageTurn && snap.fanPressureCalibrated,
                            onClick = actions::clearPressureCalibration,
                        )
                    })
                },
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * More panel appearance settings subpage (dimensions, icon/text size, dismiss outside).
 */
@Composable
fun MorePanelSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_panel),
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
                keyPrefix = "panel",
                items = buildList {
                    add(CardItem("w") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_width),
                            value = snap.panelWidthPct,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_W_PCT,
                            min = RemotePrefsPctMin,
                            max = RemotePrefsPctMax,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setPanelWidthPct,
                        )
                    })
                    add(CardItem("h") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_height),
                            value = snap.panelHeightPct,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_H_PCT,
                            min = RemotePrefsPctMin,
                            max = RemotePrefsPctMax,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setPanelHeightPct,
                        )
                    })
                    add(CardItem("icon") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_icon),
                            value = snap.panelIconDp,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_ICON_DP,
                            min = RemotePrefsIconMin,
                            max = RemotePrefsIconMax,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = actions::setPanelIconDp,
                        )
                    })
                    add(CardItem("text") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_text),
                            value = snap.panelTextSp,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_TEXT_SP,
                            min = RemotePrefsTextMin,
                            max = RemotePrefsTextMax,
                            valueText = { stringResource(R.string.sp_value, it) },
                            onCommit = actions::setPanelTextSp,
                        )
                    })
                    add(CardItem("dismiss_outside") {
                        DropdownRow(
                            title = stringResource(R.string.panel_dismiss_outside),
                            items = listOf(
                                stringResource(R.string.panel_dismiss_outside_single),
                                stringResource(R.string.panel_dismiss_outside_double),
                            ),
                            selectedIndex = snap.panelDismissOutside,
                            onSelect = actions::setPanelDismissOutside,
                        )
                    })
                    add(CardItem("hint") { HintRow(stringResource(R.string.more_panel_hint)) })
                },
            )

            groupedCardItems(
                keyPrefix = "recommend",
                items = buildList {
                    add(CardItem("enable") {
                        SwitchRow(
                            title = stringResource(R.string.recommend_apps_enable),
                            summary = stringResource(R.string.recommend_apps_hint),
                            checked = snap.recommendEnabled,
                            onCheckedChange = actions::setRecommendEnabled,
                        )
                    })
                    add(CardItem("count") {
                        IntInputRow(
                            title = stringResource(R.string.recommend_apps_count),
                            summary = stringResource(R.string.recommend_apps_count_summary),
                            value = snap.recommendCount,
                            min = RemotePrefs.RECOMMEND_COUNT_MIN,
                            max = RemotePrefs.RECOMMEND_COUNT_MAX,
                            enabled = snap.recommendEnabled,
                            valueText = { stringResource(R.string.recommend_apps_count_format, it) },
                            onCommit = actions::setRecommendCount,
                        )
                    })
                },
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * Experimental features subpage: Flyme-style lightweight freeform window,
 * gesture transitions, and window centering/scaling.
 */
@Composable
fun ExperimentalSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_experimental),
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
            item(key = "exp-lead") { LeadSpacer() }

            // --- 核心总开关 ---
            item(key = "exp-master-title") {
                SmallTitle(text = stringResource(R.string.exp_flyme_freeform_title))
            }
            groupedCardItems(
                keyPrefix = "exp-master",
                items = listOf(
                    CardItem("enable-flyme-freeform") {
                        SwitchRow(
                            title = stringResource(R.string.exp_flyme_freeform_title),
                            summary = stringResource(R.string.exp_flyme_freeform_summary),
                            checked = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformEnabled,
                        )
                    },
                ),
            )

            // --- 窗口尺寸与位置 ---
            // 总开关关掉后这些都是灰的（miuix 的 enabled=false）：Flyme 轻量小窗没接管时，
            // 居中/缩放/遮罩都无处生效。唯一例外是「禁止小窗偏移」——它压的是 HyperOS 自己的
            // 避让行为，原生小窗模式下同样有效，所以永远可点。
            item(key = "exp-window-title") {
                SmallTitle(text = stringResource(R.string.exp_section_window_tuning))
            }
            groupedCardItems(
                keyPrefix = "exp-window",
                items = buildList {
                    add(CardItem("center-window") {
                        SwitchRow(
                            title = stringResource(R.string.exp_center_window),
                            summary = stringResource(R.string.exp_center_window_summary),
                            checked = snap.flymeFreeformCenter,
                            enabled = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformCenter,
                        )
                    })
                    add(CardItem("vertical-window") {
                        IntSliderRow(
                            title = stringResource(R.string.exp_window_vertical),
                            summary = stringResource(R.string.exp_window_vertical_hint),
                            value = snap.flymeFreeformVerticalPct,
                            min = RemotePrefs.FLYME_FREEFORM_VERTICAL_MIN,
                            max = RemotePrefs.FLYME_FREEFORM_VERTICAL_MAX,
                            enabled = snap.flymeFreeformEnabled,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setFlymeFreeformVerticalPct,
                        )
                    })
                    add(CardItem("no-offset") {
                        SwitchRow(
                            title = stringResource(R.string.exp_no_offset),
                            summary = stringResource(R.string.exp_no_offset_summary),
                            checked = snap.flymeFreeformNoOffset,
                            onCheckedChange = actions::setFlymeFreeformNoOffset,
                        )
                    })
                    add(CardItem("scale-window") {
                        IntSliderRow(
                            title = stringResource(R.string.exp_window_scale),
                            summary = stringResource(R.string.exp_window_scale_hint),
                            value = snap.flymeFreeformScale,
                            min = RemotePrefs.FLYME_FREEFORM_SCALE_MIN,
                            max = RemotePrefs.FLYME_FREEFORM_SCALE_MAX,
                            enabled = snap.flymeFreeformEnabled,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setFlymeFreeformScale,
                        )
                    })
                    add(CardItem("dim-bg") {
                        SwitchRow(
                            title = stringResource(R.string.exp_dim_background),
                            summary = stringResource(R.string.exp_dim_background_summary),
                            checked = snap.flymeFreeformDimBg,
                            enabled = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformDimBg,
                        )
                    })
                },
            )

            // --- 小窗底部联系人条 (Flyme「联系人头像」移植) ---
            item(key = "exp-contact-bar-title") {
                SmallTitle(text = stringResource(R.string.exp_section_contact_bar))
            }
            groupedCardItems(
                keyPrefix = "exp-contact-bar",
                items = buildList {
                    add(CardItem("contact-bar") {
                        SwitchRow(
                            title = stringResource(R.string.exp_contact_bar),
                            summary = stringResource(R.string.exp_contact_bar_summary),
                            checked = snap.contactBar,
                            onCheckedChange = actions::setContactBar,
                        )
                    })
                    add(CardItem("contact-bar-min-width") {
                        IntSliderRow(
                            title = stringResource(R.string.exp_contact_bar_min_width),
                            value = snap.contactBarMinWidthPct,
                            min = RemotePrefs.CONTACT_BAR_MIN_WIDTH_MIN,
                            max = RemotePrefs.CONTACT_BAR_MIN_WIDTH_MAX,
                            enabled = snap.contactBar,
                            valueText = {
                                if (it == 0) stringResource(R.string.exp_contact_bar_min_width_off)
                                else stringResource(R.string.pct_value, it)
                            },
                            onCommit = actions::setContactBarMinWidthPct,
                        )
                    })
                    add(CardItem("contact-bar-hint") {
                        HintRow(stringResource(R.string.exp_contact_bar_note))
                    })
                },
            )

            // --- 小白条手势交互 ---
            item(key = "exp-gestures-title") {
                SmallTitle(text = stringResource(R.string.exp_section_gestures))
            }
            groupedCardItems(
                keyPrefix = "exp-gestures",
                items = buildList {
                    add(CardItem("outside-dismiss") {
                        SwitchRow(
                            title = stringResource(R.string.exp_outside_dismiss),
                            summary = stringResource(R.string.exp_outside_dismiss_summary),
                            checked = snap.flymeFreeformOutsideDismiss,
                            enabled = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformOutsideDismiss,
                        )
                    })
                    add(CardItem("outside-dismiss-action") {
                        DropdownRow(
                            title = stringResource(R.string.exp_outside_dismiss_action),
                            items = listOf(
                                stringResource(R.string.exp_outside_dismiss_action_single),
                                stringResource(R.string.exp_outside_dismiss_action_double),
                            ),
                            selectedIndex = snap.flymeFreeformOutsideDismissAction,
                            enabled = snap.flymeFreeformEnabled && snap.flymeFreeformOutsideDismiss,
                            onSelect = actions::setFlymeFreeformOutsideDismissAction,
                        )
                    })
                    add(CardItem("swipe-up-mini") {
                        SwitchRow(
                            title = stringResource(R.string.exp_swipe_up_mini),
                            summary = stringResource(R.string.exp_swipe_up_mini_summary),
                            checked = snap.flymeFreeformSwipeUpMini,
                            enabled = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformSwipeUpMini,
                        )
                    })
                    add(CardItem("swipe-up-hold-free") {
                        SwitchRow(
                            title = stringResource(R.string.exp_swipe_up_hold_free),
                            summary = stringResource(R.string.exp_swipe_up_hold_free_summary),
                            checked = snap.flymeFreeformSwipeUpHoldFree,
                            enabled = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformSwipeUpHoldFree,
                        )
                    })
                    add(CardItem("swipe-down-full") {
                        SwitchRow(
                            title = stringResource(R.string.exp_swipe_down_fullscreen),
                            summary = stringResource(R.string.exp_swipe_down_fullscreen_summary),
                            checked = snap.flymeFreeformSwipeDownFull,
                            enabled = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformSwipeDownFull,
                        )
                    })
                },
            )

            // --- 迷你小窗 (悬浮窗) ---
            // 迷你小窗长按菜单独立于 Flyme 样式小窗总开关，任意小窗转为迷你后均可长按操作
            item(key = "exp-mini-window-title") {
                SmallTitle(text = stringResource(R.string.exp_section_mini_window))
            }
            groupedCardItems(
                keyPrefix = "exp-mini-window",
                items = buildList {
                    add(CardItem("flyme-mini-menu") {
                        SwitchRow(
                            title = stringResource(R.string.exp_flyme_mini_menu),
                            summary = stringResource(R.string.exp_flyme_mini_menu_summary),
                            checked = snap.flymeMiniMenu,
                            onCheckedChange = actions::setFlymeMiniMenu,
                        )
                    })
                    // 倍率跟着菜单开关走：菜单关掉时这一项灰着，避免"改了没反应"。
                    add(CardItem("flyme-mini-enlarge") {
                        IntSliderRow(
                            title = stringResource(R.string.exp_flyme_mini_enlarge),
                            summary = stringResource(R.string.exp_flyme_mini_enlarge_summary),
                            value = snap.flymeMiniEnlargePct,
                            min = RemotePrefs.FLYME_MINI_ENLARGE_MIN,
                            max = RemotePrefs.FLYME_MINI_ENLARGE_MAX,
                            enabled = snap.flymeMiniMenu,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setFlymeMiniEnlargePct,
                        )
                    })
                },
            )

            // --- 回到桌面 ---
            // 这一项压的是 ROM 自己的行为（回桌面时把所有小窗收成迷你），和「禁止小窗偏移」同性质：
            // 不开 Flyme 样式小窗也该生效，所以**不**跟总开关联动。
            item(key = "exp-home-title") {
                SmallTitle(text = stringResource(R.string.exp_section_home))
            }
            groupedCardItems(
                keyPrefix = "exp-home",
                items = listOf(
                    CardItem("home-keep-freeform") {
                        SwitchRow(
                            title = stringResource(R.string.exp_home_keep_freeform),
                            summary = stringResource(R.string.exp_home_keep_freeform_summary),
                            checked = snap.homeKeepFreeform,
                            onCheckedChange = actions::setHomeKeepFreeform,
                        )
                    },
                ),
            )

            // --- 说明 ---
            item(key = "exp-info-title") {
                SmallTitle(text = stringResource(R.string.exp_section_info))
            }
            groupedCardItems(
                keyPrefix = "exp-info",
                items = listOf(
                    CardItem("info-text") {
                        HintRow(stringResource(R.string.exp_freeform_info))
                    },
                ),
            )

            item(key = "exp-bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * 气压校准那一行的说明文字（就是页面上的「现在到底什么状态」）。
 *
 * 状态来自 [RemotePrefs.Snapshot] 的八个字段，取值组合有讲究 —— 会话进行中用进度，结束以后用
 * 「上次结果」，而**上次失败不等于现在没有阈值**：失败只清了会话，上次算出来的校准值仍然生效
 * （[com.repl.bubbledrawer.bubble.PressureCalibrationState.applyEvent] 的规则），所以失败时要把
 * 仍在使用的那个值一并说出来，否则用户会以为重按坏了。
 */
@Composable
private fun pressureCalibrationSummary(snap: RemotePrefs.Snapshot): String {
    val total = com.repl.bubbledrawer.bubble.PressureCalibration.REQUIRED_ATTEMPTS
    if (snap.fanPressureCalibrationActive) {
        // 「太轻」要和「有效样本」分开说：低于 PressureCalibration.MIN_SAMPLE_HPA 的按压不算样本，
        // 但次数照算 —— 不告诉用户，他会以为按了 5 次就该成功。
        return when {
            snap.fanPressureCalibrationAttempts > 0 && !snap.fanPressureCalibrationLastValid -> stringResource(
                R.string.fan_pressure_calibration_status_running_light,
                snap.fanPressureCalibrationAttempts,
                total,
                snap.fanPressureCalibrationLastDelta,
                com.repl.bubbledrawer.bubble.PressureCalibration.MIN_SAMPLE_HPA,
            )

            snap.fanPressureCalibrationAttempts > 0 -> stringResource(
                R.string.fan_pressure_calibration_status_running_sample,
                snap.fanPressureCalibrationAttempts,
                total,
                snap.fanPressureCalibrationLastDelta,
            )

            else -> stringResource(
                R.string.fan_pressure_calibration_status_running,
                snap.fanPressureCalibrationAttempts,
                total,
            )
        }
    }

    val outcome = snap.fanPressureCalibrationOutcome
    val base = when (outcome) {
        com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.UNSUPPORTED ->
            stringResource(R.string.fan_pressure_calibration_status_unsupported)

        com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.TIMEOUT ->
            stringResource(R.string.fan_pressure_calibration_status_timeout)

        com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.FEW_SAMPLES ->
            stringResource(R.string.fan_pressure_calibration_status_few)

        else -> if (snap.fanPressureCalibrated && snap.fanPressureCalibratedHpa > 0f) {
            if (snap.fanPressureCalibrationValidCount > 0) {
                stringResource(
                    R.string.fan_pressure_calibration_status_ok,
                    snap.fanPressureCalibrationValidCount,
                    snap.fanPressureCalibratedHpa,
                )
            } else {
                // 没有样本数就别说「0 个有效样本」（人工写入/跨版本迁移可能留下这种组合）
                stringResource(R.string.fan_pressure_calibration_status_ok_no_count, snap.fanPressureCalibratedHpa)
            }
        } else {
            stringResource(R.string.fan_pressure_calibration_status_none)
        }
    }

    val failed = outcome == com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.TIMEOUT ||
        outcome == com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.FEW_SAMPLES
    return if (failed && snap.fanPressureCalibrated && snap.fanPressureCalibratedHpa > 0f) {
        base + stringResource(R.string.fan_pressure_calibration_status_kept, snap.fanPressureCalibratedHpa)
    } else {
        base
    }
}
