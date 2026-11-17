package com.bookkeeping.app.ui.components

/**
 * 一句话记账与长按语音的时间常量、判定规则。
 *
 * 手势、进度弧、首次提示都从这里取值：Composable 里只做绘制和调度，
 * 时间与「算不算触发」这些容易出错的判断放在纯函数里，单元测试直接覆盖，
 * 不依赖 Android 运行时。
 */
object QuickEntryRule {
    /** 按住满 1 秒才算语音记账；不足 1 秒松手仍是普通点击。 */
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

    /** 是否已经按够了 1 秒。 */
    fun isLongEnough(elapsedMs: Long): Boolean = elapsedMs >= HOLD_TO_RECORD_MS

    /** 是否该给第一次轻震。 */
    fun shouldTick(elapsedMs: Long): Boolean = elapsedMs >= HAPTIC_TICK_MS

    /** 空白或只有空格的输入不算一次提交：不回调、也不报错。 */
    fun canSubmit(text: String): Boolean = text.isNotBlank()
}
