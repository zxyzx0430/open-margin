package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * A WeChat / Alipay bill list lists only money that already moved. Each row is
 * a merchant, a payment-method sentence, a bare amount and a `账单详情 >` footer,
 * and the row carries no status word at all — so "no 支付成功 on the card" used
 * to mean "unknown status", and every single row cost one confirmation tap.
 *
 * The rule pinned here is deliberately narrow:
 *  - at least two amount cards on the page (a single card is a detail page);
 *  - every amount on the page a bare currency line (a detail page labels its
 *    sub-totals: 商品金额 / 订单金额 / 实付金额);
 *  - the row carries its own `账单详情 >` footer or payment-method sentence;
 *  - no 待收款 / 已取消 / 支付失败 / 待付款 anywhere in that card.
 * Everything else keeps asking: non-final rows, refunds, transfers, red
 * packets, withdrawals, an unproven direction, an ambiguous amount, and every
 * import where auto booking is off.
 *
 * No screenshot bytes and no raw OCR dump are stored here.
 */
class ScreenshotBillListAutoBookTest {

    private val capturedAt = 1795064400000L // 2026-11-19 13:00 Asia/Shanghai
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private companion object {
        const val FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    private fun blockAt(text: String, left: Int, top: Int, right: Int) =
        TextBlock(text = text, left = left, top = top, right = right, bottom = top + 20)

    private fun plan(
        blocks: List<TextBlock>,
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
    ) = ScreenshotImportPipeline.plan(
        request = ScreenshotIntakeRequest(
            source = IntakeSource.SHARE,
            uri = "content://media/external/images/media/9",
            mimeType = "image/png",
            capturedAtMs = capturedAt,
            byteSize = 300_000L,
            imageFingerprint = FINGERPRINT,
        ),
        blocks = blocks,
        existing = emptyList(),
        allowAuto = allowAuto,
        previewOnly = previewOnly,
        timeZone = zone,
    )

    /**
     * One bill-list row exactly as WeChat lays it out: merchant, clock, payment
     * sentence, bare amount, `账单详情 >`. [status] is the state word a row that
     * has not settled would print under its amount.
     */
    private fun row(
        merchant: String,
        amount: String,
        top: Int,
        status: String? = null,
        payment: String = "使用中国银行储蓄卡(4041)支付",
    ): List<TextBlock> = listOfNotNull(
        blockAt(merchant, 190, top, 640),
        blockAt("21:46", 380, top + 30, 470),
        blockAt(payment, 210, top + 50, 670),
        blockAt(amount, 350, top + 110, 530),
        status?.let { blockAt(it, 350, top + 145, 470) },
        blockAt("账单详情 >", 370, top + 175, 500),
    )

    private fun twoRowPage(
        secondMerchant: String = "麦当劳(科技园店)",
        secondAmount: String = "¥ 23.50",
    ) = listOf(blockAt("微信支付", 20, 10, 160)) +
        row("祐迎迎生鲜超市(岭南店)", "¥ 8.00", 100) +
        row(secondMerchant, secondAmount, 360)

    // ---- the case the user reported: settled list rows cost one tap each ----

    @Test
    fun `two settled bill rows are auto booked without a status word`() {
        val blocks = twoRowPage()

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)
        assertEquals(2, evidence.drafts.size)
        assertEquals(setOf(800L, 2350L), evidence.drafts.mapNotNull { it.amountCents }.toSet())
        assertTrue(evidence.drafts.all { it.settledBillRow })
        assertTrue(evidence.drafts.all { it.completionStatus == CaptureCandidate.COMPLETION_COMPLETED })
        assertTrue(evidence.drafts.all { it.completionExplicit })

        val result = plan(blocks)
        assertEquals(2, result.decisions.size)
        assertTrue(result.decisions.all { it.action == Action.AUTO_BOOK })
        assertEquals(setOf(800L, 2350L), result.bookings.mapNotNull { it.draft.amountCents }.toSet())
        assertTrue(result.needsConfirmation.isEmpty())
    }

