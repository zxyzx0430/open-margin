package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import com.bookkeeping.app.ui.screen.capture.CaptureReviewDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Two live OCR misses, rebuilt from the handful of lines the interpreter reads:
 *  - a WeChat / Alipay bill-list card prints the shop two to four rows above the
 *    amount (icon row, payment method, timestamp), so the shop must still be
 *    found when the row directly above the amount is rejected;
 *  - a WeChat chat transfer bubble is an amount plus 已被接收 / 已收款 / 待接收
 *    plus 转账, and used to produce no candidate at all. 待接收 is drawn on both
 *    sides of the chat, so it must never decide the direction by itself.
 *
 * No screenshot bytes and no raw OCR dump are stored here.
 */
class ScreenshotAuditMerchantTransferTest {

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
     * 微信支付 bill list, two cards exactly as the live page lays them out:
     * time, shop header, payment-method sentence, amount, 账单详情, then the
     * 分分捐 footer. The row directly above each amount is a payment sentence.
     */
    private fun billListPage() = listOf(
        blockAt("微信支付", 20, 10, 160),
        blockAt("21:46", 380, 100, 470),
        blockAt("祐迎迎生鲜超市(岭南店)", 190, 130, 640),
        blockAt("使用中国银行储蓄卡(4041)支付", 210, 200, 670),
        blockAt("¥ 8.00", 350, 260, 530),
        blockAt("账单详情 >", 370, 330, 500),
        blockAt("分分捐计划进行中", 190, 380, 430),
        blockAt("领小红花状态 >", 600, 380, 800),
        blockAt("21:49", 380, 460, 470),
        blockAt("祐迎迎生鲜超市(岭南店)", 190, 490, 640),
        blockAt("使用中国银行储蓄卡(4041)支付", 210, 560, 670),
        blockAt("¥ 5.00", 350, 620, 530),
    )

    @Test
    fun `bill list card reads the shop above the payment method row`() {
        val result = plan(billListPage())

        assertEquals(2, result.decisions.size)
        val byAmount = result.decisions.associateBy { it.draft.amountCents }
        assertEquals(setOf(800L, 500L), byAmount.keys)
        byAmount.values.forEach { decision ->
            assertEquals(
                listOf("祐迎迎生鲜超市(岭南店)"),
                decision.draft.merchantCandidates,
            )
            assertNotEquals(
                listOf("使用中国银行储蓄卡(4041)支付"),
                decision.draft.merchantCandidates,
            )
            assertNotEquals(listOf("21:46"), decision.draft.merchantCandidates)
            assertNotEquals(listOf("21:49"), decision.draft.merchantCandidates)
        }
    }

    @Test
    fun `bracketed shop name is kept whole on a list card`() {
        val blocks = listOf(
            block("微信支付", 10),
            block("祐迎迎生鲜超市(岭南店)", 40),
            block("使用中国银行储蓄卡(4041)支付", 70),
            block("¥18.00", 100),
            block("账单详情 >", 130),
        )

        val draft = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone).drafts.single()

