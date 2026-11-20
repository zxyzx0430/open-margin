package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * R4: OCR boxes are optional, and a missing box must never delete money.
 *
 * A page that carries a box for some lines and none for others used to drop
 * every un-boxed line, so a real transaction amount vanished from a page that
 * also had boxed amounts. These tests pin the geometry contract:
 *  - every amount line anchors its own card, with or without a box;
 *  - a missing or degenerate box is never read as a position, so it can never
 *    attract the status word of another card;
 *  - a page with no usable geometry at all keeps its old page-level shape.
 */
class ScreenshotAuditGeometryTest {

    private val capturedAt = 1795064400000L // 2026-11-19 13:00 Asia/Shanghai
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    /** A line the OCR engine placed on the page. */
    private fun block(text: String, top: Int) =
        TextBlock(text = text, left = 20, top = top, right = 320, bottom = top + 20)

    /** A line the OCR engine returned without any box. */
    private fun unplaced(text: String) = TextBlock(text = text)

    private fun plan(blocks: List<TextBlock>) = ScreenshotImportPipeline.plan(
        request = ScreenshotIntakeRequest(
            source = IntakeSource.SHARE,
            uri = "content://media/external/images/media/8",
            mimeType = "image/png",
            capturedAtMs = capturedAt,
            byteSize = 300_000L,
            imageFingerprint = "c".repeat(64),
        ),
        blocks = blocks,
        existing = emptyList(),
        timeZone = zone,
    )

    private fun evidenceOf(blocks: List<TextBlock>) =
        ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone)

    @Test
    fun `two boxed payments stay two drafts`() {
        val blocks = listOf(
            block("微信支付", 10),
            block("星巴克咖啡", 40),
            block("-¥48.00", 70),
            block("支付成功", 100),
            block("瑞幸咖啡", 130),
            block("-¥17.90", 160),
            block("支付成功", 190),
        )

        val evidence = evidenceOf(blocks)
        assertEquals(2, evidence.drafts.size)
        assertEquals(setOf(4800L, 1790L), evidence.drafts.mapNotNull { it.amountCents }.toSet())
        assertFalse(evidence.conflictingFields)

        val result = plan(blocks)
        assertEquals(setOf(4800L, 1790L), result.decisions.mapNotNull { it.draft.amountCents }.toSet())
        assertEquals(2, result.bookings.size)
    }

    @Test
    fun `a page without any box still yields its payment`() {
        val blocks = listOf(
            unplaced("微信支付"),
            unplaced("收款方 便利店"),
            unplaced("-¥36.50"),
            unplaced("交易成功"),
        )

        val evidence = evidenceOf(blocks)
        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(3650L, draft.amountCents)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)

        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        assertEquals(Action.AUTO_BOOK, result.decisions.single().action)
    }

    @Test
    fun `an unboxed amount beside a boxed amount is never dropped`() {
        val blocks = listOf(
            block("微信支付", 10),
            unplaced("-¥10.00"),
            block("-¥20.00", 100),
            block("支付成功", 130),
        )

        val evidence = evidenceOf(blocks)
        assertEquals(setOf(1000L, 2000L), evidence.drafts.mapNotNull { it.amountCents }.toSet())
        // No box, no proof: the unboxed amount must not borrow the neighbour's
        // 支付成功, so it reaches review instead of being silently completed.
        assertEquals(
            CaptureCandidate.COMPLETION_UNKNOWN,
            evidence.drafts.single { it.amountCents == 1000L }.completionStatus,
        )
        assertEquals(
            CaptureCandidate.COMPLETION_COMPLETED,
            evidence.drafts.single { it.amountCents == 2000L }.completionStatus,
        )

        val result = plan(blocks)
        assertEquals(setOf(1000L, 2000L), result.decisions.mapNotNull { it.draft.amountCents }.toSet())
        assertEquals(Action.ASK_USER, result.decisions.single { it.draft.amountCents == 1000L }.action)
        assertEquals(Action.AUTO_BOOK, result.decisions.single { it.draft.amountCents == 2000L }.action)
    }

    @Test
    fun `a zero height box is not a geometry anchor`() {
        val zeroHeight = TextBlock(text = "-¥10.00", left = 20, top = 70, right = 320, bottom = 70)
        // A box was returned, it just has no vertical span: that is not a row.
        assertTrue(zeroHeight.hasBounds)
        val blocks = listOf(
            block("微信支付", 10),
            block("店铺A", 40),
            zeroHeight,
            block("支付成功", 100),
            block("店铺B", 200),
            block("-¥20.00", 230),
            block("支付成功", 260),
        )

        val evidence = evidenceOf(blocks)
        assertEquals(setOf(1000L, 2000L), evidence.drafts.mapNotNull { it.amountCents }.toSet())
        // 支付成功 sits right under the degenerate box; if that box counted as
        // geometry it would steal the word and auto book the ¥10.
        assertEquals(
            CaptureCandidate.COMPLETION_UNKNOWN,
            evidence.drafts.single { it.amountCents == 1000L }.completionStatus,
        )
        assertEquals(
            CaptureCandidate.COMPLETION_COMPLETED,
            evidence.drafts.single { it.amountCents == 2000L }.completionStatus,
        )
        assertEquals(Action.ASK_USER, plan(blocks).decisions.single { it.draft.amountCents == 1000L }.action)
    }

    @Test
    fun `an all zero box is not a geometry anchor`() {
        val zeroBox = TextBlock(text = "-¥10.00", left = 0, top = 0, right = 0, bottom = 0)
        assertFalse(zeroBox.hasBounds)
        val blocks = listOf(
            block("微信支付", 10),
            block("店铺A", 40),
            zeroBox,
            block("支付成功", 100),
            block("店铺B", 200),
            block("-¥20.00", 230),
            block("支付成功", 260),
        )

        val evidence = evidenceOf(blocks)
        assertEquals(setOf(1000L, 2000L), evidence.drafts.mapNotNull { it.amountCents }.toSet())
        assertEquals(
            CaptureCandidate.COMPLETION_UNKNOWN,
            evidence.drafts.single { it.amountCents == 1000L }.completionStatus,
        )
        assertEquals(
            CaptureCandidate.COMPLETION_COMPLETED,
            evidence.drafts.single { it.amountCents == 2000L }.completionStatus,
        )
        assertEquals(Action.ASK_USER, plan(blocks).decisions.single { it.draft.amountCents == 1000L }.action)
    }
}
