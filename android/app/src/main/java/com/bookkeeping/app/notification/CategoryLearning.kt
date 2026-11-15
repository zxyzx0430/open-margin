package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantRules
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Counts user corrections per merchant. A single correction stays a suggestion;
 * two consistent corrections (and a strict majority) become a learned mapping.
 * Explicit merchant rules from the settings editor still live in MerchantRules
 * and win over learned mappings.
 */
object CategoryLearning {
    private val WEAK_MERCHANTS = setOf(
        "微信支付", "支付宝", "微信", "云闪付", "未知商户", "其他", "账单", "页面",
    )
    private val counts = ConcurrentHashMap<String, MutableMap<TransactionCategory, Int>>()
    private val disabled = ConcurrentHashMap.newKeySet<String>()
    private val loaded = AtomicBoolean(false)

    fun replace(
        values: Map<String, Map<TransactionCategory, Int>>,
        disabledKeys: Set<String> = emptySet(),
    ) {
        counts.clear()
        values.forEach { (merchant, categories) ->
            val key = MerchantRules.normalize(merchant)
            if (key.isNotBlank()) counts[key] = ConcurrentHashMap(categories)
        }
        disabled.clear()
        disabled.addAll(disabledKeys.map(MerchantRules::normalize))
        loaded.set(true)
    }

    fun record(merchant: String, category: TransactionCategory) {
        val key = MerchantRules.normalize(merchant)
        if (key.isBlank() || key in WEAK_MERCHANTS) return
        counts.computeIfAbsent(key) { ConcurrentHashMap() }
            .merge(category, 1, Int::plus)
    }

    fun bestCategory(merchant: String): TransactionCategory? {
        val key = MerchantRules.normalize(merchant)
        if (key.isBlank() || key in disabled) return null
        val categories = counts[key] ?: return null
        val total = categories.values.sum()
        if (total < 2) return null
        val sorted = categories.entries.sortedByDescending { it.value }
        val first = sorted.firstOrNull() ?: return null
        val second = sorted.getOrNull(1)
        if (first.value < 2) return null
        if (second != null && second.value == first.value) return null
        return first.key
    }

    fun snapshot(): Map<String, TransactionCategory> =
        counts.keys.mapNotNull { merchant -> bestCategory(merchant)?.let { merchant to it } }.toMap()

    fun countsSnapshot(): Map<String, Map<TransactionCategory, Int>> =
        counts.mapValues { (_, value) -> value.toMap() }

    fun disabledSnapshot(): Set<String> = disabled.toSet()

    fun disable(merchant: String) {
        val key = MerchantRules.normalize(merchant)
        if (key.isNotBlank()) disabled.add(key)
    }

    fun enable(merchant: String) {
        disabled.remove(MerchantRules.normalize(merchant))
    }

    fun isDisabled(merchant: String): Boolean =
        MerchantRules.normalize(merchant) in disabled

    fun remove(merchant: String) {
        val key = MerchantRules.normalize(merchant)
        counts.remove(key)
        disabled.remove(key)
    }

    fun clear() {
        counts.clear()
        disabled.clear()
    }

    fun isLoaded(): Boolean = loaded.get()
}
