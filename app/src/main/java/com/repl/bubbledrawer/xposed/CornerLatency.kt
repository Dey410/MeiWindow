package com.repl.bubbledrawer.xposed

/**
 * 角落手势「手指按下 → 抢下指针流」的时延样本（毫秒）—— 纯算术，见 `CornerLatencyTest`。
 *
 * 为什么要把它变成数字：这一笔是**和 launcher 抢**的（MiuiHome 的 Rust 识别器在 DOWN 之后约 8ms 就会
 * 认下 HOME），所以用户的「时灵时不灵」只能靠 p50/p90/max 加超阈值计数来说清楚 —— 诊断报告里那一笔
 * 是 `lat=15`，而正常一笔是 ~2ms。
 *
 * 拆分同样重要：`recv` = dispatcher 投递到我们线程的时延，`pil` = 我们处理 + binder `pilferPointers`。
 * 前者大说明**我们的线程被别的活挡住了**（或者系统忙），后者大说明是我们自己处理慢 ——
 * 2026-10-12 的那次就是前者：看门狗每 250ms 在**输入线程**上做两次 binder 调用。
 */
class LatencyStats(
    private val capacity: Int = 64,
    val slowMs: Int = SLOW_MS,
) {
    private val samples = IntArray(capacity)
    private var written = 0
    private var slowCount = 0
    private var reported = 0

    /** 样本数（最多 [capacity] 个，环形覆盖）。 */
    val count: Int get() = minOf(written, capacity)

    /** 见过的最大值（不随环形覆盖清零）。 */
    var max: Int = 0
        private set

    /** 超过 [slowMs] 的笔数（不随环形覆盖清零）。 */
    val slow: Int get() = slowCount

    /** 记一笔，返回这一笔是否偏慢（调用方据此立刻打一行日志）。 */
    fun record(ms: Int): Boolean {
        samples[written % capacity] = ms
        written++
        if (ms > max) max = ms
        val slowNow = ms > slowMs
        if (slowNow) slowCount++
        return slowNow
    }

    /** 到 [every] 笔就返回 true 一次 —— 用来控制汇总行的频率（每笔都打会淹掉日志）。 */
    fun dueForReport(every: Int): Boolean {
        if (written < every) return false
        if (written - reported < every) return false
        reported = written
        return true
    }

    /** 第 [p] 百分位（0..100），没有样本时 0。 */
    fun percentile(p: Int): Int {
        val n = count
        if (n == 0) return 0
        val sorted = IntArray(n) { samples[it] }
        sorted.sort()
        val index = (p.coerceIn(0, 100).toLong() * n / 100).toInt().coerceIn(0, n - 1)
        return sorted[index]
    }

    /** `n=40 p50=2 p90=6 max=15 slow(>8ms)=3` —— 诊断报告直接读这一行（正则见 `ModuleLogDiagnosis`）。 */
    fun summary(): String =
        "n=$count p50=${percentile(50)} p90=${percentile(90)} max=$max slow(>${slowMs}ms)=$slowCount"

    fun reset() {
        written = 0
        reported = 0
        slowCount = 0
        max = 0
    }

    companion object {
        /**
         * 偏慢的门槛：8ms。
         *
         * 不是拍脑袋 —— 诊断里那句「超过 8ms 就有被 launcher 抢先的风险」就是它，而设备实测正常一笔
         * 是 dispatcher 发布 DOWN 后 ~2ms 完成 pilfer。
         */
        const val SLOW_MS = 8
    }
}
