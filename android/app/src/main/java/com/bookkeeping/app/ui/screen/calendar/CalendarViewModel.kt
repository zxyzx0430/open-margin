package com.bookkeeping.app.ui.screen.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.subscription.RecurringCandidateDetector
import com.bookkeeping.app.data.subscription.SubscriptionFxRates
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.data.subscription.SubscriptionRepository
import com.bookkeeping.app.ui.screen.subscriptions.SubscriptionHistoryStats
import com.bookkeeping.app.ui.screen.subscriptions.computeSubscriptionHistoryStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject

data class SubscriptionOccurrence(
    val subscription: SubscriptionEntity,
    val dueMs: Long,
    val dayOfMonth: Int,
    val cnyAmountCents: Long,
    val isPaidInCycle: Boolean,
)

data class MonthlyRenewalSummary(
    val totalCount: Int = 0,
    val totalCnyCents: Long = 0L,
    val remainingUnpaidCnyCents: Long = 0L,
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    repository: TransactionRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val fxRatesStore: SubscriptionFxRates,
) : ViewModel() {

    private val _selectedMonthKey = MutableStateFlow(BudgetMonth.currentKey())

    val transactions: StateFlow<List<TransactionEntity>> = repository.observeUiTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyList())

    internal val indexedTransactions: StateFlow<CalendarLedgerSnapshot> = transactions
        .map { rows ->
            CalendarLedgerSnapshot(rows, rows.asSequence().filterNot { it.syncDeleted }.groupBy { dateOf(it.timestamp) })
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), CalendarLedgerSnapshot())

    val allSubscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyList())

    private val activeSubscriptions: StateFlow<List<SubscriptionEntity>> =
        subscriptionRepository.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyList())

    val fxRates: StateFlow<Map<String, Double>> = activeSubscriptions
        .map { fxRatesStore.currentRates() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(30_000),
            SubscriptionFxRatesCalculator.DEFAULT_RATES,
        )

    val activeSubscriptionCount: StateFlow<Int> = activeSubscriptions
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), 0)

    val recurringCandidateCount: StateFlow<Int> = combine(
        transactions,
        allSubscriptions,
        subscriptionRepository.observeDismissedCandidateKeys(),
    ) { txs, subs, dismissedKeys ->
        RecurringCandidateDetector.detect(
            transactions = txs,
            existingSubscriptions = subs,
            dismissedKeys = dismissedKeys,
        ).size
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), 0)

    val monthlyOccurrencesByDay: StateFlow<Map<Int, List<SubscriptionOccurrence>>> = combine(
        _selectedMonthKey,
        activeSubscriptions,
        transactions,
        fxRates,
    ) { monthKey, subs, txs, rates ->
        val parts = BudgetMonth.parse(monthKey) ?: return@combine emptyMap()
        val tz = TimeZone.getDefault()
        val occurrences = mutableListOf<SubscriptionOccurrence>()
        for (sub in subs) {
            val cnyCents = SubscriptionProjection.estimateCnyCents(sub, rates)
            val dueDates = SubscriptionProjection.occurrencesInMonth(sub, parts.year, parts.month, tz)
            for (dueMs in dueDates) {
                val paid = txs.any { tx ->
                    SubscriptionProjection.matchesTransaction(
                        subscription = sub.copy(nextDueMs = dueMs),
                        transaction = tx,
                        fxRates = rates,
                        timeZone = tz,
                    )
                }
                occurrences.add(
                    SubscriptionOccurrence(
                        subscription = sub,
                        dueMs = dueMs,
                        dayOfMonth = SubscriptionProjection.dayOfMonth(dueMs, tz),
                        cnyAmountCents = cnyCents,
                        isPaidInCycle = paid,
                    ),
                )
            }
        }
        occurrences
            .sortedWith(compareBy<SubscriptionOccurrence> { it.dueMs }.thenBy { it.subscription.id })
            .groupBy { it.dayOfMonth }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyMap())

    val monthlyRenewalSummary: StateFlow<MonthlyRenewalSummary> = monthlyOccurrencesByDay
        .map { byDay ->
            val all = byDay.values.flatten()
            MonthlyRenewalSummary(
                totalCount = all.size,
                totalCnyCents = all.sumOf { it.cnyAmountCents },
                remainingUnpaidCnyCents = all.filter { !it.isPaidInCycle }.sumOf { it.cnyAmountCents },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), MonthlyRenewalSummary())

    val historyStatsBySubscriptionId: StateFlow<Map<Long, SubscriptionHistoryStats>> = combine(
        allSubscriptions,
        transactions,
        fxRates,
    ) { subs, txs, rates ->
        val nowMs = System.currentTimeMillis()
        subs.associate { sub ->
            sub.id to computeSubscriptionHistoryStats(
                subscription = sub,
                transactions = txs,
                fxRates = rates,
                nowMs = nowMs,
            )
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30_000), emptyMap())

    fun setMonthKey(monthKey: String) {
        if (BudgetMonth.isValid(monthKey)) {
            _selectedMonthKey.value = monthKey
        }
    }

    fun advanceToNextCycle(id: Long) {
        viewModelScope.launch {
            subscriptionRepository.markPaidAndAdvance(id)
        }
    }

    fun toggleAutoRenew(id: Long) {
        viewModelScope.launch {
            val current = subscriptionRepository.getById(id) ?: return@launch
            subscriptionRepository.setAutoRenew(id, !current.autoRenew)
        }
    }

    fun togglePendingCancel(id: Long) {
        viewModelScope.launch {
            val current = subscriptionRepository.getById(id) ?: return@launch
            subscriptionRepository.setPendingCancel(id, !current.pendingCancel)
        }
    }

    fun toggleNotify(id: Long) {
        viewModelScope.launch {
            val current = subscriptionRepository.getById(id) ?: return@launch
            subscriptionRepository.setNotifySettings(
                id = id,
                enabled = !current.notifyEnabled,
                daysBefore = current.notifyDaysBefore,
            )
        }
    }

    fun setNotifyDaysBefore(id: Long, daysBefore: Int) {
        viewModelScope.launch {
            val current = subscriptionRepository.getById(id) ?: return@launch
            subscriptionRepository.setNotifySettings(
                id = id,
                enabled = true,
                daysBefore = daysBefore.coerceIn(0, 30),
            )
        }
    }

    fun deactivate(id: Long) {
        viewModelScope.launch {
            subscriptionRepository.setActive(id, false)
        }
    }

    fun saveSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            subscriptionRepository.save(subscription)
        }
    }

    fun softDeleteSubscription(id: Long) {
        viewModelScope.launch {
            subscriptionRepository.softDelete(id)
        }
    }
}

internal data class CalendarLedgerSnapshot(
    val rows: List<TransactionEntity> = emptyList(),
    val byDate: Map<CalendarDate, List<TransactionEntity>> = emptyMap(),
)
