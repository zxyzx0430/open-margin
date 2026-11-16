package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.merchant.MerchantRules
import kotlin.math.abs

/**
 * 采集自动合并规则：同一笔支付被两条采集路径各扫一次时，合成一条记录。
 *
 * 典型场景：微信通知文本只拿到金额（商户为空），支付成功页拿到完整商户名。
 * 过去这种组合会插入两条记录并弹「待确认重复」卡片，现在直接写回先入库的
 * 那条，把新记录里的有效字段补进去，不再打断用户。
 *
 * 纯逻辑，无 Android 依赖，可直接跑 JVM 单测。
 *
 * 与 [DuplicateReviewPolicy] 的分工：
 * - 本规则只处理「证据足够、可确定是同一笔」的情况（同来源 + 同金额方向币种
 *   + 120 秒内 + 商户/订单号互证），命中即自动合并，不再插入新行。
 * - [DuplicateReviewPolicy] 的 3 分钟窗口继续兜底「可能重复」：跨来源、商户
 *   明显不同、或超时的记录仍然入库并弹人工确认卡片。
 */
object CaptureMergePolicy {
    /**
     * 自动合并窗口（120 秒）。刻意小于 [DuplicateReviewPolicy.WINDOW_MS]
     * （3 分钟的可能重复窗口）：窗口内证据充分才自动合并，窗口外一律人工确认。
     */
    const val AUTO_MERGE_WINDOW_MS: Long = 120_000L

    const val STATUS_POSSIBLE_DUPLICATE: String = "possible_duplicate"

    /**
     * 被合并掉的那条记录复用现有「不计入」语义（excluded）：所有统计、预算、
     * 日历查询本来就会跳过它，无需改数据库结构，也不引入新状态值。
     * 与用户手动「不计入」的区别是 duplicateOfId 仍指向保留的那条。
     */
    const val STATUS_MERGED_DUPLICATE: String = "excluded"

    private const val STATUS_UNVERIFIED: String = "unverified"

    /**
     * 采集路径没拿到真实商户时写下的占位名。与 CategoryLearning.WEAK_MERCHANTS
     * 保持一致：这些名字不构成「一方有商户」的证据。
     */
    private val PLACEHOLDER_MERCHANTS = setOf(
        "微信支付", "支付宝", "微信", "云闪付", "未知商户", "未知商家", "其他", "账单", "页面",
    )

    /** 用户/规则已经明确「不计入」的状态，不能作为合并目标，也不能被合并掉。 */
    private val NON_MERGEABLE_STATUSES = setOf("excluded", "refund", "transfer")

    /** 空商户、占位商户都算「没有商户信息」。 */
    fun hasMerchantInfo(merchant: String): Boolean {
        val normalized = MerchantRules.normalize(merchant)
        return normalized.isNotEmpty() && normalized !in PLACEHOLDER_MERCHANTS
    }

    /**
     * 已经是「自动合并后保留的那条」的对侧记录：excluded + 指向保留行。
     * 列表默认不显示它，待确认卡片也不再弹出。
     */
    fun isAutoMergedDuplicate(transaction: TransactionEntity): Boolean =
        transaction.duplicateOfId != null &&
            transaction.remoteReviewStatus == STATUS_MERGED_DUPLICATE

    /** 仍需用户手工确认的重复记录（现有卡片的数据来源）。 */
    fun needsManualReview(transaction: TransactionEntity): Boolean {
        if (transaction.syncDeleted) return false
        if (isAutoMergedDuplicate(transaction)) return false
        return transaction.duplicateOfId != null ||
            transaction.remoteReviewStatus == STATUS_POSSIBLE_DUPLICATE
    }

    /** 可以作为合并目标 / 被合并掉的行：未删除、未被合并过、不是转账退款。 */
    fun canReceiveMerge(transaction: TransactionEntity): Boolean =
        !transaction.syncDeleted &&
            !isAutoMergedDuplicate(transaction) &&
            transaction.remoteReviewStatus !in NON_MERGEABLE_STATUSES

