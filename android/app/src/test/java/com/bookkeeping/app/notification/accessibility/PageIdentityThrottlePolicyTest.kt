package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageIdentityThrottlePolicyTest {
    private fun candidate(id: String, orderId: String? = null) = CaptureCandidate(
        packageName = null,
        pageIdentity = "merchant:Fixture:amount:1250:minute:10:35",
        amountCents = 1_250L,
        merchantCandidates = listOf("Fixture"),
        orderId = orderId,
        capturedAtMs = 10_000L,
        screenshotPath = null,
        confidence = 0.9f,
        reason = "synthetic same-text page",
        candidateId = id,
    )

    @Test
    fun `partial batch save leaves prior page throttle state unchanged`() {
        val previous = PageCaptureThrottleState(
            identity = "previous-page",
            eventId = "previous-order",
            capturedAtMs = 10_000L,
        )
        val saved = candidate("saved-candidate", orderId = "order-new")

        assertEquals(
            previous,
            PageCaptureThrottlePolicy.afterVerifiedBatch(
                current = previous,
                intendedCandidateCount = 2,
                saved = listOf(saved),
                failed = 1,
                savedAtMs = 11_000L,
            ),
        )
    }

    @Test
    fun `complete batch save advances page throttle from a verified row`() {
        val previous = PageCaptureThrottleState(
            identity = "previous-page",
            eventId = "previous-order",
            capturedAtMs = 10_000L,
        )
        val first = candidate("first-saved", orderId = " order-new ")
        val second = candidate("second-saved", orderId = "second-order")

        assertEquals(
            PageCaptureThrottleState(
                identity = first.pageIdentity,
                eventId = "order-new",
                capturedAtMs = 12_000L,
            ),
            PageCaptureThrottlePolicy.afterVerifiedBatch(
                current = previous,
                intendedCandidateCount = 2,
                saved = listOf(first, second),
                failed = 0,
                savedAtMs = 12_000L,
            ),
        )
    }

    @Test
    fun `saved count mismatch leaves page throttle state unchanged`() {
        val previous = PageCaptureThrottleState(
            identity = "previous-page",
            eventId = "previous-order",
            capturedAtMs = 10_000L,
        )

        assertEquals(
            previous,
            PageCaptureThrottlePolicy.afterVerifiedBatch(
                current = previous,
                intendedCandidateCount = 2,
                saved = listOf(candidate("only-saved", orderId = "order-new")),
                failed = 0,
                savedAtMs = 11_000L,
            ),
        )
    }

    @Test
    fun `failed single candidate save leaves page throttle state unchanged and verified save advances it`() {
        val previous = PageCaptureThrottleState(
            identity = "previous-page",
            eventId = "previous-order",
            capturedAtMs = 10_000L,
        )
        val afterFailure = PageCaptureThrottlePolicy.afterVerifiedSave(
            current = previous,
            stored = null,
            savedAtMs = 11_000L,
        )
        assertEquals(previous, afterFailure)

        val stored = candidate("saved-candidate", orderId = " order-new ")
        assertEquals(
            PageCaptureThrottleState(
                identity = stored.pageIdentity,
                eventId = "order-new",
                capturedAtMs = 12_000L,
            ),
            PageCaptureThrottlePolicy.afterVerifiedSave(
                current = afterFailure,
                stored = stored,
                savedAtMs = 12_000L,
            ),
        )
    }

    @Test
    fun `same merchant amount minute text without order id stays reviewable`() {
        val identity = "merchant:Fixture:amount:1250:minute:10:35"
        assertFalse(
            PageIdentityThrottlePolicy.shouldSuppress(
                previousIdentity = identity,
                previousEventId = null,
                previousCapturedAtMs = 10_000L,
                identity = identity,
                eventId = null,
                nowMs = 11_000L,
                windowMs = 5 * 60_000L,
            ),
        )
        assertFalse(
            PageIdentityThrottlePolicy.shouldSuppress(
                previousIdentity = identity,
                previousEventId = "order-123",
                previousCapturedAtMs = 10_000L,
                identity = identity,
                eventId = null,
                nowMs = 11_000L,
                windowMs = 5 * 60_000L,
            ),
        )
    }

    @Test
    fun `only the same stable event id can use the short page throttle`() {
        val previous = "merchant:Fixture:amount:1250:minute:10:35"
        assertTrue(
            PageIdentityThrottlePolicy.shouldSuppress(
                previousIdentity = previous,
                previousEventId = "order-123",
                previousCapturedAtMs = 10_000L,
                identity = previous,
                eventId = " order-123 ",
                nowMs = 11_000L,
                windowMs = 5 * 60_000L,
            ),
        )
        assertFalse(
            PageIdentityThrottlePolicy.shouldSuppress(
                previousIdentity = previous,
                previousEventId = "order-123",
                previousCapturedAtMs = 10_000L,
                identity = previous,
                eventId = "order-456",
                nowMs = 11_000L,
                windowMs = 5 * 60_000L,
            ),
        )
    }

    @Test
    fun `distinct same amount same text pages without exact transaction ids are not collapsed`() {
        val first = candidate("first-page")
        val second = candidate("second-page")
        assertNotEquals(first.candidateId, second.candidateId)
        assertEquals(first.amountCents, second.amountCents)
        assertEquals(first.pageIdentity, second.pageIdentity)

        assertFalse(
            PageIdentityThrottlePolicy.shouldSuppress(
                previousIdentity = first.pageIdentity,
                previousEventId = first.orderId,
                previousCapturedAtMs = first.capturedAtMs,
                identity = second.pageIdentity,
                eventId = second.orderId,
                nowMs = second.capturedAtMs + 1_000L,
                windowMs = 5 * 60_000L,
            ),
        )
    }

    @Test
    fun `missing screenshot evidence is routed to review instead of auto booking`() {
        val unavailable = candidate("missing-image")
            .copy(screenshotUnavailableReason = CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA)

        assertTrue(PageCaptureScreenshotPolicy.isUnavailable(unavailable, screenshot = null))
        assertTrue(PageCaptureScreenshotPolicy.isUnavailable(candidate("missing-bytes"), byteArrayOf(1)))
        assertFalse(
            PageCaptureScreenshotPolicy.isUnavailable(
                candidate("image-present").copy(screenshotPath = "/private/page_capture/shot_fixture.jpg"),
                screenshot = null,
            ),
        )
    }
}
