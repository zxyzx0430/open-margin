package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.transactions.QuickEntryNotice
import com.bookkeeping.app.ui.screen.transactions.QuickEntryPrefill
import com.bookkeeping.app.voice.VoiceDraft
import com.bookkeeping.app.voice.VoiceTransactionParser
import com.bookkeeping.app.voice.VoiceType
import com.bookkeeping.app.voice.VoiceWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 一句话 → 新建账单表单的预填映射。
 *
 * 时间全部用固定 nowMs（2026-11-17 10:00 +08，周二）和显式时区，
 * 在任何机器、任何时区跑结果都一样。
 */
class QuickEntryPrefillTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-11-17 是周二。 */
    private val now: Long = at(2026, 11, 17, 10, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun draft(
        amountCents: Long? = 3650L,
        type: VoiceType = VoiceType.EXPENSE,
        merchant: String? = "麦当劳",
        category: TransactionCategory = TransactionCategory.FOOD,
        occurredAtMs: Long? = null,
        dateKnown: Boolean = false,
        preciseTime: Boolean = false,
        note: String? = null,
        warnings: Set<VoiceWarning> = emptySet(),
    ): VoiceDraft = VoiceDraft(
        amountCents = amountCents,
        type = type,
        merchant = merchant,
        category = category,
        occurredAtMs = occurredAtMs,
        dateKnown = dateKnown,
        preciseTime = preciseTime,
        timeOfDayLabel = null,
        account = null,
        note = note,
        warnings = warnings,
        raw = "测试",
    )

    // ================================================================ 端到端：占位示例那句话

    @Test
    fun exampleSentenceFillsAmountTypeCategoryMerchantAndTime() {
        val raw = "11月16日中午12点在麦当劳花了36块5，餐饮，微信支付，备注加班餐"
        val parsed = VoiceTransactionParser.parse(raw, now, zone)
        val prefill = QuickEntryPrefill.from(parsed, now, zone)

        assertEquals("36.50", prefill.amountText)
        assertEquals(TransactionType.EXPENSE, prefill.type)
        assertEquals(TransactionCategory.FOOD, prefill.category)
        assertEquals("麦当劳", prefill.merchant)
        assertEquals(at(2026, 11, 16, 12, 0), prefill.timestampMs)
        // 金额和商户都对上了，不该再拿「没看出金额」去打断用户。
        assertNotEquals(QuickEntryNotice.AMOUNT_MISSING, prefill.notice)
    }

    // ================================================================ 金额

    @Test
    fun centsBecomeTwoDecimalYuanText() {
        assertEquals("36.50", QuickEntryPrefill.centsToText(3650L))
        assertEquals("0.05", QuickEntryPrefill.centsToText(5L))
        assertEquals("1234.56", QuickEntryPrefill.centsToText(123456L))
        assertEquals("18.00", QuickEntryPrefill.centsToText(1800L))
    }

    @Test
    fun missingAmountLeavesTheFieldAloneAndAsksForIt() {
        val prefill = QuickEntryPrefill.from(
            draft(amountCents = null, warnings = setOf(VoiceWarning.AMOUNT_MISSING)),
            now,
            zone,
        )
        // null = 调用方保持用户原来的金额，不猜。
        assertNull(prefill.amountText)
        assertEquals(QuickEntryNotice.AMOUNT_MISSING, prefill.notice)
    }

    @Test
    fun otherWarningsStillFillButAskForReview() {
        val prefill = QuickEntryPrefill.from(
            draft(warnings = setOf(VoiceWarning.DATE_APPROXIMATE, VoiceWarning.MULTI_AMOUNT)),
            now,
            zone,
        )
        assertEquals("36.50", prefill.amountText)
        assertEquals(QuickEntryNotice.NEEDS_REVIEW, prefill.notice)
    }

    @Test
    fun cleanParseNeedsNoNotice() {
        assertNull(QuickEntryPrefill.from(draft(), now, zone).notice)
    }

    // ================================================================ 时间

    @Test
    fun dateNotSpokenMeansKeepTheCurrentTime() {
        // dateKnown=false：解析器给的 occurredAtMs 只是「今天」，不要动用户表单里的时间。
        val prefill = QuickEntryPrefill.from(
            draft(occurredAtMs = now, dateKnown = false),
            now,
            zone,
        )
        assertNull(prefill.timestampMs)
    }

    @Test
    fun dateWithoutClockKeepsClockOfNow() {
        // 只说了 11 月 16 日：日期换成 16 号，时分秒保留 10:00，不写成 00:00。
        val prefill = QuickEntryPrefill.from(
            draft(occurredAtMs = at(2026, 11, 16), dateKnown = true, preciseTime = false),
            now,
            zone,
        )
        assertEquals(at(2026, 11, 16, 10, 0), prefill.timestampMs)
    }

    @Test
    fun preciseClockIsUsedAsIs() {
        val prefill = QuickEntryPrefill.from(
            draft(occurredAtMs = at(2026, 11, 15, 21, 30), dateKnown = true, preciseTime = true),
            now,
            zone,
        )
        assertEquals(at(2026, 11, 15, 21, 30), prefill.timestampMs)
    }

    // ================================================================ 收支方向

    @Test
    fun transferBecomesExpenseWithTransferCategory() {
        val prefill = QuickEntryPrefill.from(
            draft(type = VoiceType.TRANSFER, category = TransactionCategory.OTHER),
            now,
            zone,
        )
        assertEquals(TransactionType.EXPENSE, prefill.type)
        assertEquals(TransactionCategory.TRANSFER, prefill.category)
    }

    @Test
    fun refundBecomesIncome() {
        val prefill = QuickEntryPrefill.from(
            draft(type = VoiceType.REFUND, merchant = null, category = TransactionCategory.OTHER),
            now,
            zone,
        )
        assertEquals(TransactionType.INCOME, prefill.type)
        assertEquals(TransactionCategory.OTHER, prefill.category)
    }

    @Test
    fun incomeKeepsParsedCategoryAndBlankTextIsDropped() {
        val prefill = QuickEntryPrefill.from(
            draft(
                type = VoiceType.INCOME,
                merchant = "  ",
                category = TransactionCategory.SALARY,
                note = "补发",
            ),
            now,
            zone,
        )
        assertEquals(TransactionType.INCOME, prefill.type)
        assertEquals(TransactionCategory.SALARY, prefill.category)
        // 空白商户当没解析出来，不要用空格覆盖输入框。
        assertNull(prefill.merchant)
        assertEquals("补发", prefill.note)
    }
}
