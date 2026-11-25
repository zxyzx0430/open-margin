package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic page fixtures exercise the same persistent review route used by the service. */
class PaymentCardReviewQueueTest {
    private val capturedAt = 1_800_000_000_000L

    @Test
    fun `accessibility tree multi-card page persists every card for separate confirmation`() = withStore { store ->
        val children = listOf(
            node("支付成功", 10),
            node("Fixture breakfast shop", 40),
            node("¥10.00", 70),
            node("交易关闭", 100),
            node("Fixture coffee shop", 130),
            node("¥25.00", 160),
        )
        val snapshot = ScreenSnapshot.of(
            PaymentCardDetector.WECHAT,
            capturedAt,
            SnapshotNode(children = children),
        )

        val detections = PaymentCardDetector.fromSnapshot(
            PaymentCardDetector.WECHAT,
            snapshot,
            capturedAt,
        )
        assertEquals(2, detections.size)
        val route = PaymentCardReviewQueue.route(
            detections = detections,
            capturedAtMs = capturedAt,
            store = store,
        )
        val result = (route as PaymentCardReviewQueue.Route.HeldForReview).saved

        assertEquals(PaymentCardReviewQueue.MULTI_CARD_REASON, route.reason)
        assertEquals(2, result.saved.size)
        assertEquals(0, result.failed)
        assertEquals(2, store.pending().size)
        assertEquals(setOf(1000L, 2500L), store.pending().map { it.amountCents }.toSet())
        assertTrue(store.pending().all { it.status == CaptureCandidate.STATUS_PENDING })
        assertTrue("the completed card is explicitly actionable", CaptureBookGate.mayConfirm(
            store.pending().single { it.amountCents == 1000L },
        ))
        assertFalse("the cancelled card remains visible but cannot be booked", CaptureBookGate.mayConfirm(
            store.pending().single { it.amountCents == 2500L },
        ))
    }

    @Test
    fun `OCR multi-card page persists every detection as pending confirmation`() = withStore { store ->
        val lines = listOf(
            line("支付成功", 10),
            line("Fixture breakfast shop", 40),
            line("¥10.00", 70),
            line("交易关闭", 100),
            line("Fixture coffee shop", 130),
            line("¥25.00", 160),
        )
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.ALIPAY,
            lines = lines,
            capturedAtMs = capturedAt,
            screenHeightPx = 600,
            pageIdentity = "fixture-ocr-multi-card",
        )
        val route = PaymentCardReviewQueue.route(
            detections = detections,
            capturedAtMs = capturedAt,
            store = store,
        )
        val result = (route as PaymentCardReviewQueue.Route.HeldForReview).saved

