package com.bookkeeping.app.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.budget.BudgetCalculations
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.MonthComparison
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
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
import java.util.Calendar
import javax.inject.Inject

// 统计页 ViewModel：选中月份、支出/收入聚合与环比
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    // 收入还是支出（切换显示哪个方向的统计）
    private val _viewingType = MutableStateFlow(TransactionType.EXPENSE)
    val viewingType: StateFlow<TransactionType> = _viewingType

    private val _selectedMonth = MutableStateFlow(BudgetMonth.currentKey())
    val selectedMonth: StateFlow<String> = _selectedMonth

    // 选中月份按分类聚合后的列表（金额降序）
    val categoryStats: StateFlow<List<CategoryStat>> = _selectedMonth
        .flatMapLatest { month ->
            val (start, end) = BudgetMonth.range(month)
            combine(
                repository.observeBetween(start, end),
                _viewingType,
            ) { transactions, type ->
                val filtered = filterForType(transactions, type)
                if (filtered.isEmpty()) return@combine emptyList()

                val total = filtered.sumOf { it.amountCents }
                filtered
                    .groupBy {
                        runCatching { TransactionCategory.valueOf(it.category) }
                            .getOrDefault(TransactionCategory.OTHER)
                    }
                    .map { (cat, list) ->
                        val catTotal = list.sumOf { it.amountCents }
                        CategoryStat(
                            category = cat,
                            totalCents = catTotal,
                            count = list.size,
                            percentage = if (total > 0) catTotal.toFloat() / total else 0f,
                        )
                    }
                    .sortedByDescending { it.totalCents }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 选中月份该方向的总金额（分）
    val totalCents: StateFlow<Long> = categoryStats
        .map { list -> list.sumOf { it.totalCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // 选中月份每日合计（柱状图用）。无交易的日子填 0
    val dailyTotals: StateFlow<List<DailyTotal>> = _selectedMonth
        .flatMapLatest { month ->
            val (start, end) = BudgetMonth.range(month)
            combine(
                repository.observeBetween(start, end),
                _viewingType,
            ) { transactions, type ->
                val filtered = filterForType(transactions, type)
                val byDay = filtered.groupBy {
                    Calendar.getInstance().apply { timeInMillis = it.timestamp }
                        .get(Calendar.DAY_OF_MONTH)
                }
                (1..BudgetMonth.daysInMonth(month)).map { day ->
                    DailyTotal(
                        day = day,
                        totalCents = byDay[day]?.sumOf { it.amountCents } ?: 0L,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 上一月同方向总额，用于环比
    val previousMonthTotalCents: StateFlow<Long> = _selectedMonth
        .flatMapLatest { month ->
            val (start, end) = BudgetMonth.range(BudgetMonth.previousKey(month))
            combine(
                repository.observeBetween(start, end),
                _viewingType,
            ) { transactions, type ->
                filterForType(transactions, type).sumOf { it.amountCents }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // 目标统计页口径：月总额 / 当月天数
    val dailyAverageCents: StateFlow<Long> = combine(totalCents, _selectedMonth) { total, month ->
        BudgetCalculations.averagePerDay(total, month)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val previousComparison: StateFlow<MonthComparison> = combine(
        totalCents,
        previousMonthTotalCents,
    ) { current, previous ->
        BudgetCalculations.comparison(current, previous)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MonthComparison(0L, 0L, null),
    )

    fun setViewingType(type: TransactionType) {
        _viewingType.value = type
    }

    fun setMonth(month: String) {
        if (BudgetMonth.isValid(month)) _selectedMonth.value = month
    }

    private fun filterForType(
        transactions: List<TransactionEntity>,
        type: TransactionType,
    ): List<TransactionEntity> = transactions.filter {
        it.type == type.name && it.currency == "CNY" &&
            it.duplicateOfId == null &&
            it.remoteReviewStatus !in setOf(
                "excluded", "transfer", "refund", "possible_duplicate",
            ) &&
            (it.type == "INCOME" || it.category != "TRANSFER")
    }
}
