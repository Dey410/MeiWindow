package com.repl.bubbledrawer.launch

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.xposed.ModuleResources

/**
 * Renders Flyme-style long-press floating action menu (悬浮/迷你小窗长按菜单)
 * directly over a mini freeform window.
 *
 * Implements Flyme's dual action buttons:
 * - 静音 / 取消静音 (Mute / Unmute): Toggles audio stream via [MiniAppAudioHelper]
 * - 放大 (Scale/Maximize): Restores mini window back to standard freeform mode
 *
 * Attaches directly to the task's SurfaceControl leash via SurfaceControlViewHost
 * and WindowlessWindowManager (via reflection for compatibility across Android 13/14/15),
 * ensuring 0-latency tracking and hardware-accelerated rounded corner clipping.
 */
class FlymeMiniMenuOverlay(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onScaleClicked: (taskId: Int) -> Unit,
    private val onDismissed: (taskId: Int) -> Unit,
) {
    companion object {
        private const val TAG = "FlymeMiniMenuOverlay"
        private const val OVERLAY_LAYER = 30000

        /** 菜单显示期间多久看一眼真实静音状态；400ms 够快，也不至于让主线程忙碌。 */
        private const val MUTE_POLL_MS = 400L
    }

    private var activeTaskId: Int = -1
    private var surfaceControl: SurfaceControl? = null
    private var viewHost: Any? = null
    private var rootLayout: ViewGroup? = null
    private var menuContainerView: View? = null
    var screenBounds: Rect? = null
        private set
    private var isShowing = false

    /**
     * 静音/取消静音按钮的现场状态（2026-10-18）。
     *
     * 音频是被 `MiniAppAudioHelper` 从**系统**层面改的，用户随时可以用音量键、音量面板或应用自己的
     * 按钮把声音开回来 —— 那种改变不会通知我们。所以菜单显示期间按 [MUTE_POLL_MS] 轮询一次真实
     * 状态，只更新图标（不碰播放）：用户报的「静音后用其他方式开启声音，按钮状态不会自动切换」
     * 就是这里少了一块。
     */
    private var muteButton: ImageView? = null
    private var activePackageName: String = ""
    private var activeUid: Int = -1
    private var activeIsEnlarged: Boolean = false
    private var shownMuted: Boolean? = null
    private var muteTicker: Runnable? = null

    fun isShowingForTask(taskId: Int): Boolean = isShowing && activeTaskId == taskId

    fun isShowing(): Boolean = isShowing

    /**
     * 按**真实**静音状态刷新图标（大/小两套图标见 `ic_large_*` / `ic_small_*`）。
     *
     * @param force true = 无论如何重设一次（刚弹出时用），false = 状态没变就不碰 drawable
     */
    private fun refreshMuteIcon(force: Boolean = false) {
        val button = muteButton ?: return
        val muted = MiniAppAudioHelper.isAppMuted(context, activePackageName, activeUid)
        if (!force && muted == shownMuted) return
        shownMuted = muted
        val iconRes = when {
            activeIsEnlarged && muted -> R.drawable.ic_large_unmute
            activeIsEnlarged -> R.drawable.ic_large_mute
            muted -> R.drawable.ic_small_unmute
            else -> R.drawable.ic_small_mute
        }
        val drawable = loadDrawable(iconRes)
        if (drawable != null) {
            button.setImageDrawable(drawable)
        } else {
            Log.w(TAG, "Failed to load mute drawable resId=$iconRes")
        }
        Log.d(TAG, "Mute icon pkg=$activePackageName muted=$muted enlarged=$activeIsEnlarged")
    }

    /**
     * 菜单显示期间轮询静音状态 —— 声音可能被菜单以外的方式打开/关掉，系统不会通知我们。
     */
    private fun startMuteTicker() {
        stopMuteTicker()
        val ticker = object : Runnable {
            override fun run() {
                if (!isShowing) return
                refreshMuteIcon()
                handler.postDelayed(this, MUTE_POLL_MS)
            }
        }
        muteTicker = ticker
        handler.postDelayed(ticker, MUTE_POLL_MS)
    }

    private fun stopMuteTicker() {
        muteTicker?.let { handler.removeCallbacks(it) }
        muteTicker = null
    }

    private fun loadDrawable(resId: Int): Drawable? {
        val moduleContext = ModuleResources.packageContext(context) ?: context
        return runCatching {
            moduleContext.getDrawable(resId)
        }.getOrNull() ?: runCatching {
            moduleContext.resources.getDrawable(resId, moduleContext.theme)
        }.getOrNull()
    }

    /**
     * Shows the floating action menu for a mini window.
     */
    fun show(
        taskId: Int,
        packageName: String,
        uid: Int,
        bounds: Rect,
        leash: SurfaceControl,
        config: Configuration?,
        displayId: Int,
        miniScale: Float = 0.28f,
        screenBounds: Rect? = null,
        isEnlarged: Boolean = false,
    ) {
        if (isShowing) {
            dismiss(animate = false)
        }

        activeTaskId = taskId
        this.screenBounds = screenBounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)

        val moduleContext = ModuleResources.packageContext(context) ?: context
        val fanContext = ModuleResources.FanContext(context, moduleContext)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(displayId) ?: dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            Log.e(TAG, "Cannot find display for id=$displayId")
            return
        }

        val effectiveConfig = config ?: context.resources.configuration

        // 1. Build child SurfaceControl parented to mini task leash
        val sc = runCatching {
            val builder = SurfaceControl.Builder()
                .setName("FlymeMiniMenuOverlay-$taskId")
            runCatching {
                builder.javaClass.getMethod("setContainerLayer").invoke(builder)
            }
            runCatching {
                builder.javaClass.getMethod("setParent", SurfaceControl::class.java).invoke(builder, leash)
            }
            builder.build()
        }.getOrNull()

        if (sc == null) {
            Log.e(TAG, "Failed to create SurfaceControl for taskId=$taskId")
            return
        }
        surfaceControl = sc

        // Position and layer transaction
        runCatching {
            val tx = SurfaceControl.Transaction()
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setTrustedOverlay", SurfaceControl::class.java, java.lang.Boolean.TYPE)
                    .invoke(tx, sc, true)
            }
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setLayer", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                    .invoke(tx, sc, OVERLAY_LAYER)
            }
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setPosition", SurfaceControl::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .invoke(tx, sc, 0f, 0f)
            }
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setBufferSize", SurfaceControl::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(tx, sc, width, height)
            }
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("show", SurfaceControl::class.java)
                    .invoke(tx, sc)
            }
            tx.apply()
        }.onFailure {
            Log.e(TAG, "Failed to configure SurfaceControl transaction", it)
        }

        // 2. Build WindowlessWindowManager
        val wwm = createWindowlessWindowManager(effectiveConfig, sc)
        if (wwm == null) {
            Log.e(TAG, "Failed to create WindowlessWindowManager")
            removeSurface(sc)
            return
        }

        // 3. Build SurfaceControlViewHost
        val host = createSurfaceControlViewHost(fanContext, display, wwm)
        if (host == null) {
            Log.e(TAG, "Failed to create SurfaceControlViewHost")
            removeSurface(sc)
            return
        }
        viewHost = host

        // Scale compensation: since this SurfaceControl is a child of the task leash,
        // it gets scaled down by miniScale on screen. We compensate so that buttons
        // appear at a comfortable physical size (48dp) and corners match the mini window.
        val scaleFactor = if (miniScale in 0.05f..0.95f) (1f / miniScale) else 3.57f
        val density = fanContext.resources.displayMetrics.density

        // 4. Construct view hierarchy programmatically
        val root = FrameLayout(fanContext).apply {
            layoutParams = ViewGroup.LayoutParams(width, height)
            isClickable = true
            isFocusable = false
        }
        rootLayout = root

        // 遮罩画成**直角矩形**，圆角交给窗口 leash 自己裁（2026-10-23 用户实测：系统确实会裁）。
        // 以前这里自绘 `18dp × 1/scale` 的圆角，和 ROM 给小窗的真实圆角对不上，四角会露出黑边；
        // 这个 SurfaceControl 是 leash 的子层，父层的圆角裁剪对子层同样生效。
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x33000000) // Flyme 20% black mask
        }

        val menuContainer = FrameLayout(fanContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = bgDrawable
            isClickable = true
            isFocusable = false
        }
        menuContainerView = menuContainer
        root.addView(menuContainer)

        val isPortrait = width < height
        val innerMenu = LinearLayout(fanContext).apply {
            orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        menuContainer.addView(innerMenu)

        // Target 56dp on physical screen: (density * 56 * scaleFactor)
        val rawButtonSize = (density * 56f * scaleFactor).toInt()
        val maxBtnWidth = (width * 0.55f).toInt()
        val maxBtnHeight = (height * 0.38f).toInt()
        val buttonSize = rawButtonSize.coerceAtMost(minOf(maxBtnWidth, maxBtnHeight))
            .coerceAtLeast((density * 48f).toInt())

        val marginPx = (density * 12f * scaleFactor).toInt()
            .coerceAtMost(if (isPortrait) (height * 0.08f).toInt() else (width * 0.08f).toInt())

        val pad = (buttonSize * 0.10f).toInt()

        val muteBtn = ImageView(fanContext).apply {
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
        }

        val scaleBtn = ImageView(fanContext).apply {
            val lp = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                if (isPortrait) topMargin = marginPx else marginStart = marginPx
            }
            layoutParams = lp
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
        }

        innerMenu.addView(muteBtn)
        innerMenu.addView(scaleBtn)

        // 图标状态存在字段里：轮询线程与点击回调都要用同一份，见 [refreshMuteIcon]。
        muteButton = muteBtn
        activePackageName = packageName
        activeUid = uid
        activeIsEnlarged = isEnlarged
        shownMuted = null
        refreshMuteIcon(force = true)

        val scaleIconRes = if (isEnlarged) R.drawable.ic_large_scale_min else R.drawable.ic_small_scale_max
        val scaleD = loadDrawable(scaleIconRes)
        if (scaleD != null) {
            scaleBtn.setImageDrawable(scaleD)
        } else {
            Log.w(TAG, "Failed to load scale drawable resId=$scaleIconRes")
        }

        // Prevent WMShell from intercepting touch during button/menu interaction
        val touchDisabler = View.OnTouchListener { v, ev ->
            Log.d(TAG, "Touch on view=${v.javaClass.simpleName} action=${ev.actionMasked} x=${ev.x} y=${ev.y}")
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                FlymeFreeformController.setDisableTouch(true)
            }
            false
        }
        root.setOnTouchListener(touchDisabler)
        menuContainer.setOnTouchListener(touchDisabler)
        muteBtn.setOnTouchListener(touchDisabler)
        scaleBtn.setOnTouchListener(touchDisabler)

        // Click actions
        muteBtn.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            // 每次都按**真实**状态取反：菜单可能已经开了一会儿，用户在这期间用音量键改过。
            val currentlyMuted = MiniAppAudioHelper.isAppMuted(context, packageName, uid)
            val newMute = !currentlyMuted
            val applied = MiniAppAudioHelper.setAppMuted(context, packageName, uid, newMute)
            refreshMuteIcon(force = true)
            // 用户 2026-10-18：点静音按钮就把面板收掉（和「放大」一致），别让它挡着小窗。
            Log.i(TAG, "Mute toggled: from=$currentlyMuted to=$newMute applied=$applied pkg=$packageName")
            FlymeFreeformController.log(
                "MINI_MUTE taskId=$taskId pkg=$packageName uid=$uid from=$currentlyMuted " +
                    "to=$newMute applied=$applied",
            )
            dismiss(animate = true)
        }

        scaleBtn.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Log.i(TAG, "Scale clicked for taskId=$taskId")
            dismiss(animate = true)
            onScaleClicked(taskId)
        }

        // Tap on background to dismiss
        root.setOnClickListener {
            Log.i(TAG, "Root background clicked, dismiss overlay for taskId=$taskId")
            dismiss(animate = true)
        }
        menuContainer.setOnClickListener {
            Log.i(TAG, "Menu container clicked, dismiss overlay for taskId=$taskId")
            dismiss(animate = true)
        }

        // Layout params for WindowlessWindowManager
        //
        // 2026-10-23 用户报「静音后按音量键完全没反应，系统音量条都不弹」：菜单本身不需要键盘，
        // 但 `TYPE_APPLICATION` 默认是**可聚焦**的 —— 这个全屏窗口一旦拿到输入焦点，菜单关掉后
        // 焦点不一定会还给下面的小窗，硬件键（音量键）就被吞掉了。加 `FLAG_NOT_FOCUSABLE`
        // 让它永远不参与键盘焦点，触摸/点击不受影响。
        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "FlymeMiniMenuOverlay-$taskId"
            runCatching {
                WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(this)
            }
        }

        var setViewSuccess = false

        // 1. Precise lookup: setView(View, WindowManager.LayoutParams)
        val methodWithWmLp = runCatching {
            host.javaClass.getMethod("setView", View::class.java, WindowManager.LayoutParams::class.java)
        }.getOrNull() ?: host.javaClass.methods.firstOrNull {
            it.name == "setView" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0].isAssignableFrom(View::class.java) &&
                it.parameterTypes[1].isAssignableFrom(WindowManager.LayoutParams::class.java)
        }

        if (methodWithWmLp != null) {
            runCatching {
                methodWithWmLp.invoke(host, root, lp)
                setViewSuccess = true
            }.onFailure {
                Log.e(TAG, "Failed to invoke setView(View, WindowManager.LayoutParams)", it)
            }
        }

        // 2. Custom LayoutParams lookup (e.g. SurfaceControlViewHost.LayoutParams wrapper)
        if (!setViewSuccess) {
            val customLpMethod = host.javaClass.methods.firstOrNull {
                it.name == "setView" && it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(View::class.java)
            }
            if (customLpMethod != null) {
                val paramType = customLpMethod.parameterTypes[1]
                val converted = runCatching {
                    paramType.getConstructor(WindowManager.LayoutParams::class.java).newInstance(lp)
                }.getOrNull() ?: runCatching {
                    paramType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(width, height)
                }.getOrNull()

                if (converted != null) {
                    runCatching {
                        customLpMethod.invoke(host, root, converted)
                        setViewSuccess = true
                    }.onFailure {
                        Log.e(TAG, "Failed to invoke setView with converted LayoutParams", it)
                    }
                }
            }
        }

        // 3. Fallback: setView(View, int width, int height)
        if (!setViewSuccess) {
            val dimsMethod = host.javaClass.methods.firstOrNull {
                it.name == "setView" &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0].isAssignableFrom(View::class.java) &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (dimsMethod != null) {
                runCatching {
                    dimsMethod.invoke(host, root, width, height)
                    setViewSuccess = true
                }.onFailure {
                    Log.e(TAG, "Failed to invoke setView(View, int, int)", it)
                }
            }
        }

        if (!setViewSuccess) {
            Log.e(TAG, "All setView methods failed for taskId=$taskId")
            // 半成品也要拆干净：字段与真实资源保持一致，否则下一次 [show] 的 `isShowing` 判断
            // 和退出清理都会指向一个已经不存在的 surface。
            val failedHost = viewHost
            removeSurface(sc)
            releaseHost(failedHost)
            surfaceControl = null
            viewHost = null
            rootLayout = null
            menuContainerView = null
            return
        }

        isShowing = true
        FlymeFreeformController.setDisableTouch(true)
        startMuteTicker()

        // Enter animation
        menuContainer.alpha = 0f
        menuContainer.scaleX = 0.85f
        menuContainer.scaleY = 0.85f
        menuContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()

        Log.i(TAG, "FlymeMiniMenuOverlay shown for taskId=$taskId bounds=$bounds portrait=$isPortrait")
    }

    /**
     * Dismisses the menu overlay.
     *
     * 退出动画的 120ms 里可能又弹出新菜单（用户在另一个小窗上长按）。所以这里先把**当前这一份**
     * host / SurfaceControl 摘出来（字段立刻置空），结束回调只放掉摘出来的那一份 —— 否则回调会把
     * 新菜单的 surface 一起拆了，表现出来就是「菜单闪一下没了」。
     */
    fun dismiss(animate: Boolean = true) {
        if (!isShowing) return
        val taskId = activeTaskId
        val container = menuContainerView
        val host = viewHost
        val surface = surfaceControl
        isShowing = false
        activeTaskId = -1
        screenBounds = null
        menuContainerView = null
        viewHost = null
        rootLayout = null
        surfaceControl = null
        stopMuteTicker()
        muteButton = null
        shownMuted = null
        FlymeFreeformController.setDisableTouch(false)
        FlymeFreeformController.suppressSingleTapTaskId = -1

        val finish = {
            releaseHost(host)
            removeSurface(surface)
            if (taskId > 0) onDismissed(taskId)
        }
        if (animate && container != null && container.isAttachedToWindow) {
            container.animate()
                .alpha(0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(120)
                .withEndAction { finish() }
                .start()
        } else {
            finish()
        }
    }

    private fun releaseHost(host: Any?) {
        if (host == null) return
        handler.post {
            runCatching {
                host.javaClass.getMethod("release").invoke(host)
            }.onFailure {
                // 释放失败 = 这个 SCVH 窗口（连同它的输入通道）留在系统里：菜单看不见了，
                // 但硬件键可能还被它接着。以前这里静默吞掉，正是「关掉菜单后音量键失灵」的温床。
                Log.e(TAG, "release view host failed", it)
                FlymeFreeformController.log("MINI_MENU_HOST_RELEASE_FAILED error=${it.message}")
            }
        }
    }

    private fun removeSurface(sc: SurfaceControl?) {
        if (sc == null) return
        runCatching {
            val tx = SurfaceControl.Transaction()
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("remove", SurfaceControl::class.java)
                    .invoke(tx, sc)
            }
            tx.apply()
        }
    }

    private fun createWindowlessWindowManager(
        config: Configuration,
        sc: SurfaceControl,
    ): Any? {
        val wwmClass = runCatching { Class.forName("android.view.WindowlessWindowManager") }.getOrNull() ?: return null
        for (ctor in wwmClass.constructors) {
            if (ctor.parameterCount == 3 &&
                ctor.parameterTypes[0] == Configuration::class.java &&
                ctor.parameterTypes[1] == SurfaceControl::class.java
            ) {
                return runCatching { ctor.newInstance(config, sc, null) }.getOrNull()
            }
            if (ctor.parameterCount == 2 &&
                ctor.parameterTypes[0] == Configuration::class.java &&
                ctor.parameterTypes[1] == SurfaceControl::class.java
            ) {
                return runCatching { ctor.newInstance(config, sc) }.getOrNull()
            }
        }
        return null
    }

    private fun createSurfaceControlViewHost(
        ctx: Context,
        display: Display,
        wwm: Any,
    ): Any? {
        val scvhClass = runCatching { Class.forName("android.view.SurfaceControlViewHost") }.getOrNull() ?: return null
        val wwmClass = wwm.javaClass
        for (ctor in scvhClass.constructors) {
            if (ctor.parameterCount == 4 &&
                ctor.parameterTypes[0] == Context::class.java &&
                ctor.parameterTypes[1] == Display::class.java &&
                ctor.parameterTypes[2].isAssignableFrom(wwmClass)
            ) {
                return runCatching {
                    ctor.newInstance(ctx, display, wwm, "FlymeMiniMenuOverlay")
                }.getOrNull()
            }
            if (ctor.parameterCount == 3 &&
                ctor.parameterTypes[0] == Context::class.java &&
                ctor.parameterTypes[1] == Display::class.java &&
                ctor.parameterTypes[2].isAssignableFrom(wwmClass)
            ) {
                return runCatching {
                    ctor.newInstance(ctx, display, wwm)
                }.getOrNull()
            }
        }
        return null
    }
}
