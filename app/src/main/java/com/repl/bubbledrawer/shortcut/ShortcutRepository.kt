package com.repl.bubbledrawer.shortcut

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.repl.bubbledrawer.launch.MiuiFreeform
import com.repl.bubbledrawer.lightopen.LightOpen
import com.repl.bubbledrawer.xposed.SettingsStore
import org.xmlpull.v1.XmlPullParser

object ShortcutRepository {

    private const val TAG = "ShortcutRepo"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** 两次扫描之间的最小间隔（毫秒）—— 刷新广播是导出的，挡一下风暴。 */
    private const val SCAN_MIN_INTERVAL_MS = 3_000L

    /** 扫描结果的复用窗口：面板连着开几次不该重扫（几十毫秒到几百毫秒 + 几百张图标）。 */
    private const val SCAN_TTL_MS = 30_000L

    /** App 侧缓存多旧才值得再请求一次全量上报。 */
    private const val REFRESH_TTL_MS = 30 * 60_000L

    /** App 侧两次刷新请求之间的最小间隔。 */
    private const val REFRESH_MIN_INTERVAL_MS = 60_000L

    private val scanLock = Any()
    private var lastScan: List<ShortcutItem> = emptyList()
    private var lastScanAt = 0L

    /** 上一次扫描里 LauncherApps 是否真的答上来了（false = 那份清单不完整，不能上报覆盖）。 */
    private var lastScanLauncherAppsOk = false
    private var lastReportedFingerprint = 0
    private var lastRefreshRequestAt = 0L

    /** 「上一次上报的是空清单」的哨兵指纹（真清单的 hash 也可能是 0，所以不能复用 0）。 */
    private const val EMPTY_FINGERPRINT = -1

