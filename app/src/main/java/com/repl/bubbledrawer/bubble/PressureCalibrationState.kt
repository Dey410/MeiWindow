package com.repl.bubbledrawer.bubble

/**
 * 气压校准的**状态机**（纯逻辑）：把 SystemUI 侧捕获到的事件（开始 / 每次按压 / 结果 / 超时 /
 * 无传感器）折算成一份要写进 prefs 的状态。
 *
 * 为什么单独抽出来：校准的采集在 SystemUI（`FanHost` + 气压计），而状态的**持久化**在 App 进程
 * （只有它能写 LSPosed 远程组，见 `PinSyncReceiver` 的注释），两边靠广播交接。事件经过进程边界
 * 以后最容易出的错就是「谁覆盖谁」—— 比如一次重试的 STARTED 把刚算出的阈值抹掉、或者一次迟到的
 * SAMPLE 把已经结束的会话又点亮。把规则收进这个纯函数里，就能用单测把每种到达顺序钉死，而不是
 * 靠在真机上按 5 次去撞。
 *
 * prefs 里存的字段就是这里的字段（一键一值），`SettingsStore` 负责读写。
 */
data class PressureCalibrationState(
    /** 会话进行中（App 写 true 请求开始，SystemUI 结束后由结果事件写回 false）。 */
    val active: Boolean = false,
    /** 是否有一份可用的校准阈值（与 [outcome] 无关：失败的一次校准不会丢掉上一次的好结果）。 */
    val calibrated: Boolean = false,
    /** 校准出的阈值（hPa）；[calibrated] 为 false 时无意义。 */
    val calibratedHpa: Float = 0f,
    /** 本次会话已完成的按压次数（含无效样本，最多 [PressureCalibration.REQUIRED_ATTEMPTS]）。 */
    val attempts: Int = 0,
    /** 本次会话已收集到的有效样本数。 */
    val validCount: Int = 0,
    /** 最近一次按压的峰值（hPa），用于页面上「第 n/5 次有效，气压升高 x.xxx hPa」。 */
    val lastDeltaHpa: Float = 0f,
    /** 最近一次按压是否被记为有效样本。 */
    val lastValid: Boolean = false,
    /** 上一次会话的结果，取值见 [PressureCalibration.Outcome]。 */
    val outcome: Int = PressureCalibration.Outcome.NONE,
) {
    /** 会话的四个阶段 + 两个终止态，跨进程广播的 `stage` 取值。 */
    object Stage {
        /** SystemUI 已受理，会话开始（App 收到后把计数清零）。 */
        const val STARTED = "started"

        /** 记到了一次按压。 */
        const val SAMPLE = "sample"

        /** 按满了 [PressureCalibration.REQUIRED_ATTEMPTS] 次，带着计算结果结束。 */
        const val RESULT = "result"

        /** 超时结束（[PressureCalibration.SESSION_TIMEOUT_MS] 内没按满）。 */
        const val TIMEOUT = "timeout"

        /** 本机没有气压计，会话无法开始。 */
        const val UNSUPPORTED = "unsupported"
    }

    companion object {
        /** 清除校准结果后的状态：回到「用灵敏度预设」，且不留下任何上次的残留。 */
        fun cleared(): PressureCalibrationState = PressureCalibrationState()

        /**
         * 应用一个来自 SystemUI 的事件。
         *
         * 关键规则（每一条都有对应的单测）：
         *  - `STARTED` 只清计数，**不动** [calibrated]/[calibratedHpa]：重新校准的整个过程里，旧阈值
         *    必须一直可用，否则用户在「开始校准 → 按满 5 次」之间会突然失去重按功能；
         *  - `RESULT` 只在 [valid] 时写入新阈值，失败（样本不足）保留旧值 —— 参考实现失败时会把手势
         *    连阈值一起打回默认值，这里选择不破坏已有的好结果；
         *  - `TIMEOUT`/`UNSUPPORTED` 只结束会话并记录原因，同样不碰已有阈值；
         *  - `SAMPLE` 只在会话仍然 [active] 时生效：会话结束之后迟到的按压事件丢掉，不能让页面从
         *    「已校准」又跳回「校准中」；
         *  - 任何事件都把 [active] 定成它该有的值（迟到的事件因此不会让会话卡在「进行中」）；
         *  - 未知 stage 原样返回（跨版本混装时宁可不动，也不要瞎写）。
         */
        fun applyEvent(
            state: PressureCalibrationState,
            stage: String,
            attempts: Int = 0,
            delta: Float = 0f,
            valid: Boolean = false,
            threshold: Float = 0f,
            validCount: Int = 0,
        ): PressureCalibrationState = when (stage) {
            Stage.STARTED -> state.copy(
                active = true,
                attempts = 0,
                validCount = 0,
                lastDeltaHpa = 0f,
                lastValid = false,
                outcome = PressureCalibration.Outcome.NONE,
            )

            // 会话已经结束（RESULT/TIMEOUT 之后迟到的一次按压）就丢掉：否则页面会从「已校准」
            // 又跳回「校准中 3/5」，看起来像卡死 —— 和收藏那次「迟到的推送把新编辑盖掉」是同一类
            // 错误，处理方式也一样：认会话状态，不认事件本身。
            Stage.SAMPLE -> if (!state.active) state else state.copy(
                attempts = attempts.coerceIn(0, PressureCalibration.REQUIRED_ATTEMPTS),
                validCount = validCount.coerceAtLeast(0),
                lastDeltaHpa = if (delta.isFinite() && delta > 0f) delta else 0f,
                lastValid = valid,
                outcome = PressureCalibration.Outcome.NONE,
            )

            Stage.RESULT -> if (valid && threshold.isFinite() && threshold > 0f) {
                state.copy(
                    active = false,
                    calibrated = true,
                    calibratedHpa = threshold,
                    validCount = validCount.coerceAtLeast(0),
                    outcome = PressureCalibration.Outcome.OK,
                )
            } else {
                state.copy(active = false, outcome = PressureCalibration.Outcome.FEW_SAMPLES)
            }

            Stage.TIMEOUT -> state.copy(active = false, outcome = PressureCalibration.Outcome.TIMEOUT)

            Stage.UNSUPPORTED -> state.copy(active = false, outcome = PressureCalibration.Outcome.UNSUPPORTED)

            else -> state
        }
    }
}
