package com.bookkeeping.app.notification

// 通知正文/时间抽取（蓝图 D04）。纯 Kotlin，无任何 Android 依赖，可直接 JVM 单测。
// Pure Kotlin, zero Android imports: fully unit-testable on the JVM.
object NotificationTextExtractor {

    // 24h 窗口：MessagingStyle 会话重发、通知刷新都会把 postTime 往后推，
    // 但一条支付通知不会在 24h 之后才被重新发布。
    private const val ORIGINAL_TIME_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * 合并通知正文，按优先级：bigText -> textLines -> messages -> text。
     * 优先返回"含数字"的候选，因为只有含金额的通知才能被记账。
     *
     * 支付证据往往只存在于展开态/分组形态：短文本常常只是 "微信支付"，
     * 金额在 BIG_TEXT / TEXT_LINES / MESSAGES 里。SmartLedger 等同类记账
     * 应用同样读 BIG_TEXT / TEXT_LINES / MESSAGES 补全内容。
     * Payment evidence may only exist in the expanded/grouped forms; the short
     * text alone is often just a title.
     */
    fun bestBody(
        text: String?,
        bigText: String?,
        textLines: List<String>,
        messages: List<String>,
    ): String? {
        val candidates = buildList<String> {
            bigText?.let { add(it) }
            joinDistinct(textLines)?.let { add(it) }
            joinDistinct(messages)?.let { add(it) }
            text?.let { add(it) }
        }.filterNot { it.isBlank() }

        // 首个含数字的候选；都不含数字时退回首个非空候选（保持原有兜底行为）。
        // First digit-bearing candidate, else the first non-blank one.
        return candidates.firstOrNull { candidate -> candidate.any { it.isDigit() } }
            ?: candidates.firstOrNull()
    }

    // 空行丢弃（分组文本常带占位空行），重复行去重且保持原有顺序。
    // Drops blank lines and de-duplicates while preserving order.
    private fun joinDistinct(lines: List<String>): String? {
        val kept = LinkedHashSet<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            kept.add(line)
        }
        return if (kept.isEmpty()) null else kept.joinToString(" ")
    }

    /**
     * 还原真实支付时间：postTime 会被 MessagingStyle 重发/通知刷新推后，
     * 真正的时间是 24h 内最早的候选（notification.when_ 或各条 message.time）。
     * Reposts move postTime forward; the real payment time is the earliest
     * plausible candidate (when_ plus MessagingStyle message times) inside 24h.
     */
    fun resolveOriginalTimeMs(
        postTimeMs: Long,
        notificationWhenMs: Long,
        messageTimesMs: List<Long>,
    ): Long {
        // when_ 缺省为 0，表示未设置，不作为候选。
        // when_ defaults to 0, which means "unset".
        val candidates = buildList<Long> {
            if (notificationWhenMs > 0L) add(notificationWhenMs)
            addAll(messageTimesMs)
        }
        val earliestAllowed = postTimeMs - ORIGINAL_TIME_WINDOW_MS
        // 只接受不晚于 postTime 且不早于 24h 的候选，取最早的一个。
        return candidates
            .filter { it in earliestAllowed..postTimeMs }
            .minOrNull()
            ?: postTimeMs
    }
}
