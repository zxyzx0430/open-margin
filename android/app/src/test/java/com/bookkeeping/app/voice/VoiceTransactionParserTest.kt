package com.bookkeeping.app.voice

import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 语音解析器的回归测试。
 *
 * 所有时间都用固定的 nowMs（2026-11-17 10:00 +08，周二），
 * 不依赖真实时钟，所以在任何机器和任何一天跑结果都一样。
 */
class VoiceTransactionParserTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-11-17 是周二。 */
    private val now: Long = at(2026, 11, 17, 10, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun parse(text: String, nowMs: Long = now): VoiceDraft =
        VoiceTransactionParser.parse(text, nowMs, zone)

    private fun assertWarning(expected: VoiceWarning, draft: VoiceDraft) {
        assertTrue("expected $expected in ${draft.warnings}", draft.warnings.contains(expected))
    }

    // ================================================================ 金额：中文数字

    @Test
    fun chineseYuanAndJiao() {
        assertEquals(1850L, parse("十八块五").amountCents)
    }

    @Test
    fun chineseThirtySixYuanFive() {
        assertEquals(3650L, parse("三十六块五").amountCents)
    }

    @Test
    fun chineseZeroJiaoThenFen() {
        // 三十六块零五 = 36.05，不是 36.5
        assertEquals(3605L, parse("三十六块零五").amountCents)
    }

    @Test
    fun twoYuanFiveMao() {
        assertEquals(250L, parse("两块五毛").amountCents)
    }

    @Test
    fun oneYuanHalf() {
        assertEquals(150L, parse("一块半").amountCents)
    }

    @Test
    fun oneHundredTwentyShorthand() {
        assertEquals(12000L, parse("一百二").amountCents)
    }

    @Test
    fun twoHundredFiftyShorthand() {
        assertEquals(25000L, parse("两百五").amountCents)
    }

    @Test
    fun oneThousandFiftyWithLing() {
        assertEquals(105000L, parse("一千零五十").amountCents)
    }

    @Test
    fun twoThousandThreeShorthand() {
        assertEquals(230000L, parse("两千三").amountCents)
    }

    @Test
    fun tenThousandTwoShorthand() {
        assertEquals(1200000L, parse("一万二").amountCents)
    }

    @Test
    fun oneHundredFiveWithLingIsNotShorthand() {
        assertEquals(10500L, parse("一百零五块").amountCents)
    }

    @Test
    fun oneThousandTwoHundredThree() {
        assertEquals(123000L, parse("一千二百三").amountCents)
    }

    @Test
    fun chineseDozenYuanEight() {
        // 十二块八 = 12.8，别把"十"当成单位跳过
        assertEquals(1280L, parse("十二块八").amountCents)
    }

    @Test
    fun chineseFiveYuanTwoMao() {
        assertEquals(520L, parse("五块二毛").amountCents)
    }

    // ================================================================ 金额：阿拉伯数字与符号

    @Test
    fun arabicDecimal() {
        assertEquals(3650L, parse("36.5").amountCents)
    }

    @Test
    fun arabicYuanThenJiaoDigit() {
        assertEquals(3650L, parse("36元5").amountCents)
    }

    @Test
    fun yenSymbolWithTwoDecimals() {
        assertEquals(3650L, parse("¥36.50").amountCents)
    }

    @Test
    fun yuanThenJiaoWord() {
        assertEquals(1850L, parse("18元5角").amountCents)
    }

    @Test
    fun threeKuaiThree() {
        assertEquals(330L, parse("3块3").amountCents)
    }

    @Test
    fun halfYuan() {
        assertEquals(50L, parse("0.5元").amountCents)
    }

    @Test
    fun fiveMaoAlone() {
        assertEquals(50L, parse("五毛").amountCents)
    }

    @Test
    fun spacesBetweenNumberAndUnit() {
        assertEquals(3650L, parse("36 块 5").amountCents)
    }

    @Test
    fun oneThousandPlain() {
        assertEquals(100000L, parse("花了 1000").amountCents)
    }

    @Test
    fun tenThousandMixedArabic() {
        assertEquals(1200000L, parse("1万2").amountCents)
    }

    @Test
    fun amountWithNoteKeyword() {
        assertEquals(10000L, parse("花了100元 备注：和客户吃饭").amountCents)
    }

    // ================================================================ 近似金额

    @Test
    fun thirtySomething() {
        val draft = parse("吃饭 三十多")
        assertEquals(3000L, draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_APPROXIMATE, draft)
    }

    @Test
    fun hundredOrSo() {
        val draft = parse("一百来块")
        assertEquals(10000L, draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_APPROXIMATE, draft)
    }

    @Test
    fun aboutFifty() {
        val draft = parse("差不多五十")
        assertEquals(5000L, draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_APPROXIMATE, draft)
    }

    @Test
    fun fiftyAround() {
        val draft = parse("50 左右")
        assertEquals(5000L, draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_APPROXIMATE, draft)
    }

    @Test
    fun aboutFiftyWithSpace() {
        val draft = parse("差不多 100 块")
        assertEquals(10000L, draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_APPROXIMATE, draft)
    }

    // ================================================================ 数量词不能当金额

    @Test
    fun twoBaoziIsQuantityNotAmount() {
        val draft = parse("两个包子")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.QUANTITY_NOT_AMOUNT, draft)
    }

    @Test
    fun threeCupsOfCoffeeIsQuantity() {
        val draft = parse("三杯咖啡")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.QUANTITY_NOT_AMOUNT, draft)
    }

    @Test
    fun fiveJinOfApplesIsQuantity() {
        val draft = parse("五斤苹果")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.QUANTITY_NOT_AMOUNT, draft)
    }

    @Test
    fun twoTakeoutBoxesIsQuantity() {
        val draft = parse("两份外卖")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.QUANTITY_NOT_AMOUNT, draft)
    }

    @Test
    fun fourPeopleIsQuantity() {
        val draft = parse("四个人")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.QUANTITY_NOT_AMOUNT, draft)
    }

    @Test
    fun eightyPercentOffIsNotAmount() {
        val draft = parse("打了八折")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.QUANTITY_NOT_AMOUNT, draft)
    }

    @Test
    fun quantityAndAmountTogetherKeepsAmount() {
        val draft = parse("买了 2 个包子 6 块")
        assertEquals(600L, draft.amountCents)
        assertFalse(draft.warnings.contains(VoiceWarning.QUANTITY_NOT_AMOUNT))
    }

    // ================================================================ 折扣 / 多金额 / 外币 / 缺失

    @Test
    fun discountTakesPaidAmount() {
        val draft = parse("原价 100 打折 80")
        assertEquals(8000L, draft.amountCents)
        assertFalse(draft.warnings.contains(VoiceWarning.MULTI_AMOUNT))
    }

    @Test
    fun multipleAmountsTakeFirstAndWarn() {
        val draft = parse("买了咖啡 18 和蛋糕 25")
        assertEquals(1800L, draft.amountCents)
        assertWarning(VoiceWarning.MULTI_AMOUNT, draft)
    }

    @Test
    fun foreignCurrencyIsNotConverted() {
        val draft = parse("花了 100 美元")
        assertEquals(10000L, draft.amountCents)
        assertWarning(VoiceWarning.CURRENCY_UNSUPPORTED, draft)
    }

    @Test
    fun hongKongDollarIsUnsupported() {
        val draft = parse("酒店 800 港币")
        assertWarning(VoiceWarning.CURRENCY_UNSUPPORTED, draft)
    }

    @Test
    fun missingAmountWarns() {
        val draft = parse("昨天买东西")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    // ================================================================ 类型

    @Test
    fun spendVerbIsExpense() {
        assertEquals(VoiceType.EXPENSE, parse("花了 30 买水果").type)
    }

    @Test
    fun buyVerbIsExpense() {
        assertEquals(VoiceType.EXPENSE, parse("买了咖啡 18").type)
    }

    @Test
    fun payVerbIsExpense() {
        assertEquals(VoiceType.EXPENSE, parse("用支付宝支付了 58").type)
    }

    @Test
    fun phoneTopUpIsExpense() {
        val draft = parse("充了话费 100")
        assertEquals(VoiceType.EXPENSE, draft.type)
        assertEquals(TransactionCategory.UTILITIES, draft.category)
    }

    @Test
    fun salaryIsIncome() {
        assertEquals(VoiceType.INCOME, parse("工资 8000").type)
    }

    @Test
    fun reimbursementIsIncome() {
        assertEquals(VoiceType.INCOME, parse("报销 350").type)
    }

    @Test
    fun interestIsIncome() {
        assertEquals(VoiceType.INCOME, parse("利息到账 12").type)
    }

    @Test
    fun soldSomethingIsIncome() {
        assertEquals(VoiceType.INCOME, parse("卖了二手书 20").type)
    }

    @Test
    fun refundIsRefundNotIncome() {
        val draft = parse("退款 36 块 5")
        assertEquals(VoiceType.REFUND, draft.type)
        assertWarning(VoiceWarning.REFUND_NEEDS_CONFIRM, draft)
    }

    @Test
    fun refundArrivalStaysRefund() {
        val draft = parse("退款到账 50")
        assertEquals(VoiceType.REFUND, draft.type)
    }

    @Test
    fun returnedMoneyIsRefund() {
        val draft = parse("退了 36")
        assertEquals(VoiceType.REFUND, draft.type)
        assertWarning(VoiceWarning.REFUND_NEEDS_CONFIRM, draft)
    }

    @Test
    fun transferIsTransfer() {
        val draft = parse("转账给妈妈 500")
        assertEquals(VoiceType.TRANSFER, draft.type)
        assertWarning(VoiceWarning.TRANSFER_NEEDS_CONFIRM, draft)
    }

    @Test
    fun creditCardRepaymentIsTransfer() {
        val draft = parse("还信用卡 3000")
        assertEquals(VoiceType.TRANSFER, draft.type)
        assertWarning(VoiceWarning.TRANSFER_NEEDS_CONFIRM, draft)
    }

    @Test
    fun topUpIsTransfer() {
        assertEquals(VoiceType.TRANSFER, parse("充值 100").type)
    }

    @Test
    fun withdrawIsTransfer() {
        assertEquals(VoiceType.TRANSFER, parse("提现 100").type)
    }

    @Test
    fun giveSomeoneTransferIsTransfer() {
        assertEquals(VoiceType.TRANSFER, parse("给小李转了 200").type)
    }

    @Test
    fun unknownVerbIsExpenseWithWarning() {
        val draft = parse("36.5")
        assertEquals(VoiceType.EXPENSE, draft.type)
        assertWarning(VoiceWarning.TYPE_UNCERTAIN, draft)
    }

    // ================================================================ 日期

    @Test
    fun today() {
        val draft = parse("今天 打车 26")
        assertTrue(draft.dateKnown)
        assertEquals(at(2026, 11, 17), draft.occurredAtMs)
    }

    @Test
    fun yesterday() {
        val draft = parse("昨天 买菜 40")
        assertTrue(draft.dateKnown)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
    }

    @Test
    fun dayBeforeYesterday() {
        assertEquals(at(2026, 11, 15), parse("前天 买菜 40").occurredAtMs)
    }

    @Test
    fun threeDaysAgo() {
        assertEquals(at(2026, 11, 14), parse("大前天 买菜 40").occurredAtMs)
    }

    @Test
    fun lastFriday() {
        val draft = parse("上周五打车 23")
        assertEquals(at(2026, 11, 13), draft.occurredAtMs)
        assertFalse(draft.warnings.contains(VoiceWarning.FUTURE_DATE))
    }

    @Test
    fun lastWeekWednesdayWithLaili() {
        assertEquals(at(2026, 11, 11), parse("上礼拜三 星巴克 32").occurredAtMs)
    }

    @Test
    fun bareWeekdayMeansMostRecent() {
        // 今天是周二，说"周三"记的是已经过去的那个周三
        assertEquals(at(2026, 11, 11), parse("周三 瑞幸 19").occurredAtMs)
    }

    @Test
    fun sundayWithXingqi() {
        assertEquals(at(2026, 11, 15), parse("星期天 超市 88").occurredAtMs)
    }

    @Test
    fun weekendIsApproximate() {
        val draft = parse("周末 永辉超市 128")
        assertEquals(at(2026, 11, 14), draft.occurredAtMs)
        assertWarning(VoiceWarning.DATE_APPROXIMATE, draft)
    }

    @Test
    fun monthAndDay() {
        assertEquals(at(2026, 11, 15), parse("11月15号 打车 26").occurredAtMs)
    }

    @Test
    fun slashDate() {
        assertEquals(at(2026, 11, 15), parse("11/15 电影 80").occurredAtMs)
    }

    @Test
    fun dayOnly() {
        assertEquals(at(2026, 11, 15), parse("15号 药店 56").occurredAtMs)
    }

    @Test
    fun chineseDayOnly() {
        assertEquals(at(2026, 11, 15), parse("十五号 交房租 3000").occurredAtMs)
    }

    @Test
    fun lastMonthWithDay() {
        assertEquals(at(2026, 10, 25), parse("上月25号 房租 3000").occurredAtMs)
    }

    @Test
    fun lastMonthWithoutDayIsApproximate() {
        val draft = parse("上个月 物业 200")
        assertEquals(at(2026, 10, 1), draft.occurredAtMs)
        assertWarning(VoiceWarning.DATE_APPROXIMATE, draft)
    }

    @Test
    fun tomorrowIsFuture() {
        val draft = parse("明天 聚餐 300")
        assertEquals(at(2026, 11, 18), draft.occurredAtMs)
        assertWarning(VoiceWarning.FUTURE_DATE, draft)
    }

    @Test
    fun nextMondayIsFuture() {
        val draft = parse("下周一 机票 1200")
        assertEquals(at(2026, 11, 23), draft.occurredAtMs)
        assertWarning(VoiceWarning.FUTURE_DATE, draft)
    }

    @Test
    fun thisWeekFutureDayWarns() {
        val draft = parse("这周三 电影 60")
        assertEquals(at(2026, 11, 18), draft.occurredAtMs)
        assertWarning(VoiceWarning.FUTURE_DATE, draft)
    }

    @Test
    fun noDateMeansTodayButNotKnown() {
        val draft = parse("买了咖啡 18")
        assertFalse(draft.dateKnown)
        assertEquals(now, draft.occurredAtMs)
    }

    @Test
    fun dateKeepsClockWhenKnown() {
        val draft = parse("昨天 打车 23")
        assertTrue(draft.dateKnown)
        assertFalse(draft.preciseTime)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
    }

    // ================================================================ 钟点与时段

    @Test
    fun morningEight() {
        val draft = parse("早上八点 打车 26")
        assertTrue(draft.preciseTime)
        assertEquals(at(2026, 11, 17, 8, 0), draft.occurredAtMs)
        assertEquals("早上", draft.timeOfDayLabel)
    }

    @Test
    fun halfPastEight() {
        assertEquals(at(2026, 11, 17, 8, 30), parse("八点半 地铁 4").occurredAtMs)
    }

    @Test
    fun quarterPastEight() {
        assertEquals(at(2026, 11, 17, 8, 15), parse("八点一刻 咖啡 20").occurredAtMs)
    }

    @Test
    fun fivePastEight() {
        assertEquals(at(2026, 11, 17, 8, 5), parse("八点零五 早饭 8").occurredAtMs)
    }

    @Test
    fun digitalClock() {
        val draft = parse("20:30 打车 30")
        assertTrue(draft.preciseTime)
        assertEquals(at(2026, 11, 17, 20, 30), draft.occurredAtMs)
    }

    @Test
    fun eveningShiftsToTwentyFourHour() {
        val draft = parse("晚上八点 晚饭 88")
        assertEquals(at(2026, 11, 17, 20, 0), draft.occurredAtMs)
        assertEquals("晚上", draft.timeOfDayLabel)
    }

    @Test
    fun afternoonShiftsToFifteen() {
        assertEquals(at(2026, 11, 17, 15, 0), parse("下午三点 咖啡 25").occurredAtMs)
    }

    @Test
    fun afternoonOnTomorrowKeepsBoth() {
        val draft = parse("明天下午三点 咖啡 30")
        assertEquals(at(2026, 11, 18, 15, 0), draft.occurredAtMs)
        assertWarning(VoiceWarning.FUTURE_DATE, draft)
    }

    @Test
    fun midnightTwelveIsZero() {
        assertEquals(at(2026, 11, 17, 0, 0), parse("半夜十二点 打车 60").occurredAtMs)
    }

    @Test
    fun timeOfDayOnlyDoesNotInventClock() {
        val draft = parse("昨晚 夜宵 45")
        assertFalse(draft.preciseTime)
        assertEquals("晚上", draft.timeOfDayLabel)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
        assertWarning(VoiceWarning.DATE_APPROXIMATE, draft)
    }

    @Test
    fun noonOnlyDoesNotInventClock() {
        val draft = parse("中午 吃饭 30")
        assertFalse(draft.preciseTime)
        assertEquals("中午", draft.timeOfDayLabel)
        assertEquals(at(2026, 11, 17), draft.occurredAtMs)
    }

    @Test
    fun eveningWordFromDateAppliesToClock() {
        // "昨晚八点"：时段词来自日期短语，钟点要按晚上补 12 小时
        val draft = parse("昨晚八点 吃饭 60")
        assertEquals(at(2026, 11, 16, 20, 0), draft.occurredAtMs)
        assertEquals("晚上", draft.timeOfDayLabel)
        assertTrue(draft.preciseTime)
    }

    @Test
    fun dateWithoutClockIsNotPrecise() {
        val draft = parse("昨天麦当劳 36.5")
        assertFalse(draft.preciseTime)
        assertNull(draft.timeOfDayLabel)
    }

    @Test
    fun morningWordIsRecordedForTodayMorning() {
        val draft = parse("今早 早餐 12")
        assertTrue(draft.dateKnown)
        assertEquals("早上", draft.timeOfDayLabel)
        assertFalse(draft.preciseTime)
    }

    // ================================================================ 商户

    @Test
    fun merchantAfterZai() {
        val draft = parse("我昨天在麦当劳花了 36 块 5，吃晚饭，微信支付的")
        assertEquals("麦当劳", draft.merchant)
    }

    @Test
    fun merchantAfterQu() {
        assertEquals("星巴克", parse("去星巴克买咖啡 32").merchant)
    }

    @Test
    fun brandMeituanTakeout() {
        assertEquals("美团外卖", parse("美团外卖 45").merchant)
    }

    @Test
    fun brandDidi() {
        assertEquals("滴滴", parse("滴滴 28").merchant)
    }

    @Test
    fun brandKfc() {
        assertEquals("肯德基", parse("肯德基 39").merchant)
    }

    @Test
    fun suffixSupermarket() {
        assertEquals("永辉超市", parse("周末 永辉超市 128").merchant)
    }

    @Test
    fun suffixConvenienceStore() {
        assertEquals("楼下便利店", parse("楼下便利店 12").merchant)
    }

    @Test
    fun suffixPharmacy() {
        assertEquals("同仁堂药店", parse("同仁堂药店 56").merchant)
    }

    @Test
    fun noMerchantForTaxi() {
        val draft = parse("上周五打车 23")
        assertNull(draft.merchant)
        assertWarning(VoiceWarning.MERCHANT_UNKNOWN, draft)
    }

    @Test
    fun noMerchantForGenericPurchase() {
        val draft = parse("买了点东西 66")
        assertNull(draft.merchant)
        assertWarning(VoiceWarning.MERCHANT_UNKNOWN, draft)
    }

    // ================================================================ 账户

    @Test
    fun accountWechatPay() {
        assertEquals("微信支付", parse("麦当劳 36 微信支付").account)
    }

    @Test
    fun accountAlipay() {
        assertEquals("支付宝", parse("用支付宝付了 58").account)
    }

    @Test
    fun accountHuabei() {
        assertEquals("花呗", parse("花呗 99").account)
    }

    @Test
    fun accountCmBank() {
        assertEquals("招行", parse("招行 320").account)
    }

    @Test
    fun accountCash() {
        assertEquals("现金", parse("用现金 20").account)
    }

    @Test
    fun accountWechatBalance() {
        assertEquals("微信零钱", parse("微信零钱 15").account)
    }

    @Test
    fun noAccountIsNull() {
        assertNull(parse("麦当劳 36").account)
    }

    // ================================================================ 分类

    @Test
    fun categoryFood() {
        assertEquals(TransactionCategory.FOOD, parse("麦当劳 36").category)
    }

    @Test
    fun categoryTransport() {
        assertEquals(TransactionCategory.TRANSPORT, parse("地铁 4").category)
    }

    @Test
    fun categoryShopping() {
        assertEquals(TransactionCategory.SHOPPING, parse("淘宝 199").category)
    }

    @Test
    fun categoryEntertainment() {
        assertEquals(TransactionCategory.ENTERTAINMENT, parse("电影票 45").category)
    }

    @Test
    fun categoryHousing() {
        assertEquals(TransactionCategory.HOUSING, parse("房租 3000").category)
    }

    @Test
    fun categoryHealth() {
        assertEquals(TransactionCategory.HEALTH, parse("药店 56").category)
    }

    @Test
    fun categoryEducation() {
        assertEquals(TransactionCategory.EDUCATION, parse("学费 5000").category)
    }

    @Test
    fun categoryUtilities() {
        assertEquals(TransactionCategory.UTILITIES, parse("话费 100").category)
    }

    @Test
    fun categoryGift() {
        assertEquals(TransactionCategory.GIFT, parse("红包 200").category)
    }

    @Test
    fun categoryOtherWarnsMerchantUnknown() {
        val draft = parse("买了个东西 66")
        assertEquals(TransactionCategory.OTHER, draft.category)
        assertWarning(VoiceWarning.MERCHANT_UNKNOWN, draft)
    }

    @Test
    fun merchantRuleBeatsKeyword() {
        val draft = VoiceTransactionParser.parse("麦当劳 36", now, zone) {
            if (it == "麦当劳") TransactionCategory.EDUCATION else null
        }
        assertEquals(TransactionCategory.EDUCATION, draft.category)
    }

    @Test
    fun merchantRuleIgnoredWhenMerchantUnknown() {
        val draft = VoiceTransactionParser.parse("打车 23", now, zone) { TransactionCategory.GIFT }
        assertEquals(TransactionCategory.TRANSPORT, draft.category)
    }

    // ================================================================ 同音纠错

    @Test
    fun kuaiHomophone() {
        assertEquals(3650L, parse("36快5").amountCents)
    }

    @Test
    fun yuanHomophones() {
        assertEquals(3600L, parse("36园").amountCents)
        assertEquals(3600L, parse("36员").amountCents)
        assertEquals(3600L, parse("36圆").amountCents)
    }

    @Test
    fun zhifubaoHomophone() {
        assertEquals("支付宝", parse("支付包付了 30").account)
    }

    @Test
    fun weixinHomophone() {
        assertEquals("微信", parse("用威信付了 22").account)
    }

    @Test
    fun huabeiHomophone() {
        assertEquals("花呗", parse("花贝 88").account)
    }

    @Test
    fun mcdonaldsHomophone() {
        val draft = parse("麦当老 35")
        assertEquals("麦当劳", draft.merchant)
        assertEquals(TransactionCategory.FOOD, draft.category)
    }

    @Test
    fun luckinHomophone() {
        assertEquals("瑞幸咖啡", parse("瑞星咖啡 19").merchant)
    }

    @Test
    fun mixueHomophone() {
        assertEquals("蜜雪冰城", parse("蜜雪冰成 8").merchant)
    }

    @Test
    fun kfcHomophone() {
        assertEquals("肯德基", parse("肯德鸡 42").merchant)
    }

    @Test
    fun homophoneFixKeepsOtherWordsIntact() {
        // 只改明显的同音词，别把正常句子改坏
        assertEquals(VoiceTransactionParser.normalize("快点买单"), "快点买单")
    }

    // ================================================================ 整句

    @Test
    fun fullSentence() {
        val draft = parse("我昨天在麦当劳花了 36 块 5，吃晚饭，微信支付的")
        assertEquals(3650L, draft.amountCents)
        assertEquals(VoiceType.EXPENSE, draft.type)
        assertEquals("麦当劳", draft.merchant)
        assertEquals(TransactionCategory.FOOD, draft.category)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
        assertEquals("微信支付", draft.account)
        assertEquals("吃晚饭", draft.note)
        assertTrue(draft.warnings.isEmpty())
    }

    @Test
    fun amountFirstThenMerchantThenDate() {
        val draft = parse("36 块 5，麦当劳，昨天")
        assertEquals(3650L, draft.amountCents)
        assertEquals("麦当劳", draft.merchant)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
    }

    @Test
    fun terseMerchantThenAmount() {
        val draft = parse("昨天麦当劳 36.5")
        assertEquals(3650L, draft.amountCents)
        assertEquals("麦当劳", draft.merchant)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
    }

    @Test
    fun terseTaxiLastFriday() {
        val draft = parse("上周五打车 23")
        assertEquals(2300L, draft.amountCents)
        assertEquals(TransactionCategory.TRANSPORT, draft.category)
        assertEquals(at(2026, 11, 13), draft.occurredAtMs)
    }

    @Test
    fun purposePhraseGoesToNote() {
        assertEquals("打车回家", parse("昨天 打车回家 23").note)
    }

    @Test
    fun explicitNoteWins() {
        assertEquals("和客户吃饭", parse("花了100元 备注：和客户吃饭").note)
    }

    @Test
    fun dateAfterAmountStillParses() {
        val draft = parse("打车 23，上周五，微信")
        assertEquals(2300L, draft.amountCents)
        assertEquals(at(2026, 11, 13), draft.occurredAtMs)
        assertEquals("微信", draft.account)
    }

    @Test
    fun rawTextIsPreserved() {
        val raw = "  昨天麦当劳 36.5  "
        // raw 保留原话（只去掉首尾空白），方便 UI 回显"刚才说的是什么"
        assertEquals("昨天麦当劳 36.5", parse(raw).raw)
    }

    // ================================================================ 边界

    @Test
    fun emptyString() {
        val draft = parse("")
        assertNull(draft.amountCents)
        assertNull(draft.merchant)
        assertNull(draft.occurredAtMs)
        assertFalse(draft.dateKnown)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
        assertWarning(VoiceWarning.TYPE_UNCERTAIN, draft)
    }

    @Test
    fun onlySpaces() {
        val draft = parse("     ")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun onlyNumber() {
        val draft = parse("36.5")
        assertEquals(3650L, draft.amountCents)
        assertNull(draft.merchant)
        assertWarning(VoiceWarning.TYPE_UNCERTAIN, draft)
        assertFalse(draft.dateKnown)
    }

    @Test
    fun onlyFillerWords() {
        val draft = parse("嗯……那个……就是……")
        assertNull(draft.amountCents)
        assertNull(draft.merchant)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun onlyDate() {
        val draft = parse("昨天")
        assertNull(draft.amountCents)
        assertEquals(at(2026, 11, 16), draft.occurredAtMs)
        assertTrue(draft.dateKnown)
    }

    @Test
    fun onlyMerchant() {
        val draft = parse("麦当劳")
        assertNull(draft.amountCents)
        assertEquals("麦当劳", draft.merchant)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun punctuationOnly() {
        val draft = parse("。。。！！！")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun extremelyLongAmountIsRejected() {
        // 超过 1 亿的"金额"基本是识别错误，宁可当成没有金额
        val draft = parse("花了 999999999999 元")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }

    @Test
    fun zeroIsNotAnAmount() {
        val draft = parse("上午十点 开会 0")
        assertNull(draft.amountCents)
        assertWarning(VoiceWarning.AMOUNT_MISSING, draft)
    }
}
