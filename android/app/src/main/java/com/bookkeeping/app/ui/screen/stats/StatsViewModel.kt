package com.bookkeeping.app.ui.screen.stats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.budget.BudgetCalculations
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.MonthComparison
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.subscription.MonthlyForecastPoint
import com.bookkeeping.app.data.subscription.SubscriptionFxRates
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.data.subscription.SubscriptionRepository
import com.bookkeeping.app.data.subscription.TwelveMonthForecast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.roundToLong

/** Top-level segmented tabs on the stats screen. */
enum class StatsTab { EXPENSE, INCOME, SUBSCRIPTION }

/** Day, month, or full-year view for the stats screen. */
enum class StatsPeriod { DAY, MONTH, YEAR }

/** Calendar date selected by the independent day view. Month is 1..12. */
data class StatsDate(
    val year: Int,
    val month: Int,
    val day: Int,
)

data class DayStatsSummary(
    val expenseCents: Long,
    val incomeCents: Long,
    val balanceCents: Long,
    val transactionCount: Int,
    val isLoaded: Boolean = false,
)

data class SubscriptionBreakdownItem(
    val label: String,
    val count: Int,
    val monthlyCnyCents: Long,
    val percentage: Float,
)

data class SubscriptionRankItem(
    val id: Long,
    val name: String,
    val cadenceLabel: String,
    val channel: String,
    val monthlyCnyCents: Long,
    val percentage: Float,
    val pendingCancel: Boolean,
)

/** Aggregated subscription metrics and 12-month renewal projection for the Stats Subscription tab. */
data class SubscriptionStatsState(
    val activeCount: Int = 0,
    val monthlyTotalCnyCents: Long = 0L,
    val averageMonthlyCnyCents: Long = 0L,
    val mostExpensiveName: String? = null,
    val mostExpensiveMonthlyCnyCents: Long = 0L,
    val manualRenewalCount: Int = 0,
    val pendingCancelCount: Int = 0,
    val pendingCancelMonthlySavingsCents: Long = 0L,
    val forecast12Months: List<MonthlyForecastPoint> = emptyList(),
    val peakMonth: MonthlyForecastPoint? = null,
    val recurringCandidateCount: Int = 0,
    val topSubscriptions: List<SubscriptionRankItem> = emptyList(),
    val channelBreakdown: List<SubscriptionBreakdownItem> = emptyList(),
    val cadenceBreakdown: List<SubscriptionBreakdownItem> = emptyList(),
    val isLoaded: Boolean = false,
)

/** Range, category, and optional account carried from a stats drilldown into the ledger. */
data class StatsLedgerDrilldown(
    val period: StatsPeriod,
    val year: Int,
    val month: Int,
    val day: Int? = null,
    val category: TransactionCategory? = null,
    val type: TransactionType,
    val account: String? = null,
)

/** One month of a full-year view (month is 1..12). */
data class MonthlyTotal(
    val month: Int,
    val totalCents: Long,
)

/**
 * Single policy for every Android total (stats page and drilldown).
 *
 * Stats intentionally count every real CNY movement, including transfer-status
 * rows, TRANSFER category and refunds. Only rows the user excluded, rows that
 * are duplicates of another row, and foreign-currency rows are omitted.
 * Duplicate flags are kept out so one physical payment is not counted twice.
 */
internal object StatsTransactionPolicy {
    private val EXCLUDED_STATUSES = setOf(
        "excluded",
        "possible_duplicate",
        "duplicate",
    )

