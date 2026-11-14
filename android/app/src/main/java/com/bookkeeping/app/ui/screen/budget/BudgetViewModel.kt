package com.bookkeeping.app.ui.screen.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.budget.BudgetCalculations
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.BudgetPreferences
import com.bookkeeping.app.data.budget.BudgetSettings
import com.bookkeeping.app.data.budget.BudgetSummary
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetPrefs: BudgetPreferences,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val _selectedMonth = MutableStateFlow(BudgetMonth.currentKey())
    val selectedMonth: StateFlow<String> = _selectedMonth

    val budget: StateFlow<BudgetSettings> = _selectedMonth
        .flatMapLatest { month -> budgetPrefs.observe(month) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetSettings())

    // 选中月份各分类的支出（cents）。只统计 EXPENSE。
    val expenseByCategory: StateFlow<Map<TransactionCategory, Long>> = _selectedMonth
        .flatMapLatest { month ->
            val (start, end) = BudgetMonth.range(month)
            transactionRepository.observeBetween(start, end).map { txs ->
                txs.filter {
                    it.type == TransactionType.EXPENSE.name && it.currency == "CNY" &&
                        it.duplicateOfId == null &&
                        it.remoteReviewStatus !in setOf(
                            "excluded", "transfer", "refund", "possible_duplicate",
                        ) &&
                        it.category != "TRANSFER"
                }
                    .groupBy {
                        runCatching { TransactionCategory.valueOf(it.category) }
                            .getOrDefault(TransactionCategory.OTHER)
                    }
                    .mapValues { (_, list) -> list.sumOf { it.amountCents } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val totalExpenseCents: StateFlow<Long> = expenseByCategory
        .map { it.values.sum() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val summary: StateFlow<BudgetSummary> = combine(
        _selectedMonth,
        budget,
        totalExpenseCents,
    ) { month, settings, spent ->
        BudgetCalculations.summary(month, settings.monthlyTotalCents, spent)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BudgetCalculations.summary(BudgetMonth.currentKey(), 0L, 0L),
    )

    fun setMonth(month: String) {
        if (BudgetMonth.isValid(month)) _selectedMonth.value = month
    }

    fun setMonthlyTotal(yuan: Double) {
        viewModelScope.launch {
            budgetPrefs.setMonthlyTotal(_selectedMonth.value, (yuan * 100).toLong())
        }
    }

    fun setCategoryBudget(category: TransactionCategory, yuan: Double) {
        viewModelScope.launch {
            budgetPrefs.setCategoryBudget(
                _selectedMonth.value,
                category,
                (yuan * 100).toLong(),
            )
        }
    }

    fun removeCategoryBudget(category: TransactionCategory) {
        viewModelScope.launch {
            budgetPrefs.setCategoryBudget(_selectedMonth.value, category, 0L)
        }
    }

    // 选中月份还剩几天（当前月含今天；过去月为 0；未来月为整月）
    fun daysRemaining(): Int = BudgetMonth.daysRemaining(_selectedMonth.value)
}
