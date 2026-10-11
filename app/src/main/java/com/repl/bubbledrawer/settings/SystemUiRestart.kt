package com.repl.bubbledrawer.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.repl.bubbledrawer.R
import java.util.concurrent.Executors

/**
 * 重启系统界面（`killall com.android.systemui`；它是 system_server 拉起的常驻进程，杀掉后会自动
 * 重新拉起 —— 屏幕闪一下、状态栏/导航栏消失约一秒，不用重启手机）。
 *
 * 为什么设置页需要这个按钮：模块的 hook 全部活在 SystemUI 进程里。
 *
 *  - `adb install -r` 触发的 LSPosed **热重载**会丢弃上一代所有 hook，只重建模块自己的运行时；
 *    `SystemUiHookInstaller.reinstall` 现在会重装方法 hook，但进程里那些"只在启动时读一次"的状态
 *    （输入监视器、窗口缓存、`MiuiFreeformModeDisplayInfo` 等）不会因此重来。
 *  - 调试时最想要的就是一个干净进程：复现问题、确认某个开关是否真的生效、或者让改动立刻生效。
 *
 * 因此这里给一个一键入口，而不是让用户去开 adb 或重启手机。需要 root：`su` 不可用或未授权时
 * 什么都不做，只报一句提示（三条命令依次尝试：toybox 的 `killall`、`pkill`、`pidof` + `kill`）。
 */
object SystemUiRestart {

    private const val TAG = "BubbleDrawer"
    private const val TARGET = "com.android.systemui"

    private const val COMMAND =
        "killall $TARGET 2>/dev/null || pkill -f $TARGET 2>/dev/null || kill -9 \$(pidof $TARGET) 2>/dev/null"

    /**
     * `su` 的位置随 root 方案而变：KernelSU / Magisk 都在 `/system/bin/su`，老一些的 Magisk 在
     * `/system/xbin/su` 或 `/debug_ramdisk/su`。app 进程的 PATH 里有 `/system/bin`（zygote 传下来的），
     * 所以裸 `su` 通常就能用；列出来是为了换 root 方案时不用改代码 —— **只有"起不来"才换下一个**，
     * 起来了但退出码非 0（未授权）就不要再试，免得连弹几次授权框。
     */
    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su")

    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "systemui-restart") }
    private val main = Handler(Looper.getMainLooper())

    fun restart(context: Context) {
        val app = context.applicationContext
        // Toast 属于本进程的窗口，SystemUI 重启期间照样能显示（而且这里必须在杀之前发出去）。
        toast(app, app.getString(R.string.restart_systemui_toast))
        worker.execute {
            val exit = runSu(app)
            Log.i(TAG, "SYSTEMUI_RESTART exit=$exit")
            // 0 = 至少一条命令命中并执行了；未授权通常是 1，找不到 su 是 null。
            if (exit != 0) toast(app, app.getString(R.string.restart_systemui_failed))
        }
    }

    /** 依次尝试 [SU_CANDIDATES]；返回 `su -c` 的退出码，null 表示没有一个能起来。 */
    private fun runSu(context: Context): Int? {
        for (candidate in SU_CANDIDATES) {
            val process = runCatching {
                ProcessBuilder(candidate, "-c", COMMAND).redirectErrorStream(true).start()
            }.onFailure {
                Log.i(TAG, "SYSTEMUI_RESTART su-missing candidate=$candidate")
            }.getOrNull() ?: continue
            return runCatching {
                // 读干输出，免得提示把管道塞满；同时留下日志供排查。
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) Log.i(TAG, "SYSTEMUI_RESTART $candidate: $line")
                }
                process.waitFor()
            }.getOrNull()
        }
        return null
    }

    private fun toast(context: Context, text: String) {
        main.post { runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() } }
    }
}
