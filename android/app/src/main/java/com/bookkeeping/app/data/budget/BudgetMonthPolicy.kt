package com.bookkeeping.app.data.budget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.bookkeeping.app.data.local.entity.TransactionCategory

/** Keys, legacy fallback and one-time migration rules for month-scoped budgets. */
object BudgetMonthPolicy {
    val MIGRATED = booleanPreferencesKey("month_scope_migrated_v1")

    private val budgetCategories: List<TransactionCategory> =
        TransactionCategory.entries.filter {
            it != TransactionCategory.SALARY && it != TransactionCategory.TRANSFER
        }

    fun totalKey(month: String) = longPreferencesKey("monthly_total_$month")
    fun categoryKey(month: String, category: TransactionCategory) =
        longPreferencesKey("cat_${category.name}_$month")

    fun legacyTotalKey() = longPreferencesKey("monthly_total")
    fun legacyCategoryKey(category: TransactionCategory) = longPreferencesKey("cat_${category.name}")

    fun settings(prefs: Preferences, month: String): BudgetSettings {
        val total = (prefs[totalKey(month)] ?: 0L).coerceAtLeast(0L)
        val perCategory = budgetCategories.mapNotNull { category ->
            val value = prefs[categoryKey(month, category)] ?: 0L
            if (value > 0L) category to value else null
        }.toMap()
        return BudgetSettings(monthlyTotalCents = total, perCategory = perCategory)
    }

    fun legacySettings(prefs: Preferences): BudgetSettings {
        val total = (prefs[legacyTotalKey()] ?: 0L).coerceAtLeast(0L)
        val perCategory = budgetCategories.mapNotNull { category ->
            val value = prefs[legacyCategoryKey(category)] ?: 0L
            if (value > 0L) category to value else null
        }.toMap()
        return BudgetSettings(monthlyTotalCents = total, perCategory = perCategory)
    }

    /**
     * Before migration is marked, the current month may still be served by the
     * legacy global keys. After the marker is set, an intentionally cleared month
     * must stay empty; it must not resurrect old values.
     */
    fun settingsWithLegacyFallback(
        prefs: Preferences,
        month: String,
        currentMonth: String,
    ): BudgetSettings {
        val scoped = settings(prefs, month)
        if (hasScopedValues(prefs, month)) return scoped
        if (month == currentMonth && prefs[MIGRATED] != true) return legacySettings(prefs)
        return scoped
    }

    /** Distinguishes "never set" from an intentional clear (key present with 0). */
    fun hasScopedValues(prefs: Preferences, month: String): Boolean =
        prefs[totalKey(month)] != null ||
            budgetCategories.any { prefs[categoryKey(month, it)] != null }

    /** Values to copy into the current month; legacy keys are intentionally kept. */
    fun migratedValues(prefs: Preferences, currentMonth: String): Map<Preferences.Key<Long>, Long> {
        if (prefs[MIGRATED] == true) return emptyMap()
        val out = linkedMapOf<Preferences.Key<Long>, Long>()
        val total = prefs[legacyTotalKey()] ?: 0L
        if (total > 0L && prefs[totalKey(currentMonth)] == null) {
            out[totalKey(currentMonth)] = total
        }
        budgetCategories.forEach { category ->
            val value = prefs[legacyCategoryKey(category)] ?: 0L
            if (value > 0L && prefs[categoryKey(currentMonth, category)] == null) {
                out[categoryKey(currentMonth, category)] = value
            }
        }
        return out
    }
}
