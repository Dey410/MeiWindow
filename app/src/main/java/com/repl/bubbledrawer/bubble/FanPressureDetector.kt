package com.repl.bubbledrawer.bubble

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Recognizes heavy press (3D Touch / Force Touch) gestures using the phone's built-in
 * barometer (Sensor.TYPE_PRESSURE = 6).
 *
 * Physical principle:
 * Sealed modern smartphones experience micro-deflection when the glass is pressed firmly,
 * reducing internal volume and causing a rapid positive spike in air pressure (Boyle's Law).
 */
class FanPressureDetector(
    context: Context,
    private val onHeavyPress: () -> Unit,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    val pressureSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var thresholdHpa: Float = 1.2f

    /**
     * 校准会话的接收端：非 null 时进入「只记按压、不翻页」模式，每完成一次按压回一次峰值（hPa）。
     *
     * 回调在**传感器线程**上（跟传感器事件同一个线程，`FanPressureThread`）—— 接的一方自己负责
     * 转投递。之所以不在这里 post 到主线程：`stopListening()` 会 `removeCallbacksAndMessages(null)`
     * 清掉主线程队列，而「手指抬起时结算最后一次按压」正是走那条路，post 出去的那一下会被自己清掉。
     */
    @Volatile
    var calibrationSink: ((Float) -> Unit)? = null

    /** 把连续差值切成一次次按压（见 [PressurePressTracker]）；只在 [calibrationSink] 存在时使用。 */
    private val pressTracker = PressurePressTracker()

    @Volatile
    private var isListening = false

    @Volatile
    var isTouchActive: Boolean = false
        private set

    private var baseline = 0f
    private var baselinePending = true
    private var isHeavyPressed = false
    private var minPressure = Float.MAX_VALUE
    private var lastTriggerTime = 0L

    // Settle window: suppress triggers right after startListening while the user is completing the swipe
    private var settleUntilUptime = 0L

    // Motion suppression: suppress triggers while finger is actively sliding fast
    private var motionSuppressedUntilUptime = 0L

    val isSupported: Boolean get() = pressureSensor != null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!isTouchActive || !isListening) return
            if (event == null || event.sensor.type != Sensor.TYPE_PRESSURE) return
            val values = event.values ?: return
            if (values.isEmpty()) return
            val value = values[0]
            if (!value.isFinite()) return

            val now = SystemClock.uptimeMillis()

            if (baselinePending) {
                baseline = value
                minPressure = value
                baselinePending = false
                return
            }

            // During the initial settle window (swipe-to-open gesture settling), keep tracking baseline
            if (now < settleUntilUptime) {
                baseline = baseline * 0.5f + value * 0.5f
                minPressure = minOf(minPressure, value)
                return
            }

            // If finger is currently sliding fast, adapt baseline to the motion and suppress
            if (now < motionSuppressedUntilUptime) {
                baseline = baseline * 0.7f + value * 0.3f
                minPressure = minOf(minPressure, value)
                return
            }

            if (value < minPressure) {
                minPressure = value
                // Smoothly adapt baseline downwards if baseline shifted
                baseline = baseline * 0.7f + value * 0.3f
            }

            val delta = value - baseline

            // 校准会话：只做按压分段 + 峰值上报，绝不触发翻页 —— 校准期间用户要连按 5 次，
            // 每次都被当成翻页的话，扇形会一边校准一边翻页。
            val sink = calibrationSink
            if (sink != null) {
                pressTracker.onDelta(delta)?.let(sink)
                return
            }

            val threshold = thresholdHpa
            val releaseThreshold = threshold * 0.4f

            if (delta >= threshold) {
                if (!isHeavyPressed && (now - lastTriggerTime >= 250L)) {
                    isHeavyPressed = true
                    lastTriggerTime = now
                    mainHandler.post {
                        if (isTouchActive && isEnabled) {
                            onHeavyPress()
                        }
                    }
                }
            } else if (delta < releaseThreshold) {
                isHeavyPressed = false
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Start listening when a touch stroke begins on the fan panel.
     * @param settleMs Warmup/settle period during which swipe pressure is absorbed into baseline.
     */
    fun startListening(settleMs: Long = 280L) {
        if (!isSupported) return
        val now = SystemClock.uptimeMillis()
        isTouchActive = true
        settleUntilUptime = now + settleMs
        motionSuppressedUntilUptime = 0L
        baseline = 0f
        baselinePending = true
        isHeavyPressed = false
        minPressure = Float.MAX_VALUE
        pressTracker.reset()

        if (isListening) return

        if (sensorThread == null) {
            val thread = HandlerThread("FanPressureThread")
            thread.start()
            sensorThread = thread
            sensorHandler = Handler(thread.looper)
        }

        try {
            // ~15Hz sampling rate (66667 us, balanced mode)
            isListening = sensorManager?.registerListener(
                sensorListener,
                pressureSensor,
                66667,
                sensorHandler,
            ) ?: false
        } catch (e: Throwable) {
            isListening = false
            Log.w("BubbleDrawer", "Failed to register pressure sensor", e)
        }
    }

    /**
     * Notify detector that the finger moved significantly (suppressing heavy press during fast slides).
     */
    fun onMotionOccurred(suppressDurationMs: Long = 180L) {
        val now = SystemClock.uptimeMillis()
        motionSuppressedUntilUptime = maxOf(motionSuppressedUntilUptime, now + suppressDurationMs)
    }

    fun stopListening() {
        // 抬手（或流被系统掐断）时把还按着的那一次按压结算掉：不 post 到主线程，因为下面的
        // removeCallbacksAndMessages(null) 会把它清掉（见 calibrationSink 的注释）。
        calibrationSink?.let { sink -> pressTracker.flush()?.let(sink) }
        pressTracker.reset()
        isTouchActive = false
        mainHandler.removeCallbacksAndMessages(null)
        if (!isListening) return
        try {
            sensorManager?.unregisterListener(sensorListener)
        } catch (_: Throwable) {}
        isListening = false
        isHeavyPressed = false
        baselinePending = true
    }

    fun resetHeavyPressState() {
        isHeavyPressed = false
    }

    fun destroy() {
        // 先摘掉校准接收端：拆机器（热重载）时不该再报一次按压。
        calibrationSink = null
        stopListening()
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
    }
}
