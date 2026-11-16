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
import kotlin.math.roundToLong

/** Month vs. full-year view for the stats screen. */
enum class StatsPeriod { MONTH, YEAR }

/** One month of a full-year view (month is 1..12). */
data class MonthlyTotal(
    val month: Int,
    val totalCents: Long,
)

/**
 * Single policy for every Android total (ledger header, stats page, budget).
 * Keep it in sync with TransactionDao.observeTotalIncomeBetween /
 * observeTotalExpenseBetween and BudgetViewModel.expenseByCategory.
 *
 * INCOME counts every real CNY income row, including `transfer`-status income
 * and TRANSFER/GIFT categories (incoming transfers and red packets). Only
 * excluded, refund, possible/confirmed duplicate and foreign-currency rows are
 * omitted.
 *
 * EXPENSE keeps the original policy: no transfers, refunds, excluded rows or
 * duplicates.
 */
internal object StatsTransactionPolicy {
    private val INCOME_EXCLUDED_STATUSES = setOf(
        "excluded",
        "refund",
        "possible_duplicate",
        "duplicate",
    )

    private val EXPENSE_EXCLUDED_STATUSES = setOf(
        "excluded",
        "transfer",
        "refund",
        "possible_duplicate",
    )

    fun matches(transaction: TransactionEntity, type: TransactionType): Boolean {
        if (transaction.type != type.name) return false
        if (transaction.currency != "CNY") return false
        if (transaction.duplicateOfId != null) return false
        return when (type) {
            TransactionType.INCOME ->
                transaction.remoteReviewStatus !in INCOME_EXCLUDED_STATUSES
            TransactionType.EXPENSE ->
                transaction.remoteReviewStatus !in EXPENSE_EXCLUDED_STATUSES &&
                    transaction.category != TransactionCategory.TRANSFER.name
        }
    }

    fun filter(
        transactions: List<TransactionEntity>,
        type: TransactionType,
    ): List<TransactionEntity> = transactions.filter { matches(it, type) }
}

/** Pure aggregation helpers so the monthly and yearly views share one pass. */
internal object StatsAggregation {
    fun totalCents(transactions: List<TransactionEntity>, type: TransactionType): Long =
        StatsTransactionPolicy.filter(transactions, type).sumOf { it.amountCents }

    fun categoryStats(
        transactions: List<TransactionEntity>,
        type: TransactionType,
    ): List<CategoryStat> {
        val filtered = StatsTransactionPolicy.filter(transactions, type)
        if (filtered.isEmpty()) return emptyList()
        val total = filtered.sumOf { it.amountCents }
        return filtered
            .groupBy {
                runCatching { TransactionCategory.valueOf(it.category) }
                    .getOrDefault(TransactionCategory.OTHER)
            }
            .map { (category, list) ->
                val categoryTotal = list.sumOf { it.amountCents }
                CategoryStat(
                    category = category,
                    totalCents = categoryTotal,
                    count = list.size,
                    percentage = if (total > 0) categoryTotal.toFloat() / total else 0f,
                )
            }
            .sortedByDescending { it.totalCents }
    }

    fun dailyTotals(
        transactions: List<TransactionEntity>,
        type: TransactionType,
        monthKey: String,
    ): List<DailyTotal> {
        val byDay = StatsTransactionPolicy.filter(transactions, type).groupBy {
            calendarAt(it.timestamp).get(Calendar.DAY_OF_MONTH)
        }
        return (1..BudgetMonth.daysInMonth(monthKey)).map { day ->
            DailyTotal(
                day = day,
                totalCents = byDay[day]?.sumOf { it.amountCents } ?: 0L,
            )
        }
    }

    /**
     * Twelve calendar months of [year]. The caller already queries the exact
     * year range, and the year check below keeps this correct in isolation, so
     * every transaction is counted exactly once.
     */
    fun monthlyTotals(
        transactions: List<TransactionEntity>,
        type: TransactionType,
        year: Int,
    ): List<MonthlyTotal> {
        val byMonth = StatsTransactionPolicy.filter(transactions, type)
            .filter { calendarAt(it.timestamp).get(Calendar.YEAR) == year }
            .groupBy { calendarAt(it.timestamp).get(Calendar.MONTH) + 1 }
        return (1..12).map { month ->
            MonthlyTotal(
                month = month,
                totalCents = byMonth[month]?.sumOf { it.amountCents } ?: 0L,
            )
        }
    }

    /** [Jan 1 00:00, next Jan 1 00:00) in the device timezone. */
    fun yearRange(year: Int): Pair<Long, Long> {
        val start = BudgetMonth.range(BudgetMonth.keyOf(year, 1)).first
        val end = BudgetMonth.range(BudgetMonth.keyOf(year, 12)).second
        return start to end
    }

