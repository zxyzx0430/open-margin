package com.bookkeeping.app.ui.screen.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.TransactionRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Calendar
import javax.inject.Inject

enum class LedgerTimeFilter { ALL, THIS_MONTH }

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
        if (amount != null && transaction.amountCents == amount) return true
        fun contains(value: String) = value.contains(q, ignoreCase = true)
        return contains(transaction.merchant) ||
            contains(transaction.note) ||
            contains(transaction.category)
    }
}

@HiltViewModel
class TransactionViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val dao: TransactionDao,
) : ViewModel() {

    data class DuplicateCandidate(
        val candidate: TransactionEntity,
        val original: TransactionEntity?,
    )

    private data class FilterState(
        val type: TransactionType?,
        val category: TransactionCategory?,
        val time: LedgerTimeFilter,
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _typeFilter = MutableStateFlow<TransactionType?>(null)
    val typeFilter: StateFlow<TransactionType?> = _typeFilter

    private val _categoryFilter = MutableStateFlow<TransactionCategory?>(null)
    val categoryFilter: StateFlow<TransactionCategory?> = _categoryFilter

    private val _timeFilter = MutableStateFlow(LedgerTimeFilter.ALL)
    val timeFilter: StateFlow<LedgerTimeFilter> = _timeFilter

    // Kept for callers that only need the raw list (duplicate originals).
    private val allTransactions: StateFlow<List<TransactionEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val displayedTransactions: StateFlow<List<TransactionEntity>> =
        combine(
            _searchQuery.debounce(220),
            combine(_typeFilter, _categoryFilter, _timeFilter) { type, category, time ->
                FilterState(type, category, time)
            },
        ) { query, filters -> query to filters }
            .flatMapLatest { (query, filters) ->
                val (start, end) = if (filters.time == LedgerTimeFilter.THIS_MONTH) {
                    currentMonthRange()
                } else {
                    null to null
                }
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

    val monthlyExpenseCents: StateFlow<Long> = run {
        val (start, end) = currentMonthRange()
        repository.observeTotalExpenseBetween(start, end)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    }

    val monthlyIncomeCents: StateFlow<Long> = run {
        val (start, end) = currentMonthRange()
        repository.observeTotalIncomeBetween(start, end)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    }

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

    fun clearFilters() {
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
    ) {
        viewModelScope.launch {
            val entity = TransactionEntity(
                amountCents = amountCents,
                type = type.name,
                category = category.name,
                merchant = merchant,
                note = note,
                source = TransactionSource.MANUAL.name,
                timestamp = System.currentTimeMillis(),
            )
            repository.insert(entity)
        }
    }

    fun updateTransaction(
        original: TransactionEntity,
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
    ) {
        viewModelScope.launch {
            repository.update(
                original.copy(
                    amountCents = amountCents,
                    type = type.name,
                    category = category.name,
                    merchant = merchant,
                    note = note,
                )
            )
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

    private fun currentMonthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        return start to end
    }
}
