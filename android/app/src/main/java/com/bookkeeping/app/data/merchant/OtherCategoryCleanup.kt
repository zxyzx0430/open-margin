package com.bookkeeping.app.data.merchant

import com.bookkeeping.app.data.local.entity.TransactionEntity

internal data class OtherMerchantGroup(val key: String, val merchant: String, val singleId: Long?, val rows: List<TransactionEntity>)

internal fun otherMerchantGroups(rows: List<TransactionEntity>): List<OtherMerchantGroup> =
    rows.filter { !it.syncDeleted && it.category == "OTHER" }.groupBy {
        val normalized = MerchantRules.normalize(it.merchant)
        if (normalized.isBlank()) "id:${it.id}" else "merchant:$normalized"
    }.map { (key, group) ->
        OtherMerchantGroup(key, group.first().merchant, if (key.startsWith("id:")) group.first().id else null, group)
    }.sortedWith(compareByDescending<OtherMerchantGroup> { it.rows.size }.thenBy { it.key })

internal fun matchesOtherMerchant(row: TransactionEntity, merchant: String, singleId: Long? = null): Boolean {
    if (row.syncDeleted || row.category != "OTHER") return false
    val key = MerchantRules.normalize(merchant)
    return if (key.isBlank()) singleId != null && row.id == singleId && MerchantRules.normalize(row.merchant).isBlank()
    else MerchantRules.normalize(row.merchant) == key
}

internal fun markCategorized(row: TransactionEntity, category: String, now: Long, mutationId: String): TransactionEntity =
    row.copy(category = category, syncDirty = true, syncModifiedMs = now, syncMutationId = mutationId)
