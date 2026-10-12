package com.repl.bubbledrawer.contactbar

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy

/**
 * 「通知栏（窗帘）展开了吗」—— 联系人条用它让开（用户 2026-10-12：下拉通知栏之后联系人条会叠在通知上，
 * 「能不能让他的图层默认比通知栏低」）。
 *
 * ## 为什么不直接把图层调低
 *
 * 联系人条的窗口类型是 2024（`TYPE_NAVIGATION_BAR_PANEL`），通知栏是 `TYPE_STATUS_BAR`（2000）——
 * **系统窗口类型全部 ≥ 2000**（`FIRST_SYSTEM_WINDOW`），也就是说系统窗口里没有比通知栏更低的一层
 * （唯一在下面的壁纸是特例）。要真的更低就得用应用窗口（类型 1..99），那需要一个 activity/window token，
 * 本模块没有。所以做法是等价的：**窗帘一拉开条就让开，收起来再回来** —— 对用户来说与"被压在通知栏下面"
 * 看到的效果一致（而且窗帘是半透明的，真压下去反而会透出来）。
 *
 * ## 信号来自 ROM 自己的展开监听
 *
 * `com.android.systemui.shade.ShadeExpansionStateManager.addExpansionListener`（设备 dex 实测存在：
 * `addExpansionListener` / `ShadeExpansionListener` / `ShadeExpansionChangeEvent` 都在），
 * 回调里给的是 `ShadeExpansionChangeEvent`，它的 `fraction`（0..1）就是拉开比例。
 * 监听器实例用动态代理接：ROM 的接口方法名（`onPanelExpansionChanged`）与事件字段名（`fraction`）
 * 都靠**字段反射**读，不写死版本 —— 换 ROM 时最坏情况是这个回调不再触发，联系人条退回今天的行为。
 *
 * 安装时机与 `RecentConversations.installHooks` 一样（构造函数 + 热重载时的 Dagger 回退），
 * 而且**必须在主线程**完成监听注册（ROM 的监听集合是主线程亲和的，见那里的注释）。
 */
object ShadeState {

    private const val TAG = "BubbleDrawer"

    /**
     * 拉开多少算"把联系人条盖住了"。
     *
     * 条贴在小窗下沿（屏幕中下部），而小幅下拉只露出屏幕顶部，所以不必一有下拉就躲 —— 1/3 屏之后
     * 才开始让开，避免手指轻碰时条闪烁。`ContactBarController` 的判据就是这个值。
     */
    private const val EXPANDED_FRACTION = 0.35f

    /** 窗帘是否已经拉开到会盖住联系人条。 */
    @Volatile
    private var expanded = false

    /** 变化回调（在主线程调用实现；见 `ContactBarController`）。 */
    @Volatile
    var onChanged: ((Boolean) -> Unit)? = null

    fun isExpanded(): Boolean = expanded

    @Volatile
    private var manager: Any? = null

    @Volatile
    private var listenerAttached = false

    @Volatile
    private var moduleRef: XposedModule? = null

