package com.repl.bubbledrawer.diagnostics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 一次日志采样。[source] 说明它是从哪儿读来的（给用户看的），[error] 非空表示这次没读成。
 */
data class LogSnapshot(
    val lines: List<String>,
    val source: String,
    val error: String? = null,
)

/**
 * 读模块自己的日志。模块的输出有两个去处，都**需要 root**：
 *
 *  1. logcat（进程内 `Log.i("BubbleDrawer", …)`）—— 内容最新，但 ring buffer 会滚掉旧行，
 *     重启系统界面之后"安装记录"可能已经不在了；
 *  2. LSPosed 的模块日志 `/data/adb/lspd/log/modules_*.log` —— 会一直留着，适合事后回看。
 *
 * 采集顺序按"时间从旧到新"拼接（持久日志在前、logcat 在后），这样分析器里"最后一行"始终等于
 * "最近发生的事" —— 热重载那类需要判断先后的结论才不会读反。
 *
 * 走 `su -c`：Magisk / KernelSU 第一次会弹授权框（和「重启系统界面」同一个前提）。没有 root 时
 * 什么都不问、直接把原因交给页面显示 —— 这个诊断本来就是给"拿不到 adb 的测试机"用的，
 * 所以它必须说清楚自己为什么读不到，而不是一直转圈。
 */
object ModuleLogSource {

    private const val TAG = "BubbleDrawer"

    /** `su` 的位置随 root 方案而变（与 `SystemUiRestart` 同一份候选表）。 */
    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su")

    private const val LOGCAT_MARK = "###LOGCAT"
    private const val LSPD_MARK = "###LSPD"

    /** logcat 取最后这么多行；`-s BubbleDrawer` 只留本模块的行。 */
    private const val LOGCAT_TAIL = 4000

    /** 持久日志取最后这么多行（里面还混着别的模块，分析器会自己筛）。 */
    private const val LSPD_TAIL = 1500

    private const val TIMEOUT_SECONDS = 25L

    private val COMMAND: String = listOf(
        "echo $LOGCAT_MARK",
        "logcat -d -t $LOGCAT_TAIL -s BubbleDrawer 2>/dev/null",
        "echo $LSPD_MARK",
        "f=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1)",
        "[ -n \"\$f\" ] && tail -n $LSPD_TAIL \"\$f\"",
    ).joinToString("\n")

    suspend fun collect(): LogSnapshot = withContext(Dispatchers.IO) {
        val output = runSu()
        when {
            output == null -> LogSnapshot(
                lines = emptyList(),
                source = "无",
                error = "没能执行 su：这个诊断需要 root 才能读系统日志（模块本身不需要 root）",
            )
            output.isBlank() -> LogSnapshot(
                lines = emptyList(),
                source = "无",
                error = "root 命令没有返回内容：可能未授权（第一次使用请在弹窗里允许），或者这台机器没有 root",
            )
            else -> {
                val snapshot = parse(output)
                if (ModuleLogDiagnosis.moduleLines(snapshot.lines).isEmpty()) {
                    // su 的错误信息会混在里面（"permission denied" 之类），原样带出来，别让用户猜。
                    val hint = output.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                    LogSnapshot(
                        lines = snapshot.lines,
                        source = snapshot.source,
                        error = buildString {
                            append("读到了 ${snapshot.lines.size} 行输出，但里面没有模块日志")
                            if (hint != null) append("（root 说：$hint）")
                            append("：确认已授予 root，并且模块在系统界面里运行过")
                        },
                    )
                } else {
                    snapshot
                }
            }
        }
    }

    private fun parse(output: String): LogSnapshot {
        val logcat = mutableListOf<String>()
        val persistent = mutableListOf<String>()
        var bucket = logcat
        for (line in output.lineSequence()) {
            when (line.trim()) {
                LOGCAT_MARK -> bucket = logcat
                LSPD_MARK -> bucket = persistent
                else -> if (line.isNotBlank()) bucket.add(line)
            }
        }
        val logcatModule = ModuleLogDiagnosis.moduleLines(logcat)
        val persistentModule = ModuleLogDiagnosis.moduleLines(persistent)

        // logcat 是"现场"，优先；只有当它已经滚掉了模块输出时才拿持久日志补，并且放在前面，
        // 保持"旧 → 新"的顺序。
        val lines = when {
            logcatModule.isNotEmpty() && persistentModule.isNotEmpty() -> persistent + logcat
            logcatModule.isNotEmpty() -> logcat
            else -> persistent
        }
        val source = when {
            logcatModule.isNotEmpty() && persistentModule.isNotEmpty() -> "logcat + LSPosed 模块日志"
            logcatModule.isNotEmpty() -> "logcat"
            persistentModule.isNotEmpty() -> "LSPosed 模块日志"
            else -> "logcat"
        }
        return LogSnapshot(lines = lines, source = source)
    }

    /**
     * 依次试 [SU_CANDIDATES]，返回命令的完整输出；null = 没有一个能起来、或超时被杀。
     *
     * 输出必须在**另一个线程**上读：`su` 未授权时会停在授权弹窗上不退出，若在主线程
     * `readText()` 就会永远等下去，超时也轮不到。
     */
    private fun runSu(): String? {
        for (candidate in SU_CANDIDATES) {
            val process = runCatching {
                ProcessBuilder(candidate, "-c", COMMAND).redirectErrorStream(true).start()
            }.onFailure {
                Log.i(TAG, "DIAGNOSIS su-missing candidate=$candidate")
            }.getOrNull() ?: continue

            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                }
            }
            reader.isDaemon = true
            reader.start()

            val finished = runCatching { process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                Log.i(TAG, "DIAGNOSIS timeout candidate=$candidate lines=${output.lineSequence().count()}")
            }
            runCatching { reader.join(2000) }
            // 起来了（哪怕未授权）：不再试下一个，免得连弹几次授权框。
            return output.toString()
        }
        return null
    }
}
