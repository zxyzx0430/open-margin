package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PassivePaymentEvidencePolicyTest {
    private val now = 1_800_000_000_000L
    private fun card(amount: Long = 666L, order: String? = null, merchant: String = "Fixture shop",
        status: PaymentCardStatus = PaymentCardStatus.COMPLETED, kind: PaymentCardKind = PaymentCardKind.PAYMENT,
        direction: PaymentDirection = PaymentDirection.EXPENSE, review: Boolean = false) =
        PaymentCardDetection(PaymentCardSource.ALIPAY, kind, status, direction, amount,
            listOf(merchant), order, null, 0.9f, "fixture", null, "page", "card",
            completionExplicit = status == PaymentCardStatus.COMPLETED, reviewRequired = review)

    @Test fun historyWinsOverRowSuccessAndDetailLinks() {
        val texts = listOf("账单", "筛选", "本月支出", "Fixture merchant", "-6.66",
            "支付成功", "交易详情", "退款记录", "+256.04")
        assertTrue(PaymentCardDetector.isBillListPage(texts))
        val lines = texts.map { BillTextLine(it) }
        assertTrue(PaymentCardDetector.fromLines(PaymentCardDetector.ALIPAY, lines, now, 600,
            "history", allowUnattributedAmounts = false).isEmpty())
        val snapshot = ScreenSnapshot.of(PaymentCardDetector.ALIPAY, now,
            SnapshotNode(children = texts.map { SnapshotNode(text = it) }))
        assertTrue(PaymentCardDetector.fromSnapshot(PaymentCardDetector.ALIPAY, snapshot, now,
            allowUnattributedAmounts = false).isEmpty())
    }

    @Test fun historicalRefundAndStatisticsTitlesAreExcluded() {
        assertTrue(PaymentCardDetector.isBillListPage(listOf("退款记录", "退款成功", "退款金额 ￥256.04")))
        assertTrue(PaymentCardDetector.isBillListPage(listOf("账单统计", "收入", "支出", "付款成功")))
    }

    @Test fun realDetailAndWechatServiceStreamAreNotHistory() {
        assertFalse(PaymentCardDetector.isBillListPage(listOf("账单详情", "交易成功",
            "交易金额 ￥6.66", "商户名称 Fixture shop", "付款方式 银行卡")))
        assertFalse(PaymentCardDetector.isBillListPage(listOf("微信支付", "我的账单", "支付服务", "支付成功", "¥6.66")))
        assertFalse(PaymentCardDetector.isBillListPage(listOf("支付成功", "¥6.66", "查看全部账单", "账单详情",
            "交易单号 2025111422001234567890")))
    }

    @Test fun cancelledAndUnfinishedNeverEnterPassiveReview() {
        val completed = card()
        val raw = listOf(completed, card(status = PaymentCardStatus.CANCELLED),
            card(status = PaymentCardStatus.FAILED), card(status = PaymentCardStatus.PENDING),
            card(status = PaymentCardStatus.CREATED))
        val accepted = PassivePaymentEvidencePolicy.actionable(raw)
        assertEquals(listOf(completed), accepted)
        assertFalse(PassivePaymentEvidencePolicy.requiresOcr(raw, accepted))
    }

    @Test fun invalidGeometryUsesOcrButRealTransactionSurvivesEmptyOcr() {
        val real = card(review = true)
        assertTrue(PassivePaymentEvidencePolicy.requiresOcr(listOf(real), listOf(real)))
        assertEquals(listOf(real), PassivePaymentEvidencePolicy.selectAfterOcr(listOf(real), emptyList(), false))
        assertTrue(PassivePaymentEvidencePolicy.selectAfterOcr(listOf(real), emptyList(), true).isEmpty())
    }

    @Test fun unknownUnattributedTransferDoesNotMultiplyPayment() {
        val unknown = card(status = PaymentCardStatus.UNKNOWN, kind = PaymentCardKind.TRANSFER,
            direction = PaymentDirection.UNKNOWN)
        val real = card()
        assertEquals(listOf(real), PassivePaymentEvidencePolicy.actionable(listOf(real, unknown)))
    }

    @Test fun ocrPreservesIndependentStrongOrderIds() {
        val first = card(order = "a")
        val second = card(order = "b", amount = 25604L)
        val selected = PassivePaymentEvidencePolicy.selectAfterOcr(listOf(first, second), listOf(first), false)
        assertEquals(setOf("a", "b"), selected.map { it.orderId }.toSet())
        assertTrue(selected.single { it.orderId == "b" }.reviewRequired)
    }

    @Test fun sixCallbacksWithinSameVisitOnlyProduceOnePending() {
        val root = Files.createTempDirectory("same-visit").toFile()
        try {
            val store = CaptureCandidateStore(root)
            val base = PaymentCardReviewQueue.candidate(card(), now, "visit-one")!!
                .copy(merchantCandidates = emptyList())
            for (index in 0..5) {
                val candidate = base.copy(candidateId = "callback-$index",
                    pageIdentity = "frame-$index", capturedAtMs = now + index * 100L,
                    eventTimeMs = now + index * 100L)
                PaymentCardReviewQueue.saveForReview(listOf(candidate), store)
            }
            assertEquals(1, store.pending().size)
            // The same amount on a genuinely separate page visit is not merged.
            val independent = base.copy(candidateId = "second-visit", pageIdentity = "other-frame",
                pageVisitId = "visit-two", capturedAtMs = now + 700L, eventTimeMs = now + 700L)
            assertEquals(1, PaymentCardReviewQueue.saveForReview(listOf(independent), store).saved.size)
            assertEquals(2, store.pending().size)
        } finally { root.deleteRecursively() }
    }

    @Test fun strongOrderIdsAndActualEventTimesOverrideBurstHeuristic() {
        val first = PaymentCardReviewQueue.candidate(card(order = "a"), now, "visit")!!
        val second = first.copy(orderId = "b", candidateId = "second")
        assertFalse(PaymentCardReviewQueue.isDuplicatePending(second, listOf(first)))
        val actual = first.copy(orderId = null, eventTimeSource = CaptureCandidate.TIME_SOURCE_ACTUAL,
            eventTimeMs = now, eventTimeNeedsConfirmation = false)
        assertFalse(PaymentCardReviewQueue.isDuplicatePending(
            actual.copy(eventTimeMs = now + 500L, capturedAtMs = now + 500L), listOf(actual)))
    }

    @Test fun visitIdentitySurvivesCandidateJsonAndOldCandidatesRemainReadable() {
        val candidate = PaymentCardReviewQueue.candidate(card(), now, "visit")!!
        assertEquals("visit", CaptureCandidate.fromJson(candidate.toJson())?.pageVisitId)
        val old = candidate.toJson().apply { remove("pageVisitId") }
        assertNull(CaptureCandidate.fromJson(old)?.pageVisitId)
    }
}
