package com.bookkeeping.app.data.budget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.bookkeeping.app.data.local.entity.TransactionCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

// DataStore 委托必须放在 file top level（不能在 class 里）
private val Context.budgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "budget_prefs")

// 预算的持久化层，基于 DataStore Preferences；v2 起按 YYYY-MM 隔离。
@Singleton
class BudgetPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun observe(month: String = BudgetMonth.currentKey()): Flow<BudgetSettings> =
        context.budgetDataStore.data
            .onStart { migrateLegacyIfNeeded() }
            .map { prefs ->
                BudgetMonthPolicy.settingsWithLegacyFallback(
                    prefs = prefs,
                    month = month,
                    currentMonth = BudgetMonth.currentKey(),
                )
            }

    suspend fun setMonthlyTotal(month: String, cents: Long) {
        context.budgetDataStore.edit { prefs ->
            val key = BudgetMonthPolicy.totalKey(month)
            if (cents > 0L) prefs[key] = cents else prefs.remove(key)
        }
    }

    suspend fun setMonthlyTotal(cents: Long) {
        setMonthlyTotal(BudgetMonth.currentKey(), cents)
    }

    suspend fun setCategoryBudget(month: String, category: TransactionCategory, cents: Long) {
        context.budgetDataStore.edit { prefs ->
            val key = BudgetMonthPolicy.categoryKey(month, category)
            if (cents > 0L) prefs[key] = cents else prefs.remove(key)
        }
    }

    suspend fun setCategoryBudget(category: TransactionCategory, cents: Long) {
        setCategoryBudget(BudgetMonth.currentKey(), category, cents)
    }

    private suspend fun migrateLegacyIfNeeded() {
        context.budgetDataStore.edit { prefs ->
            if (prefs[BudgetMonthPolicy.MIGRATED] == true) return@edit
            val currentMonth = BudgetMonth.currentKey()
            BudgetMonthPolicy.migratedValues(prefs, currentMonth).forEach { (key, value) ->
                if (prefs[key] == null) prefs[key] = value
            }
            // Legacy keys are deliberately kept for rollback; the marker prevents
            // a later intentionally cleared month from resurrecting them.
            prefs[BudgetMonthPolicy.MIGRATED] = true
        }
    }
}