    fun daysInYear(year: Int): Int = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.DAY_OF_YEAR, 1)
    }.getActualMaximum(Calendar.DAY_OF_YEAR)

    /** Matches the monthly rule: total divided by calendar days in the period. */
    fun averagePerDay(totalCents: Long, year: Int): Long {
        val days = daysInYear(year)
        return if (days <= 0) 0L else (totalCents.toDouble() / days).roundToLong()
    }

    private fun calendarAt(timestampMs: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = timestampMs }
}

// 统计页 ViewModel：选中月份/全年、支出/收入聚合与环比
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

    private val _period = MutableStateFlow(StatsPeriod.MONTH)
    val period: StateFlow<StatsPeriod> = _period

    /** Year of [selectedMonth]; the year stepper preserves the chosen month. */
    val selectedYear: StateFlow<Int> = _selectedMonth
        .map { yearOf(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            yearOf(BudgetMonth.currentKey()),
        )

    private val _statsLoaded = MutableStateFlow(false)
    val statsLoaded: StateFlow<Boolean> = _statsLoaded

    // 选中月份/年份按分类聚合后的列表（金额降序）
    val categoryStats: StateFlow<List<CategoryStat>> =
        combine(_selectedMonth, _period) { month, period -> month to period }
            .flatMapLatest { (month, period) ->
                _statsLoaded.value = false
                val (start, end) = rangeFor(month, period)
                combine(
                    repository.observeBetween(start, end),
                    _viewingType,
                ) { transactions, type ->
                    _statsLoaded.value = true
                    StatsAggregation.categoryStats(transactions, type)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 选中月份/年份该方向的总金额（分）
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
                StatsAggregation.dailyTotals(transactions, type, month)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 选中月份的 12 个月分布（全年视图用；每月恰好统计一次）
    val monthlyTotals: StateFlow<List<MonthlyTotal>> = _selectedMonth
        .flatMapLatest { month ->
            val year = yearOf(month)
            val (start, end) = StatsAggregation.yearRange(year)
            combine(
                repository.observeBetween(start, end),
                _viewingType,
            ) { transactions, type ->
                StatsAggregation.monthlyTotals(transactions, type, year)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 上一月/上一年同方向总额，用于环比
    val previousPeriodTotalCents: StateFlow<Long> =
        combine(_selectedMonth, _period) { month, period -> month to period }
            .flatMapLatest { (month, period) ->
                val range = when (period) {
                    StatsPeriod.MONTH -> BudgetMonth.range(BudgetMonth.previousKey(month))
                    StatsPeriod.YEAR -> {
                        val year = yearOf(month)
                        if (year > MIN_YEAR) {
                            StatsAggregation.yearRange(year - 1)
                        } else {
                            0L to 0L
                        }
                    }
                }
                combine(
                    repository.observeBetween(range.first, range.second),
                    _viewingType,
                ) { transactions, type ->
                    StatsAggregation.totalCents(transactions, type)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // 目标统计页口径：期间总额 / 期间天数
    val dailyAverageCents: StateFlow<Long> =
        combine(totalCents, _selectedMonth, _period) { total, month, period ->
            when (period) {
                StatsPeriod.MONTH -> BudgetCalculations.averagePerDay(total, month)
                StatsPeriod.YEAR -> StatsAggregation.averagePerDay(total, yearOf(month))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // 分类下钻使用：当前选中月份的全部交易
    val monthTransactions: StateFlow<List<TransactionEntity>> = _selectedMonth
        .flatMapLatest { month ->
            val (start, end) = BudgetMonth.range(month)
            repository.observeBetween(start, end)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val previousComparison: StateFlow<MonthComparison> = combine(
        totalCents,
        previousPeriodTotalCents,
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

    fun setPeriod(period: StatsPeriod) {
        _period.value = period
    }

    fun setMonth(month: String) {
        if (BudgetMonth.isValid(month)) _selectedMonth.value = month
    }

    /** Change the year while keeping the selected month, then clamp to 2000..2099. */
    fun setYear(year: Int) {
        if (year !in MIN_YEAR..MAX_YEAR) return
        val month = BudgetMonth.parse(_selectedMonth.value)?.month ?: 1
        _selectedMonth.value = BudgetMonth.keyOf(year, month)
    }

    fun shiftYear(delta: Int) {
        setYear(yearOf(_selectedMonth.value) + delta)
    }

    private fun rangeFor(month: String, period: StatsPeriod): Pair<Long, Long> =
        when (period) {
            StatsPeriod.MONTH -> BudgetMonth.range(month)
            StatsPeriod.YEAR -> StatsAggregation.yearRange(yearOf(month))
        }

    private fun yearOf(month: String): Int =
        BudgetMonth.parse(month)?.year ?: Calendar.getInstance().get(Calendar.YEAR)

    companion object {
        const val MIN_YEAR: Int = 2000
        const val MAX_YEAR: Int = 2099
    }
}
