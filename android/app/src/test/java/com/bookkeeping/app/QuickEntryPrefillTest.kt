package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.transactions.QuickEntryNotice
import com.bookkeeping.app.ui.screen.transactions.QuickEntryPrefill
import com.bookkeeping.app.ui.screen.transactions.TimePrecision
import com.bookkeeping.app.voice.VoiceDraft
import com.bookkeeping.app.voice.VoiceTransactionParser
import com.bookkeeping.app.voice.VoiceType
import com.bookkeeping.app.voice.VoiceWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        account: String? = null,
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
        account = account,
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

    // ================================================================ 商户记忆（分类优先级）

    /** 假的商户记忆：瑞幸→购物、麦当劳→餐饮；其余返回 null（记忆里没有）。 */
    private val memory: (String, TransactionCategory?) -> TransactionCategory? = { merchant, _ ->
        when (merchant) {
            "瑞幸" -> TransactionCategory.SHOPPING
            "麦当劳" -> TransactionCategory.FOOD
            else -> null
        }
    }

    @Test
    fun merchantMemoryOverridesParsedCategoryWhenItHasAnEntry() {
        // 解析器猜餐饮（「咖啡」关键词），但用户之前把瑞幸改成了购物：记忆优先。
        val prefill = QuickEntryPrefill.from(
            draft(merchant = "瑞幸", category = TransactionCategory.FOOD),
            now,
            zone,
            categoryResolver = memory,
        )
        assertEquals(TransactionCategory.SHOPPING, prefill.category)
    }

    @Test
    fun merchantMemoryCanFillACategoryTheParserMissed() {
        val prefill = QuickEntryPrefill.from(
            draft(merchant = "麦当劳", category = TransactionCategory.OTHER),
            now,
            zone,
            categoryResolver = memory,
        )
        assertEquals(TransactionCategory.FOOD, prefill.category)
    }

    @Test
    fun resolverReturningNullKeepsTheParsedCategory() {
        val prefill = QuickEntryPrefill.from(
            draft(merchant = "楼下小卖部", category = TransactionCategory.SHOPPING),
            now,
            zone,
            categoryResolver = memory,
        )
        assertEquals(TransactionCategory.SHOPPING, prefill.category)
    }

    @Test
    fun blankMerchantNeverTouchesMerchantMemory() {
        var called = false
        val resolver: (String, TransactionCategory?) -> TransactionCategory? = { _, _ ->
            called = true
            TransactionCategory.SHOPPING
        }
        val prefill = QuickEntryPrefill.from(
            draft(merchant = "   ", category = TransactionCategory.FOOD),
            now,
            zone,
            categoryResolver = resolver,
        )
        assertFalse("商户为空时不该查商户记忆", called)
        assertEquals(TransactionCategory.FOOD, prefill.category)
    }

    @Test
    fun transferIsDecidedByDirectionAndIgnoresMerchantMemory() {
        val prefill = QuickEntryPrefill.from(
            draft(type = VoiceType.TRANSFER, merchant = "瑞幸", category = TransactionCategory.OTHER),
            now,
            zone,
            categoryResolver = memory,
        )
        assertEquals(TransactionType.EXPENSE, prefill.type)
        assertEquals(TransactionCategory.TRANSFER, prefill.category)
    }

    @Test
    fun refundKeepsItsParsedCategoryAndIgnoresMerchantMemory() {
        // 退款由钱的方向决定：不能被「这家店上次记成了购物」改写。
        val prefill = QuickEntryPrefill.from(
            draft(type = VoiceType.REFUND, merchant = "瑞幸", category = TransactionCategory.OTHER),
            now,
            zone,
            categoryResolver = memory,
        )
        assertEquals(TransactionType.INCOME, prefill.type)
        assertEquals(TransactionCategory.OTHER, prefill.category)
    }

    @Test
    fun resolverCanUseParsedCategoryAsItsOwnFallback() {
        // 解析参数允许 resolver 自己决定怎么用解析结果；这里确认签名真的把它传进去了。
        var seen: TransactionCategory? = null
        val resolver: (String, TransactionCategory?) -> TransactionCategory? = { _, parsed ->
            seen = parsed
            null
        }
        QuickEntryPrefill.from(
            draft(merchant = "瑞幸", category = TransactionCategory.FOOD),
            now,
            zone,
            categoryResolver = resolver,
        )
        assertEquals(TransactionCategory.FOOD, seen)
    }

    // ================================================================ 时间精度 → 重复检查窗口

    @Test
    fun spokeAClockMeansPrecise() {
        val prefill = QuickEntryPrefill.from(
            draft(occurredAtMs = at(2026, 11, 16, 21, 30), dateKnown = true, preciseTime = true),
            now,
            zone,
        )
        assertEquals(TimePrecision.PRECISE, prefill.timePrecision)
    }

    @Test
    fun onlyADayMeansDateOnly() {
        val prefill = QuickEntryPrefill.from(
            draft(occurredAtMs = at(2026, 11, 16), dateKnown = true, preciseTime = false),
            now,
            zone,
        )
        assertEquals(TimePrecision.DATE_ONLY, prefill.timePrecision)
    }

    @Test
    fun noDateAtAllMeansNoPrecisionClaim() {
        val prefill = QuickEntryPrefill.from(
            draft(occurredAtMs = now, dateKnown = false),
            now,
            zone,
        )
        assertEquals(TimePrecision.NONE, prefill.timePrecision)
    }

    // ================================================================ 账户

    @Test
    fun spokenPaymentMethodFillsAccountNotSource() {
        // 语音只是录入方式：说「微信支付」填的是账户，来源仍然是 MANUAL（由 ViewModel 写）。
        val raw = "11月16日中午12点在麦当劳花了36块5，餐饮，微信支付，备注加班餐"
        val parsed = VoiceTransactionParser.parse(raw, now, zone)
        val prefill = QuickEntryPrefill.from(parsed, now, zone)

        assertEquals("微信", prefill.account)
    }

    @Test
    fun accountIsPassedThroughWhenParsed() {
        val prefill = QuickEntryPrefill.from(draft(account = "支付宝"), now, zone)
        assertEquals("支付宝", prefill.account)
    }

    @Test
    fun accountNotSpokenStaysNullSoTheFormKeepsItsOwnValue() {
        val prefill = QuickEntryPrefill.from(draft(account = null), now, zone)
        assertNull(prefill.account)
    }

    @Test
    fun blankAccountIsTreatedAsNotParsed() {
        val prefill = QuickEntryPrefill.from(draft(account = "  "), now, zone)
        assertNull(prefill.account)
    }

    @Test
    fun wechatVariantsFoldIntoTheWechatPresetButSpecificAccountsStay() {
        // 表单预设只有「微信」，微信支付/零钱并过去；花呗、招商银行是具体账户，原文保留。
        assertEquals("微信", QuickEntryPrefill.accountName("微信支付"))
        assertEquals("微信", QuickEntryPrefill.accountName(" 微信零钱 "))
        assertEquals("支付宝", QuickEntryPrefill.accountName("支付宝"))
        assertEquals("花呗", QuickEntryPrefill.accountName("花呗"))
        assertEquals("招商银行", QuickEntryPrefill.accountName("招商银行"))
        assertEquals("现金", QuickEntryPrefill.accountName("现金"))
    }
}