    /**
     * 判定两条记录是同一笔支付。
     *
     * 硬条件：金额、方向、币种、来源全部相同，且时间差 ≤ [AUTO_MERGE_WINDOW_MS]。
     * 证据条件（任一）：
     * 1. 一方没有商户信息（空或占位名），另一方有；
     * 2. 两边商户按 [MerchantRules.normalize] 归一后相同（分店后缀会被去掉）；
     * 3. 一方有订单号、另一方没有，且两边商户互不矛盾（归一后一方包含另一方）。
     *
     * 两边都有商户且明显不同（归一后既不相等也不互相包含）时绝不自动合并，
     * 即使一方带订单号——那更可能是两笔真实支付。
     */
    fun isSamePayment(existing: TransactionEntity, candidate: TransactionEntity): Boolean {
        if (existing.id != 0L && existing.id == candidate.id) return false
        if (existing.syncDeleted || candidate.syncDeleted) return false
        if (existing.amountCents != candidate.amountCents) return false
        if (existing.type != candidate.type) return false
        if (!existing.currency.equals(candidate.currency, ignoreCase = true)) return false
        if (existing.source != candidate.source) return false
        if (abs(existing.timestamp - candidate.timestamp) > AUTO_MERGE_WINDOW_MS) return false

        val existingMerchant = MerchantRules.normalize(existing.merchant)
        val candidateMerchant = MerchantRules.normalize(candidate.merchant)
        val existingHasMerchant = hasMerchantInfo(existing.merchant)
        val candidateHasMerchant = hasMerchantInfo(candidate.merchant)
        if (!existingHasMerchant || !candidateHasMerchant) return true
        if (existingMerchant == candidateMerchant) return true

        val oneSideHasOrderId =
            existing.sourceTransactionId.isNullOrBlank() !=
                candidate.sourceTransactionId.isNullOrBlank()
        return oneSideHasOrderId && merchantsRelated(existingMerchant, candidateMerchant)
    }

    /**
     * 把 [incoming] 里的有效信息补进 [keep]，返回要写回数据库的那一行。
     *
     * 只填空缺 / 明显更具体的字段，绝不把富记录降级：
     * 保留 id、时间、金额、来源、同步字段；商户为空的旧记录会拿到新商户，
     * 商户只是「禧迎迎」这类截断名时会升级成支付成功页的完整名字。
     */
    fun mergeInto(keep: TransactionEntity, incoming: TransactionEntity): TransactionEntity {
        val keepHasMerchant = hasMerchantInfo(keep.merchant)
        val incomingHasMerchant = hasMerchantInfo(incoming.merchant)
        val takeIncomingMerchant = when {
            !keepHasMerchant -> incomingHasMerchant
            !incomingHasMerchant -> false
            else -> isMoreSpecificMerchant(incoming.merchant, keep.merchant)
        }
        val merchant = if (takeIncomingMerchant) incoming.merchant.trim() else keep.merchant
        val category = when {
            keep.category == TransactionCategory.OTHER.name &&
                incoming.category != TransactionCategory.OTHER.name -> incoming.category
            else -> keep.category
        }
        return keep.copy(
            merchant = merchant,
            category = category,
            sourceTransactionId = keep.sourceTransactionId?.takeIf { it.isNotBlank() }
                ?: incoming.sourceTransactionId?.takeIf { it.isNotBlank() },
            account = keep.account?.takeIf { it.isNotBlank() }
                ?: incoming.account?.takeIf { it.isNotBlank() },
            note = keep.note.takeIf { it.isNotBlank() } ?: incoming.note,
            merchantIconKey = if (takeIncomingMerchant) {
                incoming.merchantIconKey ?: keep.merchantIconKey
            } else {
                keep.merchantIconKey ?: incoming.merchantIconKey
            },
            merchantIconPath = if (takeIncomingMerchant) {
                incoming.merchantIconPath ?: keep.merchantIconPath
            } else {
                keep.merchantIconPath ?: incoming.merchantIconPath
            },
            captureConfidence = listOfNotNull(keep.captureConfidence, incoming.captureConfidence)
                .maxOrNull(),
            captureKind = keep.captureKind ?: incoming.captureKind,
            captureObservedAtMs = keep.captureObservedAtMs ?: incoming.captureObservedAtMs,
            // 合并后这条不再是「待确认」：清掉本地标记，状态回到未核实。
            duplicateOfId = null,
            remoteReviewStatus = if (keep.remoteReviewStatus == STATUS_POSSIBLE_DUPLICATE) {
                STATUS_UNVERIFIED
            } else {
                keep.remoteReviewStatus
            },
        )
    }

