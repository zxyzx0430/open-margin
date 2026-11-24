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
    private val CHANNEL_PREFIX = Regex("^(支付宝|微信支付|财付通|银联|云闪付)[-—\\s:：]+")
    private val PARENTHETICAL = Regex("[（(][^）)]*[）)]")
    private val CARD_OR_STORE_TAIL = Regex("(?i)(?:[*xX]{2,}|[-\\s])\\d{1,4}$")
    private val BRANCH_SUFFIX = Regex("(?:第[0-9一二三四五六七八九十]+店|分店|门店|广场店|分号)$")

    @Volatile
    private var rules: Map<String, TransactionCategory> = emptyMap()

    fun normalize(merchant: String): String =
        merchant
            .trim()
            .replace(CHANNEL_PREFIX, "")
            .replace(PARENTHETICAL, "")
            .replace(CARD_OR_STORE_TAIL, "")
            .replace(BRANCH_SUFFIX, "")
            .replace(Regex("\\s+"), "")
            .lowercase(Locale.ROOT)

    fun matches(transactionMerchant: String, ruleMerchant: String): Boolean {
        val txKey = normalize(transactionMerchant)
        val ruleKey = normalize(ruleMerchant)
        if (txKey.isBlank() || ruleKey.isBlank()) return false
        if (txKey == ruleKey) return true
        return ruleKey.length >= 2 && txKey.contains(ruleKey)
    }

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

    fun categoryFor(merchant: String): TransactionCategory? {
        val key = normalize(merchant)
        if (key.isBlank()) return null
        val snapshot = rules
        snapshot[key]?.let { return it }
        return snapshot.entries
            .asSequence()
            .filter { (ruleKey, _) -> ruleKey.length >= 2 && key.contains(ruleKey) }
            .maxByOrNull { (ruleKey, _) -> ruleKey.length }
            ?.value
    }

    fun snapshot(): Map<String, TransactionCategory> = rules

    fun clear() {
        synchronized(lock) {
            rules = emptyMap()
        }
    }
}