    fun matches(transaction: TransactionEntity, type: TransactionType): Boolean {
        if (transaction.type != type.name) return false
        if (transaction.currency != "CNY") return false
        if (transaction.duplicateOfId != null) return false
        return transaction.remoteReviewStatus !in EXCLUDED_STATUSES
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

    fun transactionCount(transactions: List<TransactionEntity>): Int =
        StatsTransactionPolicy.filter(transactions, TransactionType.EXPENSE).size +
            StatsTransactionPolicy.filter(transactions, TransactionType.INCOME).size

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

    fun accountStats(
        transactions: List<TransactionEntity>,
        type: TransactionType,
    ): List<AccountStat> = AccountAggregation.accountStats(transactions, type)

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

    fun currentDate(): StatsDate = Calendar.getInstance().toStatsDate()

    fun dateRange(date: StatsDate): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            clear()
            set(date.year, date.month - 1, date.day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return start.timeInMillis to end.timeInMillis
    }

    fun shiftDate(date: StatsDate, days: Int): StatsDate = Calendar.getInstance().apply {
        clear()
        set(date.year, date.month - 1, date.day, 12, 0, 0)
        add(Calendar.DAY_OF_MONTH, days)
    }.toStatsDate()

    /** Matches the monthly rule: total divided by calendar days in the period. */
    fun averagePerDay(totalCents: Long, year: Int): Long {
        val days = daysInYear(year)
        return if (days <= 0) 0L else (totalCents.toDouble() / days).roundToLong()
    }

    private fun calendarAt(timestampMs: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = timestampMs }

    private fun Calendar.toStatsDate() = StatsDate(
        year = get(Calendar.YEAR),
        month = get(Calendar.MONTH) + 1,
        day = get(Calendar.DAY_OF_MONTH),
    )
}

/** Pure subscription analytics math so unit tests can verify monthly/peak metrics directly. */
internal object SubscriptionStatsCalculator {
    fun compute(
        activeSubscriptions: List<SubscriptionEntity>,
        startYear: Int = Calendar.getInstance().get(Calendar.YEAR),
        startMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1,
        fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
        forecast: TwelveMonthForecast? = null,
        monthlyTotalOverrideCnyCents: Long? = null,
        pendingCancelSavingsOverrideCnyCents: Long? = null,
        recurringCandidateCount: Int = 0,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): SubscriptionStatsState {
        val active = activeSubscriptions.filter { it.active && it.deletedAtMs == null }
        val withMonthly = active.map { sub ->
            sub to SubscriptionProjection.monthlyEquivalentCnyCents(sub, fxRates)
        }
        val monthlyTotal = monthlyTotalOverrideCnyCents ?: withMonthly.sumOf { it.second }
        val activeCount = active.size
        val averageMonthly = if (activeCount == 0) {
            0L
        } else {
            (monthlyTotal.toDouble() / activeCount).roundToLong()
        }
        val mostExpensive = withMonthly.maxByOrNull { it.second }
        val manualRenewalCount = active.count { !it.autoRenew }
        val pendingCancelSubs = withMonthly.filter { it.first.pendingCancel }
        val pendingCancelCount = pendingCancelSubs.size
        val pendingCancelSavings =
            pendingCancelSavingsOverrideCnyCents ?: pendingCancelSubs.sumOf { it.second }
        val resolvedForecast = forecast ?: SubscriptionProjection.forecast12Months(
            subscriptions = active,
            startYear = startYear,
            startMonth = startMonth,
            fxRates = fxRates,
            timeZone = timeZone,
        )
        val totalForPct = monthlyTotal.coerceAtLeast(1L).toFloat()
        val topSubscriptions = withMonthly
            .sortedByDescending { it.second }
            .take(6)
            .map { (sub, monthly) ->
                SubscriptionRankItem(
                    id = sub.id,
                    name = sub.name,
                    cadenceLabel = when (sub.cadence.lowercase()) {
                        "weekly" -> "每周"
                        "quarterly" -> "每季"
                        "yearly" -> "每年"
                        else -> "每月"
                    },
                    channel = sub.paymentChannel.trim().ifBlank { "未指定" },
                    monthlyCnyCents = monthly,
                    percentage = if (monthlyTotal > 0L) monthly.toFloat() / totalForPct else 0f,
                    pendingCancel = sub.pendingCancel,
                )
            }
        val channelBreakdown = withMonthly
            .groupBy { (sub, _) -> sub.paymentChannel.trim().ifBlank { "未指定" } }
            .map { (channel, list) ->
                val sum = list.sumOf { it.second }
                SubscriptionBreakdownItem(
                    label = channel,
                    count = list.size,
                    monthlyCnyCents = sum,
                    percentage = if (monthlyTotal > 0L) sum.toFloat() / totalForPct else 0f,
                )
            }
            .sortedByDescending { it.monthlyCnyCents }
        val cadenceBreakdown = withMonthly
            .groupBy { (sub, _) ->
                when (sub.cadence.lowercase()) {
                    "weekly" -> "周付"
                    "quarterly" -> "季付"
                    "yearly" -> "年付"
                    else -> "月付"
                }
            }
            .map { (label, list) ->
                val sum = list.sumOf { it.second }
                SubscriptionBreakdownItem(
                    label = label,
                    count = list.size,
                    monthlyCnyCents = sum,
                    percentage = if (monthlyTotal > 0L) sum.toFloat() / totalForPct else 0f,
                )
            }
            .sortedByDescending { it.monthlyCnyCents }
        return SubscriptionStatsState(
            activeCount = activeCount,
            monthlyTotalCnyCents = monthlyTotal,
            averageMonthlyCnyCents = averageMonthly,
            mostExpensiveName = mostExpensive?.first?.name,
            mostExpensiveMonthlyCnyCents = mostExpensive?.second ?: 0L,
            manualRenewalCount = manualRenewalCount,
            pendingCancelCount = pendingCancelCount,
            pendingCancelMonthlySavingsCents = pendingCancelSavings,
            forecast12Months = resolvedForecast.months,
            peakMonth = resolvedForecast.peakMonth,
            recurringCandidateCount = recurringCandidateCount,
            topSubscriptions = topSubscriptions,
            channelBreakdown = channelBreakdown,
            cadenceBreakdown = cadenceBreakdown,
            isLoaded = true,
        )
    }
}

// 统计页 ViewModel：选中日期/月/年、支出/收入/订阅聚合与环比
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val fxRatesStore: SubscriptionFxRates,
) : ViewModel() {

    init {
        viewModelScope.launch {
            fxRatesStore.refreshIfStale()
        }
    }

    private val _selectedTab = MutableStateFlow(StatsTab.EXPENSE)
    val selectedTab: StateFlow<StatsTab> = _selectedTab

    // 收入还是支出（切换显示哪个方向的统计）
    private val _viewingType = MutableStateFlow(TransactionType.EXPENSE)
    val viewingType: StateFlow<TransactionType> = _viewingType

    private val _selectedMonth = MutableStateFlow(BudgetMonth.currentKey())
    val selectedMonth: StateFlow<String> = _selectedMonth

    private val _period = MutableStateFlow(StatsPeriod.MONTH)
    val period: StateFlow<StatsPeriod> = _period

    private val _selectedDate = MutableStateFlow(StatsAggregation.currentDate())
    val selectedDate: StateFlow<StatsDate> = _selectedDate

    /** Year of [selectedMonth]; the year stepper preserves the chosen month. */
    val selectedYear: StateFlow<Int> = _selectedMonth
        .map { yearOf(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(30_000),
            yearOf(BudgetMonth.currentKey()),
        )

    private val _statsLoaded = MutableStateFlow(false)
    val statsLoaded: StateFlow<Boolean> = _statsLoaded

    // One current-range Room query shared by the related derived metrics.
    private val currentRangeRows = combine(_selectedMonth, _selectedDate, _period) { month, date, period ->
        rangeFor(month, date, period)
    }.distinctUntilChanged().flatMapLatest { (start, end) ->
        _statsLoaded.value = false
        repository.observeBetween(start, end)
    }.flowOn(Dispatchers.Default).shareIn(viewModelScope,
        SharingStarted.WhileSubscribed(30_000, replayExpirationMillis = 0), replay = 1)

    internal val coreUiState: StateFlow<StatsCoreUiState> =
        combine(currentRangeRows, _viewingType) { rows, type ->
            val categories = StatsAggregation.categoryStats(rows, type)
            StatsCoreUiState(
                rows = rows,
                categories = categories,
                totalCents = categories.sumOf { it.totalCents },
                balanceCents = StatsAggregation.totalCents(rows, TransactionType.INCOME) -
                    StatsAggregation.totalCents(rows, TransactionType.EXPENSE),
                accounts = AccountAggregation.accountStats(rows, type),
                mostlyUncategorized = AccountAggregation.isMostlyUncategorized(rows, type),
            ).also { _statsLoaded.value = true }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), StatsCoreUiState())

    // Keep the public derived streams for existing callers, but compose the
    // active page from one coherent result instead of six intermediate emissions.
    val categoryStats: StateFlow<List<CategoryStat>> = coreUiState.map { it.categories }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totalCents: StateFlow<Long> = coreUiState.map { it.totalCents }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val balanceCents: StateFlow<Long> = coreUiState.map { it.balanceCents }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val selectedDaySummary: StateFlow<DayStatsSummary> = _selectedDate
        .flatMapLatest { date ->
            val (start, end) = StatsAggregation.dateRange(date)
            repository.observeBetween(start, end).map { transactions ->
                val expenses = StatsAggregation.totalCents(transactions, TransactionType.EXPENSE)
                val income = StatsAggregation.totalCents(transactions, TransactionType.INCOME)
                DayStatsSummary(
                    expenseCents = expenses,
                    incomeCents = income,
                    balanceCents = income - expenses,
                    transactionCount = StatsAggregation.transactionCount(transactions),
                    isLoaded = true,
                )
            }.onStart { emit(DayStatsSummary(0L, 0L, 0L, 0)) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(30_000),
            DayStatsSummary(0L, 0L, 0L, 0),
        )

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
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyList())

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
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyList())

    // 上一月/上一年同方向总额，用于环比
    val previousPeriodTotalCents: StateFlow<Long> =
        combine(_selectedMonth, _selectedDate, _period) { month, date, period ->
            Triple(month, date, period)
        }
            .flatMapLatest { (month, date, period) ->
                val range = when (period) {
                    StatsPeriod.DAY -> StatsAggregation.dateRange(
                        StatsAggregation.shiftDate(date, -1),
                    )
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
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), 0L)

    // 目标统计页口径：期间总额 / 期间天数
    val dailyAverageCents: StateFlow<Long> =
        combine(totalCents, _selectedMonth, _period) { total, month, period ->
            when (period) {
                StatsPeriod.DAY -> total
                StatsPeriod.MONTH -> BudgetCalculations.averagePerDay(total, month)
                StatsPeriod.YEAR -> StatsAggregation.averagePerDay(total, yearOf(month))
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), 0L)

    // 分类下钻使用：当前选中统计范围内的全部交易。
    val periodTransactions: StateFlow<List<TransactionEntity>> = currentRangeRows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyList())

    val previousComparison: StateFlow<MonthComparison> = combine(
        totalCents,
        previousPeriodTotalCents,
    ) { current, previous ->
        BudgetCalculations.comparison(current, previous)
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(30_000),
        MonthComparison(0L, 0L, null),
    )

    val accountStats: StateFlow<List<AccountStat>> = coreUiState.map { it.accounts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accountCoverageRatio: StateFlow<Float> = combine(
        periodTransactions,
        _viewingType,
    ) { transactions, type ->
        AccountAggregation.coverageRatio(transactions, type)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), 0f)

    val isAccountMostlyUncategorized: StateFlow<Boolean> = coreUiState.map { it.mostlyUncategorized }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val subscriptionStats: StateFlow<SubscriptionStatsState> =
        combine(
            subscriptionRepository.observeActive(),
            repository.observeUiTransactions(),
            fxRatesStore.ratesFlow,
        ) { activeSubs, _, rates -> activeSubs to rates }
            .map { (activeSubs, rates) ->
                val cal = Calendar.getInstance()
                val startYear = cal.get(Calendar.YEAR)
                val startMonth = cal.get(Calendar.MONTH) + 1
                val forecast = subscriptionRepository.forecast12Months(startYear, startMonth)
                val monthlyTotal = subscriptionRepository.monthlyEquivalentTotalCnyCents()
                val pendingCancelSavings = subscriptionRepository.pendingCancelMonthlySavingsCnyCents()
                val candidateCount = subscriptionRepository.detectRecurringCandidates().size
                SubscriptionStatsCalculator.compute(
                    activeSubscriptions = activeSubs,
                    startYear = startYear,
                    startMonth = startMonth,
                    forecast = forecast,
                    monthlyTotalOverrideCnyCents = monthlyTotal,
                    pendingCancelSavingsOverrideCnyCents = pendingCancelSavings,
                    recurringCandidateCount = candidateCount,
                    fxRates = rates,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(30_000),
                SubscriptionStatsState(),
            )

    fun setSelectedTab(tab: StatsTab) {
        _selectedTab.value = tab
        when (tab) {
            StatsTab.EXPENSE -> _viewingType.value = TransactionType.EXPENSE
            StatsTab.INCOME -> _viewingType.value = TransactionType.INCOME
            StatsTab.SUBSCRIPTION -> Unit
        }
    }

    fun setViewingType(type: TransactionType) {
        _viewingType.value = type
        _selectedTab.value = when (type) {
            TransactionType.EXPENSE -> StatsTab.EXPENSE
            TransactionType.INCOME -> StatsTab.INCOME
        }
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

    fun shiftDay(delta: Int) {
        val shifted = StatsAggregation.shiftDate(_selectedDate.value, delta)
        if (shifted.year in MIN_YEAR..MAX_YEAR) _selectedDate.value = shifted
    }

    fun setDate(date: StatsDate) {
        if (date.year !in MIN_YEAR..MAX_YEAR || date.month !in 1..12) return
        val monthKey = BudgetMonth.keyOf(date.year, date.month)
        val day = date.day.coerceIn(1, BudgetMonth.daysInMonth(monthKey))
        _selectedDate.value = StatsDate(date.year, date.month, day)
    }

    private fun rangeFor(
        month: String,
        date: StatsDate,
        period: StatsPeriod,
    ): Pair<Long, Long> =
        when (period) {
            StatsPeriod.DAY -> StatsAggregation.dateRange(date)
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

internal data class StatsCoreUiState(
    val rows: List<TransactionEntity> = emptyList(),
    val categories: List<CategoryStat> = emptyList(),
    val totalCents: Long = 0L,
    val balanceCents: Long = 0L,
    val accounts: List<AccountStat> = emptyList(),
    val mostlyUncategorized: Boolean = false,
)
