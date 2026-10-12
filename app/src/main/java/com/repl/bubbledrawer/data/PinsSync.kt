package com.repl.bubbledrawer.data

/**
 * Reconciliation of the two pin stores (see [PinBackend.readRev]).
 *
 * Pure function on purpose: this is the whole "which 收藏 list is the real one" decision, and
 * it is the part that has to be testable without a device (see PinsSyncTest).
 *
 * Ordering rule: the newer stamp wins. Equal stamps — including the legacy case where BOTH
 * sides predate stamping (rev 0) — fall back to the shared bus (the LSPosed remote group the
 * two processes read), because that is the copy a third process would have observed. The
 * caller is expected to write the winner back into the losing store ([Decision.pushBack])
 * so the two converge instead of merely agreeing once.
 */
object PinsSync {

    enum class Source { LOCAL, REMOTE, NONE }

    /**
     * @param value the winning encoded pin string ("" = no pins)
     * @param rev stamp to store alongside it
     * @param source which store it came from (for logs)
     * @param pushBack true when the OTHER store must be overwritten with this value
     */
    data class Decision(
        val value: String,
        val rev: Long,
        val source: Source,
        val pushBack: Boolean,
    )

    /**
     * 逐 key 回调（远程共享组）里，要不要采纳远端那一份。
     *
     * 规则只有一条：**远端的戳不小于本地就采纳；否则什么都不做**。
     * 尤其**不许因为「值不一样」就把自己这一份推回去** —— 那正是「自动反复添加/移除」的引擎。
     *
     * 为什么必须容忍"撕裂的一对"：收藏的值与它的时间戳是**两个 key**，而 LSPosed 的远程组是
     * **逐 key 镜像**的（同一批设置写入会一个 key 一次回调 —— 设备实测过 `PREFS_APPLIED` 同一毫秒
     * 十几行）。于是对面可能先看到新值、旧戳：
     *
     * ```
     * 事件 1：值 = 新（用户刚加了一项），戳 = 旧        ← 值先到，戳还没镜像过来
     * 事件 2：值 = 新，戳 = 新
     * ```
     *
     * 旧代码在事件 1 里判定"远端不比本地新 + 值不同"→ 把自己的**旧值**推回去，把用户刚刚那笔编辑顶掉；
     * 对面收到回推又写一遍 → 再触发我这边的回调 → 两份副本在旧值/新值之间来回翻 = 收藏项自动
     * 反复添加/移除（2026-10-12 用户报告，模块主页的收藏管理）。
     * 按"不小于就采纳"之后：事件 1 直接把新值收下（戳还没到而已），事件 2 是同值的重复采纳，环当场断掉。
     *
     * `remoteRev == localRev` 而值不同也采纳，与 [decide] 的平票规则一致：共享组是"第三个进程会看到的
     * 那一份"，平戳时以它为准。真正需要恢复的场景（对面很久没收到过我的手写）走的是**读路径**
     * （[decide] 的 `pushBack`），那里两个 key 都已落定。
     */
    fun shouldAdoptOnKeyEvent(remoteRev: Long, localRev: Long): Boolean = remoteRev >= localRev

    /**
     * 收到对面**主动推过来**的一份（广播/服务）时能不能落盘。
     *
     * 旧数据（rev 缺失/0）只能来自更老的模块版本，照单收下；其余要求不低于本地已有的戳。
     * **低于本地时只丢不下、绝不回推**：这条通道上每一次回推都可能再触发对方的回调，
     * 而"我的更新"这件事由对面的读路径自己发现（[decide] 的 `pushBack`）。
     */
    fun shouldApplyPush(rev: Long, localRev: Long): Boolean = rev <= 0L || rev >= localRev

    fun decide(
        localValue: String,
        localRev: Long,
        remoteValue: String,
        remoteRev: Long,
    ): Decision {
        val local = localValue.trim()
        val remote = remoteValue.trim()

        // Stamped data decides purely by freshness — an EMPTY value from a stamped store is a
        // real edit ("unpin everything"), not a cold store. Skipping this is how a "clear all"
        // would silently come back to life from the other side's older list.
        if (localRev > 0L || remoteRev > 0L) {
            if (remoteRev > localRev) return Decision(remote, remoteRev, Source.REMOTE, true)
            if (localRev > remoteRev) return Decision(local, localRev, Source.LOCAL, true)
            return if (local == remote) {
                Decision(local, localRev, Source.LOCAL, false)
            } else {
                // Equal stamps (e.g. two writes inside the same millisecond): the shared bus
                // is the copy a third process would have observed, so it is the tie-breaker.
                Decision(remote, remoteRev, Source.REMOTE, true)
            }
        }

        // Legacy, unstamped data: here "" can only mean "never filled".
        return when {
            local.isEmpty() && remote.isEmpty() -> Decision("", 0L, Source.NONE, false)
            remote.isEmpty() -> Decision(local, localRev, Source.LOCAL, true)
            local.isEmpty() -> Decision(remote, remoteRev, Source.REMOTE, true)
            local == remote -> Decision(local, localRev, Source.LOCAL, false)
            else -> Decision(remote, remoteRev, Source.REMOTE, true)
        }
    }
}
