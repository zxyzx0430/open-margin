package com.bookkeeping.app.notification.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCandidateTest {

    private fun candidate(
        packageName: String = CaptureCandidate.WECHAT,
        pageIdentity: String = "com.tencent.mm|abc",
        amountCents: Long = 1_524L,
        confidence: Float = 0.9f,
    ) = CaptureCandidate(
        packageName = packageName,
        pageIdentity = pageIdentity,
        amountCents = amountCents,
        merchantCandidates = listOf("赵一鸣"),
        orderId = "4200001234567890",
        capturedAtMs = 1_700_000_000_000L,
        screenshotPath = null,
        confidence = confidence,
        reason = "ok",
    )

    @Test
    fun validCandidatePasses() {
        assertTrue(candidate().isValid())
        assertTrue(candidate(packageName = CaptureCandidate.ALIPAY).isValid())
    }

    @Test
    fun invalidAmountPackageAndConfidenceAreRejected() {
        assertFalse(candidate(amountCents = 0L).isValid())
        assertFalse(candidate(packageName = "com.example.other").isValid())
        assertFalse(candidate(confidence = 1.1f).isValid())
        assertFalse(candidate(pageIdentity = "").isValid())
    }

    @Test
    fun tooManyMerchantCandidatesAreRejected() {
        val tooMany = candidate().copy(
            merchantCandidates = (1..CaptureCandidate.MAX_MERCHANTS + 1).map { "商户$it" },
        )
        assertFalse(tooMany.isValid())
    }

    @Test
    fun unknownDirectionAndKindAreRejected() {
        assertFalse(candidate().copy(direction = "SIDEWAYS").isValid())
        assertFalse(candidate().copy(kind = "UNKNOWN_KIND").isValid())
    }

    @Test
    fun stableKeySeparatesIncomeFromExpense() {
        val expense = candidate()
        val income = expense.copy(direction = CaptureCandidate.DIRECTION_INCOME)
        assertTrue(expense.stableKey() != income.stableKey())
    }

    @Test
    fun dedupKeyIgnoresPageIdentityButKeepsOrderIdentity() {
        val first = candidate(pageIdentity = "page-a")
        val sameCard = first.copy(pageIdentity = "page-b")
        assertTrue(first.dedupKey() == sameCard.dedupKey())

        val differentOrder = first.copy(orderId = "4200009999999999")
        assertTrue(first.dedupKey() != differentOrder.dedupKey())
    }

    @Test
    fun diagnosticAttemptIdSurvivesCandidateJsonAndOldJsonRemainsReadable() {
        val withAttempt = candidate().copy(diagnosticAttemptId = "attempt-stable")
        val roundTrip = CaptureCandidate.fromJson(withAttempt.toJson())
        assertTrue(roundTrip?.diagnosticAttemptId == "attempt-stable")

        val oldRecord = candidate().toJson().apply { remove("diagnosticAttemptId") }
        assertTrue(CaptureCandidate.fromJson(oldRecord)?.diagnosticAttemptId == null)
    }

    @Test
    fun dedupKeySeparatesAmountMerchantAndDirection() {
        val base = candidate().copy(orderId = null)
        assertTrue(base.dedupKey() != base.copy(amountCents = 1_525L).dedupKey())
        assertTrue(
            base.dedupKey() != base.copy(merchantCandidates = listOf("另一家")).dedupKey(),
        )
        assertTrue(
            base.dedupKey() != base.copy(direction = CaptureCandidate.DIRECTION_INCOME).dedupKey(),
        )
    }
}
