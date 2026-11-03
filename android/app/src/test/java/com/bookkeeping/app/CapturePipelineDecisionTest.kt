package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.CandidateNature
import com.bookkeeping.app.notification.CompletionEvidence
import com.bookkeeping.app.notification.DuplicateResolver
import com.bookkeeping.app.notification.GateDecision
import com.bookkeeping.app.notification.SettlementGate
import com.bookkeeping.app.notification.TransactionCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 蓝图 T01–T17 中可由纯逻辑判定的合成回归用例。
 * 页面/真机类用例（T02/T06/T07/T10/T13/T14/T16/T17）不在本文件范围，
 * 需要真机样本与提取器测试，不在此假装覆盖。
 */
class CapturePipelineDecisionTest {

    private val t0 = 1_730_000_000_000L

    private fun candidate(
        amountCents: Long = 1300,
        type: TransactionType = TransactionType.EXPENSE,
        completion: CompletionEvidence = CompletionEvidence.SETTLED,
        occurredAtMs: Long = t0,
        source: TransactionSource = TransactionSource.WECHAT,
        merchant: String = "美宜佳",
        txId: String? = null,
    ) = TransactionCandidate(
        amountCents = amountCents,
        type = type,
        nature = CandidateNature.PAYMENT,
        completion = completion,
        occurredAtMs = occurredAtMs,
        observedAtMs = occurredAtMs,
        source = source,
        merchant = merchant,
        sourceTransactionId = txId,
    )

    private fun existing(
        id: Long = 1,
        amountCents: Long = 1300,
        type: TransactionType = TransactionType.EXPENSE,
        occurredAtMs: Long = t0,
        source: TransactionSource = TransactionSource.WECHAT,
        merchant: String = "美宜佳",
        txId: String? = null,
    ) = DuplicateResolver.ExistingTx(id, amountCents, type, occurredAtMs, source, merchant, txId)

    // ---------- SettlementGate ----------

    @Test
    fun `T03 buy-now-pay-later not yet charged is ignored`() {
        assertEquals(
            GateDecision.IGNORED,
            SettlementGate.evaluate(candidate(completion = CompletionEvidence.PENDING)),
        )
    }

    @Test
    fun `T11 failed charge is ignored`() {
        assertEquals(
            GateDecision.IGNORED,
            SettlementGate.evaluate(candidate(completion = CompletionEvidence.FAILED)),
        )
    }

    @Test
    fun `settled payment is accepted`() {
        assertEquals(GateDecision.ACCEPTED, SettlementGate.evaluate(candidate()))
    }

    @Test
    fun `no completion evidence goes to review, never guessed`() {
        assertEquals(
            GateDecision.REVIEW,
            SettlementGate.evaluate(candidate(completion = CompletionEvidence.NONE)),
        )
    }

    @Test
    fun `non-positive amount goes to review`() {
        assertEquals(GateDecision.REVIEW, SettlementGate.evaluate(candidate(amountCents = 0)))
    }

    // ---------- DuplicateResolver ----------

    @Test
    fun `T01 wechat payment plus bank sms with matching merchant merges`() {
        val result = DuplicateResolver.resolve(
            candidate(source = TransactionSource.BANK_SMS, occurredAtMs = t0 + 60_000),
            listOf(existing()),
        )
        assertEquals(DuplicateResolver.Decision.SAME, result.decision)
        assertEquals(1L, result.matchedId)
    }

    @Test
    fun `T01 variant cross-source but merchant evidence differs is only possible`() {
        val result = DuplicateResolver.resolve(
            candidate(source = TransactionSource.BANK_SMS, merchant = "财付通", occurredAtMs = t0 + 60_000),
            listOf(existing()),
        )
        assertEquals(DuplicateResolver.Decision.POSSIBLE, result.decision)
    }

    @Test
    fun `notification replay within window is same`() {
        val result = DuplicateResolver.resolve(
            candidate(occurredAtMs = t0 + 10_000),
            listOf(existing()),
        )
        assertEquals(DuplicateResolver.Decision.SAME, result.decision)
    }

    @Test
    fun `T08 reopening old transfer detail with same transaction id is same`() {
        val result = DuplicateResolver.resolve(
            candidate(occurredAtMs = t0 + 86_400_000, txId = "4200001234"),
            listOf(existing(txId = "4200001234")),
        )
        assertEquals(DuplicateResolver.Decision.SAME, result.decision)
    }

    @Test
    fun `T09 two real purchases same amount two minutes apart are both kept`() {
        val result = DuplicateResolver.resolve(
            candidate(occurredAtMs = t0 + 120_000),
            listOf(existing()),
        )
        assertEquals(DuplicateResolver.Decision.NEW, result.decision)
    }

    @Test
    fun `T12 same amount different merchant same day are both kept`() {
        val result = DuplicateResolver.resolve(
            candidate(merchant = "麦当劳", occurredAtMs = t0 + 3_600_000),
            listOf(existing()),
        )
        assertEquals(DuplicateResolver.Decision.NEW, result.decision)
    }

    @Test
    fun `income never merges with expense of same amount`() {
        val result = DuplicateResolver.resolve(
            candidate(type = TransactionType.INCOME, occurredAtMs = t0 + 5_000),
            listOf(existing()),
        )
        assertEquals(DuplicateResolver.Decision.NEW, result.decision)
    }
}
