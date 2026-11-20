package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.ui.screen.capture.CaptureReviewDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3, second stage: an unreadable direction stays unbookable, but the review UI
 * may turn it into an explicit expense/income the user picked. Until the user
 * picks, the confirm action must stay blocked; after the pick, the chosen
 * direction must reach the confirm chain with directionExplicit = true.
 */
class ScreenshotAuditDirectionReviewTest {

    private fun candidate(
        direction: String,
        allowed: Boolean = true,
        explicit: Boolean = direction != CaptureCandidate.DIRECTION_UNKNOWN,
    ) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:detail",
        amountCents = 1280L,
        merchantCandidates = listOf("商店"),
        orderId = null,
        capturedAtMs = 1795064400000L,
        screenshotPath = null,
        confidence = 0.6f,
        reason = "direction unknown",
        direction = direction,
        directionExplicit = explicit,
        ledgerWriteAllowed = allowed,
    )

    @Test
    fun `unknown direction asks for a selection and stays blocked without one`() {
        val candidate = candidate(CaptureCandidate.DIRECTION_UNKNOWN)
        assertTrue(
            "an unknown direction must open the picker",
            CaptureReviewDirection.requiresSelection(candidate),
        )
        assertFalse(
            "no pick yet: confirm must not be allowed",
            CaptureReviewDirection.canConfirm(candidate, null),
        )
        val resolved = CaptureReviewDirection.resolve(candidate, null)
        assertEquals(
            "an unresolved candidate keeps the unknown direction",
            CaptureCandidate.DIRECTION_UNKNOWN,
            resolved.direction,
        )
        assertFalse(
            "the gate must keep refusing the unresolved candidate",
            CaptureBookGate.mayConfirm(resolved),
        )
    }

    @Test
    fun `unknown direction becomes expense after the user picks expense`() {
        val candidate = candidate(CaptureCandidate.DIRECTION_UNKNOWN)
        assertEquals(
            "the picker offers exactly expense and income",
            setOf(CaptureCandidate.DIRECTION_EXPENSE, CaptureCandidate.DIRECTION_INCOME),
            CaptureReviewDirection.directions,
        )
        assertTrue(
            "picking expense unlocks confirm",
            CaptureReviewDirection.canConfirm(candidate, CaptureCandidate.DIRECTION_EXPENSE),
        )
        val resolved = CaptureReviewDirection.resolve(candidate, CaptureCandidate.DIRECTION_EXPENSE)
        assertEquals(
            "the pick must reach the confirm chain as EXPENSE",
            CaptureCandidate.DIRECTION_EXPENSE,
            resolved.direction,
        )
        assertTrue(
            "a user pick makes the direction explicit",
            resolved.directionExplicit,
        )
        assertTrue(CaptureBookGate.mayConfirm(resolved))
    }

    @Test
    fun `unknown direction becomes income after the user picks income`() {
        val candidate = candidate(CaptureCandidate.DIRECTION_UNKNOWN)
        assertTrue(
            "picking income unlocks confirm",
            CaptureReviewDirection.canConfirm(candidate, CaptureCandidate.DIRECTION_INCOME),
        )
        val resolved = CaptureReviewDirection.resolve(candidate, CaptureCandidate.DIRECTION_INCOME)
        assertEquals(
            "the pick must reach the confirm chain as INCOME",
            CaptureCandidate.DIRECTION_INCOME,
            resolved.direction,
        )
        assertTrue(resolved.directionExplicit)
        assertTrue(CaptureBookGate.mayConfirm(resolved))
    }

    @Test
    fun `known direction needs no picker and confirms as before`() {
        val expense = candidate(CaptureCandidate.DIRECTION_EXPENSE)
        assertFalse(
            "a known direction needs no selection",
            CaptureReviewDirection.requiresSelection(expense),
        )
        assertTrue(
            "a known direction confirms without a pick",
            CaptureReviewDirection.canConfirm(expense, null),
        )
        assertEquals(
            "resolve must not touch an already known direction",
            CaptureCandidate.DIRECTION_EXPENSE,
            CaptureReviewDirection.resolve(expense, null).direction,
        )
    }

    @Test
    fun `a conflicting pick cannot rewrite a known direction`() {
        val income = candidate(CaptureCandidate.DIRECTION_INCOME)
        assertEquals(
            CaptureCandidate.DIRECTION_INCOME,
            CaptureReviewDirection.resolve(income, CaptureCandidate.DIRECTION_EXPENSE).direction,
        )
    }

    @Test
    fun `junk selections never unlock the gate`() {
        val candidate = candidate(CaptureCandidate.DIRECTION_UNKNOWN)
        assertFalse(
            CaptureReviewDirection.canConfirm(candidate, CaptureCandidate.DIRECTION_UNKNOWN),
        )
        assertFalse(CaptureReviewDirection.canConfirm(candidate, "SOMETHING_ELSE"))
    }

    @Test
    fun `preview only candidate stays blocked even after a pick`() {
        val preview = candidate(CaptureCandidate.DIRECTION_UNKNOWN, allowed = false)
        assertFalse(
            "picking a direction must not turn a preview into a ledger write",
            CaptureReviewDirection.canConfirm(preview, CaptureCandidate.DIRECTION_INCOME),
        )
    }
}
