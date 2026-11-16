package com.bookkeeping.app.ui.screen.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Calendar
import javax.inject.Inject

enum class LedgerTimeFilter { MONTH, YEAR, ALL }

enum class LedgerLoadState { INITIAL, LOADING, READY }

internal fun TransactionEntity.isLedgerTotalsCandidate(): Boolean =
    currency == "CNY" &&
        !syncDeleted &&
        duplicateOfId == null &&
        remoteReviewStatus !in setOf("excluded", "transfer", "refund", "possible_duplicate")

internal object LedgerFilter {
    fun parseAmountCents(query: String): Long? {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !trimmed.matches(Regex("""\d{1,9}(\.\d{0,2})?"""))) return null
        return runCatching {
            BigDecimal(trimmed).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    fun matches(
        transaction: TransactionEntity,
        query: String,
        type: TransactionType?,
        category: TransactionCategory?,
        startMs: Long?,
        endMs: Long?,
    ): Boolean {
        if (type != null && transaction.type != type.name) return false
        if (category != null && transaction.category != category.name) return false
        if (startMs != null && transaction.timestamp < startMs) return false
        if (endMs != null && transaction.timestamp >= endMs) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        val amount = parseAmountCents(q)
        if (amount != null) {
            val decimals = q.substringAfter('.', "").length
            val amountEnd = when (decimals) {
                0 -> amount + 99L
                1 -> amount + 9L
                else -> amount
            }
            if (transaction.amountCents in amount..amountEnd) return true
        }
        fun contains(value: String) = value.contains(q, ignoreCase = true)
        return contains(transaction.merchant) ||
            contains(transaction.note) ||
            contains(transaction.category) ||
            contains(transaction.source)
    }
}

@HiltViewModel
class TransactionViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val dao: TransactionDao,
    private val settings: SettingsPreferences,
) : ViewModel() {

    data class DuplicateCandidate(
        val candidate: TransactionEntity,
        val original: TransactionEntity?,
    )

    private data class PeriodState(
        val time: LedgerTimeFilter,
        val month: String,
        val year: Int,
    )

    private data class FilterState(
        val type: TransactionType?,
        val category: TransactionCategory?,
        val period: PeriodState,
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _typeFilter = MutableStateFlow<TransactionType?>(null)
    val typeFilter: StateFlow<TransactionType?> = _typeFilter

    private val _categoryFilter = MutableStateFlow<TransactionCategory?>(null)
    val categoryFilter: StateFlow<TransactionCategory?> = _categoryFilter

    private val _timeFilter = MutableStateFlow(LedgerTimeFilter.ALL)
    val timeFilter: StateFlow<LedgerTimeFilter> = _timeFilter

    private val _selectedMonth = MutableStateFlow(BudgetMonth.currentKey())
    val selectedMonth: StateFlow<String> = _selectedMonth

    private val _selectedYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    val selectedYear: StateFlow<Int> = _selectedYear

    private val periodState: StateFlow<PeriodState> =
        combine(_timeFilter, _selectedMonth, _selectedYear) { time, month, year ->
            PeriodState(time, month, year)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PeriodState(LedgerTimeFilter.ALL, BudgetMonth.currentKey(), Calendar.getInstance().get(Calendar.YEAR)),
        )

    private val _ledgerLoadState = MutableStateFlow(LedgerLoadState.INITIAL)
    val ledgerLoadState: StateFlow<LedgerLoadState> = _ledgerLoadState

    // Kept for callers that only need the raw list (duplicate originals, calendar).
    val allTransactions: StateFlow<List<TransactionEntity>> = repository.observeAll()
        .onStart { _ledgerLoadState.value = LedgerLoadState.LOADING }
        .onEach { _ledgerLoadState.value = LedgerLoadState.READY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAnyTransactions: StateFlow<Boolean> = allTransactions
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val periodTransactions: StateFlow<List<TransactionEntity>> =
        combine(allTransactions, periodState) { all, period ->
            val range = periodRange(period)
            all.filter { transaction ->
                !transaction.syncDeleted &&
                    (range == null ||
                        (transaction.timestamp >= range.first && transaction.timestamp < range.second))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val displayedTransactions: StateFlow<List<TransactionEntity>> =
        combine(
            _searchQuery.debounce(220),
            combine(_typeFilter, _categoryFilter, periodState) { type, category, period ->
                FilterState(type, category, period)
            },
        ) { query, filters -> query to filters }
            .flatMapLatest { (query, filters) ->
                val range = periodRange(filters.period)
                val start = range?.first
                val end = range?.second
                val amountQuery = LedgerFilter.parseAmountCents(query)
                val daoQuery = if (amountQuery != null) "" else query
                dao.observeFiltered(
                    query = daoQuery,
                    type = filters.type?.name,
                    category = filters.category?.name,
                    startMs = start,
                    endMs = end,
                ).map { list ->
                    if (amountQuery != null) {
                        list.filter {
                            LedgerFilter.matches(
                                it,
                                query,
                                filters.type,
                                filters.category,
                                start,
                                end,
                            )
                        }
                    } else {
                        list
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val duplicateCandidates: StateFlow<List<DuplicateCandidate>> =
        combine(
            repository.observeDuplicateCandidates(),
            allTransactions,
        ) { candidates, all ->
            val byId = all.associateBy { it.id }
            candidates.map { candidate ->
                DuplicateCandidate(
                    candidate = candidate,
                    original = candidate.duplicateOfId?.let(byId::get),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val periodExpenseCents: StateFlow<Long> = periodTransactions
        .map { list ->
            list.filter {
                it.type == TransactionType.EXPENSE.name &&
                    it.category != TransactionCategory.TRANSFER.name &&
                    it.isLedgerTotalsCandidate()
            }.sumOf { it.amountCents }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val periodIncomeCents: StateFlow<Long> = periodTransactions
        .map { list ->
            list.filter {
                it.type == TransactionType.INCOME.name &&
                    it.isLedgerTotalsCandidate()
            }.sumOf { it.amountCents }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setTypeFilter(type: TransactionType?) {
        _typeFilter.value = type
    }

    fun setCategoryFilter(category: TransactionCategory?) {
        _categoryFilter.value = category
    }

    fun setTimeFilter(time: LedgerTimeFilter) {
        _timeFilter.value = time
    }

    /** Single-select chips: selecting one dimension clears the other two. */
    fun selectTimeFilterOnly(time: LedgerTimeFilter) {
        _typeFilter.value = null
        _categoryFilter.value = null
        _timeFilter.value = time
    }

    fun selectTypeFilterOnly(type: TransactionType) {
        _categoryFilter.value = null
        _timeFilter.value = LedgerTimeFilter.ALL
        _typeFilter.value = type
    }

    fun selectCategoryFilterOnly(category: TransactionCategory) {
        _typeFilter.value = null
        _timeFilter.value = LedgerTimeFilter.ALL
        _categoryFilter.value = category
    }

    fun setMonth(year: Int, month: Int) {
        _selectedYear.value = year
        _selectedMonth.value = BudgetMonth.keyOf(year, month)
        _timeFilter.value = LedgerTimeFilter.MONTH
    }

    fun setYear(year: Int) {
        _selectedYear.value = year
        _timeFilter.value = LedgerTimeFilter.YEAR
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _typeFilter.value = null
        _categoryFilter.value = null
        _selectedMonth.value = BudgetMonth.currentKey()
        _selectedYear.value = Calendar.getInstance().get(Calendar.YEAR)
        _timeFilter.value = LedgerTimeFilter.ALL
    }

    /** Show every transaction, used by the explicit “全部” filter chip. */
    fun showAll() {
        _searchQuery.value = ""
        _typeFilter.value = null
        _categoryFilter.value = null
        _timeFilter.value = LedgerTimeFilter.ALL
    }

    fun addTransaction(
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        viewModelScope.launch {
            val entity = TransactionEntity(
                amountCents = amountCents,
                type = type.name,
                category = category.name,
                merchant = merchant,
                note = note,
                source = TransactionSource.MANUAL.name,
                timestamp = timestampMs,
            )
            repository.insert(entity)
            if (merchant.isNotBlank()) {
                settings.recordCategoryLearning(merchant, category)
            }
        }
    }

    fun updateTransaction(
        original: TransactionEntity,
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        timestampMs: Long = original.timestamp,
    ) {
        viewModelScope.launch {
            repository.update(
                original.copy(
                    amountCents = amountCents,
                    type = type.name,
                    category = category.name,
                    merchant = merchant,
                    note = note,
                    timestamp = timestampMs,
                )
            )
            if (original.category != category.name && merchant.isNotBlank()) {
                settings.recordCategoryLearning(merchant, category)
            }
        }
    }

    fun saveMerchantRule(merchant: String, category: TransactionCategory) {
        if (merchant.isBlank()) return
        viewModelScope.launch { settings.rememberMerchantCategory(merchant, category) }
    }

    fun updateCategory(entity: TransactionEntity, category: TransactionCategory) {
        if (entity.category == category.name) return
        viewModelScope.launch {
            repository.update(entity.copy(category = category.name))
            if (entity.merchant.isNotBlank()) {
                settings.recordCategoryLearning(entity.merchant, category)
            }
        }
    }

    fun setReviewStatus(entity: TransactionEntity, status: String) {
        if (status !in setOf("unverified", "confirmed", "excluded", "transfer")) return
        if (entity.remoteReviewStatus == "refund") return
        viewModelScope.launch {
            repository.update(
                entity.copy(
                    remoteReviewStatus = status,
                    duplicateOfId = if (status == "excluded") null else entity.duplicateOfId,
                )
            )
        }
    }

    fun delete(entity: TransactionEntity) {
        viewModelScope.launch { repository.delete(entity) }
    }

    fun keepBoth(entity: TransactionEntity) {
        viewModelScope.launch { repository.keepBoth(entity) }
    }

    private fun periodRange(period: PeriodState): Pair<Long, Long>? = when (period.time) {
        LedgerTimeFilter.MONTH -> BudgetMonth.range(period.month)
        LedgerTimeFilter.YEAR -> {
            val start = Calendar.getInstance().apply {
                clear()
                set(period.year, Calendar.JANUARY, 1, 0, 0, 0)
            }.timeInMillis
            val end = Calendar.getInstance().apply {
                clear()
                set(period.year + 1, Calendar.JANUARY, 1, 0, 0, 0)
            }.timeInMillis
            start to end
        }
        LedgerTimeFilter.ALL -> null
    }
}
