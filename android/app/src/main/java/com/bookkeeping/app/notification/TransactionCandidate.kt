package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType

/**
 * 交易候选（蓝图 M4）：采集层先把证据整理成候选，再决定是否入账。
 * Pure model — no Android imports, so the gate/resolver logic is JVM-testable.
 *
 * 核心原则（专项已确认）：只记真实资金变动。先用后付未扣款、扣款失败、
 * 促销金额都不是交易；证据不足时进 REVIEW，绝不靠猜测补出一笔账。
 */
data class TransactionCandidate(
    val amountCents: Long,
    val type: TransactionType,
    val nature: CandidateNature,
    val completion: CompletionEvidence,
    /** 资金实际发生时间（不是被观察/重开页面的时间）。 */
    val occurredAtMs: Long,
    /** 本次被系统观察到的时间。 */
    val observedAtMs: Long,
    val source: TransactionSource,
    /** 平台可提供的交易号；多数渠道拿不到，允许为空。 */
    val sourceTransactionId: String? = null,
    val merchant: String = "",
    /** 付款账户/银行卡摘要（如尾号），仅用于同笔证据核对。 */
    val accountHint: String = "",
    val failReason: String = "",
)

enum class CandidateNature { PAYMENT, TRANSFER, REFUND, RED_PACKET, UNKNOWN }

enum class CompletionEvidence {
    /** 已确认实际扣款/到账。 */
    SETTLED,

    /** 明确未完成：待扣款、待领取（对付款方需另看扣款证据）、处理中。 */
    PENDING,

    /** 明确失败：扣款失败、支付失败。 */
    FAILED,

    /** 没有读到完成态证据。 */
    NONE,
}

enum class GateDecision { ACCEPTED, REVIEW, IGNORED }

/**
 * SettlementGate（蓝图 M4）：候选进入去重之前的资金变动判定。
 * 决策顺序固定，避免各提取器各自为政。
 */
object SettlementGate {
    fun evaluate(candidate: TransactionCandidate): GateDecision = when {
        // 明确未发生的资金变动永远不入账（T03 先用后付未扣款、T11 扣款失败）。
        candidate.completion == CompletionEvidence.FAILED -> GateDecision.IGNORED
        candidate.completion == CompletionEvidence.PENDING -> GateDecision.IGNORED
        // 没有完成态证据：不猜，进待核对（T13 页面读不到金额/状态同理）。
        candidate.completion == CompletionEvidence.NONE -> GateDecision.REVIEW
        candidate.amountCents <= 0L -> GateDecision.REVIEW
        else -> GateDecision.ACCEPTED
    }
}
