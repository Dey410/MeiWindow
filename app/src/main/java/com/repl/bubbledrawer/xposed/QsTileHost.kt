package com.repl.bubbledrawer.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repl.bubbledrawer.shortcut.ShortcutBridge
import com.repl.bubbledrawer.shortcut.ShortcutItem
import com.repl.bubbledrawer.shortcut.ShortcutRepository
import com.repl.bubbledrawer.shortcut.ShortcutType
import com.repl.bubbledrawer.ui.util.IconColors

/**
 * 控制中心「应用磁贴」的执行入口。
 *
 * 磁贴本体是应用声明的 `TileService`，SystemUI 把它包成 `CustomTile`（`QSTileImpl` 子类）；
 * 点击路径是 `CustomTile.handleClick()` → `TileLifecycleManager.onClick(token)`。这条路径只能在
 * SystemUI 进程里跑：`BIND_QUICK_SETTINGS_TILE` 是 signature|privileged，模块 App 进程没有。
 *
 * 句柄来源（不新增方法 hook，沿用现有注入拿到的 classloader）：
 * `SystemUIAppComponentFactoryBase.systemUIInitializer` → `getSysUIComponent()` →
 * `ReferenceSysUIComponentImpl.miuiQSHostAdapterProvider`（public provider 字段）→ `get()`。
 * 这是 `RecentConversations.acquirePipeline()` 已经验证过的 Dagger 取法；字段/方法名变了就
 * 打 `QS_TILE_HOST_*` 日志并回退「启动宿主应用」，绝不静默。
 *
 * 注意：这里只创建**点击那一刻**的 tile，点完 `destroy()`；枚举阶段一个都不建，否则 SystemUI
 * 启动时会把几十个 TileService 全 bind 一遍（`TileServices.DEFAULT_MAX_BOUND` 也扛不住）。
 */
object QsTileHost {

    private const val TAG = "BubbleDrawer"

    /** App 进程 → SystemUI 的「执行一次磁贴」广播（管理页没有绑定权限）。 */
    const val ACTION_CLICK_TILE = "com.repl.bubbledrawer.action.CLICK_QS_TILE"
    const val EXTRA_PACKAGE = "tile_pkg"
    const val EXTRA_CLASS = "tile_class"

    /** 系统 UI 里的 `MiuiQSHost`（`MiuiQSHostAdapter`）。未就绪时 null。 */
    @Volatile
    private var host: Any? = null

    /**
     * SystemUI 的 classloader（`SystemUiHookInstaller.install()` 拿到的那个）。
     *
     * 不能直接用 `context.classLoader`：面板里的 Context 是 `ModuleResources.FanContext`，
     * 它的 `getClassLoader()` 故意返回**模块**的 classloader（为了 R.string / 布局资源），
     * 拿它 `Class.forName` 找不到 `SystemUIAppComponentFactoryBase`。
     */
    @Volatile
    private var systemUiLoader: ClassLoader? = null

    /** 由 [SystemUiHookInstaller] 在安装/热重载时调用。 */
    fun install(loader: ClassLoader) {
        reset()
        systemUiLoader = loader
    }

    /** 上次尝试解析的时间，避免每个磁贴都走一遍反射。 */
    @Volatile
    private var lastResolveAt = 0L
    private const val RESOLVE_RETRY_MS = 3_000L

    fun isAvailable(): Boolean = host != null

