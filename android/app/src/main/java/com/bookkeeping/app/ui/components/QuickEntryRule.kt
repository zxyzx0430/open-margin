package com.bookkeeping.app.ui.components

/** 一次「按住 +」结束时的动作分类：VoiceFab 只按这个结果回调，判断本身可单测。 */
enum class HoldRelease {
    /** 没到 1 秒就松手：普通点击，打开新建账单表单。 */
    TAP,

    /** 已经开录，手指仍按住时滑出：取消本次录音并关面板。 */
    ABORT_RECORDING,

    /** 已开录后正常松手，或还没开录就滑走 / 被系统取消：什么都不做。 */
    NOTHING,
}

/**
 * 一句话记账与长按语音的时间常量、判定规则。
 *
 * 手势、进度弧、首次提示都从这里取值：Composable 里只做绘制和调度，
 * 时间与「算不算触发」这些容易出错的判断放在纯函数里，单元测试直接覆盖，
 * 不依赖 Android 运行时。
 */
object QuickEntryRule {
    /** 按住满 1 秒就开始录音（不是松手才录）；不足 1 秒松手仍是普通点击。 */
    const val HOLD_TO_RECORD_MS = 1_000L

    /** 按住 0.5 秒轻震一次：提示「再坚持一下」。 */
    const val HAPTIC_TICK_MS = 500L

    /** 首次提示气泡的停留时间。 */
    const val HINT_VISIBLE_MS = 4_000L

    /** 松手后进度弧回收的时间（毫秒），比扫出去快，避免拖尾。 */
    const val RING_RELEASE_MS = 140f

    /** 0 → 1 的进度：到 1 秒正好扫满一圈，之后保持满格。 */
    fun ringProgress(elapsedMs: Long): Float =
        (elapsedMs.coerceAtLeast(0L).toFloat() / HOLD_TO_RECORD_MS).coerceIn(0f, 1f)

    /** 是否已经按够了 1 秒（到达这一帧就开录，不等松手）。 */
    fun isLongEnough(elapsedMs: Long): Boolean = elapsedMs >= HOLD_TO_RECORD_MS

    /** 是否该给第一次轻震。 */
    fun shouldTick(elapsedMs: Long): Boolean = elapsedMs >= HAPTIC_TICK_MS

    /** 空白或只有空格的输入不算一次提交：不回调、也不报错。 */
    fun canSubmit(text: String): Boolean = text.isNotBlank()

    /**
     * 这一段按压结束时该做什么。
     *
     * @param armed 满 1 秒时已经回调过「开始录音」。
     * @param cancelled 手指仍按住时滑出阈值。
     */
    fun releaseAction(armed: Boolean, cancelled: Boolean): HoldRelease = when {
        // 开录之后仍按着时滑走：取消录音 + 关面板，绝不能当成点击去开表单。
        armed && cancelled -> HoldRelease.ABORT_RECORDING
        // 开录之后正常抬手：录音继续，只有面板「停止」按钮能结束。
        armed -> HoldRelease.NOTHING
        // 没开录就滑走：既不算点击也不算录音。
        cancelled -> HoldRelease.NOTHING
        // 不到 1 秒的普通点击。
        else -> HoldRelease.TAP
    }
}