    @Test
    fun `a settled row keeps its merchant and expense direction`() {
        val draft = ScreenshotPageInterpreter
            .interpret(twoRowPage(), capturedAt, zone)
            .drafts.single { it.amountCents == 800L }

        assertEquals(listOf("祐迎迎生鲜超市(岭南店)"), draft.merchantCandidates)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, draft.direction)
        assertTrue(draft.directionExplicit)
        assertTrue(draft.amountUnambiguous)
        assertEquals(CaptureCandidate.KIND_PAYMENT, draft.kind)
        assertTrue(draft.supportedPaymentPage)
        assertEquals("settled bill list row", draft.reason)
    }

    // ---- every unfinished word on one row keeps that row out of the ledger ----

    @Test
    fun `a pending row is never auto booked`() {
        val blocks = listOf(blockAt("微信支付", 20, 10, 160)) +
            row("祐迎迎生鲜超市(岭南店)", "¥ 8.00", 100) +
            row("张三", "¥ 50.00", 360, status = "待收款")

        val result = plan(blocks)

        val pending = result.decisions.single { it.draft.amountCents == 5000L }
        assertEquals(CaptureCandidate.COMPLETION_PENDING, pending.draft.completionStatus)
        assertFalse(pending.draft.settledBillRow)
        assertEquals(Action.WAIT_FOR_EVIDENCE, pending.action)
        // The settled row beside it is still booked: one waiting row must not
        // hold back the rest of the list.
        assertEquals(setOf(800L), result.bookings.mapNotNull { it.draft.amountCents }.toSet())
    }

    @Test
    fun `a cancelled row is never auto booked`() {
        val blocks = listOf(blockAt("微信支付", 20, 10, 160)) +
            row("祐迎迎生鲜超市(岭南店)", "¥ 8.00", 100) +
            row("麦当劳(科技园店)", "¥ 23.50", 360, status = "已取消")

        val result = plan(blocks)

        val cancelled = result.decisions.single { it.draft.amountCents == 2350L }
        assertEquals(CaptureCandidate.COMPLETION_CANCELLED, cancelled.draft.completionStatus)
        assertFalse(cancelled.draft.settledBillRow)
        assertEquals(Action.WAIT_FOR_EVIDENCE, cancelled.action)
        assertEquals(setOf(800L), result.bookings.mapNotNull { it.draft.amountCents }.toSet())
    }

    @Test
    fun `a failed row is never auto booked`() {
        val blocks = listOf(blockAt("微信支付", 20, 10, 160)) +
            row("祐迎迎生鲜超市(岭南店)", "¥ 8.00", 100) +
            row("麦当劳(科技园店)", "¥ 23.50", 360, status = "支付失败")

        val result = plan(blocks)

        val failed = result.decisions.single { it.draft.amountCents == 2350L }
        assertEquals(CaptureCandidate.COMPLETION_FAILED, failed.draft.completionStatus)
        assertFalse(failed.draft.settledBillRow)
        assertEquals(Action.WAIT_FOR_EVIDENCE, failed.action)
        assertEquals(setOf(800L), result.bookings.mapNotNull { it.draft.amountCents }.toSet())
    }

    // ---- the rule must not leak into a detail page ----

    @Test
    fun `a single amount card keeps asking even with a bill footer`() {
        val blocks = listOf(
            blockAt("微信支付", 20, 10, 160),
            blockAt("祐迎迎生鲜超市(岭南店)", 190, 60, 640),
            blockAt("使用中国银行储蓄卡(4041)支付", 210, 110, 670),
            blockAt("¥ 8.00", 350, 170, 530),
            blockAt("账单详情 >", 370, 240, 500),
        )

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)
        val draft = evidence.drafts.single()
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
        assertFalse(draft.completionExplicit)
        assertFalse(draft.settledBillRow)

        val result = plan(blocks)
        assertEquals(Action.ASK_USER, result.decisions.single().action)
        assertEquals(CaptureCandidate.TIER_MEDIUM, result.decisions.single().tier)
        assertTrue(result.bookings.isEmpty())
    }

    @Test
    fun `a detail page with labelled sub totals is never read as a list`() {
        // Alipay detail page: the big amount and the 实付 amount are two cards,
        // both labelled. They are one transaction's parts, not two list rows.
        val blocks = listOf(
            blockAt("支付宝", 20, 10, 160),
            blockAt("星巴克咖啡", 190, 60, 640),
            blockAt("商品金额 ¥20.00", 190, 120, 640),
            blockAt("实付金额 ¥18.00", 190, 200, 640),
        )

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)
        assertEquals(2, evidence.drafts.size)
        assertTrue(evidence.drafts.none { it.settledBillRow })
        assertTrue(evidence.drafts.all { it.completionStatus == CaptureCandidate.COMPLETION_UNKNOWN })

        val result = plan(blocks)
        assertTrue(result.bookings.isEmpty())
        assertTrue(result.decisions.none { it.action == Action.AUTO_BOOK })
    }

    // ---- kinds that always need the user, even on a list row ----

    @Test
    fun `refund transfer red packet and withdrawal rows still ask`() {
        val specials = listOf(
            "退款" to "¥ 5.00",
            "转账给 张三" to "¥ 6.00",
            "微信红包" to "¥ 7.00",
            "提现到中国银行储蓄卡" to "¥ 9.00",
        )

        specials.forEach { (label, amount) ->
            val blocks = listOf(blockAt("微信支付", 20, 10, 160)) +
                row("祐迎迎生鲜超市(岭南店)", "¥ 8.00", 100) +
                row(label, amount, 360, payment = "使用零钱支付")

            val result = plan(blocks)
            val special = result.decisions.single { it.draft.amountCents != 800L }

            assertNotEquals("$label must not be auto booked", Action.AUTO_BOOK, special.action)
            assertEquals("$label must be confirmed", Action.ASK_USER, special.action)
            assertTrue("$label is still a settled row", special.draft.settledBillRow)
            assertEquals(
                "$label must not reach the ledger",
                setOf(800L),
                result.bookings.mapNotNull { it.draft.amountCents }.toSet(),
            )
        }
    }

    // ---- ambiguity keeps its old answer: no booking on a guess ----

    @Test
    fun `a list screenshot without boxes never auto books`() {
        val blocks = listOf(
            TextBlock(text = "微信支付"),
            TextBlock(text = "祐迎迎生鲜超市(岭南店)"),
            TextBlock(text = "使用中国银行储蓄卡(4041)支付"),
            TextBlock(text = "¥ 8.00"),
            TextBlock(text = "账单详情 >"),
            TextBlock(text = "麦当劳(科技园店)"),
            TextBlock(text = "使用零钱支付"),
            TextBlock(text = "¥ 23.50"),
            TextBlock(text = "账单详情 >"),
        )

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)
        assertEquals(1, evidence.drafts.size)
        assertFalse(evidence.drafts.single().amountUnambiguous)
        assertFalse(evidence.drafts.single().settledBillRow)

        val result = plan(blocks)
        assertTrue(result.bookings.isEmpty())
        assertTrue(result.decisions.none { it.action == Action.AUTO_BOOK })
    }

    @Test
    fun `a row with no direction evidence is not auto booked`() {
        // No payment sentence and no other direction word: the row is settled
        // but the page never says whether money left or arrived.
        val blocks = listOf(blockAt("微信支付", 20, 10, 160)) +
            row("祐迎迎生鲜超市(岭南店)", "¥ 8.00", 100) +
            listOf(
                blockAt("麦当劳(科技园店)", 190, 360, 640),
                blockAt("¥ 23.50", 350, 470, 530),
                blockAt("账单详情 >", 370, 535, 500),
            )

        val result = plan(blocks)
        val unknown = result.decisions.single { it.draft.amountCents == 2350L }

        assertEquals(CaptureCandidate.DIRECTION_UNKNOWN, unknown.draft.direction)
        assertFalse(unknown.draft.directionExplicit)
        assertNotEquals(Action.AUTO_BOOK, unknown.action)
        assertEquals(setOf(800L), result.bookings.mapNotNull { it.draft.amountCents }.toSet())
    }

    // ---- the switches still win ----

    @Test
    fun `auto booking disabled still asks for every settled row`() {
        val blocks = twoRowPage()

        listOf(
            plan(blocks, allowAuto = false),
            plan(blocks, previewOnly = true),
        ).forEach { result ->
            assertEquals(2, result.decisions.size)
            assertTrue(result.decisions.none { it.action == Action.AUTO_BOOK })
            assertTrue(result.bookings.isEmpty())
            assertTrue(result.decisions.all { !it.ledgerWriteAllowed })
            // The rows are still recognised as settled: the user is asked
            // because auto booking is off, not because the page was unclear.
            assertTrue(result.decisions.all { it.draft.settledBillRow })
            assertTrue(result.decisions.all { it.tier == CaptureCandidate.TIER_HIGH })
        }
    }

    // ---- guard: a page that names itself a bill list keeps its old meaning ----

    @Test
    fun `a bill list page that names itself still belongs to the import flow`() {
        val blocks = listOf(blockAt("全部账单", 20, 10, 200)) + twoRowPage()

        val evidence = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)

        assertEquals(ScreenshotPageInterpreter.PAGE_LIST, evidence.pageType)
        assertTrue(evidence.drafts.isEmpty())
        assertFalse(evidence.transactionLike)
        assertEquals(FailureReason.NOT_TRANSACTION, plan(blocks).failure)
    }
}
