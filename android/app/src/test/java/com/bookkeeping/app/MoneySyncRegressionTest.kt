package com.bookkeeping.app

import com.bookkeeping.app.data.MoneyAmount
import com.bookkeeping.app.notification.NotificationParser
import com.bookkeeping.app.notification.SmsParser
import org.junit.Assert.*
import org.junit.Test

class MoneySyncRegressionTest {
    @Test fun preciseDecimalYuanConversion() {
        assertEquals(1881L, MoneyAmount.cents("18.81"))
        assertEquals(29L, MoneyAmount.cents("0.29"))
        assertEquals(100L, MoneyAmount.cents("1"))
        assertEquals(100L, MoneyAmount.cents("1.00"))
        assertNull(MoneyAmount.cents("18.811"))
        assertNull(MoneyAmount.cents("-2.00"))
        assertNull(MoneyAmount.cents("0.00"))
    }

    @Test fun weixinPayNotificationDoesNotLoseOneCent() {
        val tx=NotificationParser.parse(
            "com.tencent.mm", "Weixin Pay", "[6]Weixin Pay: ¥18.81 paid", 1791512160000L
        )
        assertNotNull(tx)
        assertEquals(1881L, tx!!.amountCents)
        assertEquals("WECHAT", tx.source)
        assertEquals("EXPENSE", tx.type)
    }

    @Test fun alipayPromotionalRedPacketIsNotACharge() {
        val time=1791635098455L
        assertNull(NotificationParser.parse(
            "com.eg.android.AlipayGphone", "支付宝卡包",
            "你有淘宝闪购14元红包今晚失效", time))
        assertNull(NotificationParser.parse(
            "com.eg.android.AlipayGphone", "支付宝",
            "未领取18.86元红包，点击领取", time))
        assertNull(NotificationParser.parse(
            "com.eg.android.AlipayGphone", "支付宝",
            "14.00元优惠券即将失效", time))
        assertNull(NotificationParser.parse(
            "com.tencent.mm", "微信红包", "领取14.00元红包", time))
    }

    @Test fun paymentsRemainCapturedEvenWhenRewardAdvertised() {
        val time=1791635098455L
        val paid=NotificationParser.parse(
            "com.eg.android.AlipayGphone", "付款成功",
            "支付成功18.86元，恭喜领取3元电费红包", time)
        assertNotNull(paid)
        assertEquals("EXPENSE",paid!!.type)
        assertEquals(1886L,paid.amountCents)
        val spend=NotificationParser.parse(
            "com.eg.android.AlipayGphone", "交易提醒",
            "你有一笔58.00元的支出，点此查看详情",time)
        assertEquals(5800L,spend!!.amountCents)
    }

    @Test fun genericAmountWithoutSettlementIsIgnored() {
        val time=1791635098455L
        assertNull(NotificationParser.parse("com.eg.android.AlipayGphone",
            "账户提醒", "你的余额是500.00元",time))
        assertNull(NotificationParser.parse("com.eg.android.AlipayGphone",
            "领取优惠", "红包可抵扣14.00元",time))
    }

    @Test fun notificationUsesActualPaymentNotFirstCurrencySymbolOrCoupon() {
        val time=1791512160000L
        val couponFirst=NotificationParser.parse(
            "com.eg.android.AlipayGphone", "付款成功",
            "你有一张￥5.00优惠券，实际付款成功18.86元",time
        )
        assertNotNull(couponFirst)
        assertEquals(1886L,couponFirst!!.amountCents)
        assertEquals("EXPENSE",couponFirst.type)

        val promoThenActual=NotificationParser.parse(
            "com.eg.android.AlipayGphone", "支付提醒",
            "14.00元红包今晚失效，付款成功￥20.00",time
        )
        assertNotNull(promoThenActual)
        assertEquals(2000L,promoThenActual!!.amountCents)

        val doubleFormatCoupon=NotificationParser.parse(
            "com.eg.android.AlipayGphone", "付款成功",
            "有一张优惠券¥5.00元，付款成功¥18.86元",time
        )
        assertNotNull(doubleFormatCoupon)
        assertEquals(1886L,doubleFormatCoupon!!.amountCents)

        val balanceThenPaid=NotificationParser.parse(
            "com.eg.android.AlipayGphone", "支付提醒",
            "余额￥500.00，支付成功¥12.30",time
        )
        assertNotNull(balanceThenPaid)
        assertEquals(1230L,balanceThenPaid!!.amountCents)
    }

    @Test fun notificationPreservesIncomeRefundMerchantAndLegacyCapture() {
        val time=1791512160000L
        val income=NotificationParser.parse(
            "com.tencent.mm","微信支付","向你付款 100.00 元",time
        )
        assertEquals("INCOME",income!!.type)
        assertEquals(10000L,income.amountCents)
        val expense=NotificationParser.parse(
            "com.tencent.mm","微信支付","向 麦当劳 付款 12.30 元",time
        )
        assertEquals("EXPENSE",expense!!.type)
        assertEquals(1230L,expense.amountCents)
        val refunded=NotificationParser.parse(
            "com.eg.android.AlipayGphone","支付宝","退款成功 18.86 元",time
        )
        // Intentionally retain existing INCOME serialization for compatibility;
        // proper refund/exclusion semantics need an explicit schema change.
        assertEquals("INCOME",refunded!!.type)
        assertEquals(1886L,refunded.amountCents)
        assertNull(NotificationParser.parse(
            "com.some.unrelated.app","付款成功","¥12.30",time
        ))
    }

    @Test fun bankSmsChoosesTransactionAmountOverBalance() {
        val time=1791512160000L
        val tx=SmsParser.parse("95555",
            "【招商银行】账户当前余额人民币500.00元，尾号1234已支付人民币12.30元",time)
        assertNotNull(tx)
        assertEquals(1230L,tx!!.amountCents)
        assertEquals("EXPENSE",tx.type)
        assertEquals("BANK_SMS",tx.source)
    }

    @Test fun bankSmsUsesNearestDirectionAndKeepsPostAmountKeywords() {
        val time=1791512160000L
        val mixed=SmsParser.parse("95555",
            "【招商银行】工资入账提醒，尾号1234消费人民币25.81元，余额200.00元",time)
        assertEquals("EXPENSE",mixed!!.type)
        assertEquals(2581L,mixed.amountCents)
        val postAmount=SmsParser.parse("95566",
            "【中国银行】尾号1234人民币100.00元已转入账户",time)
        assertEquals("INCOME",postAmount!!.type)
        assertEquals(10000L,postAmount.amountCents)
        assertNull(SmsParser.parse("95555",
            "【招商银行】尾号1234可用余额300.00元，今日转入和转出请查看详情",time))
    }

    @Test fun smsExactCentsAndNoFalseOtp() {
        val bank="【招商银行】尾号1234消费人民币25.81元，余额1234.56元"
        val tx=SmsParser.parse(null,bank,1791512160000L)
        assertEquals(2581L,tx!!.amountCents)
        assertNull(SmsParser.parse(null,"验证码 123456，用于登录",1791512160000L))
    }
}
