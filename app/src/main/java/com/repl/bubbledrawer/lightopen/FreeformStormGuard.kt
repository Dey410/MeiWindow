package com.repl.bubbledrawer.lightopen

/**
 * 小窗风暴熔断：同一应用在极短时间里被批量塞进小窗时，暂停给它小窗参数。
 *
 * ## 为什么需要（用户 2026-10-23）
 *
 * 「竖屏通知开启小窗」后点酷安的升级通知，目标页落进一个**全新的 freeform 任务当任务根**，
 * 酷安的 `UpgradeManagerActivity` 判定"非正常入口"→ 回闪屏重启（`MainActivity` CLEAR_TASK），
 * 路由又把它送回来，两条规则互踢永不停止；而它的路由 Intent 自带
 * `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK`，每一轮都开新任务，ROM 的
 * `MiuiFreeformServiceImpl.addLaunchFreeformActivityOptionIfNeed` 又给源是小窗的启动**链式继承**
 * freeform —— 31 秒弹了上百个小窗（取证日志 work_logs/crash_repro_2026-10-23.log）。
 * 同一条通知全屏打开完全正常（用户同日对照实验）。
 *
 * 熔断**不修**应用的死循环（那在应用进程里，修不了），只保证：
 * 「无论应用怎么抽，小窗不再连环弹」—— 触发后一段时间内该包拿不到 freeform 参数，
 * 它的连环启动退化成全屏任务互换；若死循环其实是被 freeform 拖出来的（全屏形态自检能过），
 * 连环启动本身也会随之收敛。
 *
 * ## 为什么两个进程各持一份
 *
 * hook 进程写不了 LSPosed 远程组（见 `RemotePrefs` 里气压校准那段注释），所以不做跨进程
 * 状态，而是两边各自观察、各自熔断，口径不同但语义一致（正常人手速开不出这种密度）：
 *
 *  - **system_server**（`LightOpenGate.installStormBreaker`）：拦
 *    `MiuiMultiWindowUtils.getActivityOptions` —— 每一次"要给小窗参数"都过这里，计的是
 *    **授予次数**；冷却期内直接返回 null（该方法本来就允许返回 null = "这个应用不给小窗"，
 *    ROM 各调用方都按 null 走原生路径）。
 *  - **SystemUI**（`FlymeFreeformController.rememberWindow` 报点、`LightOpenSystemUi.freeformBundle`
 *    降级）：计的是**新小窗任务数**；冷却期内模块自己的通知小窗注入也退化为普通打开，
 *    免得用户在风暴里再点一次通知又点着一把火。
 */
class FreeformStormGuard(
    /** 统计窗口（毫秒）：窗口内的事件数达到 [threshold] 才算风暴。 */
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    /** 窗口内多少个事件判为风暴。对照实验：酷安死循环约每 300ms 弹一对（2 个）小窗任务。 */
    private val threshold: Int = DEFAULT_THRESHOLD,
    /** 首次触发的冷却时长（毫秒）：够应用把一轮连环启动跑完并收敛。 */
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    /** 冷却结束后很快又起风暴时，冷却按这个倍数放大（防"风暴 ↔ 间歇"反复横跳）。 */
    private val escalationFactor: Int = DEFAULT_ESCALATION_FACTOR,
    /** 冷却上限（毫秒）。 */
    private val maxCooldownMs: Long = DEFAULT_MAX_COOLDOWN_MS,
    /** 冷却结束后这么久之内再触发，视为"没救回来"，按放大后的时长冷却。 */
    private val retriggerMs: Long = DEFAULT_RETRIGGER_MS,
    /** 单调时钟（跨进程语义一致用 `SystemClock.elapsedRealtime`，测试里注入假时钟）。 */
    private val clock: () -> Long,
) {
    private class PkgState {
        val events = ArrayDeque<Long>()
        var suppressUntil = 0L
        var lastTriggerAt = 0L
        var lastCooldown = 0L
    }

    private val states = HashMap<String, PkgState>()

    /** 这个包现在是不是冷却期（此时调用方应退化为普通启动，不要再给小窗参数）。 */
    @Synchronized
    fun isSuppressing(packageName: String): Boolean {
        val state = states[packageName] ?: return false
        return state.suppressUntil > clock()
    }

    /**
     * 记一次事件（system_server 的"授予"或 SystemUI 的"新小窗任务"）。
     *
     * @return true = 这一次触发了熔断（调用方应打一条醒目日志）。
     */
    @Synchronized
    fun record(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val now = clock()
        val state = states.getOrPut(packageName) { PkgState() }
        // 冷却期内不累计也不触发 —— 否则死循环自己就能把冷却永远续下去，
        // 用户就再也等不到正常的小窗了。冷却结束后再起风暴才升级时长。
        if (state.suppressUntil > now) return false
        while (state.events.isNotEmpty() && now - state.events.first() > windowMs) {
            state.events.removeFirst()
        }
        state.events.addLast(now)
        if (state.events.size < threshold) return false
        // 升级判据是"冷却结束后多快又起风暴"，不是"距上次触发多久"：按后者算的话，
        // 60 秒的长冷却反而会把自己降回 15 秒（触发间隔必然大于 30 秒）。
        val escalating = state.lastTriggerAt > 0L && now - state.suppressUntil <= retriggerMs
        val cooldown = if (escalating) {
            (state.lastCooldown * escalationFactor).coerceAtMost(maxCooldownMs)
        } else {
            cooldownMs
        }
        state.lastCooldown = cooldown
        state.suppressUntil = now + cooldown
        state.lastTriggerAt = now
        state.events.clear()
        return true
    }

    /** 冷却还剩多少毫秒（0 = 没在冷却；给日志/测试用）。 */
    @Synchronized
    fun remainingCooldownMs(packageName: String): Long {
        val state = states[packageName] ?: return 0L
        return (state.suppressUntil - clock()).coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
        const val DEFAULT_THRESHOLD = 5
        const val DEFAULT_COOLDOWN_MS = 15_000L
        const val DEFAULT_ESCALATION_FACTOR = 4
        const val DEFAULT_MAX_COOLDOWN_MS = 120_000L
        const val DEFAULT_RETRIGGER_MS = 30_000L

        /**
         * 进程内共用的一份。**故意不做跨进程同步**（hook 进程写不了远程组）：
         * 两个进程各看各的、各断各的，见类注释。
         */
        val processWide: FreeformStormGuard by lazy {
            FreeformStormGuard { android.os.SystemClock.elapsedRealtime() }
        }
    }
}