        assertEquals(listOf("祐迎迎生鲜超市(岭南店)"), draft.merchantCandidates)
        assertEquals(1800L, draft.amountCents)
    }

    @Test
    fun `shop name with at sign and dots is kept whole`() {
        val blocks = listOf(
            block("微信支付", 10),
            block("21:46", 40),
            block("廖工@百顺装修.设计.建材", 70),
            block("使用零钱支付", 100),
            block("¥48.00", 130),
        )

        val draft = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone).drafts.single()

        assertEquals(listOf("廖工@百顺装修.设计.建材"), draft.merchantCandidates)
        assertEquals(4800L, draft.amountCents)
    }

    @Test
    fun `shop name that carries digits is read on a list card`() {
        val blocks = listOf(
            block("微信支付", 10),
            block("7-11", 40),
            block("使用中国银行储蓄卡(4041)支付", 70),
            block("¥ 8.00", 100),
        )

        val draft = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone).drafts.single()

        assertEquals(listOf("7-11"), draft.merchantCandidates)
    }

    /** Sender side of a WeChat chat: the orange bubble on the left. */
    private fun sentTransferBubblePage() = listOf(
        blockAt("在吗", 60, 120, 220),
        blockAt("晚上一起吃饭", 420, 120, 760),
        blockAt("¥200.00", 80, 200, 300),
        blockAt("已被接收", 90, 240, 200),
        blockAt("转账", 90, 280, 160),
    )

    /** Receiver side of the same chat: the bubble sits on the right. */
    private fun receivedTransferBubblePage() = listOf(
        blockAt("收到", 60, 120, 200),
        blockAt("¥200.00", 500, 200, 720),
        blockAt("已收款", 510, 240, 620),
        blockAt("转账", 510, 280, 580),
    )

    @Test
    fun `accepted transfer bubble becomes a completed expense that is auto booked`() {
        val evidence = ScreenshotPageInterpreter.interpret(sentTransferBubblePage(), capturedAt, zone)

        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, draft.direction)
        assertTrue(draft.directionExplicit)
        // 已被接收 means the counterparty took the money: the transfer is final
        // and the expense is complete, not an open question for the user.
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)
        assertTrue(draft.completionExplicit)
        assertTrue(draft.supportedPaymentPage)
        // The chat text above the bubble is not a merchant.
        assertTrue(draft.merchantCandidates.isEmpty())

        val result = plan(sentTransferBubblePage())
        assertEquals(1, result.decisions.size)
        assertEquals(Action.AUTO_BOOK, result.decisions.single().action)
        assertEquals(1, result.bookings.size)
        assertTrue(result.needsConfirmation.isEmpty())
    }

    @Test
    fun `received transfer bubble becomes a completed income that is auto booked`() {
        val evidence = ScreenshotPageInterpreter.interpret(receivedTransferBubblePage(), capturedAt, zone)

        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, draft.direction)
        assertTrue(draft.directionExplicit)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)
        assertTrue(draft.completionExplicit)

        val result = plan(receivedTransferBubblePage())
        assertEquals(1, result.decisions.size)
        val decision = result.decisions.single()
        assertEquals(Action.AUTO_BOOK, decision.action)
        assertEquals(1, result.bookings.size)
        assertTrue(result.needsConfirmation.isEmpty())
    }

    @Test
    fun `waiting transfer bubble is never treated as completed or booked`() {
        val blocks = listOf(
            blockAt("¥200.00", 500, 200, 720),
            blockAt("待接收", 510, 240, 620),
            blockAt("转账", 510, 280, 580),
        )

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)

        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertNotEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)

        val result = plan(blocks)
        assertTrue(result.bookings.isEmpty())
        assertTrue(result.decisions.none { it.action == Action.AUTO_BOOK })
    }

    @Test
    fun `two stacked transfer bubbles stay two independent candidates`() {
        val blocks = listOf(
            blockAt("¥200.00", 80, 200, 300),
            blockAt("已被接收", 90, 240, 200),
            blockAt("转账", 90, 280, 160),
            blockAt("¥200.00", 500, 420, 720),
            blockAt("已收款", 510, 460, 620),
            blockAt("转账", 510, 500, 580),
        )

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)

        assertEquals(2, evidence.drafts.size)
        assertEquals(
            setOf(CaptureCandidate.DIRECTION_EXPENSE, CaptureCandidate.DIRECTION_INCOME),
            evidence.drafts.map { it.direction }.toSet(),
        )
        assertTrue(evidence.drafts.all { it.kind == CaptureCandidate.KIND_TRANSFER })
        assertTrue(evidence.drafts.all { it.amountCents == 20_000L })
        assertEquals(2, evidence.drafts.map { it.index }.distinct().size)

        val result = plan(blocks)
        assertEquals(2, result.decisions.size)
        // Two settled transfers in opposite directions are two real rows; both
        // may book automatically, but neither may be merged into the other.
        assertTrue(result.decisions.all { it.action == Action.AUTO_BOOK })
        assertEquals(2, result.bookings.size)
        assertTrue(result.needsConfirmation.isEmpty())
    }

    /**
     * Guard: the ordinary single-payment success page must keep exactly the
     * merchant and status it had before this change.
     */
    @Test
    fun `ordinary payment page merchant is unchanged`() {
        val blocks = listOf(
            block("微信支付", 10),
            block("收款方 便利店", 40),
            block("-¥36.50", 80),
            block("当前状态", 120),
            block("交易成功", 140),
            block("2026-11-19 12:30", 180),
            block("订单号 1000123456789012", 220),
        )

        val result = plan(blocks)

        val decision = result.decisions.single()
        assertEquals(listOf("便利店"), decision.draft.merchantCandidates)
        assertEquals(CaptureCandidate.KIND_PAYMENT, decision.draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, decision.draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, decision.draft.completionStatus)
        assertEquals(Action.AUTO_BOOK, decision.action)
    }

    // ---- 待接收: the state word is drawn on both sides of the chat ----

    /** OCR lines with no bounding box at all: no bubble side can be derived. */
    private fun textOnly(vararg lines: String) = lines.map { TextBlock(text = it) }

    /** The same waiting bubble drawn at a known horizontal position. */
    private fun waitingTransferPage(amountLeft: Int, amountRight: Int) = listOf(
        blockAt("在吗", 60, 120, 220),
        blockAt("晚上一起吃饭", 420, 120, 760),
        blockAt("¥200.00", amountLeft, 200, amountRight),
        blockAt("待接收", amountLeft + 10, 240, amountRight - 10),
        blockAt("转账", amountLeft + 10, 280, amountRight - 10),
    )

    /**
     * 待接收 appears on the sender's bubble (money already left me, waiting for
     * the other side) and on the receiver's (waiting for me). Without a bubble
     * side the word proves no direction, so the draft must stay UNKNOWN and the
     * review chain must refuse to book it before the user picks 我收钱 / 我付钱.
     */
    @Test
    fun `waiting transfer without geometry stays direction unknown and needs a pick`() {
        val blocks = textOnly("¥200.00", "待接收", "转账")

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)

        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_UNKNOWN, draft.direction)
        assertFalse("an unproven direction is not explicit", draft.directionExplicit)
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
        assertFalse("待接收 is not a completion marker", draft.completionExplicit)
        assertTrue(draft.supportedPaymentPage)
        // The reason may say the direction is still open, never that it is income.
        assertTrue(draft.reason.contains("direction"))
        assertFalse(draft.reason.contains("income", ignoreCase = true))
        assertFalse(draft.reason.contains("expense", ignoreCase = true))

        // No one-tap income booking: the candidate cannot be confirmed until a
        // direction is picked, and either pick is accepted.
        val result = plan(blocks)
        val decision = result.decisions.single()
        assertNotEquals(Action.AUTO_BOOK, decision.action)
        assertTrue(result.bookings.isEmpty())

        val candidate = ScreenshotCandidateFactory.create(
            decision = decision,
            source = IntakeSource.SHARE,
            capturedAtMs = capturedAt,
            imageFingerprint = FINGERPRINT,
        )
        assertEquals(CaptureCandidate.DIRECTION_UNKNOWN, candidate.direction)
        assertFalse(candidate.directionExplicit)
        assertTrue(CaptureReviewDirection.requiresSelection(candidate))
        assertFalse(
            "an unpicked direction must not reach the ledger",
            CaptureReviewDirection.canConfirm(candidate, null),
        )
        assertFalse(CaptureBookGate.mayConfirm(candidate))
        assertTrue(
            CaptureReviewDirection.canConfirm(candidate, CaptureCandidate.DIRECTION_EXPENSE),
        )
        assertTrue(
            CaptureReviewDirection.canConfirm(candidate, CaptureCandidate.DIRECTION_INCOME),
        )
    }

    @Test
    fun `waiting transfer on the sender side with geometry is an expense`() {
        val draft = ScreenshotPageInterpreter
            .interpret(waitingTransferPage(amountLeft = 80, amountRight = 300), capturedAt, zone)
            .drafts.single()

        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, draft.direction)
        assertTrue(draft.directionExplicit)
        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
        assertFalse(draft.completionExplicit)
    }

    @Test
    fun `waiting transfer on the receiver side with geometry is an income`() {
        val draft = ScreenshotPageInterpreter
            .interpret(waitingTransferPage(amountLeft = 500, amountRight = 720), capturedAt, zone)
            .drafts.single()

        assertEquals(CaptureCandidate.DIRECTION_INCOME, draft.direction)
        assertTrue(draft.directionExplicit)
        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
        assertFalse(draft.completionExplicit)
    }

    @Test
    fun `waiting transfer in the middle with geometry stays unknown`() {
        val draft = ScreenshotPageInterpreter
            .interpret(waitingTransferPage(amountLeft = 300, amountRight = 480), capturedAt, zone)
            .drafts.single()

        assertEquals(CaptureCandidate.DIRECTION_UNKNOWN, draft.direction)
        assertFalse(draft.directionExplicit)
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
        assertFalse(draft.completionExplicit)
    }

    // ---- regression: the words that do identify their side keep their meaning ----

    @Test
    fun `accepted transfer without geometry stays a completed sender expense`() {
        val blocks = textOnly("¥200.00", "已被接收", "转账")

        val draft = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone).drafts.single()

        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, draft.direction)
        assertTrue(draft.directionExplicit)
        // The word alone identifies the side and the settled state, so no box is
        // needed to prove either one.
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)
        assertTrue(draft.completionExplicit)
    }

    @Test
    fun `received transfer without geometry stays an income that is completed`() {
        val blocks = textOnly("¥200.00", "已收款", "转账")

        val draft = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone).drafts.single()

        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, draft.direction)
        assertTrue(draft.directionExplicit)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)
        assertTrue(draft.completionExplicit)
    }

    /** Guard: non-transfer WeChat / Alipay pages still use the text rules. */
    @Test
    fun `ordinary wechat and alipay pages keep their direction`() {
        val wechatPaid = listOf(
            block("微信支付", 10),
            block("收款方 便利店", 40),
            block("-¥36.50", 80),
            block("当前状态", 120),
            block("交易成功", 140),
        )
        val alipayPaid = listOf(
            block("支付宝", 10),
            block("交易成功", 40),
            block("¥18.00", 80),
            block("付款方式 余额宝", 120),
        )
        val alipayReceived = listOf(
            block("支付宝", 10),
            block("收款成功", 40),
            block("¥88.00", 80),
        )

        listOf(wechatPaid, alipayPaid).forEach { page ->
            val draft = ScreenshotPageInterpreter.interpret(page, capturedAt, zone).drafts.single()
            assertEquals(CaptureCandidate.DIRECTION_EXPENSE, draft.direction)
            assertTrue(draft.directionExplicit)
        }
        val incoming = ScreenshotPageInterpreter
            .interpret(alipayReceived, capturedAt, zone)
            .drafts.single()
        assertEquals(CaptureCandidate.DIRECTION_INCOME, incoming.direction)
        assertTrue(incoming.directionExplicit)
    }
}
