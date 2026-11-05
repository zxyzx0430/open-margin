package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Field-level contract for bank SMS -> TransactionEntity extraction.
class SmsParserTest {

    private val ts = 1_700_000_000_000L

    private fun parse(body: String) = SmsParser.parse("+8613800000000", body, ts)

    @Test fun icbcExpensePreservesEveryUploadedField() {
        val tx = parse("【工商银行】您尾号1234的工银信用卡，于06月15日18:30消费人民币25.00元，余额1234.56元")!!
        assertEquals(2500L, tx.amountCents)
        assertEquals(TransactionType.EXPENSE.name, tx.type)
        assertEquals(TransactionCategory.OTHER.name, tx.category)
        assertEquals("", tx.merchant)
        assertEquals(TransactionSource.BANK_SMS.name, tx.source)
        assertEquals("工商银行 (1234)", tx.account)
        assertEquals("CNY", tx.currency)
        assertEquals(ts, tx.timestamp)
        assertTrue(tx.note.startsWith("[银行短信]"))
    }

    @Test fun cmbExpenseAndAccountTail() {
        val tx = parse("【招商银行】您尾号1234招行一卡通06月15日15:20消费人民币25.00元")!!
        assertEquals(2500L, tx.amountCents)
        assertEquals(TransactionType.EXPENSE.name, tx.type)
        assertEquals("招商银行 (1234)", tx.account)
    }

    @Test fun ccbSalaryIsIncomeAndSalaryCategory() {
        val tx = parse("【建设银行】您尾号5678账户12月25日 工资到账人民币 8000.00元")!!
        assertEquals(800_000L, tx.amountCents)
        assertEquals(TransactionType.INCOME.name, tx.type)
        assertEquals(TransactionCategory.SALARY.name, tx.category)
        assertEquals("建设银行 (5678)", tx.account)
    }

    @Test fun bocIncomingTransferIsIncomeAndTransferCategory() {
        val tx = parse("【中国银行】您尾号9876账户 06月15日 转入人民币 500.00元")!!
        assertEquals(50_000L, tx.amountCents)
        assertEquals(TransactionType.INCOME.name, tx.type)
        assertEquals(TransactionCategory.TRANSFER.name, tx.category)
        assertEquals("中国银行 (9876)", tx.account)
    }

    @Test fun balanceBeforeTransactionAmountIsSkipped() {
        val tx = parse("【工商银行】您尾号1234账户余额1234.56元，消费人民币25.00元")!!
        assertEquals(2500L, tx.amountCents)
    }

    @Test fun thousandsSeparatorsAreParsedExactly() {
        assertEquals(123_456L, parse("【工商银行】您尾号1234账户消费人民币1,234.56元")!!.amountCents)
        assertEquals(1_234_567L, parse("【招商银行】您尾号1234账户转入12,345.67元")!!.amountCents)
    }

    @Test fun cardTailWithWeiIsRecognized() {
        val tx = parse("【工商银行】您尾号为1234的账户消费人民币25.00元")!!
        assertEquals("工商银行 (1234)", tx.account)
    }

    @Test fun bankAliasesAreNormalized() {
        assertEquals("工商银行 (1234)", parse("【工行】您尾号1234账户消费25.00元")!!.account)
        assertEquals("招商银行 (1234)", parse("【招行】您尾号1234账户消费25.00元")!!.account)
    }

    @Test fun nonBankMessagesAreRejected() {
        assertNull(parse("【验证码】您的验证码是123456，5分钟内有效"))
        assertNull(parse("【某平台】您的订单已发货，消费满100元返50元"))
    }

    @Test fun promotionalMessagesAreRejected() {
        assertNull(parse("【工商银行】信用卡消费满100元返50元，活动详情点击查看"))
        assertNull(parse("【招商银行】消费满1000元可参与抽奖，积分兑换好礼"))
    }

    @Test fun failedOrReversedTransactionsAreRejected() {
        assertNull(parse("【工商银行】您尾号1234账户消费25.00元失败"))
        assertNull(parse("【招商银行】您尾号1234账户支付25.00元交易已撤销"))
    }

    @Test fun pendingAuthorizationIsRejected() {
        assertNull(parse("【工商银行】您尾号1234账户预授权冻结100.00元"))
    }

    @Test fun directionNearestToAmountWins() {
        val first = parse("【工商银行】您尾号1234账户转入500.00元，随后转出200.00元")!!
        assertEquals(TransactionType.INCOME.name, first.type)
        assertEquals(50_000L, first.amountCents)
        val second = parse("【工商银行】您尾号1234账户转出200.00元，随后转入500.00元")!!
        assertEquals(TransactionType.EXPENSE.name, second.type)
        assertEquals(20_000L, second.amountCents)
    }

    @Test fun zeroAndOverflowAmountsAreRejected() {
        assertNull(parse("【工商银行】您尾号1234账户消费0.00元"))
        assertNull(parse("【工商银行】您尾号1234账户消费999999999999.99元"))
    }

    @Test fun fullWidthDigitsAreRejectedNotMisparsed() {
        assertNull(parse("【工商银行】您尾号1234账户消费２５.００元"))
    }

    @Test fun longTextAndUnicodeDoNotBreakExtraction() {
        val tx = parse("【工商银行】您尾号1234账户在🍔店消费25.00元 " + "啊".repeat(100_000))!!
        assertEquals(2500L, tx.amountCents)
        assertTrue(tx.note.length <= 90)
    }
}
