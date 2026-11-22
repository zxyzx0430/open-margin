package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.DuplicateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * R6: screenshot de-duplication must never swallow a distinct transaction just
 * because it looks like an earlier one.
 *
 * Two consecutive payments to the same shop for the same amount are ordinary
 * (a convenience store right after another). Only an order id / trade number
 * positively identifies "the same transaction", so without one the fuzzy
 * platform + amount + direction + merchant + two-minute shape may only ever ask
 * the user, never link silently.
 */
class ScreenshotAuditDedupTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val capturedAt = 1795064400000L // 2026-11-19 13:00 Asia/Shanghai

    private companion object {
        const val PAGE_TIME = 1_795_064_400_000L
        const val THIRTY_SECONDS = 30_000L
        const val FIVE_MINUTES = 5L * 60 * 1000
    }

    private fun draft(
        amountCents: Long? = 2800L,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        merchant: String? = "星巴克",
        orderId: String? = null,
        eventTimeMs: Long? = PAGE_TIME,
        pageIdentity: String = "alipay:bill:2800:starbucks",
        currency: String = "CNY",
    ) = TransactionDraft(
        index = 0,
        amountCents = amountCents,
        amountUnambiguous = true,
        direction = direction,
        directionExplicit = true,
        kind = CaptureCandidate.KIND_PAYMENT,
        completionStatus = CaptureCandidate.COMPLETION_COMPLETED,
        completionExplicit = true,
        merchantCandidates = listOfNotNull(merchant),
        orderId = orderId,
        eventTimeMs = eventTimeMs,
        currency = currency,
        pageIdentity = pageIdentity,
        supportedPaymentPage = true,
    )

    private fun fingerprint(
        transactionId: Long = 501,
        amountCents: Long = 2800L,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        merchant: String? = "星巴克",
        orderId: String? = null,
        eventTimeMs: Long = PAGE_TIME + THIRTY_SECONDS,
        currency: String = "CNY",
        platform: String = CaptureCandidate.PLATFORM_ALIPAY,
    ) = LedgerFingerprint(
        transactionId = transactionId,
        platform = platform,
        amountCents = amountCents,
        direction = direction,
        orderId = orderId,
        merchant = merchant,
        eventTimeMs = eventTimeMs,
        currency = currency,
    )

    private fun decision(draft: TransactionDraft, existing: List<LedgerFingerprint>) =
        ScreenshotImportPipeline.deduplicate(draft, existing)

    @Test
    fun `a matching order id is still an exact match`() {
        // Same page identity, same order id: positively the same transaction.
        val existing = listOf(
            fingerprint(transactionId = 601, orderId = "2026111922001234567", merchant = null),
        )
        val (result, matched) = decision(
            draft(orderId = "2026111922001234567"),
            existing,
        )

        assertEquals(DuplicateResult.EXACT_MATCH, result)
        assertEquals(601L, matched)
    }

    @Test
    fun `a matching order id with a conflicting transaction shape is reviewable not exact`() {
        val orderId = "2026111922001234567"
        val conflicts = listOf(
            fingerprint(transactionId = 601, amountCents = 2900L, orderId = orderId),
            fingerprint(transactionId = 601, direction = CaptureCandidate.DIRECTION_INCOME, orderId = orderId),
            fingerprint(transactionId = 601, currency = "USD", orderId = orderId),
            fingerprint(transactionId = 601, merchant = "全家", orderId = orderId),
        )

        conflicts.forEach { row ->
            val (result, matched) = decision(draft(orderId = orderId), listOf(row))

            assertEquals(DuplicateResult.AMBIGUOUS, result)
            assertEquals(601L, matched)
        }
    }

    @Test
    fun `an order id without a known platform is not treated as a duplicate`() {
        val orderId = "2026111922001234567"
        val existing = fingerprint(transactionId = 602, orderId = orderId)
        val unscoped = draft(orderId = orderId, pageIdentity = "unknown:detail:2800:starbucks")

        val (result, matched) = decision(unscoped, listOf(existing))

        assertEquals(DuplicateResult.CLEAR, result)
        assertNull(matched)
    }

    @Test
    fun `same merchant same amount thirty seconds apart without an order id is never an exact match`() {
        // Two real back-to-back purchases at one shop: nothing identifies them
        // as one transaction, so the second must not be swallowed.
        val existing = listOf(fingerprint(eventTimeMs = PAGE_TIME + THIRTY_SECONDS))
        val (result, matched) = decision(draft(), existing)

        assertNotEquals(DuplicateResult.EXACT_MATCH, result)
        assertNull(matched)
        // Downgraded to the reviewable result, not silently dropped.
        assertEquals(DuplicateResult.AMBIGUOUS, result)
    }

    @Test
    fun `same merchant same amount five minutes apart without an order id is never an exact match`() {
        val existing = listOf(fingerprint(eventTimeMs = PAGE_TIME + FIVE_MINUTES))
        val (result, matched) = decision(draft(), existing)

        assertNotEquals(DuplicateResult.EXACT_MATCH, result)
        assertNull(matched)
        assertEquals(DuplicateResult.CLEAR, result)
    }

    @Test
    fun `a different amount is never an exact match`() {
        val existing = listOf(fingerprint(amountCents = 2900L))
        val (result, matched) = decision(draft(amountCents = 2800L), existing)

        assertNotEquals(DuplicateResult.EXACT_MATCH, result)
        assertNull(matched)
    }

    @Test
    fun `a different direction is never an exact match`() {
        val existing = listOf(fingerprint(direction = CaptureCandidate.DIRECTION_INCOME))
        val (result, matched) = decision(draft(direction = CaptureCandidate.DIRECTION_EXPENSE), existing)

        assertNotEquals(DuplicateResult.EXACT_MATCH, result)
        assertNull(matched)
    }

    @Test
    fun `a different merchant is never an exact match`() {
        val existing = listOf(fingerprint(merchant = "全家"))
        val (result, matched) = decision(draft(merchant = "星巴克"), existing)

        assertNotEquals(DuplicateResult.EXACT_MATCH, result)
        assertNull(matched)
    }

    @Test
    fun `a second same merchant payment two minutes apart reaches review instead of a silent link`() {
        // The page carries no order id, so the plan may ask but never link: the
        // candidate has to land in "待确认" with nothing written.
        val page = listOf(
            TextBlock(text = "支付宝", top = 10, bottom = 30),
            TextBlock(text = "账单详情", top = 40, bottom = 60),
            TextBlock(text = "¥28.00", top = 80, bottom = 100),
            TextBlock(text = "收款方 星巴克", top = 120, bottom = 140),
            TextBlock(text = "2026-11-19 12:40", top = 160, bottom = 180),
        )
        val pageTime = ScreenshotPageInterpreter.interpret(page, capturedAt, zone).drafts.single().eventTimeMs!!
        val existing = listOf(fingerprint(eventTimeMs = pageTime + THIRTY_SECONDS))

        val plan = ScreenshotImportPipeline.plan(
            request = ScreenshotIntakeRequest(
                source = IntakeSource.SHARE,
                uri = "content://media/external/images/media/86",
                mimeType = "image/jpeg",
                capturedAtMs = capturedAt,
            ),
            blocks = page,
            existing = existing,
            timeZone = zone,
        )
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
        assertTrue(plan.bookings.isEmpty())
        assertEquals(1, plan.needsConfirmation.size)
    }
}
