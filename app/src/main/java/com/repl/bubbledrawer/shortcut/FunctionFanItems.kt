package com.repl.bubbledrawer.shortcut

/**
 * 扇形（那个 5/6 个图标、重按翻页的转盘）里要显示的功能条目。
 *
 * 扇形的每一枚 tile 只有图标、没有文字，所以"这条到底是什么"全靠图：优先用**上一次枚举结果**里的
 * 那一条（它带着快捷方式自己的图标，必要时还能用 LauncherApps 直接启动），其次用 App 发布过来的
 * 详情（名称/宿主/启动 Intent，没有图标就退回宿主应用图标），最后才是占位项。
 *
 * 纯函数：扇形的构建在**手势中间的主线程**上跑，逻辑必须一眼看得完、且不用设备就能测。
 */
object FunctionFanItems {

    /**
     * @param keys 用户选中的键，**顺序就是扇形里的顺序**
     * @param details App 进程发布的详情（可能为空：桥还没绑上、或 App 没跑过）
     * @param cached 从最近一次枚举结果里取一条（不触发新的扫描）
     */
    fun resolve(
        keys: List<String>,
        details: List<ShortcutItem>,
        cached: (String) -> ShortcutItem?,
    ): List<ShortcutItem> {
        if (keys.isEmpty()) return emptyList()
        val byKey = details.associateBy { it.pinKey }
        return keys.map { key ->
            cached(key) ?: byKey[key] ?: FunctionPinDetails.placeholder(key)
        }
    }
}
