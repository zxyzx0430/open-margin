package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the two taps users reported on the review dialog:
 *
 * 1. "ignore sometimes needs two taps" — a write in flight must only block the
 *    card being written, never the other cards.
 * 2. "after ignoring everything, one I already closed comes back" — two
 *    candidates read from one screenshot must stay two separate cards, and a
 *    failed mark must not claim success.
 */
class CaptureIgnoreReliabilityTest {

    private fun candidate(
        id: String,
        capturedAtMs: Long = NOW,
        fingerprint: String? = null,
    ): CaptureCandidate = CaptureCandidate(
        packageName = CaptureCandidate.WECHAT,
        pageIdentity = "screenshot:wechat:PAYMENT:$id",
        amountCents = 1_000L,
        merchantCandidates = listOf("店铺$id"),
        orderId = null,
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.66f,
        reason = "test",
        imageFingerprint = fingerprint,
        candidateId = id,
    )

    /** Every status record the store appended for one card and status. */
    private fun statusRecords(root: File, candidateId: String, status: String): Int {
        val index = File(File(root, "page_capture"), "candidates.jsonl")
        return index.readLines().count { line ->
            val json = JSONObject(line)
            json.optString("candidateId") == candidateId && json.optString("status") == status
        }
    }

    @Test
    fun `ignoring the first candidate keeps the second one`() {
        val first = candidate("first", capturedAtMs = NOW)
        val second = candidate("second", capturedAtMs = NOW + 1_000)
        val queue = CaptureReviewQueue(listOf(first, second))

        assertTrue("the first tap must be accepted", queue.begin(first))
        assertEquals(listOf("second"), queue.pending().map { it.candidateId })

        assertEquals(IgnoreOutcome.IGNORED, queue.settleIgnore(first, marked = true))
        assertEquals(
            "the sibling card must stay after the first one is ignored",
            listOf("second"),
            queue.pending().map { it.candidateId },
        )
        assertEquals("second", queue.pending().single().candidateId)
    }

    @Test
    fun `two candidates from one screenshot are both listed`() {
        val root = Files.createTempDirectory("capture-ignore-fingerprint").toFile()
        try {
            val store = CaptureCandidateStore(root)
            val fingerprint = "a".repeat(64)
            assertNotNull(store.save(candidate("first", NOW, fingerprint), null))
            assertNotNull(store.save(candidate("second", NOW + 1_000, fingerprint), null))

            assertEquals(
                "one screenshot with two payments must show two cards, not one",
                listOf("first", "second"),
                store.pending().map { it.candidateId },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a card in flight does not swallow a tap on another card`() {
        val a = candidate("a", capturedAtMs = NOW)
        val b = candidate("b", capturedAtMs = NOW + 1_000)
        val queue = CaptureReviewQueue(listOf(a, b))

        assertTrue(queue.begin(a))
        assertTrue("a tap on B must not be dropped while A is being written", queue.begin(b))
        assertEquals(setOf("a", "b"), queue.busyIds())
    }

    @Test
    fun `a second tap on the same card does not write twice`() {
        val root = Files.createTempDirectory("capture-ignore-double-tap").toFile()
        try {
            val store = CaptureCandidateStore(root)
            val a = candidate("a", capturedAtMs = NOW)
            assertNotNull(store.save(a, null))
            val queue = CaptureReviewQueue(store.pending())

            // Same order as CaptureReviewViewModel.ignore(): begin() gates the write.
            assertTrue(queue.begin(a))
            assertTrue(store.mark(a, CaptureCandidate.STATUS_IGNORED))
            assertFalse("the second tap on the same card must be refused", queue.begin(a))
            queue.settleIgnore(a, marked = true)

            assertEquals(1, statusRecords(root, "a", CaptureCandidate.STATUS_IGNORED))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a failed mark keeps the card and reports failure`() {
        val a = candidate("a", capturedAtMs = NOW)
        val queue = CaptureReviewQueue(listOf(a))

        assertTrue(queue.begin(a))
        assertEquals(IgnoreOutcome.MARK_FAILED, queue.settleIgnore(a, marked = false))

        assertEquals(
            "a failed mark must put the card back so the user can retry",
            listOf("a"),
            queue.pending().map { it.candidateId },
        )
        assertEquals("候选状态未能更新，请重试", CaptureReviewText.ignore(IgnoreOutcome.MARK_FAILED))
        assertFalse(
            "a failed mark must never claim the capture result was ignored",
            CaptureReviewText.ignore(IgnoreOutcome.MARK_FAILED) == "扫描结果已忽略",
        )
    }

    @Test
    fun `a successful mark removes the card and reports success`() {
        val a = candidate("a", capturedAtMs = NOW)
        val queue = CaptureReviewQueue(listOf(a))

        assertTrue(queue.begin(a))
        assertEquals(IgnoreOutcome.IGNORED, queue.settleIgnore(a, marked = true))
        assertTrue("a marked card must leave the pending list", queue.pending().isEmpty())
        assertEquals("扫描结果已忽略", CaptureReviewText.ignore(IgnoreOutcome.IGNORED))
    }

    @Test
    fun `an empty queue is safe to drive`() {
        val queue = CaptureReviewQueue()
        assertTrue(queue.pending().isEmpty())
        assertTrue(queue.busyIds().isEmpty())
        assertFalse("a card that is not on screen must not start a write", queue.begin(candidate("ghost")))

        val root = Files.createTempDirectory("capture-ignore-empty").toFile()
        try {
            assertTrue(CaptureCandidateStore(root).pending().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        /** Retention prunes anything older than seven days, so anchor on wall clock. */
        val NOW = System.currentTimeMillis()
    }
}