    /**
     * 从窗口内同金额的候选行里挑出自动合并的目标：证据充分的同来源记录中
     * id 最小（先入库）的那条。没有证据返回 null，交回 [DuplicateReviewPolicy]。
     */
    fun findMergeTarget(
        candidates: List<TransactionEntity>,
        incoming: TransactionEntity,
    ): TransactionEntity? =
        candidates
            .asSequence()
            .filter { canReceiveMerge(it) }
            .filter { isSamePayment(it, incoming) }
            .minByOrNull { it.id }

    /** 一次性纠正的结果：保留 [keep]（已补全），把 [absorbed] 标成已合并。 */
    data class RepairPlan(
        val keep: TransactionEntity,
        val absorbed: TransactionEntity,
    )

    /**
     * 历史遗留的重复对（已经各插了一条）的纠正方案。
     * 保留信息更全的那条，把穷的那条标记为「已合并」而不是物理删除。
     * 不满足同一笔条件时返回 null，交给人工确认。
     */
    fun planRepair(first: TransactionEntity, second: TransactionEntity): RepairPlan? {
        if (!canReceiveMerge(first) || !canReceiveMerge(second)) return null
        if (!isSamePayment(first, second)) return null
        val keep = if (isRicher(first, second)) first else second
        val absorbed = if (keep.id == first.id) second else first
        val merged = mergeInto(keep, absorbed)
        return RepairPlan(
            keep = merged,
            absorbed = absorbed.copy(
                duplicateOfId = merged.id,
                remoteReviewStatus = STATUS_MERGED_DUPLICATE,
            ),
        )
    }

    /** 信息量打分，只用于选出「保留哪一条」。 */
    fun informationScore(transaction: TransactionEntity): Int =
        (if (hasMerchantInfo(transaction.merchant)) 4 else 0) +
            (if (!transaction.sourceTransactionId.isNullOrBlank()) 2 else 0) +
            (if (transaction.category != TransactionCategory.OTHER.name) 1 else 0) +
            (if (transaction.note.isNotBlank()) 1 else 0) +
            (if (!transaction.account.isNullOrBlank()) 1 else 0) +
            (if (transaction.merchantIconKey != null) 1 else 0)

    private fun isRicher(first: TransactionEntity, second: TransactionEntity): Boolean {
        val firstScore = informationScore(first)
        val secondScore = informationScore(second)
        return if (firstScore != secondScore) firstScore > secondScore else first.id <= second.id
    }

    /** 支付成功页的「XX超市(岭南店)」比通知里的「XX超市」更具体，但必须是同一串名字。 */
    private fun isMoreSpecificMerchant(incoming: String, existing: String): Boolean {
        val incomingKey = MerchantRules.normalize(incoming)
        val existingKey = MerchantRules.normalize(existing)
        if (incomingKey == existingKey) return false
        if (!merchantsRelated(incomingKey, existingKey)) return false
        return incomingKey.length > existingKey.length
    }

    private fun merchantsRelated(first: String, second: String): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        val shorter = if (first.length <= second.length) first else second
        val longer = if (shorter == first) second else first
        return shorter.length >= 2 && longer.contains(shorter)
    }
}
