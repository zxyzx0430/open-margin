package com.bookkeeping.app.notification.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * R5: a later card may only close an earlier waiting placeholder when the
 * evidence positively identifies that same transaction.
 *
 * Two consecutive transfers to the same person for the same amount are
 * ordinary. Amount + direction + kind + merchant + a ten-minute window is
 * therefore never proof of identity on its own: when more than one waiting
 * item matches that shape, or when a matching item is identified by its own
 * order id, nothing may be settled. Guessing here marks a real pending
 * transaction as "already booked" and the user silently loses it.
 */
class ScreenshotAuditCorrelationTest {

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
    fun `one matching waiting candidate is still settled`() {
        val waiting = candidate(
            "waiting",
            status = CaptureCandidate.STATUS_WAITING,
            capturedAtMs = NOW - 3 * MINUTE,
        )

        assertEquals(
            waiting,
            ScreenshotWaitingCorrelation.settleTarget(candidate("completed"), listOf(waiting), NOW),
        )
    }

    @Test
    fun `two same merchant same amount waiting candidates settle neither`() {
        val first = candidate(
            "waiting-1",
            status = CaptureCandidate.STATUS_WAITING,
            capturedAtMs = NOW - 5 * MINUTE,
        )
        val second = candidate(
            "waiting-2",
            status = CaptureCandidate.STATUS_WAITING,
            capturedAtMs = NOW - 4 * MINUTE,
        )

        assertNull(
            "two real transfers of the same amount must not be merged",
            ScreenshotWaitingCorrelation.settleTarget(candidate("completed"), listOf(first, second), NOW),
        )

        // Store level: the ambiguous evidence leaves both placeholders waiting.
        val dir = Files.createTempDirectory("audit-correlation-ambiguous").toFile()
        try {
            val now = System.currentTimeMillis()
            val store = CaptureCandidateStore(dir)
            assertNotNull(store.save(first.copy(capturedAtMs = now - 5 * MINUTE), null))
            assertNotNull(store.save(second.copy(capturedAtMs = now - 4 * MINUTE), null))
            val completed = candidate("completed", capturedAtMs = now)
            assertNotNull(store.save(completed, null))

            assertNull(store.relatedWaiting(completed, now))
            assertEquals(
                listOf(CaptureCandidate.STATUS_WAITING, CaptureCandidate.STATUS_WAITING),
                store.latest().filter { it.candidateId.startsWith("waiting-") }.map { it.status },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an equal order id still settles among several candidates`() {
        val other = candidate(
            "waiting-1",
            status = CaptureCandidate.STATUS_WAITING,
            orderId = "4200000000000001",
            capturedAtMs = NOW - 5 * MINUTE,
        )
        val target = candidate(
            "waiting-2",
            status = CaptureCandidate.STATUS_WAITING,
            orderId = "4200000000000002",
            capturedAtMs = NOW - 4 * MINUTE,
        )

        assertEquals(
            target,
            ScreenshotWaitingCorrelation.settleTarget(
                candidate("completed", orderId = "4200000000000002"),
                listOf(other, target),
                NOW,
            ),
        )
    }

    @Test
    fun `an order id on one side only never settles`() {
        val waiting = candidate(
            "waiting",
            status = CaptureCandidate.STATUS_WAITING,
            capturedAtMs = NOW - MINUTE,
        )

        // The placeholder has no order id, the new page does: not the same proof.
        assertNull(
            ScreenshotWaitingCorrelation.settleTarget(
                candidate("completed", orderId = "4200000000000003"),
                listOf(waiting),
                NOW,
            ),
        )
        // The placeholder carries one, the new page does not: likewise nothing.
        assertNull(
            ScreenshotWaitingCorrelation.settleTarget(
                candidate("completed"),
                listOf(waiting.copy(orderId = "4200000000000003")),
                NOW,
            ),
        )
    }

    @Test
    fun `an opposite direction never settles`() {
        val waiting = candidate(
            "waiting",
            status = CaptureCandidate.STATUS_WAITING,
            direction = CaptureCandidate.DIRECTION_EXPENSE,
            capturedAtMs = NOW - 2 * MINUTE,
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
    fun `a waiting item that already carries an order id keeps the evidence ambiguous`() {
        // Two independent ¥200 transfers to 张三 three minutes apart. The second
        // page printed a trade number, the first did not. The completed page
        // below is a success bubble without a trade number, so it may describe
        // either transfer. Closing the id-less placeholder would drop the other
        // real transaction.
        val anonymous = candidate(
            "waiting-1",
            status = CaptureCandidate.STATUS_WAITING,
            capturedAtMs = NOW - 5 * MINUTE,
        )
        val identified = candidate(
            "waiting-2",
            status = CaptureCandidate.STATUS_WAITING,
            orderId = "4200000000000002",
            capturedAtMs = NOW - 2 * MINUTE,
        )

        assertNull(
            "an unidentified page matches more than one real transfer",
            ScreenshotWaitingCorrelation.settleTarget(candidate("completed"), listOf(anonymous, identified), NOW),
        )
    }
}
