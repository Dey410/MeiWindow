package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Port of the reference hook/SystemUiHookInstaller.kt :11-61: hook
 * `com.android.systemui.SystemUIApplication.onCreate`; after the real onCreate
 * proceeds, `chain.thisObject` IS the SystemUI Application (a Context, :28) —
 * start the corner SPY monitor with it, exactly once.
 */
class SystemUiHookInstaller(
    private val module: XposedModule,
    private val prefs: SharedPreferences,
) {
    private val bound = AtomicBoolean(false)
    private var inputMonitor: CornerInputMonitor? = null
    private var appClassLoader: ClassLoader? = null
    private var flymeController: com.repl.bubbledrawer.launch.FlymeFreeformController? = null
    private var contactBar: com.repl.bubbledrawer.contactbar.ContactBarController? = null

    /**
     * captured when the Application first runs — needed to rebuild after a hot reload.
     * `@Volatile`：写在 `SystemUIApplicationImpl.attachBaseContext` 的钩子里（Binder/主线程各有可能），
     * 读发生在轻量打开的钩子线程上，不能靠"碰巧可见"。
     */
    @Volatile
    private var appContext: Context? = null

    fun install(classLoader: ClassLoader) {
        // Keep the resolved classloader for the back-gesture corner gate below.
        appClassLoader = classLoader
        // Install hooks for Flyme-style lightweight freeform window
        runCatching {
            com.repl.bubbledrawer.launch.FlymeFreeformController.installHooks(module, prefs, classLoader)
        }.onFailure {
            module.log(Log.WARN, TAG, "FLYME_FREEFORM_HOOKS_INSTALL_FAILED", it)
        }
        // 小窗底部联系人条的数据源: the process-wide NotifPipeline (contactbar/RecentConversations).
        runCatching {
            com.repl.bubbledrawer.contactbar.RecentConversations.installHooks(module, classLoader)
        }.onFailure {
            module.log(Log.WARN, TAG, "CONTACT_BAR_HOOKS_INSTALL_FAILED", it)
        }
        // Which class creates the monitors that carry DO_NOT_PILFER (see the class docs) —
        // read-only, and installed before ours exists so creation order is visible too.
        MonitorCreationObserver(module) { priority, message, error ->
            module.log(priority, TAG, message, error)
        }.install(classLoader)
        // AOSP SystemUI names its Application com.android.systemui.SystemUIApplication
        // (reference SystemUiHookInstaller.kt:59). HyperOS 17.03 does NOT have that
        // class at all — its manifest declares the application as
        // com.android.systemui.application.impl.SystemUIApplicationImpl
        // (decompiled out/SystemUI1703_src/resources/AndroidManifest.xml:356, class
        // extends Application, public final void onCreate() :65/:155). Try candidates.
        var installed = false
        for ((index, candidate) in APPLICATION_CLASS_CANDIDATES.withIndex()) {
            try {
                val applicationClass = classLoader.loadClass(candidate)
                val onCreate = applicationClass.getDeclaredMethod("onCreate")
                module
                    .hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.systemui.corner_spy_owner.$index")
                    .intercept { chain ->
                        val context = chain.thisObject as? Context
                        if (context != null && appContext == null) {
                            appContext = context
                        }
                        val result = chain.proceed()
                        if (context != null && bound.compareAndSet(false, true)) {
                            startInputMonitor(context)
                        }
                        result
                    }
                module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_HOOK_INSTALLED_$candidate")
                installed = true
                break
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE_$candidate", exception)
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED_$candidate", exception)
            }
        }
        if (!installed) module.log(Log.ERROR, TAG, "SYSTEMUI_NO_APPLICATION_HOOKABLE")
    }

    /**
     * Tear the runtime down for a hot reload: the monitor (and its input channel), the fan
     * windows and every hook installed here belong to the classloader being replaced. The
     * framework drops the hooks; the live objects are ours to release, and `bound` is reset
     * so [reinstall] can bind again.
     */
    fun dispose() {
        runCatching { com.repl.bubbledrawer.launch.FlymeFreeformController.freeformObserver = null }
        runCatching { contactBar?.dispose() }
        contactBar = null
        runCatching { flymeController?.dispose() }
        flymeController = null
        runCatching { inputMonitor?.dispose() }
        inputMonitor = null
        bound.set(false)
        module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_DISPOSED_FOR_HOT_RELOAD")
    }

    /**
     * Rebuild after a hot reload. `Application.onCreate` has already run in this process, so
     * hooking it again would never fire — the runtime is restarted directly from the Context
     * the old instance handed over through `HotReloadingParam.setSavedInstanceState(...)`
     * (falling back to the system Context, which is enough for a gesture monitor).
     */
    fun reinstall(context: Context) {
        appContext = context
        appClassLoader = appClassLoader ?: context.classLoader
        if (bound.compareAndSet(false, true)) {
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_REINSTALLED_AFTER_HOT_RELOAD")
            startInputMonitor(context)
        } else {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_REINSTALL_ALREADY_BOUND")
        }
        // The METHOD hooks have to be re-registered too, not just the runtime. A hot reload (what
        // `adb install -r` triggers with autoHotReload) drops every hook of the old generation and
        // `install()` — the only other place that installs them — never runs again in this process, so
        // after any install every Flyme-style freeform hook (遮罩 / 居中 / 手势 / 联系人条) silently
        // stopped working until the next SystemUI restart. That is indistinguishable from "the feature
        // is broken" while looking at the phone, which is exactly how it was reported.
        runCatching {
            com.repl.bubbledrawer.launch.FlymeFreeformController.installHooks(
                module,
                prefs,
                appClassLoader ?: context.classLoader,
            )
        }.onFailure {
            module.log(Log.WARN, TAG, "FLYME_FREEFORM_HOOKS_REINSTALL_FAILED", it)
        }
        // 联系人条的数据源也是方法 hook（`NotifPipeline` 的构造 + `addCollectionListener`），
        // 同样属于"换代就没了"的那一半：少了它，条上只剩启动那一刻的记忆，之后任何通知都进不来
        // —— 设备日志上就是 `CONTACT_BAR_HIDE reason=NO_CONTACTS` 一直重复，看起来像"功能没做"。
        // （`startInputMonitor` 只建了 ContactBarController，它负责摆窗口，不负责收通知。）
        runCatching {
            com.repl.bubbledrawer.contactbar.RecentConversations.installHooks(
                module,
                appClassLoader ?: context.classLoader,
            )
        }.onFailure {
            module.log(Log.WARN, TAG, "CONTACT_BAR_HOOKS_REINSTALL_FAILED", it)
        }
    }

    /** The Application Context captured at first start — handed to the next generation. */
    fun capturedContext(): Context? = appContext

    private fun startInputMonitor(context: Context) {
        appContext = context
        try {
            val monitor = CornerInputMonitor(context, prefs) { priority, message, error ->
                module.log(priority, TAG, message, error)
            }
            inputMonitor = monitor

            // Wire FlymeFreeformController for Flyme-style lightweight window features
            val freeformCtrl = com.repl.bubbledrawer.launch.FlymeFreeformController(
                module, prefs, context, appClassLoader ?: context.classLoader
            )
            flymeController = freeformCtrl
            freeformCtrl.pilferCallback = { monitor.pilfer() }
            monitor.outsideTapHandler = { ev -> freeformCtrl.onInterceptTouchEvent(ev) }
            freeformCtrl.start()

            // 小窗底部联系人条: rides the freeform task notifications forwarded by the hooks above.
            val bar = com.repl.bubbledrawer.contactbar.ContactBarController(context, prefs) { priority, message, error ->
                module.log(priority, TAG, message, error)
            }
            contactBar = bar
            com.repl.bubbledrawer.launch.FlymeFreeformController.freeformObserver = bar
            // 聚合支付（QuickPay）的执行入口：面板点击 / App 端广播都经这个触发器进控制器。
            com.repl.bubbledrawer.contactbar.quickPayTrigger = { bar.showAggregateQuickPay() }

            monitor.start()
            module.log(
                Log.INFO,
                TAG,
                // Ask the monitor, do not guess: `start()` applies the settings (and therefore
                // brings the gesture monitor up) on the MAIN thread, so on the hot-reload entry
                // point this line runs before the transport exists — reading `monitorActive`
                // here printed `spy-view-fallback` for a monitor that came up 8 ms later, and
                // the diagnostics page repeated that as a failure.
                "SYSTEMUI_CORNER_READY transport=" + monitor.transport,
            )
            // Only arbitration left after the transport switch: keep the native back
            // handler's commit out of a corner stroke the drawer claimed for itself.
            val guard = BackGestureGuard(
                module,
                { monitor.isCornerCommitWindow() },
            ) { priority, message, error ->
                module.log(priority, TAG, message, error)
            }
            guard.install(appClassLoader ?: context.classLoader)
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_LINKAGE_FAILED", exception)
        }
    }

    private companion object {
        const val TAG = "BubbleDrawer"

        /** AOSP first (vanilla ROMs, reference target), HyperOS/MIUI 17 impl second. */
        val APPLICATION_CLASS_CANDIDATES = listOf(
            "com.android.systemui.SystemUIApplication",
            "com.android.systemui.application.impl.SystemUIApplicationImpl",
        )
    }
}
