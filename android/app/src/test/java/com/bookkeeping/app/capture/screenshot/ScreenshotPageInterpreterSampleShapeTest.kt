package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Page shapes found while walking the seven real screenshots with local
 * Tesseract text only. Nothing here stores image bytes or a raw OCR dump: each
 * page is rebuilt from the handful of lines the interpreter actually reads, so
 * the parsing rules are pinned without putting a screenshot in git or a log.
 */
class ScreenshotPageInterpreterSampleShapeTest {

    private val capturedAt = 1795064400000L // 2026-11-19 13:00 Asia/Shanghai
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private companion object {
        const val FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, bottom = top + 20)

    private fun blockAt(text: String, left: Int, top: Int, right: Int) =
        TextBlock(text = text, left = left, top = top, right = right, bottom = top + 20)

    private fun plan(blocks: List<TextBlock>) = ScreenshotImportPipeline.plan(
        request = ScreenshotIntakeRequest(
            source = IntakeSource.SHARE,
            uri = "content://media/external/images/media/7",
            mimeType = "image/png",
            capturedAtMs = capturedAt,
            byteSize = 300_000L,
            imageFingerprint = FINGERPRINT,
        ),
        blocks = blocks,
        existing = emptyList(),
        timeZone = zone,
    )

    /**
     * Refund result page: the detail row is narrative ("充电订单-预付4元"), the
     * amount appears twice, and the app footer carries two unrelated numbers.
     */
    private fun refundPage() = listOf(
        block("微信支付", 10),
        block("退款成功", 40),
        block("¥1.85", 70),
        block("退款金额 ¥1.85", 100),
        block("商品详情 充电订单-预付4元", 130),
        block("记账日报", 160),
        block("昨日支出 4.50", 180),
        block("本月统计 509.61", 200),
    )

    /**
     * 微信支付分 先用后付 page: nothing is charged until the order is confirmed.
     * Built from the rows that were actually quoted, deliberately without the
     * app name, because the header may sit outside the cropped screenshot.
     */
    private fun futureDebitPage() = listOf(
        block("订单已生成", 40),
        block("预计扣费金额 9.65元", 80),
        block("11月29日自动确认收货后扣款", 110),
        block("先用后付", 140),
        block("订单金额 ¥10.00", 170),
    )

    /** One screenshot with two independent merchant payments. */
    private fun twoMerchantPage() = listOf(
        block("支付宝", 10),
        block("星巴克咖啡", 40),
        block("¥48.00", 70),
        block("支付成功", 100),
        block("瑞幸咖啡", 130),
        block("¥17.90", 160),
        block("支付成功", 190),
    )

    /** Chat style transfer list: one cancelled transfer, one received transfer. */
    private fun transferChatPage() = listOf(
        block("微信支付", 10),
        block("转账给 张三", 40),
        block("¥200.00", 70),
        block("对方已取消", 100),
        block("转账给 李四", 130),
        block("¥200.00", 160),
        block("已收款", 190),
    )

    private fun googleOnePage() = listOf(
        block("Google One", 10),
        block("学生优惠", 40),
        block("每月仅需", 70),
        block("¥23.00", 100),
        block("100 GB 存储空间", 130),
        block("随时可以取消", 160),
    )

    private fun wechatLoginNotice() = listOf(
        block("微信团队", 10),
        block("登录通知", 40),
        block("你的微信于 2026-11-19 20:15 在新设备登录", 70),
        block("登录设备：Windows", 100),
        block("登录地点：中国 深圳", 130),
        block("验证码 123456", 160),
    )

