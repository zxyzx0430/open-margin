package com.bookkeeping.app.ui.screen.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.CaptureMergePolicy
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.ui.screen.stats.AccountNormalization
import com.bookkeeping.app.ui.screen.transactions.DuplicateCandidate as SaveCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
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
        account: String? = null,
    ): Boolean {
        if (type != null && transaction.type != type.name) return false
        if (category != null && transaction.category != category.name) return false
        if (!AccountNormalization.matchesFilter(transaction, account)) return false
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

    fun matchesDisplayed(
        transaction: TransactionEntity,
        query: String,
        type: TransactionType?,
        category: TransactionCategory?,
        startMs: Long?,
        endMs: Long?,
        account: String? = null,
    ): Boolean {
        if (type != null && transaction.type != type.name) return false
        if (category != null && transaction.category != category.name) return false
        if (!AccountNormalization.matchesFilter(transaction, account)) return false
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
            contains(transaction.category)
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
        val account: String?,
        val period: PeriodState,
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _typeFilter = MutableStateFlow<TransactionType?>(null)
    val typeFilter: StateFlow<TransactionType?> = _typeFilter

    private val _categoryFilter = MutableStateFlow<TransactionCategory?>(null)
    val categoryFilter: StateFlow<TransactionCategory?> = _categoryFilter

    private val _accountFilter = MutableStateFlow<String?>(null)
    val accountFilter: StateFlow<String?> = _accountFilter

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
            combine(_typeFilter, _categoryFilter, _accountFilter, periodState) { type, category, account, period ->
                FilterState(type, category, account, period)
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
                    val visible = list.filter {
                        !CaptureMergePolicy.isAutoMergedDuplicate(it) &&
                            AccountNormalization.matchesFilter(it, filters.account)
                    }
                    if (amountQuery != null) {
                        visible.filter {
                            LedgerFilter.matches(
                                it,
                                query,
                                filters.type,
                                filters.category,
                                start,
                                end,
                                filters.account,
                            )
                        }
                    } else {
                        visible
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
            candidates
                .filter { CaptureMergePolicy.needsManualReview(it) }
                .map { candidate ->
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

    fun setAccountFilter(account: String?) {
        _accountFilter.value = account?.trim()?.let { AccountNormalization.normalize(it) }
    }

    fun setTimeFilter(time: LedgerTimeFilter) {
        _timeFilter.value = time
    }

    /** Single-select chips: selecting one dimension clears the other two. */
    fun selectTimeFilterOnly(time: LedgerTimeFilter) {
        _typeFilter.value = null
        _categoryFilter.value = null
        _accountFilter.value = null
        _timeFilter.value = time
    }

    fun selectTypeFilterOnly(type: TransactionType) {
        _categoryFilter.value = null
        _accountFilter.value = null
        _timeFilter.value = LedgerTimeFilter.ALL
        _typeFilter.value = type
    }

    fun selectCategoryFilterOnly(category: TransactionCategory) {
        _typeFilter.value = null
        _accountFilter.value = null
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
        _accountFilter.value = null
        _selectedMonth.value = BudgetMonth.currentKey()
        _selectedYear.value = Calendar.getInstance().get(Calendar.YEAR)
        _timeFilter.value = LedgerTimeFilter.ALL
    }

    /** Show every transaction, used by the explicit “全部” filter chip. */
    fun showAll() {
        _searchQuery.value = ""
        _typeFilter.value = null
        _categoryFilter.value = null
        _accountFilter.value = null
        _timeFilter.value = LedgerTimeFilter.ALL
    }

    fun isVisibleWithCurrentFilters(transaction: TransactionEntity): Boolean {
        if (transaction.syncDeleted || CaptureMergePolicy.isAutoMergedDuplicate(transaction)) {
            return false
        }
        val range = periodRange(
            PeriodState(
                time = _timeFilter.value,
                month = _selectedMonth.value,
                year = _selectedYear.value,
            )
        )
        return LedgerFilter.matchesDisplayed(
            transaction = transaction,
            query = _searchQuery.value,
            type = _typeFilter.value,
            category = _categoryFilter.value,
            startMs = range?.first,
            endMs = range?.second,
            account = _accountFilter.value,
        )
    }

    suspend fun addTransactionWithKey(
        amountCents: Long,
        type: TransactionType,
        categoryKey: String,
        merchant: String,
        note: String,
        timestampMs: Long = System.currentTimeMillis(),
        account: String? = null,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
    ): Long = viewModelScope.async {
        val entity = TransactionEntity(
            amountCents = amountCents,
            type = type.name,
            category = categoryKey,
            merchant = merchant,
            note = note,
            account = account?.trim()?.ifBlank { null },
            source = TransactionSource.MANUAL.name,
            timestamp = timestampMs,
        )
        val id = repository.insert(entity, diagnosticInput = diagnosticInput)
        if (merchant.isNotBlank()) {
            val builtIn = TransactionCategory.entries.firstOrNull { it.name == categoryKey }
            if (builtIn != null) {
                settings.recordCategoryLearning(merchant, builtIn)
            }
        }
        id
    }.await()

    suspend fun countByCategory(categoryKey: String): Int =
        repository.countByCategory(categoryKey)

    suspend fun reassignCategory(sourceKey: String, targetKey: String): Int =
        repository.reassignCategory(sourceKey, targetKey)

    suspend fun addTransaction(
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        timestampMs: Long = System.currentTimeMillis(),
        account: String? = null,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
    ): Long = viewModelScope.async {
        val entity = TransactionEntity(
            amountCents = amountCents,
            type = type.name,
            category = category.name,
            merchant = merchant,
            note = note,
            // 账户是本地字段：空就存 null，不写空串，避免把「没填」当成一个值同步出去。
            account = account?.trim()?.ifBlank { null },
            source = TransactionSource.MANUAL.name,
            timestamp = timestampMs,
        )
        val id = repository.insert(entity, diagnosticInput = diagnosticInput)
        if (merchant.isNotBlank()) {
            settings.recordCategoryLearning(merchant, category)
        }
        id
    }.await()

    /**
     * 保存前的疑似重复检查：先按金额 + 方向 + 币种 + 12 小时粗筛，再交给纯逻辑
     * [DuplicateRule] 按 [TimePrecision] 分档判定。
     *
     * 只读、不改任何记录；命中只用于在表单上多问一句，是否入账由用户决定。
     * 查询失败时返回 null（当作没命中）——数据库读不出来不应该拦住用户记账。
     */
    suspend fun findPossibleDuplicate(
        transaction: TransactionEntity,
        diagnosticInput: TransactionDiagnosticInput,
        precision: TimePrecision,
    ): SaveCandidate? {
        val window = DuplicateRule.candidateWindowMs(precision)
        repository.recordDiagnostic(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_DEDUP,
            result = TransactionDiagnostics.RESULT_STARTED,
            details = "Duplicate precheck started",
        )
        // 读失败按「没命中」处理；协程取消原样抛出去，避免弹窗已关还继续保存。
        val rows = try {
            dao.findAmountCandidatesAround(
                amountCents = transaction.amountCents,
                type = transaction.type,
                currency = DuplicateRule.normalizeCurrency(transaction.currency),
                windowStartMs = transaction.timestamp - window,
                windowEndMs = transaction.timestamp + window,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            repository.recordDiagnostic(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = "Duplicate precheck failed",
            )
            return null
        }
        val candidate = DuplicateRule.find(
            list = rows.map { it.toDuplicateCandidate() },
            amountCents = transaction.amountCents,
            type = transaction.type,
            currency = transaction.currency,
            merchant = transaction.merchant,
            timestampMs = transaction.timestamp,
            precision = precision,
        )
        repository.recordDiagnostic(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_DEDUP,
            result = if (candidate == null) TransactionDiagnostics.RESULT_SUCCESS
            else TransactionDiagnostics.RESULT_WARNING,
            details = if (candidate == null) "No similar bill found" else "Possible duplicate requires confirmation",
        )
        return candidate
    }

    suspend fun recordDiagnostic(
        input: TransactionDiagnosticInput,
        stage: String,
        result: String,
        details: String,
    ) {
        repository.recordDiagnostic(
            attemptId = input.attemptId,
            stage = stage,
            result = result,
            details = details,
        )
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

    /**
     * 详情页「编辑账单」的全字段保存：金额、收支类型、分类、商户、备注、账户、
     * 来源、发生时间、审核状态一次写完。
     *
     * 这里只做二次校验和字段复制；syncDirty / syncModifiedMs / syncMutationId
     * 仍由 TransactionRepository.update 统一写入，同步协议不变。
     */
    fun saveTransaction(original: TransactionEntity, fields: TransactionEditFields) {
        val now = System.currentTimeMillis()
        // 与编辑页同一套校验；非法或没有改动就不写库，也不触发同步。
        if (!TransactionEditRules.errorsOf(fields, now).isValid) return
        if (!TransactionEditRules.hasChanges(original, fields)) return
        viewModelScope.launch {
            val input = TransactionDiagnosticInput()
            repository.update(TransactionEditRules.applyTo(original, fields), input)
            val category = runCatching { TransactionCategory.valueOf(fields.category) }.getOrNull()
            if (category != null && original.category != fields.category && fields.merchant.isNotBlank()) {
                settings.recordCategoryLearning(fields.merchant, category)
            }
        }
    }

    fun saveMerchantRule(merchant: String, category: TransactionCategory, updateHistory: Boolean = true) {
        if (com.bookkeeping.app.data.merchant.isCleanupSingletonMerchant(merchant)) return
        viewModelScope.launch {
            settings.rememberMerchantCategory(merchant, category)
            if (updateHistory) {
                repository.applyMerchantRuleToHistory(merchant, category)
            }
        }
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

    fun confirmEventTime(entity: TransactionEntity) {
        if (!entity.eventTimeNeedsConfirmation) return
        viewModelScope.launch {
            repository.update(entity.copy(eventTimeNeedsConfirmation = false))
        }
    }

    fun updateEventTime(entity: TransactionEntity, timestampMs: Long) {
        if (TransactionEditRules.timestampError(timestampMs, System.currentTimeMillis()) != null) return
        if (entity.timestamp == timestampMs && !entity.eventTimeNeedsConfirmation) return
        viewModelScope.launch {
            repository.update(
                entity.copy(
                    timestamp = timestampMs,
                    eventTimeNeedsConfirmation = false,
                ),
            )
        }
    }

    fun keepBoth(entity: TransactionEntity) {
        viewModelScope.launch { repository.keepBoth(entity) }
    }

    fun mergeDuplicate(original: TransactionEntity, duplicate: TransactionEntity) {
        viewModelScope.launch { repository.mergeDuplicate(original, duplicate) }
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
