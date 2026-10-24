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
     * 内建的「聚合支付」功能页条目（不来自任何应用，扫描/收藏/搜索都不参与）：点击把联系人条切到
     * 聚合支付模式，条里才放真正的微信/支付宝支付入口（见 [AggregatePayment.resolveEntries]）。
     */
    AGGREGATE_PAYMENT,
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