    @Test
    fun `only currency lines and labelled amounts are money`() {
        assertEquals(4800L, ScreenshotPageInterpreter.amountOf("¥48.00"))
        assertEquals(4800L, ScreenshotPageInterpreter.amountOf("¥48.00 支付成功"))
        assertEquals(3650L, ScreenshotPageInterpreter.amountOf("-¥36.50"))
        assertEquals(185L, ScreenshotPageInterpreter.amountOf("¥ 1.85"))
        assertEquals(4800L, ScreenshotPageInterpreter.amountOf("48.00元"))
        assertEquals(185L, ScreenshotPageInterpreter.amountOf("退款金额 ¥1.85"))
        assertEquals(965L, ScreenshotPageInterpreter.amountOf("预计扣费金额 9.65元"))
        // Narration, footer statistics and timestamps are not transactions.
        assertNull(ScreenshotPageInterpreter.amountOf("商品详情 充电订单-预付4元"))
        assertNull(ScreenshotPageInterpreter.amountOf("昨日支出 4.50"))
        assertNull(ScreenshotPageInterpreter.amountOf("本月统计 509.61"))
        assertNull(ScreenshotPageInterpreter.amountOf("11月29日自动确认收货后扣款"))
        assertNull(ScreenshotPageInterpreter.amountOf("验证码 123456"))
    }

