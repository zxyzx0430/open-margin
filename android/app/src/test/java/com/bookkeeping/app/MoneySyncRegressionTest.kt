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

    @Test fun smsExactCentsAndNoFalseOtp() {
        val bank="【招商银行】尾号1234消费人民币25.81元，余额1234.56元"
        val tx=SmsParser.parse(null,bank,1791512160000L)
        assertEquals(2581L,tx!!.amountCents)
        assertNull(SmsParser.parse(null,"验证码 123456，用于登录",1791512160000L))
    }
}