        assertEquals(2, result.saved.size)
        assertEquals(0, result.failed)
        assertEquals(PaymentCardReviewQueue.MULTI_CARD_REASON, route.reason)
        assertEquals(2, store.pending().size)
        assertEquals(setOf(1000L, 2500L), store.pending().map { it.amountCents }.toSet())
        assertTrue(store.pending().all { it.reason == PaymentCardReviewQueue.MULTI_CARD_REASON })
    }

    @Test
    fun `ambiguous OCR amounts with zero area bounds stay separate and reviewable`() = withStore { store ->
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = listOf(
                BillTextLine("支付成功"),
                BillTextLine("¥10.00", top = 0, bottom = 0, left = 0, right = 0),
                BillTextLine("¥20.00", top = 0, bottom = 0, left = 0, right = 0),
            ),
            capturedAtMs = capturedAt,
            screenHeightPx = 600,
            pageIdentity = "fixture-ocr-zero-area",
        )

        val route = PaymentCardReviewQueue.route(
            detections = detections,
            capturedAtMs = capturedAt,
            store = store,
        )
        val result = (route as PaymentCardReviewQueue.Route.HeldForReview).saved

        assertEquals(2, result.saved.size)
        assertEquals(0, result.failed)
        assertEquals(PaymentCardReviewQueue.AMBIGUOUS_GEOMETRY_REASON, route.reason)
        assertEquals(setOf(1_000L, 2_000L), store.pending().map { it.amountCents }.toSet())
        assertTrue(store.pending().all {
            it.completionStatus == CaptureCandidate.COMPLETION_UNKNOWN &&
                it.direction == CaptureCandidate.DIRECTION_UNKNOWN &&
                !CaptureBookGate.mayConfirm(it)
        })
    }

    @Test
    fun `one amount with all-zero OCR bounds is retained for user confirmation`() = withStore { store ->
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = listOf("支付成功", "Fixture breakfast shop", "¥12.34").map { text ->
                BillTextLine(text, top = 0, bottom = 0, left = 0, right = 0)
            },
            capturedAtMs = capturedAt,
            screenHeightPx = 600,
            pageIdentity = "fixture-single-amount-zero-bounds",
        )

        val candidate = assertSingleAmountHeldForReview(detections, store, 1_234L)

        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, candidate.completionStatus)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, candidate.direction)
        assertTrue("the retained candidate can be confirmed by the user", CaptureBookGate.mayConfirm(candidate))
    }

    @Test
    fun `one amount with missing and invalid OCR bounds is retained for user confirmation`() = withStore { store ->
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.ALIPAY,
            lines = listOf(
                BillTextLine("支付成功"), // Missing bounds.
                line("Fixture grocery shop", 40),
                BillTextLine("¥23.45", top = 120, bottom = 144, left = 60, right = 60), // Zero width.
            ),
            capturedAtMs = capturedAt,
            screenHeightPx = 600,
            pageIdentity = "fixture-single-amount-missing-invalid-bounds",
        )

        val candidate = assertSingleAmountHeldForReview(detections, store, 2_345L)

        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, candidate.completionStatus)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, candidate.direction)
        assertTrue("the retained candidate can be confirmed by the user", CaptureBookGate.mayConfirm(candidate))
    }

    @Test
    fun `complete card with valid OCR geometry remains on the normal single-candidate route`() = withStore { store ->
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = listOf(
                line("支付成功", 10),
                line("Fixture breakfast shop", 50),
                line("¥12.34", 90),
            ),
            capturedAtMs = capturedAt,
            screenHeightPx = 600,
            pageIdentity = "fixture-single-amount-valid-bounds",
        )

        assertEquals(1, detections.size)
        assertFalse(detections.single().reviewRequired)
        val route = PaymentCardReviewQueue.route(detections, capturedAt, store)

        assertTrue(route is PaymentCardReviewQueue.Route.SingleCandidate)
        val candidate = (route as PaymentCardReviewQueue.Route.SingleCandidate).candidate
        assertEquals(1_234L, candidate.amountCents)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, candidate.completionStatus)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, candidate.direction)
        assertTrue(CaptureBookGate.mayConfirm(candidate))
        assertTrue("normal route is not diverted to the review inbox", store.pending().isEmpty())
    }

    @Test
    fun `passive capture does not fan out ungrouped amounts from a payment page`() = withStore { store ->
        val lines = listOf("账单", "13.71", "27.40", "106.12", "8.42", "59.10", "22.50")
            .map { BillTextLine(it) }
        val cards = PaymentCardDetector.fromLines(
            PaymentCardDetector.ALIPAY, lines, capturedAt, 600, "user-a-burst",
            allowUnattributedAmounts = false,
        )
        assertTrue(cards.isEmpty())
        assertTrue(PaymentCardReviewQueue.route(cards, capturedAt, store) is
            PaymentCardReviewQueue.Route.NoBookableCandidate)
        assertTrue(store.pending().isEmpty())
        // Explicit preview retains the previously supported diagnostic candidates.
        assertTrue(PaymentCardDetector.fromLines(
            PaymentCardDetector.ALIPAY, lines, capturedAt, 600, "user-a-burst",
        ).isNotEmpty())
        val snapshot = ScreenSnapshot.of(PaymentCardDetector.ALIPAY, capturedAt,
            SnapshotNode(children = lines.map { SnapshotNode(text = it.text) }))
        assertTrue(PaymentCardDetector.fromSnapshot(
            PaymentCardDetector.ALIPAY, snapshot, capturedAt, allowUnattributedAmounts = false,
        ).isEmpty())
    }

    @Test
    fun `passive capture retains a complete card despite missing geometry and time`() = withStore { store ->
        val cards = PaymentCardDetector.fromLines(
            PaymentCardDetector.ALIPAY,
            listOf("支付成功", "Fixture grocery shop", "¥23.45").map { BillTextLine(it) },
            capturedAt, 600, "one-card-without-bounds",
            allowUnattributedAmounts = false,
        )
        val saved = assertSingleAmountHeldForReview(cards, store, 2345L)
        assertEquals(capturedAt, saved.eventTimeMs)
        assertEquals(CaptureCandidate.TIME_SOURCE_CAPTURE, saved.eventTimeSource)
        assertTrue(saved.eventTimeNeedsConfirmation)
        assertTrue(CaptureBookGate.mayConfirm(saved))
    }

    @Test
    fun `passive capture still keeps independent real cards`() = withStore { store ->
        val cards = PaymentCardDetector.fromLines(
            PaymentCardDetector.WECHAT,
            listOf("支付成功", "Fixture breakfast shop", "¥10.00",
                "支付成功", "Fixture coffee shop", "¥25.00").map { BillTextLine(it) },
            capturedAt, 600, "two-independent-cards",
            allowUnattributedAmounts = false,
        )
        assertEquals(2, cards.size)
        val route = PaymentCardReviewQueue.route(cards, capturedAt, store)
            as PaymentCardReviewQueue.Route.HeldForReview
        assertEquals(2, route.saved.saved.size)
    }

    @Test
    fun `concurrent repeated review batches save each candidate only once`() = withStore { store ->
        val prototype = CaptureCandidate(
            packageName = PaymentCardDetector.ALIPAY,
            pageIdentity = "same-page",
            amountCents = 1371L,
            merchantCandidates = listOf("Fixture store"),
            orderId = null,
            capturedAtMs = capturedAt,
            screenshotPath = null,
            confidence = 0.8f,
            reason = "fixture review",
            eventTimeSource = CaptureCandidate.TIME_SOURCE_CAPTURE,
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val tasks = (1..30).map {
                pool.submit<Int> {
                    start.await()
                    val candidate = prototype.copy(candidateId = java.util.UUID.randomUUID().toString())
                    PaymentCardReviewQueue.saveForReview(listOf(candidate), store).saved.size
                }
            }
            start.countDown()
            assertEquals(1, tasks.sumOf { it.get(60, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(1, store.pending().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `capture time fallback does not merge independent same amount pages`() = withStore { store ->
        val first = CaptureCandidate(
            packageName = PaymentCardDetector.ALIPAY,
            pageIdentity = "first-page",
            amountCents = 1000L,
            merchantCandidates = emptyList(),
            orderId = null,
            capturedAtMs = capturedAt,
            screenshotPath = null,
            confidence = 0.8f,
            reason = "fixture review",
            eventTimeSource = CaptureCandidate.TIME_SOURCE_CAPTURE,
        )
        val second = first.copy(
            candidateId = java.util.UUID.randomUUID().toString(),
            pageIdentity = "second-page",
            capturedAtMs = capturedAt + 1000L,
            eventTimeMs = capturedAt + 1000L,
        )
        assertEquals(2, PaymentCardReviewQueue.saveForReview(listOf(first, second), store).saved.size)
        assertEquals(2, store.pending().size)
    }

    @Test
    fun `same amount genuine transactions with different order IDs stay separate`() = withStore { store ->
        val first = CaptureCandidate(
            packageName = PaymentCardDetector.ALIPAY,
            pageIdentity = "same-page",
            amountCents = 1000L,
            merchantCandidates = listOf("Fixture store"),
            orderId = "order-001",
            capturedAtMs = capturedAt,
            screenshotPath = null,
            confidence = 0.8f,
            reason = "fixture review",
        )
        val second = first.copy(candidateId = java.util.UUID.randomUUID().toString(), orderId = "order-002")
        assertEquals(2, PaymentCardReviewQueue.saveForReview(listOf(first, second), store).saved.size)
    }

    @Test
    fun `passive capture recovers labeled actual charge without usable geometry`() = withStore { store ->
        val cards = PaymentCardDetector.fromLines(
            PaymentCardDetector.ALIPAY,
            listOf("支付成功", "Fixture shop", "订单金额 ¥30.00", "实付金额 ¥25.00")
                .map { BillTextLine(it) },
            capturedAt, 600, "actual-charge-detail",
            allowUnattributedAmounts = false,
        )
        val saved = assertSingleAmountHeldForReview(cards, store, 2500L)
        assertTrue(CaptureBookGate.mayConfirm(saved))
    }

    @Test
    fun `conflicting unlabeled amounts are not converted into invented payments`() = withStore { store ->
        val cards = PaymentCardDetector.fromLines(
            PaymentCardDetector.ALIPAY,
            listOf("支付成功", "Fixture shop", "¥30.00", "¥25.00")
                .map { BillTextLine(it) },
            capturedAt, 600, "conflicting-amounts",
            allowUnattributedAmounts = false,
        )
        assertTrue(cards.isEmpty())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `different merchants with same amount on same page remain separate`() = withStore { store ->
        val first = CaptureCandidate(
            packageName = PaymentCardDetector.ALIPAY,
            pageIdentity = "shared-multi-card-page",
            amountCents = 1000L,
            merchantCandidates = listOf("Fixture grocery"),
            orderId = null,
            capturedAtMs = capturedAt,
            screenshotPath = null,
            confidence = 0.8f,
            reason = "fixture review",
            eventTimeSource = CaptureCandidate.TIME_SOURCE_CAPTURE,
        )
        val second = first.copy(
            candidateId = java.util.UUID.randomUUID().toString(),
            merchantCandidates = listOf("Fixture cafe"),
        )
        assertEquals(2, PaymentCardReviewQueue.saveForReview(listOf(first, second), store).saved.size)
    }

    @Test
    fun `completed card alongside unfinished card enters normal single candidate route`() = withStore { store ->
        val completed = PaymentCardDetector.fromLines(
            PaymentCardDetector.WECHAT,
            listOf(line("支付成功", 10), line("Fixture shop", 40), line("¥10.00", 70)),
            capturedAt, 600, "mixed-status-page",
        ).single()
        val unfinished = completed.copy(
            status = PaymentCardStatus.PENDING,
            kind = PaymentCardKind.TRANSFER,
            amountCents = 2500L,
            orderId = "pending-001",
            cardKey = "unfinished",
            reviewRequired = true,
        )
        val cards = listOf(unfinished, completed)
        assertFalse(PaymentCardReviewQueue.requiresReview(cards))
        val route = PaymentCardReviewQueue.route(cards, capturedAt, store)
            as PaymentCardReviewQueue.Route.SingleCandidate
        assertEquals(1000L, route.candidate.amountCents)
        assertTrue(store.pending().isEmpty())
    }

    private fun assertSingleAmountHeldForReview(
        detections: List<PaymentCardDetection>,
        store: CaptureCandidateStore,
        amountCents: Long,
    ): CaptureCandidate {
        assertEquals(1, detections.size)
        val detection = detections.single()
        assertEquals(amountCents, detection.amountCents)
        assertTrue(detection.reviewRequired)

        val route = PaymentCardReviewQueue.route(detections, capturedAt, store)
        assertTrue("unusable geometry requires the persistent review route", route is PaymentCardReviewQueue.Route.HeldForReview)
        val held = route as PaymentCardReviewQueue.Route.HeldForReview
        assertEquals(PaymentCardReviewQueue.AMBIGUOUS_GEOMETRY_REASON, held.reason)
        assertEquals(1, held.saved.saved.size)
        assertEquals(0, held.saved.failed)

        val candidate = store.pending().single()
        assertEquals(amountCents, candidate.amountCents)
        assertEquals(CaptureCandidate.STATUS_PENDING, candidate.status)
        return candidate
    }

    private fun node(text: String, top: Int) = SnapshotNode(
        text = text,
        bounds = SnapshotRect(40, top, 1000, top + 24),
    )

    private fun line(text: String, top: Int) = BillTextLine(
        text = text,
        top = top,
        bottom = top + 24,
        left = 40,
        right = 1000,
    )

    private fun withStore(block: (CaptureCandidateStore) -> Unit) {
        val root = Files.createTempDirectory("payment-card-review").toFile()
        try {
            block(CaptureCandidateStore(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `lazy OCR evidence is only prepared once for new candidates and not for duplicates`() = withStore { store ->
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.ALIPAY,
            lines = listOf(
                line("支付成功", 10),
                line("Fixture breakfast shop", 40),
                line("¥10.00", 70),
                line("交易关闭", 100),
                line("Fixture coffee shop", 130),
                line("¥25.00", 160),
            ),
            capturedAtMs = capturedAt,
            screenHeightPx = 600,
            pageIdentity = "lazy-evidence",
        )
        var encodes = 0
        val provider: () -> ByteArray? = { encodes++; null }
        val first = PaymentCardReviewQueue.route(detections, capturedAt, store, screenshotProvider = provider)
            as PaymentCardReviewQueue.Route.HeldForReview
        assertEquals(2, first.saved.saved.size)
        assertEquals(1, encodes)
        val second = PaymentCardReviewQueue.route(detections, capturedAt, store, screenshotProvider = provider)
            as PaymentCardReviewQueue.Route.HeldForReview
        assertEquals(0, second.saved.saved.size)
        assertEquals(1, encodes)
        assertEquals(2, store.pending().size)
    }

    @Test
    fun `no OCR candidate never requests JPEG evidence`() = withStore { store ->
        var encoded = false
        val route = PaymentCardReviewQueue.route(emptyList(), capturedAt, store,
            screenshotProvider = { encoded = true; null })
        assertEquals(PaymentCardReviewQueue.Route.NoBookableCandidate, route)
        assertFalse(encoded)
    }

}
