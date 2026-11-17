package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.voice.VoiceDraft
import com.bookkeeping.app.voice.VoiceType
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 解析结果预填到表单后，需要给用户看的一句提醒。
 *
 * 文案留在枚举里，UI 侧用 tr([message]) 取当前语言的文本；这里不碰 Compose，
 * 纯 JVM 单测就能覆盖「什么情况该提醒」。
 */
enum class QuickEntryNotice(val message: String) {
    /** 一句话里没看出金额：金额一栏保持用户原来的输入，不猜一个数字顶上。 */
    AMOUNT_MISSING("没看出金额，请手动补上"),

    /** 填进去了，但解析器标了风险（大概金额、多个金额、日期是推测的等），要求核对。 */
    NEEDS_REVIEW("已按这句话填好，请核对金额、分类和时间"),
}

/**
 * 一句话解析结果 → 「新建账单」表单字段的映射。
 *
 * 只做预填，不做保存：
 * - 解析不出的字段一律返回 null，调用方保持用户原来的输入不动，绝不拿猜的值覆盖；
 * - 金额用「分」转字符串，不经过浮点，不会出现 36.499999；
 * - 时间只说了「哪一天」时，保留「现在」的时分秒，避免把用户没说的钟点写成 00:00。
 */
data class QuickEntryPrefill(
    val amountText: String? = null,
    val type: TransactionType? = null,
    val category: TransactionCategory? = null,
    val merchant: String? = null,
    val note: String? = null,
    val timestampMs: Long? = null,
    val notice: QuickEntryNotice? = null,
) {
    companion object {
        fun from(
            draft: VoiceDraft,
            nowMs: Long,
            zone: TimeZone = TimeZone.getDefault(),
        ): QuickEntryPrefill {
            val (type, category) = when (draft.type) {
                VoiceType.EXPENSE -> TransactionType.EXPENSE to draft.category
                VoiceType.INCOME -> TransactionType.INCOME to draft.category
                // 账本里没有独立的「转账」收支类型：按支出 + 转账分类记。
                VoiceType.TRANSFER -> TransactionType.EXPENSE to TransactionCategory.TRANSFER
                // 退款是钱回到手上，按收入记，分类沿用解析结果。
                VoiceType.REFUND -> TransactionType.INCOME to draft.category
            }
            return QuickEntryPrefill(
                amountText = draft.amountCents?.let { centsToText(it) },
                type = type,
                category = category,
                merchant = draft.merchant?.takeIf { it.isNotBlank() },
                note = draft.note?.takeIf { it.isNotBlank() },
                timestampMs = draft.occurredAtMs
                    ?.takeIf { draft.dateKnown }
                    ?.let { if (draft.preciseTime) it else keepClockOf(it, nowMs, zone) },
                notice = when {
                    draft.amountCents == null -> QuickEntryNotice.AMOUNT_MISSING
                    draft.warnings.isNotEmpty() -> QuickEntryNotice.NEEDS_REVIEW
                    else -> null
                },
            )
        }

        /** 分 → 表单里的「元」文本，固定两位小数。 */
        fun centsToText(cents: Long): String {
            val abs = if (cents < 0L) -cents else cents
            return String.format(Locale.US, "%d.%02d", abs / 100L, abs % 100L)
        }

        /** 只说了哪一天、没说钟点：日期用解析结果，时分秒用「现在」。 */
        private fun keepClockOf(dayMs: Long, nowMs: Long, zone: TimeZone): Long {
            val day = Calendar.getInstance(zone).apply { timeInMillis = dayMs }
            return Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                set(Calendar.YEAR, day.get(Calendar.YEAR))
                set(Calendar.MONTH, day.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, day.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
    }
}