    /**
     * 将 Drawable 转换为 ARGB_8888 Bitmap 方便 Compose 高性能渲染。
     *
     * 尺寸**固定**在 [sizePx]（默认 [ShortcutBridge.ICON_MAX_PX]）：这里曾经写的是
     * `maxOf(sizePx, minOf(w, h))`，而自适应图标的 intrinsic 是 108dp —— 3.25 倍密度下就是 351px，
     * 一条快捷方式一张 351×351 ARGB（≈493KB）。一次全量扫描几百条，几百 MB 全压在 SystemUI 里
     * （还要原样留存到面板关闭）。
     */
    fun drawableToBitmap(drawable: Drawable, sizePx: Int = ShortcutBridge.ICON_MAX_PX): Bitmap {
        val target = sizePx.coerceAtLeast(1)
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            // 已经够小就直接交出去：不复制、不放大。
            if (!bmp.isRecycled && bmp.width <= target && bmp.height <= target) return bmp
        }
        val bitmap = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, target, target)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * 判断一个 Bitmap 是否是透明镂空底图标（即没有底板，图案悬浮其中）。
     *
     * 关键判据：
     * 1. 半透明灰底圆盘检测：小米官方应用（便签/计算器/天气等）与 LibChecker 均自带
     *    低 alpha（8..60，约占画布 30%~78%）的半透明灰底圆盘，绝非镂空；
     * 2. 覆盖率检测：微信、网易云音乐、Telegram、美团等 Adaptive Icon 圆形快捷方式，
     *    其有效像素覆盖了整个内切圆区域（通常约为 67.8% ~ 78.5%）。
     * 只有当中低透明度底色不显著（< 10%）且总体可见像素覆盖率也很低（< 46%）时，
     * 才属于真正的镂空/单色剪影透明底图标，此时才需要补上圆形底托并对前景图案进行居中归一化。
     */
    fun isTransparentBitmap(bitmap: Bitmap): Boolean {
        // getPixels 对 Config.HARDWARE 会抛 IllegalStateException，而这里的调用方在 Compose 组合里
        // （SystemUI 的主线程）—— 宁可判成「不是透明底」，也不能让一次组合把系统界面带崩。
        if (bitmap.config == Bitmap.Config.HARDWARE) return false
        if (!bitmap.hasAlpha()) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 2 || h <= 2) return false

        // 整幅一次性读进数组：逐像素 getPixel 是 JNI 调用，160×160 就是两万五千次，
        // 而这个判断在 Compose 组合里同步跑（见 ShortcutIcon）。
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var midAlphaPixels = 0
        var visiblePixels = 0
        val step = (maxOf(w, h) / 32).coerceAtLeast(1)
        var totalSampled = 0

        for (y in 0 until h step step) {
            val row = y * w
            for (x in 0 until w step step) {
                totalSampled++
                val a = android.graphics.Color.alpha(pixels[row + x])
                if (a in 8..60) {
                    midAlphaPixels++
                }
                if (a > 8) {
                    visiblePixels++
                }
            }
        }
        val safeTotal = totalSampled.coerceAtLeast(1)
        val midRatio = midAlphaPixels.toFloat() / safeTotal
        // 1. 如果存在大面积半透明灰底圆盘（小米系统应用、LibChecker），绝非透明底
        if (midRatio >= 0.10f) {
            return false
        }
        // 2. 如果总体有效像素覆盖率充足（微信、网易云、Telegram、美团等圆形或实底图标），绝非透明底
        val coverage = visiblePixels.toFloat() / safeTotal
        return coverage < 0.46f
    }

    /**
     * 判断透明底 Bitmap 中非透明前景图案是否主要是浅色/纯白。
     */
    fun isLightForeground(bitmap: Bitmap): Boolean {
        if (bitmap.config == Bitmap.Config.HARDWARE) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var totalLightness = 0f
        var count = 0
        val step = (maxOf(w, h) / 10).coerceAtLeast(1)
        for (y in 0 until h step step) {
            val row = y * w
            for (x in 0 until w step step) {
                val pixel = pixels[row + x]
                val a = android.graphics.Color.alpha(pixel)
                if (a > 100) {
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    totalLightness += l
                    count++
                }
            }
        }
        if (count == 0) return false
        return (totalLightness / count) > 0.82f
    }

    /**
     * 对透明底图标的前景图案进行视觉归一化（Foreground Size Normalization）：
     * 1. 扫描非透明像素的包围盒（Bounding Box：left, top, right, bottom），剔除四周无意义的空白；
     * 2. 计算前景图案的长宽，按目标视觉比例（例如占画布直径的 64%）等比例缩放；
     * 3. 将前景图案严格居中绘制在尺寸为 targetSize x targetSize 的正方形透明画布正中心。
     *
     * 这样无论第三方应用提供的图标偏大、偏小、还是偏移不居中，最终呈现出的视觉大小和重心完全整齐统一！
     */
    fun normalizeTransparentForeground(src: Bitmap, targetSize: Int = 192, targetFraction: Float = 0.64f): Bitmap {
        if (src.config == Bitmap.Config.HARDWARE) return src
        if (!src.hasAlpha() || src.width <= 0 || src.height <= 0) return src
        val w = src.width
        val h = src.height

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        // 提取有效前景像素边界 (alpha > 25) —— 一次性读入，别逐像素 getPixel（JNI 调用）。
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val alpha = android.graphics.Color.alpha(pixels[row + x])
                if (alpha > 25) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // 如果没有找到有效前景（全透明），或者有效区域占满整个画布（非透明剪影），直接返回
        if (maxX < minX || maxY < minY) return src
        val contentW = (maxX - minX + 1).coerceAtLeast(1)
        val contentH = (maxY - minY + 1).coerceAtLeast(1)

        val targetContentSize = targetSize * targetFraction
        val scale = targetContentSize / maxOf(contentW, contentH)
        val destW = (contentW * scale).toInt().coerceAtLeast(1)
        val destH = (contentH * scale).toInt().coerceAtLeast(1)

        val outBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        val srcRect = android.graphics.Rect(minX, minY, maxX + 1, maxY + 1)
        val destX = (targetSize - destW) / 2
        val destY = (targetSize - destH) / 2
        val destRect = android.graphics.Rect(destX, destY, destX + destW, destY + destH)

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, srcRect, destRect, paint)

        return outBitmap
    }

    /**
     * 同时加载快捷方式（功能）与桌面固定捷径。
     */
    fun loadShortcuts(
        context: Context,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
    ): Pair<List<ShortcutItem>, List<ShortcutItem>> {
        val progress = onProgress?.let { ShortcutProgress(it, countCandidates(context)) }
        val appShortcuts = loadAppShortcuts(context, progress)
        // 扫描里已经注入过桌面捷径并计过数；这里再读一次只为返回给调用方（去重由调用方做），
        // 不再重复推进进度，否则数字会超过总数。
        val desktopShortcuts = loadDesktopShortcuts(context)
        progress?.setDone(appShortcuts.size + desktopShortcuts.size)
        return Pair(appShortcuts, desktopShortcuts)
    }

    /** 粗略的总数：LauncherApps 快捷方式 + 应用磁贴 + 桌面捷径 + 微信/支付宝兜底 2 条。 */
    private fun countCandidates(context: Context): Int {
        var total = 2
        runCatching {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())
            if (launcherApps != null) {
                for (profile in profiles) {
                    val q = LauncherApps.ShortcutQuery().setQueryFlags(1051)
                    total += launcherApps.getShortcuts(q, profile)?.size ?: 0
                }
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            total += context.packageManager.queryIntentServices(
                Intent("android.service.quicksettings.action.QS_TILE"),
                0,
            ).size
        }
        runCatching {
            val cursor = context.contentResolver.query(
                Uri.parse("content://com.miui.home.launcher.settings/favorites"),
                arrayOf("_id"),
                null,
                null,
                null,
            )
            cursor?.use { total += it.count }
        }
        return total.coerceAtLeast(1)
    }

    /** 扫描进度：total 是估算值，done 只增不减；total < done 时自动抬高，避免 1/0。 */
    internal class ShortcutProgress(
        private val callback: (Int, Int) -> Unit,
        initialTotal: Int,
    ) {
        private var total = initialTotal.coerceAtLeast(1)
        private var done = 0

        fun step(count: Int = 1) {
            done += count
            emit()
        }

        fun setDone(value: Int) {
            if (value > done) done = value
            emit()
        }

        private fun emit() {
            if (done > total) total = done
            callback(done, total)
        }
    }

    /**
     * 在 SystemUI 进程内直接扫描全量快捷方式（需要 ACCESS_SHORTCUTS 权限），并上报给 App 端。
     *
     * **只有「枚举完整」的结果才允许上报**。理由是这个方向的上报是**覆盖**语义：App 会用收到的
     * 那份替换掉整个缓存。一次失败的枚举（LauncherApps 抛异常、开机还没解锁时包可见性只给一个
     * 直启子集）会返回一份「少了很多条」甚至空的清单，照发出去就等于把用户已经有的功能缓存删掉，
     * 「已选功能」在两处一起变空。所以枚举不完整时宁可不发（App 侧还有自己的 XML 兜底），
     * 并且明确记一行 `INCOMPLETE` 让诊断页能说清「页面为什么只有静态快捷方式」。
     */
    fun scanAndReportFromSystemUi(context: Context, force: Boolean = false): ScanReport {
        val list = scanSystemUiShortcutsInternal(context, force)
        if (!isEnumerationComplete(context)) {
            Log.i(TAG, "SHORTCUT_REPORT_SKIPPED enumeration-incomplete unlocked=${isUserUnlocked(context)} " +
                "launcherApps=${lastScanLauncherAppsOk} items=${list.size}")
            return ScanReport(list.size, ShortcutBridge.ReportResult.INCOMPLETE)
        }
        if (list.isEmpty()) {
            // 枚举完整且一条都没有 = 这台机器上真的没有（应用被卸载了）：报空表让 App 清掉旧条目。
            if (!force && lastReportedFingerprint == EMPTY_FINGERPRINT) {
                return ScanReport(0, ShortcutBridge.ReportResult.UNCHANGED)
            }
            val ok = runCatching { ShortcutBridge.reportToApp(context, emptyList()) }
                .onFailure { Log.w(TAG, "Failed reporting empty shortcut list", it) }
                .getOrDefault(false)
            lastReportedFingerprint = if (ok) EMPTY_FINGERPRINT else 0
            return ScanReport(0, if (ok) ShortcutBridge.ReportResult.SENT else ShortcutBridge.ReportResult.FAILED)
        }
        // 指纹含标题与磁贴底色：只按 pinKey 比对会让「快捷方式改名/换图标/磁贴换色」永远同步不到
        // App（那边显示的是缓存）。
        val fingerprint = list.joinToString("|") {
            "${it.pinKey}=${it.label}=${it.tintColor ?: 0}=${it.tileForeground}"
        }.hashCode()
        if (!force && fingerprint == lastReportedFingerprint) {
            // 内容一字不差：不重复上报。这一条是「App 请求刷新 → SystemUI 上报 → App 存盘 →
            // 通知监听者 → 又请求刷新」那个环的最后一道闸 —— 只要数据没变，环就断了。
            Log.i(TAG, "SHORTCUT_REPORT_SKIPPED unchanged items=${list.size}")
            return ScanReport(list.size, ShortcutBridge.ReportResult.UNCHANGED)
        }
        val ok = runCatching { ShortcutBridge.reportToApp(context, list) }
            .onFailure { Log.w(TAG, "Failed reporting shortcuts from SystemUI to App", it) }
            .getOrDefault(false)
        lastReportedFingerprint = if (ok) fingerprint else 0
        return ScanReport(list.size, if (ok) ShortcutBridge.ReportResult.SENT else ShortcutBridge.ReportResult.FAILED)
    }

    /**
     * 这份清单可信吗 = 用户已解锁 + LauncherApps 这一次真的答上来了。
     *
     * 前者是因为开机未解锁时包可见性只给直启子集（设备实测：216 个应用只枚举出 2 个）；后者是因为
     * `getShortcuts` 的异常全被 runCatching 吞掉，失败与「真的没有」在结果上长得一模一样。
     */
    private fun isEnumerationComplete(context: Context): Boolean =
        isUserUnlocked(context) && lastScanLauncherAppsOk

    /** 用户已解锁才敢相信「应用清单是完整的」。UserManager 拿不到时按不可信处理（fail closed）。 */
    private fun isUserUnlocked(context: Context): Boolean =
        runCatching {
            (context.getSystemService(android.os.UserManager::class.java))?.isUserUnlocked == true
        }.getOrDefault(false)

    /**
     * 从**已经扫过**的结果里取一条，绝不触发新的扫描。
     *
     * 扇形的 tile 是在手势中间、主线程上构建的：那里调 [scanSystemUiShortcutsInternal] 可能真的去枚举
     * 所有应用并渲染几百张图标（几百毫秒），划出扇形就会明显卡住。所以扇形只"顺便"用上一次的结果，
     * 拿不到就用调用方自己的兜底（App 发布的详情 / 宿主应用图标）。
     */
    fun peekCached(pinKey: String): ShortcutItem? = synchronized(scanLock) {
        lastScan.firstOrNull { it.pinKey == pinKey }
    }

    /** 一次扫描 + 上报的结果，给 hook 侧记一行日志用（诊断页读的就是这些标记）。 */
    data class ScanReport(val items: Int, val result: ShortcutBridge.ReportResult)

    /**
     * 扫描结果缓存（含 30s TTL 与 3s 最小间隔）。
     *
     * 一次全量扫描要枚举所有用户/分身、解析每个应用的 shortcuts.xml、还要为**每条**快捷方式
     * 渲染一张图标位图 —— 面板每次打开、设置页每次刷新都重跑一遍是不必要的（面板可以连开十次）。
     * 强制刷新（App 明确要求，且发送方已被验证是模块自己）穿透这两个节流：让它拿到的一定是新结果，
     * 否则「刚装的应用看不到」会被 3s 窗口里的旧结果"确认"成一次完整枚举。
     */
    internal fun scanSystemUiShortcutsInternal(
        context: Context,
        force: Boolean = false,
        progress: ShortcutProgress? = null,
    ): List<ShortcutItem> =
        synchronized(scanLock) {
            val now = SystemClock.uptimeMillis()
            val age = now - lastScanAt
            if (!force) {
                if (lastScan.isNotEmpty() && age < SCAN_TTL_MS) {
                    progress?.setDone(lastScan.size)
                    return lastScan
                }
                if (age < SCAN_MIN_INTERVAL_MS) {
                    progress?.setDone(lastScan.size)
                    return lastScan
                }
            }
            val outcome = scanSystemUiShortcutsUncached(context, progress)
            lastScan = outcome.items
            lastScanLauncherAppsOk = outcome.launcherAppsOk
            lastScanAt = now
            progress?.setDone(outcome.items.size)
            outcome.items
        }

    private class ScanOutcome(val items: List<ShortcutItem>, val launcherAppsOk: Boolean)

    private fun scanSystemUiShortcutsUncached(
        context: Context,
        progress: ShortcutProgress? = null,
    ): ScanOutcome {
        val result = mutableListOf<ShortcutItem>()
        val seenKeys = mutableSetOf<String>()
        val density = context.resources.displayMetrics.densityDpi
        val pm = context.packageManager
        var launcherAppsOk = false


        runCatching {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())

            if (launcherApps != null) {
                for (profile in profiles) {
                    val rawShortcuts: List<android.content.pm.ShortcutInfo>? = runCatching {
                        // 1051 = MATCH_DYNAMIC(1) | MATCH_PINNED(2) | MATCH_MANIFEST(8) | MATCH_CACHED(16) | FLAG_MATCH_PINNED_BY_ANY_LAUNCHER(1024)
                        val q = LauncherApps.ShortcutQuery().setQueryFlags(1051)
                        launcherApps.getShortcuts(q, profile)
                    }.getOrNull() ?: runCatching {
                        // 1035 = Flyme SystemUI standard flag
                        val q = LauncherApps.ShortcutQuery().setQueryFlags(1035)
                        launcherApps.getShortcuts(q, profile)
                    }.getOrNull() ?: runCatching {
                        // 27 = MATCH_DYNAMIC(1) | MATCH_PINNED(2) | MATCH_MANIFEST(8) | MATCH_CACHED(16)
                        val q = LauncherApps.ShortcutQuery().setQueryFlags(27)
                        launcherApps.getShortcuts(q, profile)
                    }.getOrNull()

                    if (rawShortcuts == null) {
                        // 三种查询都抛了（没有 ACCESS_SHORTCUTS / 服务不可用）：这一份清单**不完整**，
                        // 绝不能拿去覆盖 App 的缓存（见 scanAndReportFromSystemUi 的注释）。
                        Log.w(TAG, "LauncherApps query failed for profile=$profile")
                        continue
                    }
                    launcherAppsOk = true

                    for (info in rawShortcuts) {
                        if (!info.isEnabled) continue
                        val label = info.shortLabel?.toString() ?: info.longLabel?.toString() ?: continue
                        val key = "${info.`package`}#${info.id}"
                        if (!seenKeys.add(key)) continue

                        // 多层级读取图标：density -> density=0 -> badgedIcon -> 宿主应用图标兜底，彻底杜绝只显示首字
                        val icon = runCatching { launcherApps.getShortcutIconDrawable(info, density) }.getOrNull()
                            ?: runCatching { launcherApps.getShortcutIconDrawable(info, 0) }.getOrNull()
                            ?: runCatching { launcherApps.getShortcutBadgedIconDrawable(info, density) }.getOrNull()
                            ?: runCatching { launcherApps.getShortcutBadgedIconDrawable(info, 0) }.getOrNull()
                            ?: runCatching { pm.getApplicationIcon(info.`package`) }.getOrNull()

                        val bitmap = icon?.let { runCatching { drawableToBitmap(it, ShortcutBridge.ICON_MAX_PX) }.getOrNull() }
                        val appLabel = runCatching {
                            val appInfo = pm.getApplicationInfo(info.`package`, 0)
                            pm.getApplicationLabel(appInfo).toString()
                        }.getOrNull()

                        result.add(
                            ShortcutItem(
                                id = info.id,
                                packageName = info.`package`,
                                label = label,
                                subLabel = appLabel,
                                type = ShortcutType.APP_SHORTCUT,
                                shortcutInfo = info,
                                // ShortcutInfo 是跨进程的快照，**过不了 JSON 那道桥**：App 进程（管理页）
                                // 读的是缓存，拿到的 shortcutInfo 一定是 null —— 那时只能靠 intent 启动，
                                // 所以这里必须把它的 Intent 一并留下来。不留 = 管理页里点任何一条
                                // LauncherApps 来源的功能都没反应（launchShortcut 两条路都走不通）。
                                intentUri = info.intents?.firstOrNull()?.toUri(0),
                                iconBitmap = bitmap,
                                iconDrawable = icon,
                            )
                        )
                        progress?.step()
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "LauncherApps scan failed in SystemUI", it)
        }

        // 注入控制中心的应用磁贴（TileService）。扫描要在 LauncherApps 之后：万一快捷方式枚举
        // 失败，磁贴仍然应该出现在功能页里；两者的键空间不同（磁贴 id 是服务类名），不会撞。
        runCatching {
            for (tile in loadQsTiles(context, seenKeys, progress)) {
                result.add(tile)
            }
        }.onFailure {
            Log.w(TAG, "QS_TILE_SCAN_FAILED", it)
        }

        // 注入桌面固定快捷方式
        runCatching {
            val desktopShortcuts = loadDesktopShortcuts(context, progress)
            for (d in desktopShortcuts) {
                if (seenKeys.add(d.pinKey)) {
                    result.add(d)
                }
            }
        }

        // 注入微信、支付宝扫一扫/付款码核心兜底
        injectEssentialShortcuts(context, result, seenKeys)
        return ScanOutcome(result, launcherAppsOk)
    }

    /**
     * 读取各应用向系统注册的 Shortcuts。
     * - 在 SystemUI 进程内直接调用 LauncherApps 读取全量 shortcuts（含 Telegram 群组等动态快捷方式）；
     * - 在普通应用进程（管理页）优先读 SystemUI 上报的完整缓存，缓存空/过期时请求它刷新一次，
     *   辅以静态 XML 解析兜底；
     * - 无论何种情况均保障微信、支付宝扫一扫与付款码兜底可用。
     *
     * 上报**只发生在 [scanAndReportFromSystemUi]**：面板每次打开都会走到这里，而「每次打开都
     * 重新分片上报几百张图标」既贵又没有必要（App 只在缓存空/过期时才要新数据）。
     */
    internal fun loadAppShortcuts(
        context: Context,
        progress: ShortcutProgress? = null,
    ): List<ShortcutItem> {
        val result = mutableListOf<ShortcutItem>()
        val seenKeys = mutableSetOf<String>()

        // 1. 优先尝试直接从 LauncherApps 扫描（在 SystemUI 进程内直接成功）
        var launcherAppsSuccess = false
        val scanned = runCatching { scanSystemUiShortcutsInternal(context, progress = progress) }.getOrNull()
        // 磁贴条目没有 shortcutInfo（那是 LauncherApps 专有的）；只看 shortcutInfo 会让「机器上
        // 没有快捷方式、但有一堆应用磁贴」的清单被误判成「枚举失败」。
        if (!scanned.isNullOrEmpty() &&
            (scanned.any { it.shortcutInfo != null } || scanned.any { it.type == ShortcutType.QS_TILE })
        ) {
            launcherAppsSuccess = true
            for (item in scanned) {
                if (seenKeys.add(item.pinKey)) {
                    result.add(item)
                }
            }
        }

        // 2. 如果直接读取失败（普通 App 进程）：优先读取 SystemUI 上报的完整缓存
        if (!launcherAppsSuccess) {
            val cached = ShortcutCacheStore.load(context)
            if (!cached.isNullOrEmpty()) {
                for (item in cached) {
                    if (seenKeys.add(item.pinKey)) {
                        result.add(item)
                    }
                }
            }
            // 只在「手上没有 / 已经很旧」时请求 SystemUI 重扫一次（并在本进程内限流）。
            // 以前这里无条件广播：而存盘又会通知监听者（设置页）→ 再走一遍这个函数 → 再广播，
            // 于是设置页一打开就是全量扫描 + 几百张图标的 PNG 编码 + 分片传输，一圈接一圈。
            maybeRequestRefresh(context, cached.isNullOrEmpty())

            // 3. 静态 XML 作为兜底补充
            loadXmlShortcuts(context, result, seenKeys)
        }

        // 4. 微信、支付宝扫一扫与付款码核心兜底
        injectEssentialShortcuts(context, result, seenKeys)
        // 缓存/XML 分支没有逐条 step：这里把进度推到最终条数（只增不减）。
        progress?.setDone(result.size)
        return result
    }

    private fun maybeRequestRefresh(context: Context, cacheEmpty: Boolean) {
        val stale = cacheEmpty || ShortcutCacheStore.ageMillis(context) > REFRESH_TTL_MS
        if (!stale) return
        val now = SystemClock.uptimeMillis()
        synchronized(scanLock) {
            if (now - lastRefreshRequestAt < REFRESH_MIN_INTERVAL_MS) return
            lastRefreshRequestAt = now
        }
        Log.i(TAG, "SHORTCUT_REFRESH_REQUESTED cacheEmpty=$cacheEmpty")
        ShortcutBridge.requestRefresh(context)
    }

    /**
     * 免权限从系统所有已安装应用的 APK 中解析静态 Shortcuts（shortcuts.xml）。
     */
    fun loadXmlShortcuts(
        context: Context,
        result: MutableList<ShortcutItem>,
        seenKeys: MutableSet<String>,
    ) {
        val pm = context.packageManager
        val density = context.resources.displayMetrics.densityDpi

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = runCatching {
            pm.queryIntentActivities(mainIntent, PackageManager.GET_META_DATA)
        }.getOrNull().orEmpty()

        for (resolveInfo in resolveInfos) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            val metaData = activityInfo.metaData
            val appMetaData = activityInfo.applicationInfo?.metaData

            val xmlResId = metaData?.getInt("android.app.shortcuts", 0)?.takeIf { it != 0 }
                ?: appMetaData?.getInt("android.app.shortcuts", 0)?.takeIf { it != 0 }
                ?: 0

            if (xmlResId == 0) continue

            runCatching {
                val appInfo = activityInfo.applicationInfo
                val appRes = pm.getResourcesForApplication(appInfo)
                val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull()?.ifBlank { null } ?: pkg

                val parser = appRes.getXml(xmlResId)
                parseShortcutsXml(
                    parser = parser,
                    appRes = appRes,
                    pkg = pkg,
                    appLabel = appLabel,
                    density = density,
                    result = result,
                    seenKeys = seenKeys,
                    pm = pm,
                )
            }.onFailure {
                Log.w(TAG, "Failed parsing shortcuts XML for $pkg", it)
            }
        }
    }

    private fun parseShortcutsXml(
        parser: XmlResourceParser,
        appRes: Resources,
        pkg: String,
        appLabel: String,
        density: Int,
        result: MutableList<ShortcutItem>,
        seenKeys: MutableSet<String>,
        pm: PackageManager,
    ) {
        var eventType = parser.eventType
        var currentShortcutId: String? = null
        var currentEnabled = true
        var currentIconRes = 0
        var currentLabel: String? = null
        val currentIntents = mutableListOf<Intent>()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name
                if (tagName == "shortcut") {
                    currentShortcutId = getAttrValue(parser, "shortcutId")
                    currentEnabled = getAttrBoolean(parser, "enabled", true)
                    currentIconRes = getAttrResId(parser, "icon")

                    val shortLabelRes = getAttrResId(parser, "shortcutShortLabel")
                    val shortLabelStr = getAttrValue(parser, "shortcutShortLabel")
                    val longLabelRes = getAttrResId(parser, "shortcutLongLabel")
                    val longLabelStr = getAttrValue(parser, "shortcutLongLabel")

                    val resolvedShort = if (shortLabelRes != 0) runCatching { appRes.getString(shortLabelRes) }.getOrNull() else shortLabelStr
                    val resolvedLong = if (longLabelRes != 0) runCatching { appRes.getString(longLabelRes) }.getOrNull() else longLabelStr

                    currentLabel = resolvedShort?.ifBlank { null } ?: resolvedLong?.ifBlank { null }
                    currentIntents.clear()
                } else if (tagName == "intent" && currentShortcutId != null) {
                    val action = getAttrValue(parser, "action") ?: Intent.ACTION_VIEW
                    val targetPkg = getAttrValue(parser, "targetPackage") ?: pkg
                    val targetClass = getAttrValue(parser, "targetClass")
                    val data = getAttrValue(parser, "data")

                    val intent = Intent(action).apply {
                        if (!targetPkg.isNullOrEmpty() && !targetClass.isNullOrEmpty()) {
                            setClassName(targetPkg, targetClass)
                        } else if (!targetPkg.isNullOrEmpty()) {
                            setPackage(targetPkg)
                        }
                        if (!data.isNullOrEmpty()) {
                            setData(Uri.parse(data))
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    currentIntents.add(intent)
                } else if (tagName == "extra" && currentIntents.isNotEmpty()) {
                    val extraName = getAttrValue(parser, "name")
                    val extraValue = getAttrValue(parser, "value")
                    if (!extraName.isNullOrEmpty() && extraValue != null) {
                        currentIntents.last().putExtra(extraName, extraValue)
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                if (parser.name == "shortcut") {
                    val id = currentShortcutId
                    if (currentEnabled && !id.isNullOrEmpty() && currentIntents.isNotEmpty()) {
                        val key = "$pkg#$id"
                        if (seenKeys.add(key)) {
                            val label = currentLabel ?: id
                            val iconDrawable = if (currentIconRes != 0) {
                                runCatching { appRes.getDrawable(currentIconRes, null) }.getOrNull()
                            } else null
                            val finalIconDrawable = iconDrawable ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                            val iconBmp = finalIconDrawable?.let { drawableToBitmap(it) }

                            val targetIntent = currentIntents.last()

                            result.add(
                                ShortcutItem(
                                    id = id,
                                    packageName = pkg,
                                    label = label,
                                    subLabel = appLabel,
                                    type = ShortcutType.APP_SHORTCUT,
                                    intentUri = targetIntent.toUri(0),
                                    iconBitmap = iconBmp,
                                    iconDrawable = iconDrawable,
                                    isEnabled = true,
                                )
                            )
                        }
                    }
                    currentShortcutId = null
                    currentLabel = null
                    currentIntents.clear()
                }
            }
            eventType = parser.next()
        }
    }

    private fun getAttrValue(parser: XmlResourceParser, name: String): String? {
        val v = parser.getAttributeValue(ANDROID_NS, name) ?: parser.getAttributeValue(null, name)
        if (v != null) return v
        val count = parser.attributeCount
        for (i in 0 until count) {
            val attrName = parser.getAttributeName(i)
            if (attrName == name || attrName.endsWith(":$name")) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun getAttrResId(parser: XmlResourceParser, name: String): Int {
        val resId = parser.getAttributeResourceValue(ANDROID_NS, name, 0)
        if (resId != 0) return resId
        val resIdNoNs = parser.getAttributeResourceValue(null, name, 0)
        if (resIdNoNs != 0) return resIdNoNs
        val raw = getAttrValue(parser, name)
        if (raw != null && raw.startsWith("@")) {
            return raw.substring(1).toIntOrNull() ?: 0
        }
        return 0
    }

    private fun getAttrBoolean(parser: XmlResourceParser, name: String, default: Boolean): Boolean {
        val res = parser.getAttributeBooleanValue(ANDROID_NS, name, default)
        if (res != default) return res
        val raw = getAttrValue(parser, name)
        return raw?.toBooleanStrictOrNull() ?: default
    }

    /**
     * 仅保留微信与支付宝的扫一扫与付款码作为核心兜底。
     */
    private fun injectEssentialShortcuts(
        context: Context,
        result: MutableList<ShortcutItem>,
        seenKeys: MutableSet<String>,
    ) {
        val pm = context.packageManager

        // 1. 微信扫一扫
        val wechatPkg = "com.tencent.mm"
        if (isPackageInstalled(pm, wechatPkg)) {
            val key = "$wechatPkg#scan"
            if (!seenKeys.contains(key) && result.none { it.packageName == wechatPkg && it.label.contains("扫一扫") }) {
                val scanIntent = Intent("com.tencent.mm.action.BIZSHORTCUT").apply {
                    putExtra("LauncherUI.From.Scaner.Shortcut", true)
                    setPackage(wechatPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val wechatIcon = runCatching { pm.getApplicationIcon(wechatPkg) }.getOrNull()
                result.add(
                    0,
                    ShortcutItem(
                        id = "wechat_scan",
                        packageName = wechatPkg,
                        label = "扫一扫",
                        subLabel = "微信",
                        type = ShortcutType.APP_SHORTCUT,
                        intentUri = scanIntent.toUri(0),
                        iconBitmap = wechatIcon?.let { drawableToBitmap(it) },
                    )
                )
                seenKeys.add(key)
            }
        }

        // 2. 支付宝扫一扫与付款码
        val alipayPkg = "com.eg.android.AlipayGphone"
        if (isPackageInstalled(pm, alipayPkg)) {
            val alipayIcon = runCatching { pm.getApplicationIcon(alipayPkg) }.getOrNull()

            val scanKey = "$alipayPkg#scan"
            if (!seenKeys.contains(scanKey) && result.none { it.packageName == alipayPkg && it.label.contains("扫一扫") }) {
                val scanIntent = Intent(Intent.ACTION_VIEW, Uri.parse("alipays://platformapi/startapp?appId=10000007")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                result.add(
                    if (result.isNotEmpty()) 1 else 0,
                    ShortcutItem(
                        id = "alipay_scan",
                        packageName = alipayPkg,
                        label = "扫一扫",
                        subLabel = "支付宝",
                        type = ShortcutType.APP_SHORTCUT,
                        intentUri = scanIntent.toUri(0),
                        iconBitmap = alipayIcon?.let { drawableToBitmap(it) },
                    )
                )
                seenKeys.add(scanKey)
            }

            val payKey = "$alipayPkg#pay"
            if (!seenKeys.contains(payKey) && result.none { it.packageName == alipayPkg && (it.label.contains("付款") || it.label.contains("收钱")) }) {
                val payIntent = Intent(Intent.ACTION_VIEW, Uri.parse("alipays://platformapi/startapp?appId=20000056")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                result.add(
                    minOf(2, result.size),
                    ShortcutItem(
                        id = "alipay_pay",
                        packageName = alipayPkg,
                        label = "付款码",
                        subLabel = "支付宝",
                        type = ShortcutType.APP_SHORTCUT,
                        intentUri = payIntent.toUri(0),
                        iconBitmap = alipayIcon?.let { drawableToBitmap(it) },
                    )
                )
                seenKeys.add(payKey)
            }
        }
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return runCatching {
            pm.getPackageInfo(packageName, 0) != null
        }.getOrDefault(false)
    }

    /**
     * 所有应用磁贴（`TileService`）。返回 [ShortcutType.QS_TILE] 条目，键仍是 `pkg#id`，
     * `id` 是服务类名，与快捷方式天然不会撞。
     *
     * 枚举用 `queryIntentServices(QS_TILE)`，保证**没加进控制中心的磁贴也列出来**；图标优先合并
     * [com.repl.bubbledrawer.xposed.QsTileHost.currentTileItems] 的 live `QSTile.State.iconSupplier`
     * （反编译 `CustomTile.handleUpdateState` 的真实磁贴前景），没有 live 的退回
     * `TILE_SERVICE_INFO` / `ServiceInfo` / 应用图标。
     *
     * 是否显示由更多应用面板的「显示磁贴」开关控制（`panel_show_tiles`，默认开）。
     */
    private fun loadQsTiles(
        context: Context,
        seenKeys: MutableSet<String>,
        progress: ShortcutProgress? = null,
    ): List<ShortcutItem> {
        // 只在 SystemUI 里做：App 进程看不到 QSTile，也没有 SystemUI 的 classloader。
        if (context.packageName != "com.android.systemui") return emptyList()
        if (!SettingsStore.snapshot(context).panelShowTiles) {
            Log.i(TAG, "QS_TILE_SCAN_SKIPPED show_tiles=false")
            return emptyList()
        }
        val pm = context.packageManager
        val live = com.repl.bubbledrawer.xposed.QsTileHost.currentTileItems(context)
            .associateBy { it.pinKey }

        @Suppress("DEPRECATION")
        val resolved = runCatching {
            pm.queryIntentServices(
                Intent("android.service.quicksettings.action.QS_TILE"),
                PackageManager.GET_META_DATA,
            )
        }.getOrNull().orEmpty()

        val out = mutableListOf<ShortcutItem>()
        for (resolveInfo in resolved) {
            val si = resolveInfo.serviceInfo ?: continue
            if (!si.enabled || si.applicationInfo?.enabled != true) continue
            if (si.packageName == context.packageName) continue
            val component = android.content.ComponentName(si.packageName, si.name)
            val key = "${component.packageName}#${component.className}"
            if (!seenKeys.add(key)) continue

            val liveItem = live[key]
            if (liveItem != null) {
                out.add(
                    liveItem.copy(
                        subLabel = liveItem.subLabel ?: appLabelOf(pm, si.packageName),
                    ),
                )
                continue
            }

            val appLabel = appLabelOf(pm, si.packageName)
            val tileInfo = loadTileServiceInfo(pm, si)
            val serviceLabel = tileInfo.first?.toString()
                ?: runCatching { si.loadLabel(pm).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() && it != si.name && !it.contains('.') }
            val label = serviceLabel ?: appLabel ?: component.className.substringAfterLast('.')
            val icon = tileInfo.second
                ?: runCatching { si.loadIcon(pm) }.getOrNull()
                ?: runCatching { pm.getApplicationIcon(si.packageName) }.getOrNull()
            val rawBitmap = icon?.let {
                runCatching { drawableToBitmap(it, ShortcutBridge.ICON_MAX_PX) }.getOrNull()
            }
            // ServiceInfo/应用图标经常是启动图标（白色圆角矩形），不是磁贴前景。它是实底/不透明时，
            // 去 `createTile` 取一次真正的 state.iconSupplier；拿不到才用原图兜底。
            val rawIsForeground = rawBitmap != null &&
                runCatching { isTransparentBitmap(rawBitmap) }.getOrDefault(false)
            val realIcon = if (!rawIsForeground) {
                runCatching {
                    com.repl.bubbledrawer.xposed.QsTileHost.tileIconFor(context, component)
                }.getOrNull()
            } else null
            val iconBitmap = realIcon?.let {
                runCatching { drawableToBitmap(it, ShortcutBridge.ICON_MAX_PX) }.getOrNull()
            } ?: rawBitmap
            // 只有透明前景才漂白；应用图标兜底原样画（避免白色圆角方块）。
            val tileForeground = iconBitmap != null &&
                runCatching { isTransparentBitmap(iconBitmap) }.getOrDefault(false)
            val tint = applicationIcon(context, si.packageName)?.let { appIcon ->
                runCatching { com.repl.bubbledrawer.ui.util.IconColors.dominant(appIcon) }.getOrNull()
            }
            out.add(
                ShortcutItem(
                    id = component.className,
                    packageName = component.packageName,
                    label = label,
                    subLabel = appLabel,
                    type = ShortcutType.QS_TILE,
                    iconBitmap = iconBitmap,
                    tintColor = tint,
                    tileForeground = tileForeground,
                ),
            )
            progress?.step()
        }

        // 反方向兜底：live 里有、query 没返回的（包可见性/服务被禁用的边缘情况）也保留。
        for (item in live.values) {
            if (seenKeys.add(item.pinKey)) {
                out.add(item)
                progress?.step()
            }
        }
        Log.i(TAG, "QS_TILE_SCAN count=${out.size} live=${live.size} resolved=${resolved.size}")
        return out.sortedWith(compareBy({ it.subLabel.orEmpty() }, { it.label }))
    }

    private fun appLabelOf(pm: PackageManager, packageName: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.ifBlank { null }

    /**
     * 读 `android.service.quicksettings.TILE_SERVICE_INFO` 指向的 XML（`<tile-service-info>`）。
     * 不少应用只把磁贴的 label/icon 写在这里，service 本身没有 `android:label/icon`。
     */
    private fun loadTileServiceInfo(
        pm: PackageManager,
        si: android.content.pm.ServiceInfo,
    ): Pair<CharSequence?, Drawable?> {
        val metaRes = runCatching {
            si.metaData?.getInt("android.service.quicksettings.TILE_SERVICE_INFO", 0)
        }.getOrNull() ?: 0
        if (metaRes == 0) return null to null
        val parser = runCatching {
            si.loadXmlMetaData(pm, "android.service.quicksettings.TILE_SERVICE_INFO")
        }.getOrNull() ?: return null to null
        return try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "tile-service-info") {
                    val resources = runCatching {
                        pm.getResourcesForApplication(si.packageName)
                    }.getOrNull()
                    val labelRes = parser.getAttributeResourceValue(ANDROID_NS, "label", 0)
                    val iconRes = parser.getAttributeResourceValue(ANDROID_NS, "icon", 0)
                    val label = if (labelRes != 0) {
                        runCatching { resources?.getString(labelRes) }.getOrNull()
                    } else null
                    val icon = if (iconRes != 0) {
                        runCatching { resources?.getDrawable(iconRes, null) }.getOrNull()
                    } else null
                    return label to icon
                }
                event = parser.next()
            }
            null to null
        } catch (_: Exception) {
            null to null
        } finally {
            runCatching { parser.close() }
        }
    }

    /**
     * 读取小米桌面固定在桌面的快捷方式：
     * content://com.miui.home.launcher.settings/favorites
     */
    internal fun loadDesktopShortcuts(
        context: Context,
        progress: ShortcutProgress? = null,
    ): List<ShortcutItem> {
        val result = mutableListOf<ShortcutItem>()
        val favoritesUri = Uri.parse("content://com.miui.home.launcher.settings/favorites")
        val pm = context.packageManager

        runCatching {
            val cursor = context.contentResolver.query(
                favoritesUri,
                arrayOf("_id", "title", "intent", "itemType", "isShortcut", "icon"),
                null,
                null,
                null,
            ) ?: return@runCatching

            cursor.use { c ->
                val idCol = c.getColumnIndex("_id")
                val titleCol = c.getColumnIndex("title")
                val intentCol = c.getColumnIndex("intent")
                val itemTypeCol = c.getColumnIndex("itemType")
                val isShortcutCol = c.getColumnIndex("isShortcut")
                val iconCol = c.getColumnIndex("icon")

                while (c.moveToNext()) {
                    val itemType = if (itemTypeCol >= 0 && !c.isNull(itemTypeCol)) c.getInt(itemTypeCol) else -1
                    val isShortcut = if (isShortcutCol >= 0 && !c.isNull(isShortcutCol)) c.getInt(isShortcutCol) else 0

                    if (itemType != 1 && itemType != 14 && isShortcut == 0) continue

                    val rawIntent = if (intentCol >= 0 && !c.isNull(intentCol)) c.getString(intentCol) else null
                    if (rawIntent.isNullOrEmpty()) continue

                    val id = if (idCol >= 0) c.getLong(idCol).toString() else rawIntent.hashCode().toString()
                    val title = if (titleCol >= 0 && !c.isNull(titleCol)) c.getString(titleCol) else ""
                    val iconBlob = if (iconCol >= 0 && !c.isNull(iconCol)) c.getBlob(iconCol) else null

                    val bitmap = if (iconBlob != null && iconBlob.isNotEmpty()) {
                        // 桌面 Provider 存的是**桌面尺寸**的图标（MIUI/Flyme 常见 192~512px）：
                        // 原样留着，30 条固定捷径就能在 SystemUI 里压住几十 MB（见 ICON_MAX_PX）。
                        runCatching { BitmapFactory.decodeByteArray(iconBlob, 0, iconBlob.size) }
                            .getOrNull()
                            ?.let { clampIcon(it) }
                    } else null

                    val parsedIntent = runCatching { Intent.parseUri(rawIntent, 0) }.getOrNull()
                    val pkg = parsedIntent?.`package` ?: parsedIntent?.component?.packageName ?: ""

                    val appLabel = if (pkg.isNotEmpty()) {
                        runCatching {
                            val info = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(info).toString()
                        }.getOrNull()
                    } else null

                    val finalBitmap = bitmap ?: if (pkg.isNotEmpty()) {
                        runCatching { pm.getApplicationIcon(pkg) }.getOrNull()?.let { drawableToBitmap(it) }
                    } else null

                    result.add(
                        ShortcutItem(
                            id = id,
                            packageName = pkg,
                            label = title.ifEmpty { "快捷方式" },
                            subLabel = appLabel,
                            type = ShortcutType.DESKTOP_PINNED,
                            intentUri = rawIntent,
                            iconBitmap = finalBitmap,
                        )
                    )
                    progress?.step()
                }
            }
        }.onFailure {
            Log.w(TAG, "Cannot load desktop shortcuts from launcher provider", it)
        }

        return result
    }

    /**
     * 透明底图标的归一化结果缓存。
     *
     * [normalizeTransparentForeground] 每条都要新建一张目标位图，而它在 Compose 的组合里被调用，
     * LazyGrid 滚出去再滚回来就会重算 —— 24 条 × 192² × 4B ≈ 3.5MB 的上限换掉这些重算。
     */
    private val normalizedIcons = android.util.LruCache<String, Bitmap>(24)

    fun normalizedForeground(item: ShortcutItem, bitmap: Bitmap): Bitmap {
        normalizedIcons.get(item.pinKey)?.let { return it }
        val normalized = runCatching { normalizeTransparentForeground(bitmap, 192, 0.62f) }
            .getOrDefault(bitmap)
        normalizedIcons.put(item.pinKey, normalized)
        return normalized
    }

    /**
     * 磁贴前景的归一化比例：用户 2026-10-23 看过实际效果后要求「归一化还是用 0.62」，
     * 所以与快捷方式统一用 0.62（反编译的 21.33/50.18≈0.425 只作参考，不再采用）。
     */
    const val TILE_ICON_FRACTION = 0.62f

    /** 磁贴前景专用缓存：与快捷方式的 0.62 归一化分开，避免同一个 pinKey 互相顶掉。 */
    private val normalizedTileIcons = android.util.LruCache<String, Bitmap>(24)

    fun normalizedTileForeground(item: ShortcutItem, bitmap: Bitmap): Bitmap {
        normalizedTileIcons.get(item.pinKey)?.let { return it }
        val normalized = runCatching {
            normalizeTransparentForeground(bitmap, 192, TILE_ICON_FRACTION)
        }.getOrDefault(bitmap)
        normalizedTileIcons.put(item.pinKey, normalized)
        return normalized
    }

    /**
     * 位图里有没有可见像素（alpha > 8）。用于兜底：某些 TileService 的 icon 是全透明占位，
     * 归一化/漂白之后就是一张空图，界面上看起来“所有图标都不显示”。此时 UI 退回应用图标
     * 或首字。
     */
    fun hasVisiblePixels(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (bitmap.config == Bitmap.Config.HARDWARE) return true
        if (!bitmap.hasAlpha()) return true
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 8) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    /**
     * 把任意来源的位图缩到 [ShortcutBridge.ICON_MAX_PX] 以内（桌面 Provider 的 BLOB 直接解码出来
     * 往往是桌面尺寸）。已经够小的原样返回 —— 不复制、不放大。
     */
    fun clampIcon(bitmap: Bitmap, maxPx: Int = ShortcutBridge.ICON_MAX_PX): Bitmap {
        if (bitmap.isRecycled) return bitmap
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx || longest <= 0) return bitmap
        val scale = maxPx.toFloat() / longest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bitmap, w, h, true) }.getOrDefault(bitmap)
    }

    /**
     * 应用图标缓存。
     *
     * 同一个包在一次面板会话里可能被要好几张（快捷方式图标缺失时的兜底、已选区左下角的角标、
     * 桌面快捷方式的兜底），而 `getApplicationIcon` 是 PackageManager 的跨进程调用 + 一次位图
     * 绘制 —— 缓存按「包名@尺寸」存，别让 30 条同一个应用的快捷方式解 30 次。
     */
    private val appIcons = android.util.LruCache<String, Bitmap>(32)

    fun applicationIcon(
        context: Context,
        packageName: String,
        sizePx: Int = ShortcutBridge.ICON_MAX_PX,
    ): Bitmap? {
        if (packageName.isEmpty()) return null
        val key = "$packageName@$sizePx"
        appIcons.get(key)?.let { return it }
        return runCatching {
            drawableToBitmap(context.packageManager.getApplicationIcon(packageName), sizePx)
        }.getOrNull()?.also { appIcons.put(key, it) }
    }

    /**
     * 启动快捷方式（支持小窗启动与全屏回退）。
     *
     * [useFreeform] 必须由调用方给：这个函数两个进程都会用，而 `SettingsStore` 只在 **App 进程**
     * 能读到真设置（hook 进程的 `RemoteBridge.remote` 是空的，会退到 SystemUI 自己的 cfg 文件 →
     * 全是默认值，用户开着的小窗会被无视）。所以 SystemUI 侧（扇形面板）传
     * `RemotePrefs.read(prefs).freeform`，App 侧（管理页）用默认值即可。
     *
     * [viaGate]：**App 进程**（管理页）必须为 true —— 从第三方进程直接塞 MIUI 的 freeform
     * `ActivityOptions` 会把"小窗"落到调用者自己身上（详见
     * [com.repl.bubbledrawer.launch.GateLaunchStrategy] 的类注释）。走闸门时 Intent 只带
     * [LightOpen.EXTRA_FREEFORM_REQUEST]，由 system_server 用 ROM 自己的参数落窗。
     */
    fun launchShortcut(
        context: Context,
        item: ShortcutItem,
        useFreeform: Boolean = SettingsStore.snapshot(context).freeform,
        viaGate: Boolean = false,
        optionsBundle: android.os.Bundle? = null,
    ): Boolean {
        // 磁贴没有 Intent/ShortcutInfo，走不了这里；调用方必须先分流到 QsTileHost（见 FanHost /
        // PanelContentFactory / PinManageActivity）。真漏到这里就明确失败，别去 start 一个空 Intent。
        if (item.type == ShortcutType.QS_TILE) {
            Log.w(TAG, "LAUNCH_SHORTCUT_REJECTED_QS_TILE pkg=${item.packageName} id=${item.id}")
            return false
        }
        // 聚合支付同理：它自己不启动任何应用，点击应该走 quickPayTrigger 把联系人条切到聚合支付
        // 模式（条目层面才是可启动的 APP_SHORTCUT）。漏到这里说明有调用点没分流。
        if (item.type == ShortcutType.AGGREGATE_PAYMENT) {
            Log.w(TAG, "LAUNCH_SHORTCUT_REJECTED_AGGREGATE pkg=${item.packageName} id=${item.id}")
            return false
        }
        val requestFreeform = useFreeform && item.packageName.isNotEmpty()
        // 走闸门时不带 options：留给 system_server 的闸门（ROM 自己那条路）生成。
        val gate = requestFreeform && viaGate
        // [optionsBundle] 由调用方自带（聚合支付的窗内切换要塞 setLaunchTaskId，见
        // ContactBarController.onPayEntryClick），非空时优先于内部拼的那份。
        val bundle = optionsBundle ?: if (requestFreeform && !gate) {
            (MiuiFreeform.makeActivityOptions(context, item.packageName)
                ?: ActivityOptions.makeBasic().also {
                    runCatching {
                        ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                            .invoke(it, 5) // 5 = WINDOWING_MODE_FREEFORM
                    }
                }).let { MiuiFreeform.withoutFreeformAnimation(it) }.toBundle()
        } else {
            null
        }

        // 1. 系统 ShortcutInfo 启动（只有 SystemUI 侧拿得到它；App 进程走第 2 条路的 intent）
        if (item.type == ShortcutType.APP_SHORTCUT && item.shortcutInfo != null) {
            val ok = runCatching {
                // 注意不能用 `launcherApps?.` 加恒真：服务拿不到时会「报告成功但什么都没发生」。
                val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    ?: return@runCatching false
                launcherApps.startShortcut(item.packageName, item.id, null, bundle, Process.myUserHandle())
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Intent 启动
        if (!item.intentUri.isNullOrEmpty()) {
            val ok = runCatching {
                val intent = Intent.parseUri(item.intentUri, 0).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (gate) putExtra(LightOpen.EXTRA_FREEFORM_REQUEST, true)
                }
                context.startActivity(intent, bundle)
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        return false
    }
}
