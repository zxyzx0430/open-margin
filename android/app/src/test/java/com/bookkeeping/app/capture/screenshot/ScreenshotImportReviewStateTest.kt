package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.capture.screenshot.ScreenshotImportReviewState.Status
import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import sheet lists every recognised transaction in one scrolling page.
 * These tests pin the behaviour of the old one-dialog-per-transaction queue:
 * all rows visible, one confirmation only affects its own row, ignoring never
 * reaches the ledger, and a row can never be handled twice.
 */
class ScreenshotImportReviewStateTest {

    private fun candidate(id: String, amountCents: Long = 1_800L): CaptureCandidate =
        CaptureCandidate(
            packageName = CaptureCandidate.WECHAT,
            pageIdentity = "com.tencent.mm|$id",
            amountCents = amountCents,
            merchantCandidates = listOf("廖工@百顺装修"),
            orderId = null,
            capturedAtMs = 1_700_000_000_000L,
            screenshotPath = null,
            confidence = 0.8f,
            reason = "ok",
            candidateId = id,
        )

    private fun stateOf(vararg ids: String): ScreenshotImportReviewState =
        ScreenshotImportReviewState().withCandidates(ids.map { candidate(it) })

    @Test
    fun everyCandidateIsVisibleAtOnce() {
        val state = stateOf("a", "b", "c")

        // Not a queue: every recognised transaction is on the page from the start.
        assertEquals(listOf("a", "b", "c"), state.rows.map { it.candidate.candidateId })
        assertEquals(3, state.pendingCount)
        assertEquals(listOf("a", "b", "c"), state.pending.map { it.candidateId })
        assertTrue(state.rows.all { it.status == Status.PENDING })
        assertFalse(state.isDone)
    }

    @Test
    fun confirmingOneRowRemovesOnlyThatRow() {
        val state = stateOf("a", "b", "c")
            .begin("b")
            .save("b")

        assertEquals(listOf("a", "c"), state.pending.map { it.candidateId })
        assertEquals(Status.SAVED, state.statusOf("b"))
        assertEquals(Status.PENDING, state.statusOf("a"))
        assertEquals(Status.PENDING, state.statusOf("c"))
        assertEquals(1, state.savedCount)
        assertEquals(listOf("b"), state.ledgerWrites.map { it.candidateId })
        assertFalse(state.isDone)
    }

    @Test
    fun isDoneOnlyAfterEveryRowIsSettled() {
        var state = stateOf("a", "b")

        state = state.begin("a").save("a")
        assertFalse("one row still waits for the user", state.isDone)

        state = state.begin("b").skip("b")
        assertTrue(state.isDone)
        assertEquals(1, state.savedCount)
        assertEquals(1, state.skippedCount)
        assertEquals(0, state.pendingCount)
    }

    @Test
    fun emptyImportIsNeverDone() {
        assertFalse(ScreenshotImportReviewState().isDone)
    }

    @Test
    fun skippingNeverWritesTheLedger() {
        val state = stateOf("a", "b").begin("a").skip("a")

        assertEquals(Status.SKIPPED, state.statusOf("a"))
        assertEquals(0, state.savedCount)
        assertTrue("ignoring must not produce a ledger write", state.ledgerWrites.isEmpty())
        assertEquals(listOf("b"), state.pending.map { it.candidateId })
    }

    @Test
    fun aSkippedRowCannotBeBookedLater() {
        val state = stateOf("a").begin("a").skip("a").begin("a").save("a")

        assertEquals(Status.SKIPPED, state.statusOf("a"))
        assertEquals(0, state.savedCount)
        assertTrue(state.ledgerWrites.isEmpty())
    }

    @Test
    fun theSameCandidateCannotBeHandledTwice() {
        val booked = stateOf("a").begin("a").save("a")
        val again = booked.begin("a").save("a")

        assertSame("a settled row ignores further edits", booked, again)
        assertEquals(1, again.savedCount)
        assertEquals(1, again.ledgerWrites.size)

        val skippedTwice = stateOf("a").begin("a").skip("a").skip("a")
        assertEquals(1, skippedTwice.skippedCount)
    }

    @Test
    fun aWriteInFlightIsNotClaimedTwice() {
        val claimed = stateOf("a").begin("a")

        assertEquals(Status.SAVING, claimed.statusOf("a"))
        assertFalse(claimed.canConfirm("a"))
        // A second tap while the write runs must not start another one.
        val second = claimed.begin("a")
        assertEquals(Status.SAVING, second.statusOf("a"))
        assertEquals(1, second.rows.size)
        assertTrue(second.ledgerWrites.isEmpty())
    }

    @Test
    fun aFailedWriteReturnsTheRowToPending() {
        val state = stateOf("a").begin("a").fail("a")

        assertEquals(Status.PENDING, state.statusOf("a"))
        assertTrue(state.canConfirm("a"))
        assertEquals(0, state.savedCount)
        assertTrue(state.ledgerWrites.isEmpty())
    }

    @Test
    fun newCandidatesAppendWithoutResettingFinishedRows() {
        val state = stateOf("a").begin("a").save("a")
            .withCandidates(listOf(candidate("b"), candidate("a")))

        assertEquals(listOf("a", "b"), state.rows.map { it.candidate.candidateId })
        assertEquals(Status.SAVED, state.statusOf("a"))
        assertEquals(Status.PENDING, state.statusOf("b"))
        assertEquals(1, state.pendingCount)
    }

    @Test
    fun unknownCandidateIsIgnored() {
        val state = stateOf("a")

        assertSame(state, state.begin("missing"))
        assertNull(state.statusOf("missing"))
        assertFalse(state.canConfirm("missing"))
    }
}