    /**
     * 解析 `MiuiQSHost`。`loader` 必须是 SystemUI 的 classloader（`host.classLoader`）。
     * 返回是否拿到。
     */
    fun ensure(loader: ClassLoader?): Boolean {
        host?.let { return true }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastResolveAt < RESOLVE_RETRY_MS) return false
        lastResolveAt = now
        val effectiveLoader = systemUiLoader ?: loader
        if (effectiveLoader == null) {
            Log.w(TAG, "QS_TILE_HOST_NO_CLASSLOADER")
            return false
        }
        val instance = runCatching {
            val factory = Class.forName(
                "com.android.systemui.SystemUIAppComponentFactoryBase",
                false,
                effectiveLoader,
            )
            val initializer = factory.getField("systemUIInitializer").get(null)
                ?: return@runCatching null
            val component = initializer.javaClass
                .getMethod("getSysUIComponent")
                .invoke(initializer)
                ?: return@runCatching null
            // Dagger 生成类的字段名在当前 ROM 是 miuiQSHostAdapterProvider；跨版本可能有别名，
            // 所以先按已知名找，再退到「类型名里带 MiuiQSHost 的 provider 字段」。
            val field = runCatching {
                component.javaClass.getField("miuiQSHostAdapterProvider")
            }.getOrNull() ?: component.javaClass.declaredFields.firstOrNull { f ->
                f.name.contains("miuiQSHost", ignoreCase = true) ||
                    f.type.name.contains("MiuiQSHost", ignoreCase = true)
            }?.apply { isAccessible = true }
            val provider = field?.get(component) ?: return@runCatching null
            provider.javaClass.getMethod("get").invoke(provider)
        }.getOrNull()

