package com.bookkeeping.app.data.merchant

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity

/** Placeholder and payment-channel names must never be grouped or saved as rules. */
private val CLEANUP_PLACEHOLDER_MERCHANTS = setOf(
    "微信支付", "支付宝", "微信", "云闪付", "未知商户", "未知商家", "其他", "账单", "页面",
)

internal fun isCleanupSingletonMerchant(merchant: String): Boolean {
    val normalized = MerchantRules.normalize(merchant)
    return normalized.isBlank() || normalized in CLEANUP_PLACEHOLDER_MERCHANTS
}

internal data class OtherMerchantGroup(
    val key: String,
    val merchant: String,
    val singleId: Long?,
    val rows: List<TransactionEntity>,
)

internal fun otherMerchantGroups(
    rows: List<TransactionEntity>,
    minCount: Int = 2,
): List<OtherMerchantGroup> =
    rows.filter { !it.syncDeleted && it.category == TransactionCategory.OTHER.name }
        .groupBy {
            if (isCleanupSingletonMerchant(it.merchant)) {
                "id:${it.id}"
            } else {
                "merchant:${MerchantRules.normalize(it.merchant)}"
            }
        }
        .map { (key, group) ->
            OtherMerchantGroup(
                key = key,
                merchant = group.first().merchant,
                singleId = if (key.startsWith("id:")) group.first().id else null,
                rows = group,
            )
        }
        .filter { it.rows.size >= minCount }
        .sortedWith(
            compareByDescending<OtherMerchantGroup> { it.rows.size }
                .thenByDescending { group -> group.rows.maxOf { it.timestamp } }
                .thenBy { it.key },
        )

internal fun matchesOtherMerchant(
    row: TransactionEntity,
    merchant: String,
    singleId: Long? = null,
): Boolean {
    if (row.syncDeleted || row.category != TransactionCategory.OTHER.name) return false
    return if (isCleanupSingletonMerchant(merchant)) {
        singleId != null && row.id == singleId && isCleanupSingletonMerchant(row.merchant)
    } else {
        MerchantRules.normalize(row.merchant) == MerchantRules.normalize(merchant)
    }
}

internal fun markCategorized(
    row: TransactionEntity,
    category: String,
    now: Long,
    mutationId: String,
): TransactionEntity = row.copy(
    category = category,
    syncDirty = true,
    syncModifiedMs = now,
    syncMutationId = mutationId,
)
