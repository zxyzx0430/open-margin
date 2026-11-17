package com.bookkeeping.app.voice

import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 英文口述账单解析器的回归测试。
 *
 * 时间基准固定为 2026-11-17 10:00 +08（周二），和中文测试同一套约定。
 * 断言只覆盖"契约"：金额（整数分）、方向、商户（保留大小写）、账户、日期语义、
 * 分类和 warning；不锁死内部实现。
 */
class VoiceEnglishParserTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val now: Long = at(2026, 11, 17, 10, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun parse(text: String): VoiceDraft =
        VoiceTransactionParser.parse(text, now, zone)

    private fun assertWarning(expected: VoiceWarning, draft: VoiceDraft) {
        assertTrue("expected $expected in ${draft.warnings}", draft.warnings.contains(expected))
    }

    // ================================================================ 语言分流

    @Test
    fun dispatchEnglishWhenLatinLetters() {
        assertTrue(VoiceEnglishParser.looksEnglish("spent 36 at Starbucks"))
    }

    @Test
    fun dispatchChineseWhenHanPresent() {
        assertFalse(VoiceEnglishParser.looksEnglish("花了 36"))
    }

    @Test
    fun dispatchChineseForMixedText() {
        assertFalse(VoiceEnglishParser.looksEnglish("starbucks 花了 36"))
    }

    @Test
    fun dispatchChineseForPlainNumber() {
        assertFalse(VoiceEnglishParser.looksEnglish("36.5"))
    }

    @Test
    fun dispatchChineseForBlank() {
        assertFalse(VoiceEnglishParser.looksEnglish("   "))
    }

    @Test
    fun dispatchEnglishForDollarSignOnly() {
        assertTrue(VoiceEnglishParser.looksEnglish("$36.50"))
    }

    // ================================================================ 金额：数字写法

    @Test
    fun decimalOnePlace() {
        assertEquals(3650L, parse("spent 36.5 at Starbucks").amountCents)
    }

    @Test
    fun decimalTwoPlaces() {
        assertEquals(3650L, parse("paid 36.50 at Amazon").amountCents)
    }

    @Test
    fun dollarPrefix() {
        assertEquals(3650L, parse("$36.50 at Starbucks").amountCents)
    }

    @Test
    fun dollarsWord() {
        assertEquals(3600L, parse("36 dollars at Walmart").amountCents)
    }

    @Test
    fun bucksWord() {
        assertEquals(3600L, parse("36 bucks at McDonald's").amountCents)
    }

    @Test
    fun yuanWord() {
        assertEquals(3600L, parse("36 yuan at Taobao").amountCents)
    }

    @Test
    fun rmbWord() {
        assertEquals(3600L, parse("36 rmb at Taobao").amountCents)
    }

    @Test
    fun thousandsSeparator() {
        assertEquals(123456L, parse("bought a laptop 1,234.56 at Costco").amountCents)
    }

    @Test
    fun dollarsAndCentsCombined() {
        assertEquals(1250L, parse("twelve dollars fifty cents at Subway").amountCents)
    }

    @Test
    fun dollarsAndCentsWithAnd() {
        assertEquals(1250L, parse("twelve dollars and fifty cents at Subway").amountCents)
    }

    // ================================================================ 金额：拼写数字

    @Test
    fun spelledTens() {
        assertEquals(2500L, parse("twenty five dollars at Uber").amountCents)
    }

    @Test
    fun spelledTensUnitAsCents() {
        assertEquals(3650L, parse("thirty six fifty at Starbucks").amountCents)
    }

    @Test
    fun spelledHundredWithAnd() {
        assertEquals(10500L, parse("one hundred and five dollars at Amazon").amountCents)
    }

    @Test
    fun spelledHundredWithCentsTail() {
        assertEquals(10580L, parse("one hundred and five eighty at Amazon").amountCents)
    }

    @Test
    fun spelledSingleDigitNeedsCurrency() {
        // "five" 不带货币词更像数量，不猜成 5 元
        assertEquals(null, parse("spent at five guys").amountCents)
    }

    @Test
    fun spelledHundredThousand() {
        assertEquals(150000L, parse("one thousand five hundred yuan at Taobao").amountCents)
    }

    @Test
    fun centsUnitIsNotMultiplied() {
        assertEquals(99L, parse("spent 99 cents at Amazon").amountCents)
    }

    @Test
    fun spelledCentsUnitIsNotMultiplied() {
        assertEquals(50L, parse("spent fifty cents at Amazon").amountCents)
    }

    // ================================================================ 方向

    @Test
    fun expenseSpent() {
        assertEquals(VoiceType.EXPENSE, parse("spent 36 at Starbucks").type)
    }

    @Test
    fun expensePaid() {
        assertEquals(VoiceType.EXPENSE, parse("paid 36 at Starbucks").type)
    }

    @Test
    fun expenseBought() {
        assertEquals(VoiceType.EXPENSE, parse("bought coffee 36 at Starbucks").type)
    }

    @Test
    fun expenseCost() {
        assertEquals(VoiceType.EXPENSE, parse("the coffee cost 5 dollars").type)
    }

    @Test
    fun expenseCharged() {
        assertEquals(VoiceType.EXPENSE, parse("charged 36 dollars at Amazon").type)
    }

    @Test
    fun incomeReceived() {
        assertEquals(VoiceType.INCOME, parse("received 500 from Taobao").type)
    }

    @Test
    fun incomeGotPaid() {
        assertEquals(VoiceType.INCOME, parse("got paid 5000 today").type)
    }

    @Test
    fun incomeSalary() {
        assertEquals(VoiceType.INCOME, parse("salary 5000 today").type)
    }

    @Test
    fun refundType() {
        val draft = parse("refunded 36 from Amazon")
        assertEquals(VoiceType.REFUND, draft.type)
        assertWarning(VoiceWarning.REFUND_NEEDS_CONFIRM, draft)
    }

    @Test
    fun refundWordWithoutEd() {
        assertEquals(VoiceType.REFUND, parse("refund 36 from Amazon").type)
    }

    @Test
    fun transferType() {
        val draft = parse("transferred 100 to Alice")
        assertEquals(VoiceType.TRANSFER, draft.type)
        assertWarning(VoiceWarning.TRANSFER_NEEDS_CONFIRM, draft)
    }

    @Test
    fun sentIsTransfer() {
        assertEquals(VoiceType.TRANSFER, parse("sent 100 to Alice").type)
    }

    @Test
    fun noVerbIsUncertainExpense() {
        val draft = parse("36 at Starbucks")
        assertEquals(VoiceType.EXPENSE, draft.type)
        assertWarning(VoiceWarning.TYPE_UNCERTAIN, draft)
    }

    @Test
    fun paidBeatsPaidIncomePhrase() {
        // "got paid" 必须优先于裸 paid，否则工资会被记成支出
        assertEquals(VoiceType.INCOME, parse("got paid 3000 at Acme").type)
    }

    // ================================================================ 商户

    @Test
    fun merchantAt() {
        assertEquals("McDonald's", parse("spent 36 at McDonald's").merchant)
    }

    @Test
    fun merchantFrom() {
        assertEquals("Taobao", parse("bought shoes 200 from Taobao").merchant)
    }

    @Test
    fun merchantOn() {
        assertEquals("Amazon", parse("spent 36 on Amazon").merchant)
    }

    @Test
    fun merchantAtWithArticle() {
        assertEquals("Apple Store", parse("spent 999 at the Apple Store").merchant)
    }

    @Test
    fun merchantKeepsLowerCase() {
        assertEquals("starbucks", parse("spent 36 at starbucks").merchant)
    }

    @Test
    fun merchantTrailingPunctuation() {
        assertEquals("Starbucks", parse("spent 36 at Starbucks.").merchant)
    }

    @Test
    fun merchantCommaBeforeFor() {
        assertEquals("Starbucks", parse("spent 36 at Starbucks, for lunch").merchant)
    }

    @Test
    fun merchantWithDigits() {
        assertEquals("7-11", parse("paid 36 at 7-11").merchant)
    }

    @Test
    fun merchantBrandWithoutPreposition() {
        assertEquals("Starbucks", parse("Starbucks 36").merchant)
    }

    @Test
    fun merchantOnIsNotUsedForLunch() {
        // "on lunch" 是花在什么事上，不是商户
        assertNull(parse("spent 20 on lunch").merchant)
    }

    @Test
    fun merchantUnknownWarns() {
        val draft = parse("spent 36 at Blorptown")
        assertNotNull(draft.merchant)
        assertFalse(draft.warnings.contains(VoiceWarning.AMOUNT_MISSING))
    }

    // ================================================================ 账户

    @Test
    fun accountWechatPay() {
        assertEquals("微信", parse("paid 36 with wechat pay at Starbucks").account)
    }

    @Test
    fun accountWechat() {
        assertEquals("微信", parse("paid 36 with WeChat at Starbucks").account)
    }

    @Test
    fun accountAlipay() {
        assertEquals("支付宝", parse("paid 36 with alipay at Taobao").account)
    }

    @Test
    fun accountCash() {
        assertEquals("现金", parse("paid 36 in cash at Starbucks").account)
    }

    @Test
    fun accountCard() {
        assertEquals("银行卡", parse("paid 36 by card at Starbucks").account)
    }

    @Test
    fun accountCreditCard() {
        assertEquals("银行卡", parse("paid 36 by credit card at Amazon").account)
    }

    @Test
    fun accountDebitCard() {
        assertEquals("银行卡", parse("paid 36 by debit card at Amazon").account)
    }

    @Test
    fun noAccountLeavesNull() {
        assertNull(parse("paid 36 at Starbucks").account)
    }

    // ================================================================ 时间

    @Test
    fun timeToday() {
        val draft = parse("spent 36 today at Starbucks")
        assertTrue(draft.dateKnown)
        assertEquals(at(2026, 11, 17), draft.occurredAtMs)
        assertFalse(draft.preciseTime)
    }

    @Test
    fun timeYesterday() {
        val draft = parse("spent 36 yesterday at Starbucks")
        assertTrue(draft.dateKnown)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
    }

    @Test
    fun timeLastNight() {
        val draft = parse("spent 36 last night at Starbucks")
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
        assertEquals("晚上", draft.timeOfDayLabel)
    }

    @Test
    fun timeThisMorning() {
        val draft = parse("spent 36 this morning at Starbucks")
        assertEquals(at(2026, 11, 17), draft.occurredAtMs)
        assertEquals("早上", draft.timeOfDayLabel)
    }

    @Test
    fun timeThisAfternoon() {
        val draft = parse("spent 36 this afternoon at Starbucks")
        assertEquals("下午", draft.timeOfDayLabel)
    }

    @Test
    fun timeThisEvening() {
        val draft = parse("spent 36 this evening at Starbucks")
        assertEquals("晚上", draft.timeOfDayLabel)
    }

    @Test
    fun timeNoon() {
        val draft = parse("spent 36 at noon at Starbucks")
        assertEquals("中午", draft.timeOfDayLabel)
        assertEquals(at(2026, 11, 17), draft.occurredAtMs)
    }

    @Test
    fun timeMidnight() {
        val draft = parse("spent 36 at midnight at Starbucks")
        assertEquals("半夜", draft.timeOfDayLabel)
    }

    @Test
    fun timeUnknownKeepsNowAndNoDate() {
        val draft = parse("spent 36 at Starbucks")
        assertFalse(draft.dateKnown)
        assertEquals(now, draft.occurredAtMs)
        assertFalse(draft.preciseTime)
        assertNull(draft.timeOfDayLabel)
    }

    @Test
    fun timeNeverInventsClock() {
        val draft = parse("spent 36 yesterday at Starbucks")
        assertFalse(draft.preciseTime)
        assertEquals(0, Calendar.getInstance(zone).apply { timeInMillis = draft.occurredAtMs!! }.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun timeOfDayLabelWarnsApproximate() {
        val draft = parse("spent 36 this evening at Starbucks")
        assertWarning(VoiceWarning.DATE_APPROXIMATE, draft)
    }

    // ================================================================ 分类

    @Test
    fun categoryStarbucksIsFood() {
        assertEquals(TransactionCategory.FOOD, parse("spent 36 at Starbucks").category)
    }

    @Test
    fun categoryMcdonaldsIsFood() {
        assertEquals(TransactionCategory.FOOD, parse("spent 36 at McDonald's").category)
    }

    @Test
    fun categoryAmazonIsShopping() {
        assertEquals(TransactionCategory.SHOPPING, parse("spent 36 on Amazon").category)
    }

    @Test
    fun categoryUberIsTransport() {
        assertEquals(TransactionCategory.TRANSPORT, parse("paid 12 at Uber").category)
    }

    @Test
    fun categoryUnknownFallsBackToOther() {
        val draft = parse("spent 36 at Blorptown")
        assertEquals(TransactionCategory.OTHER, draft.category)
        assertWarning(VoiceWarning.MERCHANT_UNKNOWN, draft)
    }

    @Test
    fun categoryTransferWhenNoKeyword() {
        val draft = parse("transferred 100 to Alice")
        assertEquals(TransactionCategory.TRANSFER, draft.category)
    }

    // ================================================================ 备注

    @Test
    fun noteFromForLunch() {
        assertEquals("lunch", parse("spent 36 at Starbucks for lunch").note)
    }

    @Test
    fun noteExplicit() {
        assertEquals("team coffee", parse("spent 36 at Starbucks note: team coffee").note)
    }

    @Test
    fun noteAbsent() {
        assertNull(parse("spent 36 at Starbucks").note)
    }

    // ================================================================ 噪声 / 边界

    @Test
    fun noiseHelloHasNoAmount() {
        val draft = parse("hello")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun blankInputHasNoAmount() {
        val draft = parse("")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun multipleAmountsWarnsAndTakesFirst() {
        val draft = parse("coffee 5 and taxi 12")
        assertEquals(500L, draft.amountCents)
        assertWarning(VoiceWarning.MULTI_AMOUNT, draft)
    }

    @Test
    fun quantityNotAmount() {
        val draft = parse("bought two coffees")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun percentIsNotAmount() {
        val draft = parse("5% off at Nike")
        assertNull(draft.amountCents)
    }

    @Test
    fun punctuationNoiseTolerated() {
        val draft = parse("  spent   36.50   AT   Starbucks!!  ")
        assertEquals(3650L, draft.amountCents)
        assertEquals("Starbucks", draft.merchant)
    }

    @Test
    fun foreignCurrencyLeftEmpty() {
        val draft = parse("paid 30 euros at Zara")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.CURRENCY_UNSUPPORTED, draft)
    }

    @Test
    fun approximateAmountWarns() {
        val draft = parse("spent about 36 at Starbucks")
        assertEquals(3600L, draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_APPROXIMATE, draft)
    }

    @Test
    fun timeNumberIsNotAmount() {
        val draft = parse("spent 20 at 9 pm at Starbucks")
        assertEquals(2000L, draft.amountCents)
    }

    @Test
    fun rawIsPreserved() {
        assertEquals("spent 36 at Starbucks", parse("  spent 36 at Starbucks  ").raw)
    }

    // ================================================================ 中英混合

    @Test
    fun mixedKeepsChineseAmount() {
        val draft = parse("starbucks 花了 36")
        assertEquals(3600L, draft.amountCents)
        assertEquals(VoiceType.EXPENSE, draft.type)
    }

    @Test
    fun mixedWithChinesePreposition() {
        val draft = parse("在 Starbucks 花了 36")
        assertEquals(3600L, draft.amountCents)
        assertEquals("Starbucks", draft.merchant)
        assertEquals(TransactionCategory.FOOD, draft.category)
    }

    @Test
    fun mixedEnglishFragmentBecomesMerchant() {
        val draft = parse("starbucks 花了 36")
        assertEquals("starbucks", draft.merchant)
        assertEquals(TransactionCategory.FOOD, draft.category)
    }

    @Test
    fun mixedChineseAccountStaysChinese() {
        val draft = parse("微信支付 Starbucks 36")
        assertEquals("微信支付", draft.account)
        assertEquals(3600L, draft.amountCents)
    }
}
