package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import kotlin.math.abs

/**
 * DuplicateResolver（蓝图 M5）：判断一个已通过 SettlementGate 的候选
 * 与已有账目是否描述同一次资金变动。
 *
 * 决策顺序（蓝图定死，不允许只靠金额合并）：
 *  1. 交易号相同 → 同一笔（SAME）
 *  2. 同来源、金额/方向/商户一致且时间在重放窗口内 → 同一笔（通知重放）
 *  3. 不同来源、金额/方向一致、时间接近、商户相符 → 同一笔（跨渠道证据合并）
 *  4. 金额/方向一致、时间接近，但商户证据不足 → POSSIBLE（进待核对，不自动合并）
 *  5. 其余 → NEW。金额相同本身永远不构成合并理由（T09/T12 两笔真实同额消费）。
 *
 * 合并只补证据，不覆盖用户手工改过的商户/分类 —— 那一步在账本接入层处理，
 * 本类只做判定，保持纯逻辑可测。
 */
object DuplicateResolver {

    /** 同一通知被重投/重连后再次到达的窗口。 */
    const val SAME_SOURCE_REPLAY_WINDOW_MS: Long = 30_000L

    /** 跨渠道（微信通知 vs 银行短信）描述同一笔的合理时间差。 */
    const val CROSS_SOURCE_WINDOW_MS: Long = 180_000L

    data class ExistingTx(
        val id: Long,
        val amountCents: Long,
        val type: TransactionType,
        val occurredAtMs: Long,
        val source: TransactionSource,
        val merchant: String = "",
        val sourceTransactionId: String? = null,
    )

    enum class Decision { NEW, SAME, POSSIBLE }

    data class Result(val decision: Decision, val matchedId: Long? = null)

    fun resolve(candidate: TransactionCandidate, existing: List<ExistingTx>): Result {
        val sameAmountAndDirection = existing.filter {
            it.amountCents == candidate.amountCents && it.type == candidate.type
        }
        if (sameAmountAndDirection.isEmpty()) return Result(Decision.NEW)

        // 1. 交易号是最高优先级证据。
        val candidateTxId = candidate.sourceTransactionId?.takeIf { it.isNotBlank() }
        if (candidateTxId != null) {
            val byId = sameAmountAndDirection.firstOrNull { it.sourceTransactionId == candidateTxId }
            if (byId != null) return Result(Decision.SAME, byId.id)
        }

        val candidateMerchant = candidate.merchant.trim()

        // 2. 同来源重放：同商户 + 窗口内。
        val replay = sameAmountAndDirection
            .filter { it.source == candidate.source }
            .filter { abs(it.occurredAtMs - candidate.occurredAtMs) <= SAME_SOURCE_REPLAY_WINDOW_MS }
            .filter { candidateMerchant.isNotEmpty() && it.merchant.trim() == candidateMerchant }
            .minByOrNull { abs(it.occurredAtMs - candidate.occurredAtMs) }
        if (replay != null) return Result(Decision.SAME, replay.id)

        // 3/4. 跨来源：商户对得上才自动合并，否则只标 POSSIBLE。
        val cross = sameAmountAndDirection
            .filter { it.source != candidate.source }
            .filter { abs(it.occurredAtMs - candidate.occurredAtMs) <= CROSS_SOURCE_WINDOW_MS }
            .sortedBy { abs(it.occurredAtMs - candidate.occurredAtMs) }
        val crossMerchantMatch = cross.firstOrNull {
            candidateMerchant.isNotEmpty() && it.merchant.trim() == candidateMerchant
        }
        if (crossMerchantMatch != null) return Result(Decision.SAME, crossMerchantMatch.id)
        if (cross.isNotEmpty()) return Result(Decision.POSSIBLE, cross.first().id)

        // 5. 金额相同但证据不足或时间不符：独立交易。
        return Result(Decision.NEW)
    }
}