    @Test
    fun `refund page produces one refund draft and ignores narration and statistics`() {
        val blocks = refundPage()
        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)
        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(185L, draft.amountCents)
        assertEquals(CaptureCandidate.KIND_REFUND, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)
        assertTrue(draft.supportedPaymentPage)
        assertFalse(evidence.conflictingFields)

        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        assertTrue(result.decisions.none { it.draft.amountCents == 400L })
        // A landed refund is real money, but it is not ordinary income: it is
        // only written after the user confirms.
        assertEquals(Action.ASK_USER, result.decisions.single().action)
        assertTrue(result.bookings.isEmpty())
        assertEquals(1, result.needsConfirmation.size)
    }

    @Test
    fun `future debit page keeps one expected amount and waits for evidence`() {
        val blocks = futureDebitPage()
        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)
        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(965L, draft.amountCents)
        assertEquals(CaptureCandidate.COMPLETION_CREATED, draft.completionStatus)
        assertTrue(draft.completionExplicit)
        assertFalse(evidence.conflictingFields)

        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        assertEquals(Action.WAIT_FOR_EVIDENCE, result.decisions.single().action)
        assertEquals(965L, result.decisions.single().draft.amountCents)
        assertTrue(result.bookings.isEmpty())
        assertTrue(result.needsConfirmation.isEmpty())
    }

    @Test
    fun `two merchant payments stay two independent completed drafts`() {
        val blocks = twoMerchantPage()
        val result = plan(blocks)
        assertEquals(2, result.decisions.size)
        val byAmount = result.decisions.associateBy { it.draft.amountCents }
        assertEquals(setOf(4800L, 1790L), byAmount.keys)

        val starbucks = byAmount.getValue(4800L).draft
        val luckin = byAmount.getValue(1790L).draft
        assertEquals(listOf("星巴克咖啡"), starbucks.merchantCandidates)
        assertEquals(listOf("瑞幸咖啡"), luckin.merchantCandidates)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, starbucks.completionStatus)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, luckin.completionStatus)
        assertTrue(result.decisions.all { it.action == Action.AUTO_BOOK })
        assertEquals(2, result.bookings.size)
        assertEquals(2, result.decisions.map { it.draft.pageIdentity }.distinct().size)
    }

    @Test
    fun `cancelled and received transfers stay separate`() {
        val blocks = transferChatPage()
        val result = plan(blocks)
        assertEquals(2, result.decisions.size)

        val cancelled = result.decisions.first { it.draft.completionStatus == CaptureCandidate.COMPLETION_CANCELLED }
        val received = result.decisions.first { it.draft.completionStatus == CaptureCandidate.COMPLETION_COMPLETED }
        assertEquals(listOf("张三"), cancelled.draft.merchantCandidates)
        assertEquals(listOf("李四"), received.draft.merchantCandidates)
        assertEquals(20_000L, cancelled.draft.amountCents)
        assertEquals(20_000L, received.draft.amountCents)
        assertEquals(Action.WAIT_FOR_EVIDENCE, cancelled.action)
        // A received transfer is complete money but still not ordinary income:
        // it waits for the user instead of being booked.
        assertEquals(CaptureCandidate.KIND_TRANSFER, received.draft.kind)
        assertEquals(Action.ASK_USER, received.action)
        assertTrue(result.bookings.isEmpty())
        assertEquals(received.index, result.needsConfirmation.single().index)
    }

    @Test
    fun `page level future debit marker does not suppress separate completed payment`() {
        val mixed = listOf(
            block("支付宝", 10),
            block("订单已生成", 40),
            block("预计扣费金额 9.65元", 80),
            block("11月29日自动确认收货后扣款", 110),
            block("先用后付", 140),
            block("订单金额 ¥10.00", 170),
            block("星巴克咖啡", 250),
            block("¥18.00", 280),
            block("支付成功", 310),
        )
        val result = plan(mixed)

        assertEquals(2, result.decisions.size)
        val future = result.decisions.single { it.draft.amountCents == 965L }
        val paid = result.decisions.single { it.draft.amountCents == 1800L }
        assertEquals(Action.WAIT_FOR_EVIDENCE, future.action)
        assertEquals(CaptureCandidate.COMPLETION_CREATED, future.draft.completionStatus)
        assertEquals(Action.AUTO_BOOK, paid.action)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, paid.draft.completionStatus)
        assertEquals(listOf(1800L), result.bookings.map { it.draft.amountCents })
    }

    @Test
    fun `refund card beside an ordinary payment keeps both cards typed`() {
        // One page, two independent transactions: an incoming refund and a normal
        // merchant payment. The refund must not retype or hide the payment.
        val blocks = listOf(
            block("微信支付", 10),
            block("退款成功", 40),
            block("¥1.85", 70),
            block("退款金额 ¥1.85", 100),
            block("星巴克咖啡", 240),
            block("¥18.00", 280),
            block("支付成功", 310),
        )
        val result = plan(blocks)

        assertEquals(2, result.decisions.size)
        val refund = result.decisions.single { it.draft.amountCents == 185L }
        val payment = result.decisions.single { it.draft.amountCents == 1800L }
        assertEquals(CaptureCandidate.KIND_REFUND, refund.draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, refund.draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, refund.draft.completionStatus)
        assertEquals(CaptureCandidate.KIND_PAYMENT, payment.draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, payment.draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, payment.draft.completionStatus)
        assertEquals(Action.ASK_USER, refund.action)
        assertEquals(Action.AUTO_BOOK, payment.action)
        assertEquals(listOf(1800L), result.bookings.map { it.draft.amountCents })
    }

    @Test
    fun `future debit card does not drop another card order total`() {
        // The future-debit card repeats its expected debit as 订单金额. The second
        // card also uses 订单金额, but it is a different, completed transaction.
        val blocks = listOf(
            block("支付宝", 10),
            block("订单已生成", 40),
            block("预计扣费金额 9.65元", 80),
            block("先用后付", 110),
            block("订单金额 ¥10.00", 140),
            block("星巴克咖啡", 240),
            block("订单金额 ¥18.00", 280),
            block("支付成功", 310),
        )
        val result = plan(blocks)

        assertEquals(2, result.decisions.size)
        val future = result.decisions.single { it.draft.amountCents == 965L }
        val paid = result.decisions.single { it.draft.amountCents == 1800L }
        assertEquals(CaptureCandidate.COMPLETION_CREATED, future.draft.completionStatus)
        assertEquals(Action.WAIT_FOR_EVIDENCE, future.action)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, paid.draft.completionStatus)
        assertEquals(Action.AUTO_BOOK, paid.action)
        assertEquals(listOf(1800L), result.bookings.map { it.draft.amountCents })
    }

    @Test
    fun `null bounds page keeps completed status over a stray cancellation`() {
        // PaddleOCR can return lines without boxes. Zeroed coordinates are not
        // geometry, and a bare 已取消 must not override 交易成功 for the same card.
        val blocks = listOf(
            TextBlock(text = "微信支付"),
            TextBlock(text = "收款方 便利店"),
            TextBlock(text = "-¥36.50"),
            TextBlock(text = "交易成功"),
            TextBlock(text = "已取消"),
        )
        assertTrue(blocks.none { it.hasBounds })

        val decision = plan(blocks).decisions.single()

        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, decision.draft.completionStatus)
        assertEquals(Action.AUTO_BOOK, decision.action)
    }

    @Test
    fun `a bare cancellation with unknown bounds still waits when nothing confirmed`() {
        val blocks = listOf(
            TextBlock(text = "微信支付"),
            TextBlock(text = "转账给 张三"),
            TextBlock(text = "-¥200.00"),
            TextBlock(text = "已取消"),
        )

        val decision = plan(blocks).decisions.single()

        assertEquals(CaptureCandidate.COMPLETION_CANCELLED, decision.draft.completionStatus)
        assertEquals(Action.WAIT_FOR_EVIDENCE, decision.action)
    }

    @Test
    fun `cancelled voice call bubble beside payment does not cancel transfer`() {
        // Two separate columns model a payment card and an unrelated cancelled-call bubble.
        val blocks = listOf(
            blockAt("微信支付", 20, 10, 140),
            blockAt("转账给 张三", 20, 40, 140),
            blockAt("¥200.00", 20, 70, 140),
            blockAt("语音通话已取消", 230, 70, 430),
            blockAt("交易成功", 20, 100, 140),
        )
        val result = plan(blocks)

        assertEquals(1, result.decisions.size)
        val transfer = result.decisions.single()
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, transfer.draft.completionStatus)
        assertEquals(Action.ASK_USER, transfer.action)
        assertEquals("张三", transfer.draft.merchantCandidates.single())
    }

    @Test
    fun `generic cancelled notification bubble beside a completed payment is isolated geometrically`() {
        val blocks = listOf(
            blockAt("微信支付", 20, 10, 140),
            blockAt("转账给 张三", 20, 40, 140),
            blockAt("¥200.00", 20, 70, 140),
            blockAt("已取消", 230, 70, 430),
            blockAt("交易成功", 20, 100, 140),
        )
        val decision = plan(blocks).decisions.single()

        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, decision.draft.completionStatus)
        assertEquals(Action.ASK_USER, decision.action)
    }

    @Test
    fun `far generic cancelled bubble without nearby payment status remains unknown`() {
        val blocks = listOf(
            blockAt("微信支付", 20, 10, 140),
            blockAt("转账给 张三", 20, 40, 140),
            blockAt("¥200.00", 20, 70, 140),
            blockAt("已取消", 280, 70, 430),
        )
        val decision = plan(blocks).decisions.single()

        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, decision.draft.completionStatus)
        assertEquals(Action.ASK_USER, decision.action)
    }

    @Test
    fun `non payment pages produce no drafts at all`() {
        val google = plan(googleOnePage())
        assertTrue(google.decisions.isEmpty())
        assertTrue(google.bookings.isEmpty())
        assertEquals(FailureReason.NOT_TRANSACTION, google.failure)
        assertTrue(ScreenshotPageInterpreter.interpret(googleOnePage(), capturedAt, zone).drafts.isEmpty())

        val notice = plan(wechatLoginNotice())
        assertTrue(notice.decisions.isEmpty())
        assertTrue(notice.bookings.isEmpty())
        assertEquals(FailureReason.UNKNOWN_PAGE, notice.failure)
        assertTrue(ScreenshotPageInterpreter.interpret(wechatLoginNotice(), capturedAt, zone).drafts.isEmpty())

        // Even a stray currency line picked up by OCR must not turn a login
        // notice into a transaction.
        val noticeWithStrayAmount = wechatLoginNotice() + block("¥1.00", 190)
        val stray = plan(noticeWithStrayAmount)
        assertTrue(stray.decisions.isEmpty())
        assertTrue(stray.bookings.isEmpty())
        assertTrue(ScreenshotPageInterpreter.interpret(noticeWithStrayAmount, capturedAt, zone).drafts.isEmpty())
    }
}
