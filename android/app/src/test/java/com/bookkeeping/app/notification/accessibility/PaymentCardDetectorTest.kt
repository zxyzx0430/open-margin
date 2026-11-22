package com.bookkeeping.app.notification.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PaymentCardDetectorTest {

    private val capturedAt = 1_795_000_000_000L

    private fun line(text: String, top: Int): BillTextLine =
        BillTextLine(text = text, top = top, bottom = top + 40, left = 40, right = 1000)

    private fun lines(vararg entries: Pair<String, Int>): List<BillTextLine> =
        entries.map { line(it.first, it.second) }

    private fun detect(
        packageName: String,
        lines: List<BillTextLine>,
        pageIdentity: String = "test-page",
    ): List<PaymentCardDetection> =
        PaymentCardDetector.fromLines(packageName, lines, capturedAt, 2400, pageIdentity)

    private fun wechatLines(vararg entries: Pair<String, Int>) =
        detect(PaymentCardDetector.WECHAT, lines(*entries))

    private fun alipayLines(vararg entries: Pair<String, Int>) =
        detect(PaymentCardDetector.ALIPAY, lines(*entries))

    // --- payments ----------------------------------------------------------

    @Test
    fun parsesWechatWalletPaymentCard() {
        val cards = wechatLines(
            "昨天 21:21" to 100,
            "自来充" to 160,
            "使用零钱支付" to 240,
            "¥6.00" to 320,
            "账单详情" to 400,
        )
        val card = cards.single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
        assertEquals(600L, card.amountCents)
        assertEquals(listOf("自来充"), card.merchantCandidates)
        assertTrue(card.isFinal)
        assertNull(card.nonFinalReason)
        assertNull(card.orderId)
    }

    @Test
    fun parsesWechatBankCardPaymentCard() {
        val card = wechatLines(
            "今天 12:05" to 100,
            "杭州游卡" to 160,
            "使用银行卡支付" to 240,
            "¥18.00" to 320,
            "交易详情" to 400,
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(1800L, card.amountCents)
        assertEquals(listOf("杭州游卡"), card.merchantCandidates)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
    }

    @Test
    fun parsesAlipayScanPaymentPageWithOrderId() {
        val card = alipayLines(
            "10:24" to 100,
            "杭州游卡" to 160,
            "交易成功" to 240,
            "￥6.00" to 320,
            "交易号 2025111422001234567890" to 400,
            "付款方式 余额宝" to 480,
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(600L, card.amountCents)
        assertEquals(listOf("杭州游卡"), card.merchantCandidates)
        assertEquals("2025111422001234567890", card.orderId)
        assertNotNull(card.eventTimeMs)
    }

    @Test
    fun parsesAlipayBillListPaymentCard() {
        val card = alipayLines(
            "10:24" to 100,
            "饿了么" to 160,
            "支出 ￥25.00" to 240,
            "交易成功" to 320,
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(2500L, card.amountCents)
        assertEquals(listOf("饿了么"), card.merchantCandidates)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
    }

    @Test
    fun usesLabeledPayeeForAlipayPayment() {
        val card = alipayLines(
            "10:24" to 100,
            "收款方 杭州游卡" to 160,
            "付款成功" to 240,
            "￥6.00" to 320,
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(listOf("杭州游卡"), card.merchantCandidates)
    }

    // --- transfers ---------------------------------------------------------

    @Test
    fun parsesWechatIncomingTransfer() {
        val card = wechatLines(
            "昨天 18:00" to 100,
            "微信转账" to 160,
            "¥100.00" to 240,
            "已收钱" to 320,
        ).single()
        assertEquals(PaymentCardKind.TRANSFER, card.kind)
        assertEquals(PaymentDirection.INCOME, card.direction)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(10000L, card.amountCents)
        assertTrue(card.isFinal)
        assertTrue(card.merchantCandidates.isEmpty())
    }

    @Test
    fun parsesWechatOutgoingTransferPendingAsNonFinal() {
        val card = wechatLines(
            "14:20" to 100,
            "转账给张三" to 160,
            "¥50.00" to 240,
            "等待对方确认" to 320,
        ).single()
        assertEquals(PaymentCardKind.TRANSFER, card.kind)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
        assertEquals(PaymentCardStatus.PENDING, card.status)
        assertEquals(5000L, card.amountCents)
        assertEquals(listOf("张三"), card.merchantCandidates)
        assertFalse(card.isFinal)
        assertNotNull(card.nonFinalReason)
    }

    @Test
    fun parsesAlipayIncomingTransferWithCounterpart() {
        val card = alipayLines(
            "14:20" to 100,
            "转账-来自张三" to 160,
            "收入 ￥50.00" to 240,
            "已到账" to 320,
        ).single()
        assertEquals(PaymentCardKind.TRANSFER, card.kind)
        assertEquals(PaymentDirection.INCOME, card.direction)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(5000L, card.amountCents)
        assertEquals(listOf("张三"), card.merchantCandidates)
    }

    @Test
    fun parsesAlipayOutgoingTransferWithCounterpart() {
        val card = alipayLines(
            "09:10" to 100,
            "转账-转账给李四" to 160,
            "支出 ￥20.00" to 240,
            "交易成功" to 320,
        ).single()
        assertEquals(PaymentCardKind.TRANSFER, card.kind)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(2000L, card.amountCents)
        assertEquals(listOf("李四"), card.merchantCandidates)
    }

    // --- red packets -------------------------------------------------------

    @Test
    fun parsesReceivedRedPacketAsIncome() {
        val card = wechatLines(
            "昨天 20:10" to 100,
            "微信红包" to 160,
            "恭喜发财，大吉大利" to 240,
            "¥8.88" to 320,
            "已存入零钱" to 400,
        ).single()
        assertEquals(PaymentCardKind.RED_PACKET, card.kind)
        assertEquals(PaymentDirection.INCOME, card.direction)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(888L, card.amountCents)
        assertTrue(card.merchantCandidates.isEmpty())
        assertTrue(card.isFinal)
    }

    @Test
    fun parsesSentRedPacketAsExpense() {
        val card = wechatLines(
            "昨天 20:10" to 100,
            "你发出的红包" to 160,
            "恭喜发财" to 240,
            "¥8.88" to 320,
            "已领取" to 400,
        ).single()
        assertEquals(PaymentCardKind.RED_PACKET, card.kind)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(888L, card.amountCents)
    }

    @Test
    fun unopenedReceivedRedPacketIsNonFinal() {
        val card = wechatLines(
            "12:00" to 100,
            "微信红包" to 160,
            "恭喜发财" to 240,
            "¥8.88" to 320,
            "待领取" to 400,
        ).single()
        assertEquals(PaymentCardKind.RED_PACKET, card.kind)
        assertEquals(PaymentDirection.INCOME, card.direction)
        assertEquals(PaymentCardStatus.PENDING, card.status)
        assertFalse(card.isFinal)
        assertNotNull(card.nonFinalReason)
    }

    // --- withdrawals and refunds ------------------------------------------

    @Test
    fun parsesWechatWithdrawal() {
        val card = wechatLines(
            "昨天 09:00" to 100,
            "零钱提现" to 160,
            "提现到银行卡" to 240,
            "¥100.00" to 320,
            "已到账" to 400,
        ).single()
        assertEquals(PaymentCardKind.WITHDRAWAL, card.kind)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(10000L, card.amountCents)
    }

    @Test
    fun parsesAlipayWithdrawal() {
        val card = alipayLines(
            "09:00" to 100,
            "提现到银行卡" to 160,
            "提现金额 ￥100.00" to 240,
            "提现成功" to 320,
        ).single()
        assertEquals(PaymentCardKind.WITHDRAWAL, card.kind)
        assertEquals(10000L, card.amountCents)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
    }

    @Test
    fun parsesWechatRefundArrivalWithLabeledTime() {
        val card = wechatLines(
            "05:27" to 100,
            "退款到账通知" to 180,
            "退款金额" to 260,
            "¥1.45" to 340,
            "商品详情" to 420,
            "充电订单-预付6元" to 480,
            "商户名称" to 540,
            "自来充" to 600,
            "退款方式" to 660,
            "退回零钱" to 720,
            "到账时间" to 780,
            "2026年11月14日 05:26:54" to 840,
        ).single()
        assertEquals(PaymentCardKind.REFUND, card.kind)
        assertEquals(PaymentDirection.INCOME, card.direction)
        assertEquals(145L, card.amountCents)
        assertEquals(listOf("自来充"), card.merchantCandidates)
        val time = Calendar.getInstance().apply { timeInMillis = card.eventTimeMs!! }
        assertEquals(2026, time.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, time.get(Calendar.MONTH))
        assertEquals(14, time.get(Calendar.DAY_OF_MONTH))
        assertEquals(5, time.get(Calendar.HOUR_OF_DAY))
        assertEquals(26, time.get(Calendar.MINUTE))
    }

    @Test
    fun parsesAlipayRefund() {
        val card = alipayLines(
            "11:20" to 100,
            "退款成功" to 160,
            "退款金额 ￥12.00" to 240,
            "商户名称 杭州游卡" to 320,
            "退款方式 原路退回" to 400,
        ).single()
        assertEquals(PaymentCardKind.REFUND, card.kind)
        assertEquals(PaymentDirection.INCOME, card.direction)
        assertEquals(1200L, card.amountCents)
        assertEquals(listOf("杭州游卡"), card.merchantCandidates)
        assertTrue(card.isFinal)
    }

    @Test
    fun refundFailureIsRejected() {
        val cards = wechatLines(
            "退款失败" to 100,
            "退款金额" to 180,
            "¥1.45" to 240,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun pendingRefundIsNonFinal() {
        val card = alipayLines(
            "11:20" to 100,
            "退款处理中" to 160,
            "退款金额 ￥12.00" to 240,
        ).single()
        assertEquals(PaymentCardKind.REFUND, card.kind)
        assertEquals(PaymentCardStatus.PENDING, card.status)
        assertFalse(card.isFinal)
        assertNotNull(card.nonFinalReason)
    }

    @Test
    fun pendingRefundApplicationIsNonFinal() {
        val card = alipayLines(
            "11:20" to 100,
            "退款申请已提交" to 160,
            "退款金额 ￥12.00" to 240,
            "等待商家处理" to 320,
        ).single()
        assertEquals(PaymentCardKind.REFUND, card.kind)
        assertEquals(PaymentCardStatus.PENDING, card.status)
        assertFalse(card.isFinal)
        assertNotNull(card.nonFinalReason)
    }

    @Test
    fun pendingPaymentIsNonFinal() {
        val card = wechatLines(
            "10:00" to 100,
            "便利店" to 160,
            "支付处理中" to 240,
            "¥12.00" to 320,
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(PaymentCardStatus.PENDING, card.status)
        assertFalse(card.isFinal)
    }

    @Test
    fun payeeLabelIsNotMistakenForIncome() {
        val card = alipayLines(
            "10:24" to 100,
            "收款方 杭州游卡" to 160,
            "交易成功" to 240,
            "￥6.00" to 320,
        ).single()
        assertEquals(PaymentDirection.EXPENSE, card.direction)
    }

    @Test
    fun bareBadgeNumberIsNotTreatedAsMoney() {
        val cards = wechatLines(
            "支付成功" to 100,
            "3" to 180,
            "完成" to 260,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun refundMerchantStripsRefundSuffixAndIgnoresListChrome() {
        val card = wechatLines(
            "更多信息" to 80,
            "查找交易" to 140,
            "收支统计" to 200,
            "2026年11月" to 260,
            "自来充-退款" to 320,
            "11月14日 05:26" to 380,
            "+1.45" to 440,
        ).single()
        assertEquals(PaymentCardKind.REFUND, card.kind)
        assertEquals(listOf("自来充"), card.merchantCandidates)
        assertEquals(145L, card.amountCents)
    }

    // --- pay after use -----------------------------------------------------

    @Test
    fun payAfterUseCreatedIsNotBooked() {
        val card = wechatLines(
            "14:00" to 100,
            "拼多多平台商户" to 160,
            "先用后付订单已生成" to 240,
            "¥39.90" to 320,
            "下单时间 11月3日 23:36" to 400,
        ).single()
        assertEquals(PaymentCardKind.PAY_AFTER_USE, card.kind)
        assertEquals(PaymentCardStatus.CREATED, card.status)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
        assertEquals(3990L, card.amountCents)
        assertEquals(listOf("拼多多平台商户"), card.merchantCandidates)
        assertFalse(card.isFinal)
        assertNotNull(card.nonFinalReason)
    }

    @Test
    fun payAfterUseCompletedIsFinal() {
        val card = wechatLines(
            "15:31" to 100,
            "拼多多平台商户" to 160,
            "先用后付订单已完成，已自动支付" to 240,
            "通过零钱扣款" to 320,
            "¥8.40" to 400,
        ).single()
        assertEquals(PaymentCardKind.PAY_AFTER_USE, card.kind)
        assertEquals(PaymentCardStatus.COMPLETED, card.status)
        assertEquals(840L, card.amountCents)
        assertTrue(card.isFinal)
        assertNull(card.nonFinalReason)
        val time = Calendar.getInstance().apply { timeInMillis = card.eventTimeMs!! }
        assertEquals(15, time.get(Calendar.HOUR_OF_DAY))
        assertEquals(31, time.get(Calendar.MINUTE))
    }

    @Test
    fun payAfterUseCancelledIsNotBooked() {
        val card = wechatLines(
            "16:00" to 100,
            "拼多多平台商户" to 160,
            "先用后付订单已取消" to 240,
            "¥39.90" to 320,
        ).single()
        assertEquals(PaymentCardKind.PAY_AFTER_USE, card.kind)
        assertEquals(PaymentCardStatus.CANCELLED, card.status)
        assertEquals(3990L, card.amountCents)
        assertFalse(card.isFinal)
        assertNotNull(card.nonFinalReason)
        assertTrue(card.reason.contains("取消"))
    }

    // --- grouping and missing fields --------------------------------------

    @Test
    fun multipleCardsKeepTheirOwnMerchantAndAmount() {
        val cards = wechatLines(
            "10:00" to 100,
            "早餐店" to 160,
            "使用零钱支付" to 240,
            "¥10.00" to 320,
            "账单详情" to 400,
            "10:30" to 700,
            "咖啡店" to 760,
            "使用银行卡支付" to 840,
            "¥25.00" to 920,
            "账单详情" to 1000,
        )
        assertEquals(2, cards.size)
        assertEquals(listOf("早餐店"), cards.single { it.amountCents == 1000L }.merchantCandidates)
        assertEquals(listOf("咖啡店"), cards.single { it.amountCents == 2500L }.merchantCandidates)
    }

    @Test
    fun timeHeaderSplitsCardsWithoutALargeGap() {
        val cards = wechatLines(
            "10:00" to 100,
            "早餐店" to 160,
            "使用零钱支付" to 240,
            "¥10.00" to 320,
            "10:30" to 380,
            "咖啡店" to 440,
            "使用银行卡支付" to 520,
            "¥25.00" to 600,
        )
        assertEquals(2, cards.size)
        assertEquals(listOf("早餐店"), cards.single { it.amountCents == 1000L }.merchantCandidates)
        assertEquals(listOf("咖啡店"), cards.single { it.amountCents == 2500L }.merchantCandidates)
    }

    @Test
    fun adjacentSuccessfulAndCancelledCardsKeepTheirOwnStatusWithZeroBounds() {
        val rawLines = listOf(
            BillTextLine("支付成功"),
            BillTextLine("Fixture breakfast shop"),
            BillTextLine("¥10.00"),
            BillTextLine("交易关闭"),
            BillTextLine("Fixture coffee shop"),
            BillTextLine("¥25.00"),
        )

        val cards = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = rawLines,
            capturedAtMs = capturedAt,
            screenHeightPx = 2400,
            pageIdentity = "fixture-zero-bounds",
        )

        assertEquals(2, cards.size)
        assertEquals(PaymentCardStatus.COMPLETED, cards.single { it.amountCents == 1000L }.status)
        assertEquals(PaymentCardStatus.CANCELLED, cards.single { it.amountCents == 2500L }.status)
    }

    @Test
    fun adjacentSuccessfulCardsWithZeroGapRemainSeparateCandidates() {
        val cards = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.ALIPAY,
            lines = listOf(
                line("支付成功", 10),
                line("Fixture breakfast shop", 40),
                line("¥10.00", 70),
                line("交易成功", 100),
                line("Fixture coffee shop", 130),
                line("¥20.00", 160),
            ),
            capturedAtMs = capturedAt,
            screenHeightPx = 600,
            pageIdentity = "fixture-adjacent-success-cards",
        )

        assertEquals(2, cards.size)
        assertEquals(setOf(1_000L, 2_000L), cards.map { it.amountCents }.toSet())
        assertTrue(cards.all { it.status == PaymentCardStatus.COMPLETED })
    }

    @Test
    fun zeroBoundsWithMultipleAmountsReturnSeparateReviewCandidatesInsteadOfOneCombinedCard() {
        val cards = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = listOf(
                BillTextLine("支付成功"),
                BillTextLine("¥10.00"),
                BillTextLine("¥20.00"),
            ),
            capturedAtMs = capturedAt,
            screenHeightPx = 2400,
            pageIdentity = "fixture-ambiguous-bounds",
        )

        assertEquals(listOf(1000L, 2000L), cards.map { it.amountCents })
        assertTrue(cards.all { it.reviewRequired })
        assertTrue(cards.all { it.status == PaymentCardStatus.UNKNOWN })
    }

    @Test
    fun yearlessJanuaryTransactionOnDecemberCaptureUsesFollowingYear() {
        withShanghaiTimeZone {
            val capture = fixedInstant(2025, Calendar.DECEMBER, 31, 23, 55)
            val card = datedPayment("交易时间 1月1日 00:05", capture)
            val time = Calendar.getInstance().apply { timeInMillis = card.eventTimeMs!! }

            assertEquals(2026, time.get(Calendar.YEAR))
            assertEquals(Calendar.JANUARY, time.get(Calendar.MONTH))
            assertEquals(1, time.get(Calendar.DAY_OF_MONTH))
            assertEquals(5, time.get(Calendar.MINUTE))
        }
    }

    @Test
    fun yearlessDecemberTransactionOnJanuaryCaptureUsesPreviousYear() {
        withShanghaiTimeZone {
            val capture = fixedInstant(2026, Calendar.JANUARY, 1, 0, 5)
            val card = datedPayment("交易时间 12月31日 23:55", capture)
            val time = Calendar.getInstance().apply { timeInMillis = card.eventTimeMs!! }

            assertEquals(2025, time.get(Calendar.YEAR))
            assertEquals(Calendar.DECEMBER, time.get(Calendar.MONTH))
            assertEquals(31, time.get(Calendar.DAY_OF_MONTH))
            assertEquals(55, time.get(Calendar.MINUTE))
        }
    }

    private fun datedPayment(timeLine: String, capturedAtMs: Long): PaymentCardDetection {
        val lines = listOf(
            line("支付成功", 100),
            line("Fixture merchant", 160),
            line("¥10.00", 220),
            line(timeLine, 280),
        )
        return PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = lines,
            capturedAtMs = capturedAtMs,
            screenHeightPx = 2400,
            pageIdentity = "fixture-year-rollover",
        ).single()
    }

    private fun fixedInstant(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    private inline fun <T> withShanghaiTimeZone(block: () -> T): T {
        val original = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun cardWithoutMerchantStillReturnsAmount() {
        val card = wechatLines(
            "支付成功" to 100,
            "¥9.99" to 180,
        ).single()
        assertEquals(999L, card.amountCents)
        assertTrue(card.merchantCandidates.isEmpty())
        assertNull(card.orderId)
        assertNull(card.eventTimeMs)
    }

    @Test
    fun cardWithoutAmountProducesNothing() {
        val cards = wechatLines(
            "支付成功" to 100,
            "商户 A" to 180,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun ambiguousStandaloneAmountsProduceNothing() {
        val cards = wechatLines(
            "支付成功" to 100,
            "¥10.00" to 180,
            "¥20.00" to 260,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun summaryHeaderIsNotTreatedAsACard() {
        val cards = wechatLines(
            "本月统计" to 80,
            "11月已支出 ¥4251.87" to 140,
            "查看明细" to 200,
            "日报设置" to 260,
            "自来充" to 700,
            "使用零钱支付" to 780,
            "¥6.00" to 860,
            "账单详情" to 940,
        )
        assertEquals(1, cards.size)
        assertEquals(600L, cards.single().amountCents)
        assertEquals(listOf("自来充"), cards.single().merchantCandidates)
    }

    @Test
    fun summaryAmountDoesNotOverrideCardAmount() {
        val card = wechatLines(
            "支付成功" to 100,
            "商户 A" to 160,
            "¥10.00" to 220,
            "本月已支出 ¥500.00" to 300,
            "账单详情" to 360,
        ).single()
        assertEquals(1000L, card.amountCents)
        assertEquals(listOf("商户 A"), card.merchantCandidates)
    }

    @Test
    fun monthlyTransferSummaryIsNotACard() {
        val cards = wechatLines(
            "本月转账 ¥500.00" to 100,
            "查看明细" to 180,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun ordinaryBillFallbackNeedsBillPageContext() {
        val billLines = lines(
            "账单" to 100,
            "10:00" to 500,
            "便利店" to 560,
            "¥12.50" to 640,
            "订单号 202511140001" to 720,
        )
        val card = detect(PaymentCardDetector.WECHAT, billLines).single()
        assertEquals(PaymentCardKind.BILL, card.kind)
        assertEquals(1250L, card.amountCents)
        assertEquals(listOf("便利店"), card.merchantCandidates)
        assertEquals("202511140001", card.orderId)
        assertEquals(PaymentDirection.UNKNOWN, card.direction)
        assertFalse(card.isFinal)

        val withoutBillContext = lines(
            "10:00" to 500,
            "便利店" to 560,
            "¥12.50" to 640,
            "订单号 202511140001" to 720,
        )
        assertTrue(detect(PaymentCardDetector.WECHAT, withoutBillContext).isEmpty())
    }

    // --- input adapters and identity --------------------------------------

    @Test
    fun ocrFallbackRecognizesNotificationlessPages() {
        assertTrue(
            PaymentCardDetector.looksLikePaymentPage(
                PaymentCardDetector.WECHAT,
                "微信支付 退款到账通知 退款金额 ¥1.45 商户名称 自来充",
            ),
        )
        assertTrue(
            PaymentCardDetector.looksLikePaymentPage(
                PaymentCardDetector.ALIPAY,
                "账单详情 转账 来自张三 收入 ￥50.00 已到账",
            ),
        )
        assertTrue(
            PaymentCardDetector.looksLikePaymentPage(
                PaymentCardDetector.ALIPAY,
                "付款成功 ￥6.00 杭州游卡",
            ),
        )
        assertTrue(
            PaymentCardDetector.looksLikePaymentPage(
                PaymentCardDetector.WECHAT,
                "微信红包 恭喜发财 ¥8.88 已存入零钱",
            ),
        )
        assertTrue(
            PaymentCardDetector.looksLikePaymentPage(
                PaymentCardDetector.WECHAT,
                "拼多多平台商户 管理扣费服务 查看商家订单",
            ),
        )
        assertFalse(
            PaymentCardDetector.looksLikePaymentPage(
                PaymentCardDetector.WECHAT,
                "微信支付 收款码 保存到相册",
            ),
        )
        assertFalse(
            PaymentCardDetector.looksLikePaymentPage(
                PaymentCardDetector.WECHAT,
                "张三：晚上一起吃饭吗",
            ),
        )
    }

    @Test
    fun supportsAlipayAccessibilitySnapshot() {
        fun node(text: String, top: Int): SnapshotNode = SnapshotNode(
            text = text,
            bounds = SnapshotRect(40, top, 1000, top + 40),
        )
        val snapshot = ScreenSnapshot.of(
            PaymentCardDetector.ALIPAY,
            capturedAt,
            SnapshotNode(
                children = listOf(
                    node("10:24", 100),
                    node("杭州游卡", 160),
                    node("交易成功", 240),
                    node("￥6.00", 320),
                    node("交易号 2025111422001234567890", 400),
                ),
            ),
        )
        val card = PaymentCardDetector.fromSnapshot(
            PaymentCardDetector.ALIPAY,
            snapshot,
            capturedAt,
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(600L, card.amountCents)
        assertEquals(listOf("杭州游卡"), card.merchantCandidates)
        assertTrue(card.pageIdentity.startsWith(PaymentCardDetector.ALIPAY))
    }

    @Test
    fun supportsOcrLikeTextInput() {
        val cards = PaymentCardDetector.fromText(
            PaymentCardDetector.WECHAT,
            """
            昨天 21:21
            自来充
            使用零钱支付
            ¥6.00
            账单详情
            """.trimIndent(),
            capturedAt,
            "ocr-page",
        )
        val card = cards.single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(600L, card.amountCents)
        assertEquals("ocr-page", card.pageIdentity)
    }

    @Test
    fun cardKeyIsStableWithinAPageAndChangesAcrossPages() {
        val cardLines = lines(
            "支付成功" to 100,
            "商户 A" to 160,
            "¥15.24" to 220,
        )
        val first = detect(PaymentCardDetector.WECHAT, cardLines, "page-1").single()
        val second = detect(PaymentCardDetector.WECHAT, cardLines, "page-1").single()
        val otherPage = detect(PaymentCardDetector.WECHAT, cardLines, "page-2").single()
        assertEquals(first.cardKey, second.cardKey)
        assertNotEquals(first.cardKey, otherPage.cardKey)
        assertEquals("page-1", first.pageIdentity)
    }

    @Test
    fun chatServicePreviewIsNotAPaymentCard() {
        val chatLines = lines(
            "微信支付" to 100,
            "昨天" to 140,
            "已支付¥2.00" to 180,
            "615 宿舍" to 300,
            "昨天" to 340,
            "神经研究所邑大分所" to 380,
        )
        assertTrue(detect(PaymentCardDetector.WECHAT, chatLines).isEmpty())
    }

    @Test
    fun chatExpensePreviewIsNotAPaymentCard() {
        val chatLines = lines(
            "微信支付" to 100,
            "昨天" to 140,
            "消费¥2.00" to 180,
            "615 宿舍" to 300,
            "昨天" to 340,
        )
        assertTrue(detect(PaymentCardDetector.WECHAT, chatLines).isEmpty())
    }

    @Test
    fun groupCollectionWithoutOwnPaymentIsNotACard() {
        val negative = lines(
            "群收款" to 100,
            "¥54.70" to 160,
            "你无需支付" to 220,
            "他人已支付" to 280,
        )
        assertTrue(detect(PaymentCardDetector.WECHAT, negative).isEmpty())
        val summary = lines(
            "群收款" to 100,
            "¥54.70" to 160,
            "昨天 18:00" to 220,
        )
        assertTrue(detect(PaymentCardDetector.WECHAT, summary).isEmpty())
    }

    @Test
    fun groupCollectionOwnCompletedPaymentIsStillABill() {
        val card = detect(
            PaymentCardDetector.WECHAT,
            lines(
                "群收款" to 100,
                "支付成功" to 160,
                "¥54.70" to 220,
            ),
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(5470L, card.amountCents)
    }

    @Test
    fun weakExpenseWithMerchantContextIsStillABill() {
        val card = detect(
            PaymentCardDetector.ALIPAY,
            lines(
                "支出 ¥25.00" to 100,
                "商户名称 饿了么" to 160,
            ),
        ).single()
        assertEquals(PaymentCardKind.PAYMENT, card.kind)
        assertEquals(2500L, card.amountCents)
    }

    @Test
    fun unsupportedPackageIsIgnored() {
        val cardLines = lines(
            "支付成功" to 100,
            "商户 A" to 160,
            "¥15.24" to 220,
        )
        assertTrue(detect("com.example.other", cardLines).isEmpty())
        assertFalse(PaymentCardDetector.isSupported("com.example.other"))
        assertTrue(PaymentCardDetector.isSupported(PaymentCardDetector.WECHAT))
        assertTrue(PaymentCardDetector.isSupported(PaymentCardDetector.ALIPAY))
    }
}
