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

    @Test fun smsExactCentsAndNoFalseOtp() {
        val bank="【招商银行】尾号1234消费人民币25.81元，余额1234.56元"
        val tx=SmsParser.parse(null,bank,1791512160000L)
        assertEquals(2581L,tx!!.amountCents)
        assertNull(SmsParser.parse(null,"验证码 123456，用于登录",1791512160000L))
    }
}
