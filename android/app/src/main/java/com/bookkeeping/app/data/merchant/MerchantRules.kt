package com.bookkeeping.app.data.merchant

import com.bookkeeping.app.data.local.entity.TransactionCategory
import java.util.Locale

/**
 * User corrections learned from manual category edits. The map is process-wide
 * and backed by DataStore; built-in keyword rules only run when this has no
 * entry for the normalized merchant.
 */
object MerchantRules {
    private val lock = Any()

    @Volatile
    private var rules: Map<String, TransactionCategory> = emptyMap()

    fun normalize(merchant: String): String =
        merchant
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
            .replace(Regex("[（(][^）)]*[）)]"), "")

    fun replace(values: Map<String, TransactionCategory>) {
        synchronized(lock) {
            rules = values.mapNotNull { (merchant, category) ->
                normalize(merchant).takeIf { it.isNotBlank() }?.let { it to category }
            }.toMap()
        }
    }

    fun put(merchant: String, category: TransactionCategory) {
        val key = normalize(merchant)
        if (key.isBlank()) return
        synchronized(lock) {
            rules = rules + (key to category)
        }
    }

    fun remove(merchant: String) {
        val key = normalize(merchant)
        if (key.isBlank()) return
        synchronized(lock) {
            rules = rules - key
        }
    }

    fun categoryFor(merchant: String): TransactionCategory? =
        rules[normalize(merchant)]

    fun snapshot(): Map<String, TransactionCategory> = rules

    fun clear() {
        synchronized(lock) {
            rules = emptyMap()
        }
    }
}