    /**
     * 装钩子。构造函数钩子负责首次绑定；热重载时那个单例早就建好了，走 [acquire] 从 Dagger 图里拿。
     * 与通知管线相同的两条路，原因也相同（`Application.onCreate` 不会再跑一遍）。
     */
    fun installHooks(module: XposedModule, classLoader: ClassLoader) {
        moduleRef = module
        val managerClass = runCatching {
            classLoader.loadClass("com.android.systemui.shade.ShadeExpansionStateManager")
        }.getOrNull() ?: run {
            module.log(Log.WARN, TAG, "SHADE_STATE_CLASS_MISSING", null)
            return
        }

        managerClass.declaredConstructors.forEach { ctor ->
            runCatching {
                module.hook(ctor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.shade.state.${ctor.parameterCount}")
                    .intercept { chain ->
                        val result = chain.proceed()
                        chain.thisObject?.let { onMain { attach(it) } }
                        result
                    }
            }
        }
        // 主线程注册监听（构造钩子里已经排了一次队，这里只是热重载那条路）。
        onMain {
            if (manager == null) acquire(module, classLoader)
            module.log(Log.INFO, TAG, "SHADE_STATE_HOOKS_INSTALLED manager=${manager != null}", null)
        }
    }

    /** `ReferenceSysUIComponentImpl` 里那个单例：按类型名在组件字段里找，不写死字段名。 */
    private fun acquire(module: XposedModule, classLoader: ClassLoader) {
        if (manager != null) return
        val instance = runCatching {
            val factory = Class.forName("com.android.systemui.SystemUIAppComponentFactoryBase", false, classLoader)
            val initializer = factory.getField("systemUIInitializer").get(null) ?: return@runCatching null
            val component = initializer.javaClass.getMethod("getSysUIComponent").invoke(initializer)
                ?: return@runCatching null
            component.javaClass.fields.firstOrNull { field ->
                field.type.name.contains("ShadeExpansionStateManager") ||
                    field.name.contains("ShadeExpansionStateManager", ignoreCase = true)
            }?.let { field ->
                val value = field.get(component)
                value?.javaClass?.getMethod("get")?.invoke(value) ?: value
            }
        }.getOrNull() ?: return
        if (!instance.javaClass.name.contains("ShadeExpansionStateManager")) return
        attach(instance)
    }

    /** 加一个动态代理监听：ROM 每次改变展开比例都会回调它（主线程）。 */
    private fun attach(instance: Any) {
        manager = instance
        if (listenerAttached) return
        val module = moduleRef ?: return
        runCatching {
            // **接口从方法签名里取**：`addExpansionListener(<接口>)` 的参数类型就是它。
            // 不猜名字、不猜包名，也不会有 classloader 用错的问题（接口的 classloader 就是 SystemUI 的；
            // 设备实测用模块自己的 classloader `Class.forName` 会 ClassNotFoundException）。
            val add = instance.javaClass.methods.firstOrNull {
                it.name == "addExpansionListener" && it.parameterCount == 1
            } ?: return
            val listenerClass = add.parameterTypes[0]
            // 动态代理也要自己回答 equals/hashCode：默认处理器返回 null 会在 ROM 的集合操作里炸
            // （`RecentConversations` 里记着同一个坑）。
            val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
                when (method.name) {
                    "equals" -> args?.firstOrNull() === this
                    "hashCode" -> System.identityHashCode(this)
                    "toString" -> "bubbledrawer-shade-listener"
                    else -> {
                        args?.firstOrNull()?.let { update(fractionOf(it)) }
                        null
                    }
                }
            }
            add.invoke(instance, proxy)
            listenerAttached = true
            module.log(
                Log.INFO,
                TAG,
                "SHADE_STATE_LISTENER_ATTACHED iface=${listenerClass.name}",
                null,
            )
        }.onFailure { module.log(Log.WARN, TAG, "SHADE_STATE_LISTENER_FAILED", it.cause ?: it) }
    }

    /** `ShadeExpansionChangeEvent.fraction`（0..1）—— 字段反射，字段名缺失时按"没展开"处理。 */
    private fun fractionOf(event: Any): Float = runCatching {
        event.javaClass.getField("fraction").getFloat(event)
    }.getOrElse {
        runCatching { event.javaClass.getField("expanded").getBoolean(event) }
            .getOrElse { false }
            .let { if (it) 1f else 0f }
    }

    private fun update(fraction: Float) {
        val nowExpanded = fraction > EXPANDED_FRACTION
        if (nowExpanded == expanded) return
        expanded = nowExpanded
        onChanged?.invoke(nowExpanded)
    }

    /** 热重载：旧实例的引用与回调都要丢掉（监听器挂在旧 ROM 对象上，会随换代一起消失）。 */
    fun release() {
        manager = null
        listenerAttached = false
        moduleRef = null
        onChanged = null
        expanded = false
    }

    private val main = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