        if (instance == null) {
            Log.w(TAG, "QS_TILE_HOST_UNAVAILABLE")
            return false
        }
        val looksRight = instance.javaClass.methods.any {
            it.name == "createTile" && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }
        if (!looksRight) {
            Log.w(TAG, "QS_TILE_HOST_REJECTED class=${instance.javaClass.name}")
            return false
        }
        host = instance
        Log.i(TAG, "QS_TILE_HOST_READY class=${instance.javaClass.name}")
        return true
    }

    /**
     * 当前控制中心里已经存在的**应用磁贴**（`custom(pkg/cls)`）。
     *
     * 为什么不用 `ServiceInfo` 的 icon：反编译 `CustomTile.handleUpdateState` 里，磁贴的真实图标是
     * `mTile.getIcon()`（`state.iconSupplier`），`CustomTile.updateDefaultTileAndIcon` 只在没有它时
     * 才退回 `ServiceInfo.icon` / 应用图标。很多应用把 service 的 `android:icon` 写成启动图标，
     * 直接拿它当磁贴前景就会得到「圆形里套一个白色圆角矩形」。
     *
     * 这里从 `MiuiQSHost.getTiles()` 拿**已经在控制中心里的 live QSTile**，读它们的 `State.iconSupplier`：
     * 图标、label、点击对象都是现成的，不需要 bind，也不会触发几十个 TileService。
     * 代价是只列用户已经加进控制中心的磁贴 —— 也正是「控制中心那批」的定义。
     *
     * 必须在主线程读 QSTile.State；本函数从扫描线程调用时把读取投到主线程并等结果。
     */
    fun currentTileItems(context: Context): List<ShortcutItem> {
        if (!ensure(context.classLoader)) {
            Log.w(TAG, "QS_TILE_LIST_NO_HOST")
            return emptyList()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching { collectLiveTiles(context) }.getOrDefault(emptyList())
        }
        // 读 QSTile.State 放到主线程（SystemUI 的状态对象按主线程更新）；但**必须有超时**：
        // 扫描线程持着 ShortcutRepository 的锁，绝不能无限等主线程。
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: List<ShortcutItem> = emptyList()
        Handler(Looper.getMainLooper()).post {
            result = runCatching { collectLiveTiles(context) }.getOrDefault(emptyList())
            latch.countDown()
        }
        val finished = runCatching {
            latch.await(900L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            Log.w(TAG, "QS_TILE_LIST_TIMEOUT")
            return emptyList()
        }
        return result
    }

    private fun collectLiveTiles(context: Context): List<ShortcutItem> {
        val h = host ?: return emptyList()
        val raw = runCatching {
            h.javaClass.getMethod("getTiles").invoke(h) as? Collection<*>
        }.getOrNull().orEmpty()
        val pm = context.packageManager
        val out = ArrayList<ShortcutItem>(raw.size)
        val seen = HashSet<String>()
        for (tile in raw) {
            if (tile == null) continue
            val spec = runCatching {
                tile.javaClass.getMethod("getTileSpec").invoke(tile) as? String
            }.getOrNull().orEmpty()
            if (!spec.startsWith("custom(") || !spec.endsWith(")")) continue
            val component = runCatching {
                tile.javaClass.getMethod("getComponent").invoke(tile) as? ComponentName
            }.getOrNull() ?: ComponentName.unflattenFromString(
                spec.substring(7, spec.length - 1),
            ) ?: continue
            val key = "${component.packageName}#${component.className}"
            if (!seen.add(key)) continue

            val state = runCatching {
                tile.javaClass.getMethod("getState").invoke(tile)
            }.getOrNull()
            val stateLabel = runCatching {
                state?.javaClass?.getField("label")?.get(state) as? CharSequence
            }.getOrNull()
            val tileLabel = runCatching {
                tile.javaClass.getMethod("getTileLabel").invoke(tile) as? CharSequence
            }.getOrNull()
            val appLabel = runCatching {
                val info = pm.getApplicationInfo(component.packageName, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrNull()
            val label = stateLabel?.toString()?.ifBlank { null }
                ?: tileLabel?.toString()?.ifBlank { null }
                ?: appLabel
                ?: component.className.substringAfterLast('.')

            // 真实磁贴图标：state.iconSupplier / state.icon（CustomTile.handleUpdateState 的产物）。
            val stateIcon = stateIconDrawable(state, context)
            val iconBitmap = stateIcon?.let {
                runCatching { ShortcutRepository.drawableToBitmap(it, ShortcutBridge.ICON_MAX_PX) }.getOrNull()
            } ?: ShortcutRepository.applicationIcon(context, component.packageName)
            val tileForeground = stateIcon != null && iconBitmap != null &&
                runCatching { ShortcutRepository.isTransparentBitmap(iconBitmap) }.getOrDefault(false)
            val tint = ShortcutRepository.applicationIcon(context, component.packageName)?.let { appIcon ->
                runCatching { IconColors.dominant(appIcon) }.getOrNull()
            }

            Log.i(
                TAG,
                "QS_TILE_LIVE_ITEM spec=$spec label=$label icon=${stateIcon != null} " +
                    "fg=$tileForeground tint=${tint != null}",
            )
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
        }
        Log.i(TAG, "QS_TILE_LIVE count=${out.size} raw=${raw.size}")
        return out.sortedWith(compareBy({ it.subLabel.orEmpty() }, { it.label }))
    }

    /**
     * `QSTile.State.iconSupplier`（Supplier<QSTile.Icon>）优先，其次 `state.icon`。
     * `QSTile.Icon.getDrawable(Context)` 才是 SystemUI 真正画的那张图。
     */
    private fun stateIconDrawable(state: Any?, context: Context): Drawable? {
        if (state == null) return null
        val supplier = runCatching {
            state.javaClass.getField("iconSupplier").get(state)
        }.getOrNull()
        val icon = runCatching {
            supplier?.javaClass?.getMethod("get")?.invoke(supplier)
        }.getOrNull() ?: runCatching {
            state.javaClass.getField("icon").get(state)
        }.getOrNull()
        return runCatching {
            icon?.javaClass?.getMethod("getDrawable", Context::class.java)
                ?.invoke(icon, context) as? Drawable
        }.getOrNull()
    }

    /** 面板/扇形（SystemUI 进程）直接执行。 */
    fun click(context: Context, item: ShortcutItem): Boolean {
        if (item.type != ShortcutType.QS_TILE) return false
        return click(context, item.packageName, item.id, item.label)
    }

    /**
     * 执行一个磁贴。返回 false 表示「没排上队」；真正的执行结果由 TileService 自己决定。
     * 失败时回退到「启动宿主应用」，至少不是点了没反应。
     */
    fun click(
        context: Context,
        packageName: String,
        className: String,
        label: String = "",
    ): Boolean {
        if (packageName.isBlank() || className.isBlank()) return false
        if (!ensure(context.classLoader)) {
            Log.w(TAG, "QS_TILE_CLICK_NO_HOST pkg=$packageName class=$className")
            return launchApp(context, packageName, label)
        }
        val component = runCatching { ComponentName(packageName, className) }.getOrNull()
            ?: return launchApp(context, packageName, label)
        val spec = "custom(${component.flattenToShortString()})"

        val run = Runnable {
            // 优先点控制中心里已经好的 live tile：不新建、不绑定、不销毁。
            val live = runCatching {
                (host?.javaClass?.getMethod("getTiles")?.invoke(host) as? Collection<*>)
                    ?.firstOrNull { candidate ->
                        runCatching {
                            candidate?.javaClass?.getMethod("getTileSpec")?.invoke(candidate) == spec
                        }.getOrDefault(false)
                    }
            }.getOrNull()
            val existing = live ?: runCatching {
                host?.javaClass
                    ?.getMethod("getTile", String::class.java)
                    ?.invoke(host, spec)
            }.getOrNull()
            val tile = existing ?: runCatching {
                host?.javaClass
                    ?.getMethod("createTile", String::class.java)
                    ?.invoke(host, spec)
            }.getOrNull()
            if (tile == null) {
                Log.w(TAG, "QS_TILE_CREATE_NULL spec=$spec")
                launchApp(context, packageName, label)
                return@Runnable
            }
            val clicked = runCatching {
                // QSFactoryImpl 已经 setTileSpec；这里再设一次是给 getTile 命中的路径兜底。
                runCatching { tile.javaClass.getMethod("setTileSpec", String::class.java).invoke(tile, spec) }
                // QSTile.click() 是接口 default → click(null)；部分实现只暴露 click(Expandable)，
                // 所以先试无参，再按「名字 + 一个参数」找那个重载（Expandable 的包名在 AOSP /
                // HyperOS 之间换过，别写死类名）。
                val noArg = tile.javaClass.methods.firstOrNull {
                    it.name == "click" && it.parameterCount == 0
                }
                if (noArg != null) {
                    noArg.invoke(tile)
                } else {
                    val oneArg = tile.javaClass.methods.firstOrNull {
                        it.name == "click" && it.parameterCount == 1
                    } ?: error("no click method on ${tile.javaClass.name}")
                    oneArg.invoke(tile, null)
                }
                true
            }.onFailure {
                Log.w(TAG, "QS_TILE_CLICK_FAILED spec=$spec", it)
            }.getOrDefault(false)
            Log.i(TAG, "QS_TILE_CLICK spec=$spec ok=$clicked existing=${existing != null}")
            // 点击是异步的（绑定/解绑都要走 TileLifecycleManager），别立刻 destroy；
            // 3 秒后回收。**只回收自己 createTile 出来的临时 tile**：`getTile` 命中的是控制中心
            // 正在用的那个实例，destroy 掉会把用户控制中心里的磁贴也删了。
            if (existing == null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { tile.javaClass.getMethod("destroy").invoke(tile) }
                }, 3_000L)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run()
        else Handler(Looper.getMainLooper()).post(run)
        return true
    }

    /** App 进程（管理页）用：自己没有绑定权限，交给 SystemUI 执行。 */
    fun requestClick(context: Context, item: ShortcutItem): Boolean {
        if (item.type != ShortcutType.QS_TILE) return false
        val intent = Intent(ACTION_CLICK_TILE).apply {
            setPackage("com.android.systemui")
            putExtra(EXTRA_PACKAGE, item.packageName)
            putExtra(EXTRA_CLASS, item.id)
            putExtra("tile_label", item.label)
        }
        return runCatching {
            context.sendBroadcast(intent)
            Log.i(TAG, "QS_TILE_CLICK_REQUESTED pkg=${item.packageName} class=${item.id}")
            true
        }.getOrDefault(false)
    }

    private fun launchApp(context: Context, packageName: String, label: String): Boolean {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        Log.i(TAG, "QS_TILE_CLICK_FALLBACK_APP pkg=$packageName label=$label ok=$ok")
        return ok
    }

    /** 热重载/SystemUI 重启时清掉上一代的句柄。 */
    fun reset() {
        host = null
        systemUiLoader = null
        lastResolveAt = 0L
    }
}
