package com.repl.bubbledrawer.shortcut

import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

enum class ShortcutType {
    /** 来自 LauncherApps / 应用自身声明的快捷方式（微信扫一扫等） */
    APP_SHORTCUT,
    /** 来自桌面 Provider (content://.../favorites) 的桌面固定捷径 */
    DESKTOP_PINNED,

    /**
     * 控制中心的「应用磁贴」：应用声明的 `TileService`（`android.service.quicksettings.action.QS_TILE`）。
     *
     * 它和快捷方式不是一回事：启动要回到 SystemUI 里 `createTile("custom(pkg/cls)").click()`，
     * 不能在 App 进程绑服务（`BIND_QUICK_SETTINGS_TILE` 是 signature|privileged）。
     */
    QS_TILE,
}

data class ShortcutItem(
    val id: String,
    val packageName: String,
    val label: String,
    val subLabel: String? = null,
    val type: ShortcutType,
    val shortcutInfo: ShortcutInfo? = null,
    val intentUri: String? = null,
    val iconBitmap: Bitmap? = null,
    val iconDrawable: Drawable? = null,
    val appIconBitmap: Bitmap? = null,
    val isEnabled: Boolean = true,
    /**
     * 磁贴底色（ARGB）。只对 [ShortcutType.QS_TILE] 有值：SystemUI 扫描时用**宿主应用图标**算好，
     * 随 [com.repl.bubbledrawer.shortcut.ShortcutBridge] / [FunctionPinDetails] 一起过桥 ——
     * 面板（SystemUI）和管理页（App）都能直接画，不用各自再读一遍图标。
     */
    val tintColor: Int? = null,
    /**
     * 这个 [ShortcutType.QS_TILE] 的 icon 是否真的是**磁贴前景**（透明底图形）。
     *
     * `true` → UI 归一化后漂白成白色前景；`false` → 服务没声明 icon，`iconBitmap` 其实是
     * 应用图标兜底，原样画（把彩色应用图标漂成白块比不漂更糟）。跨进程一起传。
     */
    val tileForeground: Boolean = false,
) {
    /**
     * 收藏用的稳定标识 `pkg#id`。
     *
     * `id` 是应用自己声明的字符串（动态快捷方式尤其随意），唯一不能出现的是分隔符 `;`
     * —— [FunctionPinStore] 用它分隔条目。命中时替换成 `_`：生成与匹配用的是同一个
     * getter，所以替换不影响对应关系，只是让持久化格式不会被一条 id 撕成两条。
     */
    val pinKey: String get() = "$packageName#${id.replace(';', '_')}"
}
