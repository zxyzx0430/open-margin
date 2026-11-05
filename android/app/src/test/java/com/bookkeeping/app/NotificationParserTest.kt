package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.NotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Field-level contract for notification -> TransactionEntity extraction.
// Synthetic samples only; real-device formats still need D01 validation.
class NotificationParserTest {

    private val ts = 1_700_000_000_000L

    private fun wechat(title: String?, text: String?) =
        NotificationParser.parse("com.tencent.mm", title, text, ts)

    private fun alipay(title: String?, text: String?) =
        NotificationParser.parse("com.eg.android.AlipayGphone", title, text, ts)

    @Test fun wechatExpensePreservesEveryUploadedField() {
        val tx = wechat("微信支付", "向 麦当劳 付款 12.30 元")!!
        assertEquals(1230L, tx.amountCents)
        assertEquals(TransactionType.EXPENSE.name, tx.type)
        assertEquals(TransactionCategory.FOOD.name, tx.category)
        assertEquals("麦当劳", tx.merchant)
        assertEquals(TransactionSource.WECHAT.name, tx.source)
        assertEquals("微信", tx.account)
        assertEquals("CNY", tx.currency)
        assertEquals(ts, tx.timestamp)
        assertTrue(tx.note.startsWith("[自动抓取]"))
    }

    @Test fun wechatTransferIncomeKeepsCounterparty() {
        val tx = wechat("微信支付", "收到 张三 的转账 100.00 元")!!
        assertEquals(10_000L, tx.amountCents)
        assertEquals(TransactionType.INCOME.name, tx.type)
        assertEquals(TransactionCategory.TRANSFER.name, tx.category)
        assertEquals("张三", tx.merchant)
        assertEquals(TransactionSource.WECHAT.name, tx.source)
        assertEquals(ts, tx.timestamp)
    }

    @Test fun wechatPayToYouIsIncomeWithoutMerchant() {
        val tx = wechat("微信支付", "向你付款 100.00 元")!!
        assertEquals(10_000L, tx.amountCents)
        assertEquals(TransactionType.INCOME.name, tx.type)
        assertEquals("", tx.merchant)
    }

    @Test fun alipayExpenseKeepsMerchantAndCategory() {
        val tx = alipay("支付宝支付", "付款成功￥3.00 (AB)湖北武汉黄陂汉口北服装城美宜佳")!!
        assertEquals(300L, tx.amountCents)
        assertEquals(TransactionType.EXPENSE.name, tx.type)
        assertTrue("merchant=${tx.merchant}", tx.merchant.contains("美宜佳"))
        assertEquals(TransactionCategory.SHOPPING.name, tx.category)
        assertEquals("支付宝", tx.account)
    }

    @Test fun alipayGenericExpenseWithoutMerchant() {
        val tx = alipay("支付宝", "你有一笔 88.00 元的支出，点此查看详情")!!
        assertEquals(8800L, tx.amountCents)
        assertEquals(TransactionType.EXPENSE.name, tx.type)
        assertEquals("", tx.merchant)
        assertEquals("支付宝", tx.account)
    }

    @Test fun alipayRefundIsIncome() {
        val tx = alipay("支付宝", "收到退款 12.34 元")!!
        assertEquals(1234L, tx.amountCents)
        assertEquals(TransactionType.INCOME.name, tx.type)
    }

    @Test fun promotionsAndBalanceAreNotTransactions() {
        assertNull(alipay("支付宝", "14元红包今晚失效"))
        assertNull(alipay("支付宝", "优惠券5元即将过期"))
        assertNull(alipay("支付宝", "余额 100.00 元"))
        assertNull(wechat("微信支付", "卡包优惠活动 点击领取 20 元"))
    }

    @Test fun failedAndPendingPaymentsAreRejected() {
        assertNull(wechat("微信支付", "支付失败 12.30 元"))
        assertNull(alipay("支付宝", "待支付 25.00 元"))
        assertNull(alipay("支付宝", "扣款失败 25.00 元"))
    }

    @Test fun promotionalAmountBeforeTransactionIsSkipped() {
        val tx = alipay("支付宝", "付款成功，优惠券5.00元，实付12.30元")!!
        assertEquals(1230L, tx.amountCents)
    }

    @Test fun confirmedAmountWithTrailingPromotionIsKept() {
        val tx = alipay("支付宝", "付款成功 ￥3.00 红包抵扣")!!
        assertEquals(300L, tx.amountCents)
    }

    @Test fun thousandsSeparatorsAreParsedExactly() {
        assertEquals(123_456L, alipay("支付宝", "付款成功 ￥1,234.56")!!.amountCents)
        assertEquals(1_234_567L, alipay("支付宝", "付款成功 12,345.67 元")!!.amountCents)
    }

    @Test fun merchantPatternsAreStructured() {
        assertEquals("星巴克", alipay("支付宝", "付款给 星巴克 25.00 元")!!.merchant)
        assertEquals("美团", alipay("支付宝", "来自 美团 已到账 25.00 元")!!.merchant)
    }

    @Test fun malformedAmountsAreRejectedNotTruncated() {
        assertNull(alipay("支付宝", "付款成功 ￥12.345"))
        assertNull(alipay("支付宝", "付款成功 ￥1.2.3"))
        assertNull(alipay("支付宝", "付款成功 ￥0.00"))
        assertNull(alipay("支付宝", "付款成功 ￥999999999999.99"))
    }

    @Test fun unicodeMerchantAndLongTextSurvive() {
        val tx = wechat("微信支付", "向 🍔店 付款 12.30 元")!!
        assertEquals("🍔店", tx.merchant)
        assertEquals(1230L, tx.amountCents)
        val huge = wechat("微信支付", "付款成功 12.30 元 " + "啊".repeat(100_000))!!
        assertEquals(1230L, huge.amountCents)
        assertTrue(huge.note.length <= 87)
    }

    @Test fun unknownPackageBlankAndMissingAmountReturnNull() {
        assertNull(NotificationParser.parse("com.example.bank", "支付成功", "12.30 元", ts))
        assertNull(wechat(null, null))
        assertNull(wechat("微信支付", "付款成功"))
        assertNull(wechat("微信支付", "收到 张三 的转账"))
    }

    @Test fun amountAtMinimumAndMaximumBoundaries() {
        assertEquals(1L, wechat("微信支付", "付款成功 ￥0.01")!!.amountCents)
        assertEquals(100_000_000_000L,
            wechat("微信支付", "付款成功 ￥1000000000.00")!!.amountCents)
        assertNull(wechat("微信支付", "付款成功 ￥1000000000.01"))
    }
}
