package com.repl.bubbledrawer.shortcut

/**
 * 「已选功能」的**详情**（跨进程传递用）。
 *
 * 为什么需要它：已选列表存的是键（`pkg#id`），而面板（SystemUI）要把它画出来就得先"认出"这个键，
 * 也就是**自己那份枚举结果里必须有这一条**。但两个进程的枚举结果并不相同 —— 面板能读 LauncherApps
 * 但读不到 App 侧缓存里那些条目，App 侧能读缓存但没有 SystemUI 的权限；任何一边少一条，用户就会
 * 看到「设置页里选好的功能，扇形面板里没有」，而且没有任何提示（`mapNotNull` 把它悄悄丢了）。
 *
 * 所以由 App 进程（它的缓存最全）把已选功能的名称/宿主/启动 Intent 一起发布到共享组，面板直接用
 * 这份详情画，不再依赖自己的枚举结果：图标用宿主应用图标兜底（见 `ShortcutIcon`），点击用 Intent 启动。
 *
 * 格式是自定义的行文本而不是 JSON：这里只做编解码，纯逻辑才好测（org.json / Base64 在 JVM 单测里
 * 是 stub）。字段间 `\t`、记录间 `\n`，值里的这两个字符转义。
 */
object FunctionPinDetails {

    /** 共享组里的键名（App 写，SystemUI 只读）。 */
    const val KEY = "pinned_function_details"

    /** 整个载荷的上限：几十条已选功能远小于此，超了就从尾部丢。 */
    const val MAX_CHARS = 64_000

    private const val FIELD = '\t'
    private const val RECORD = '\n'
    private const val ESCAPE = '\\'

    fun encode(items: List<ShortcutItem>): String {
        val out = StringBuilder()
        for (item in items) {
            val record = listOf(
                item.pinKey,
                item.label,
                item.subLabel.orEmpty(),
                item.intentUri.orEmpty(),
                // 第 5/6 段是后来加的：老版本只有前 4 段，decode 里按「缺省 = APP_SHORTCUT / 无底色」
                // 处理。磁贴与快捷方式都存 `pkg#id`，没有 type 的话扇形会把磁贴当快捷方式启动。
                item.type.name,
                (item.tintColor ?: 0).toString(),
                item.tileForeground.toString(),
            ).joinToString(FIELD.toString()) { escape(it) }
            if (out.length + record.length + 1 > MAX_CHARS) break
            if (out.isNotEmpty()) out.append(RECORD)
            out.append(record)
        }
        return out.toString()
    }

    fun decode(raw: String?): List<ShortcutItem> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        return text.split(RECORD).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(FIELD).map(::unescape)
            val key = parts.getOrNull(0)?.trim().orEmpty()
            if (key.isEmpty()) return@mapNotNull null
            val pkg = key.substringBefore('#')
            val id = key.substringAfter('#', "")
            val type = parts.getOrNull(4)?.let {
                runCatching { ShortcutType.valueOf(it) }.getOrDefault(ShortcutType.APP_SHORTCUT)
            } ?: ShortcutType.APP_SHORTCUT
            val tint = parts.getOrNull(5)?.toIntOrNull()?.takeIf { it != 0 }
            val tileForeground = parts.getOrNull(6)?.toBooleanStrictOrNull() ?: false
            ShortcutItem(
                id = id.ifEmpty { key },
                packageName = pkg,
                label = parts.getOrNull(1)?.ifBlank { null } ?: id.ifBlank { pkg },
                subLabel = parts.getOrNull(2)?.ifBlank { null },
                type = type,
                intentUri = parts.getOrNull(3)?.ifBlank { null },
                tintColor = tint,
                tileForeground = tileForeground,
            )
        }
    }

    /**
     * 枚举里没有这一条时的占位项：**至少让它在已选区里看得见、能取消**。
     *
     * 键是 `pkg#id`，两者都能还原出来；标题优先用应用名（包名对人没有意义）。
     */
    fun placeholder(key: String, appLabel: String? = null): ShortcutItem {
        val pkg = key.substringBefore('#')
        val id = key.substringAfter('#', "")
        return ShortcutItem(
            id = id.ifEmpty { key },
            packageName = pkg,
            label = appLabel?.ifBlank { null } ?: id.ifBlank { pkg },
            subLabel = null,
            type = ShortcutType.APP_SHORTCUT,
        )
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                ESCAPE, FIELD, RECORD -> {
                    append(ESCAPE)
                    when (c) {
                        FIELD -> append('t')
                        RECORD -> append('n')
                        else -> append(ESCAPE)
                    }
                }
                else -> append(c)
            }
        }
    }

    private fun unescape(value: String): String {
        if (!value.contains(ESCAPE)) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == ESCAPE && i + 1 < value.length) {
                when (value[i + 1]) {
                    't' -> { out.append(FIELD); i += 2 }
                    'n' -> { out.append(RECORD); i += 2 }
                    ESCAPE -> { out.append(ESCAPE); i += 2 }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
