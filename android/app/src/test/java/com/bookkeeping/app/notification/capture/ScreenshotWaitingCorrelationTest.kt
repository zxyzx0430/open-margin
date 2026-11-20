package com.bookkeeping.app.notification.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Late evidence must settle the earlier waiting candidate instead of creating a
 * second actionable item, and ambiguity must change nothing. Both rules are pure
 * logic, so they are pinned here without Android.
 */
class ScreenshotWaitingCorrelationTest {

    private companion object {
        const val NOW = 1_795_064_400_000L // 2026-11-19 13:00 Asia/Shanghai
        const val MINUTE = 60_000L
    }

    private fun candidate(
        candidateId: String,
        status: String = CaptureCandidate.STATUS_PENDING,
        kind: String = CaptureCandidate.KIND_TRANSFER,
        amountCents: Long = 20_000L,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        merchant: String? = "张三",
        orderId: String? = null,
        capturedAtMs: Long = NOW,
        completionStatus: String = CaptureCandidate.COMPLETION_COMPLETED,
        platform: String = CaptureCandidate.PLATFORM_WECHAT,
    ) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:$kind:$amountCents",
        amountCents = amountCents,
        merchantCandidates = listOfNotNull(merchant),
        orderId = orderId,
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.6f,
        reason = "test candidate",
        status = status,
        kind = kind,
        eventTimeMs = capturedAtMs,
        direction = direction,
        paymentPlatform = platform,
        confidenceTier = CaptureCandidate.TIER_MEDIUM,
        completionStatus = completionStatus,
        candidateId = candidateId,
    )

    @Test
    fun `only completed evidence settles a waiting candidate`() {
        assertTrue(ScreenshotWaitingCorrelation.isSettlingEvidence(candidate("a")))
        listOf(
            CaptureCandidate.COMPLETION_PENDING,
            CaptureCandidate.COMPLETION_CREATED,
            CaptureCandidate.COMPLETION_CANCELLED,
            CaptureCandidate.COMPLETION_FAILED,
            CaptureCandidate.COMPLETION_UNKNOWN,
        ).forEach { status ->
            assertFalse(
                "status $status must not settle a waiting candidate",
                ScreenshotWaitingCorrelation.isSettlingEvidence(candidate("a", completionStatus = status)),
            )
        }
    }

    @Test
    fun `completed transfer settles exactly one waiting transfer`() {
        val waiting = candidate("waiting", status = CaptureCandidate.STATUS_WAITING, capturedAtMs = NOW - 3 * MINUTE)
        // OCR spacing differs between the two screenshots; the merchant comparison
        // is normalized, so both still describe the same counterparty.
        val completed = candidate("completed", merchant = "张 三")

        assertEquals(waiting, ScreenshotWaitingCorrelation.settleTarget(completed, listOf(waiting), NOW))
    }

    @Test
    fun `two plausible waiting candidates stay unresolved`() {
        val first = candidate("waiting-1", status = CaptureCandidate.STATUS_WAITING, capturedAtMs = NOW - 2 * MINUTE)
        val second = candidate("waiting-2", status = CaptureCandidate.STATUS_WAITING, capturedAtMs = NOW - 1 * MINUTE)
        val completed = candidate("completed")

        assertNull(ScreenshotWaitingCorrelation.settleTarget(completed, listOf(first, second), NOW))
    }

    @Test
    fun `a waiting candidate without a merchant is not settled by guesswork`() {
        val waiting = candidate(
            "waiting",
            status = CaptureCandidate.STATUS_WAITING,
            merchant = null,
            capturedAtMs = NOW - MINUTE,
        )

        assertNull(ScreenshotWaitingCorrelation.settleTarget(candidate("completed"), listOf(waiting), NOW))
        assertNull(
            ScreenshotWaitingCorrelation.settleTarget(
                candidate("completed", merchant = null),
                listOf(candidate("waiting", status = CaptureCandidate.STATUS_WAITING, capturedAtMs = NOW - MINUTE)),
                NOW,
            ),
        )
    }

    @Test
    fun `an exact order id settles across kind changes`() {
        // A future-debit placeholder (AUTO_PAY) is later charged as a payment page.
        val waiting = candidate(
            "waiting",
            status = CaptureCandidate.STATUS_WAITING,
            kind = CaptureCandidate.KIND_AUTO_PAY,
            orderId = "4200001234567890",
            capturedAtMs = NOW - 5 * MINUTE,
        )
        val completed = candidate("completed", kind = CaptureCandidate.KIND_PAYMENT, orderId = "4200001234567890")

        assertEquals(waiting, ScreenshotWaitingCorrelation.settleTarget(completed, listOf(waiting), NOW))
    }

    @Test
    fun `an order id that matches nothing never falls back to fuzzy fields`() {
        val waiting = candidate(
            "waiting",
            status = CaptureCandidate.STATUS_WAITING,
            orderId = "4200000000000001",
            capturedAtMs = NOW - MINUTE,
        )
        val completed = candidate("completed", orderId = "4200000000000002")

        assertNull(ScreenshotWaitingCorrelation.settleTarget(completed, listOf(waiting), NOW))
    }

    @Test
    fun `evidence outside the correlation window changes nothing`() {
        val waiting = candidate(
            "waiting",
            status = CaptureCandidate.STATUS_WAITING,
            capturedAtMs = NOW - 11 * MINUTE,
        )

        assertNull(ScreenshotWaitingCorrelation.settleTarget(candidate("completed"), listOf(waiting), NOW))
    }

    @Test
    fun `another platform or direction stays separate`() {
        val waiting = candidate("waiting", status = CaptureCandidate.STATUS_WAITING, capturedAtMs = NOW - MINUTE)

        assertNull(
            ScreenshotWaitingCorrelation.settleTarget(
                candidate("completed", platform = CaptureCandidate.PLATFORM_ALIPAY),
                listOf(waiting),
                NOW,
            ),
        )
        assertNull(
            ScreenshotWaitingCorrelation.settleTarget(
                candidate("completed", direction = CaptureCandidate.DIRECTION_INCOME),
                listOf(waiting),
                NOW,
            ),
        )
    }

    @Test
    fun `settled waiting transfer leaves one pending item and no duplicate`() {
        val root = Files.createTempDirectory("capture-store-waiting").toFile()
        try {
            val now = System.currentTimeMillis()
            val store = CaptureCandidateStore(root)
            val waiting = candidate(
                "waiting-1",
                status = CaptureCandidate.STATUS_WAITING,
                completionStatus = CaptureCandidate.COMPLETION_PENDING,
                capturedAtMs = now - 3 * MINUTE,
            )
            assertNotNull(store.save(waiting, null))

            val completed = candidate("completed-1", capturedAtMs = now)
            assertNotNull(store.save(completed, null))
            store.mark(completed, CaptureCandidate.STATUS_PENDING)

            val settled = store.relatedWaiting(completed, now)
            assertEquals("waiting-1", settled?.candidateId)
            assertTrue(store.mark(settled!!, CaptureCandidate.STATUS_LINKED))

            val pending = store.pending()
            assertEquals(1, pending.size)
            assertEquals("completed-1", pending.single().candidateId)
            // The settled placeholder is still on record, but no longer actionable.
            assertNull(store.relatedWaiting(completed, now))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `two waiting items are left untouched when the new evidence is ambiguous`() {
        val root = Files.createTempDirectory("capture-store-ambiguous").toFile()
        try {
            val now = System.currentTimeMillis()
            val store = CaptureCandidateStore(root)
            store.save(candidate("waiting-1", status = CaptureCandidate.STATUS_WAITING, capturedAtMs = now - 2 * MINUTE), null)
            store.save(candidate("waiting-2", status = CaptureCandidate.STATUS_WAITING, capturedAtMs = now - MINUTE), null)
            val completed = candidate("completed-1", capturedAtMs = now)
            store.save(completed, null)

            assertNull(store.relatedWaiting(completed, now))
            assertEquals(
                listOf(CaptureCandidate.STATUS_WAITING, CaptureCandidate.STATUS_WAITING),
                store.latest().filter { it.candidateId.startsWith("waiting-") }.map { it.status },
            )
            // The new evidence is still its own actionable item; only the
            // ambiguous waiting placeholders are untouched.
            assertEquals(listOf("completed-1"), store.pending().map { it.candidateId })
        } finally {
            root.deleteRecursively()
        }
    }
}
