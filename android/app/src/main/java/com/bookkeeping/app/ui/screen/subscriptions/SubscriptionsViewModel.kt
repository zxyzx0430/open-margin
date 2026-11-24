package com.bookkeeping.app.ui.screen.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.subscription.RecurringCandidate
import com.bookkeeping.app.data.subscription.RecurringCandidateDetector
import com.bookkeeping.app.data.subscription.SubscriptionFxRates
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.data.subscription.SubscriptionRepository
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
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.abs

enum class SubscriptionFilter(val label: String) {
    ALL("全部"),
    DUE_SOON("即将到期"),
    PENDING_CANCEL("准备取消"),
    INACTIVE("已停用"),
}

data class SubscriptionHistoryStats(
    val matchCount: Int = 0,
    val totalCnyCents: Long = 0L,
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val transactionRepository: TransactionRepository,
    private val fxRatesStore: SubscriptionFxRates,
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(SubscriptionFilter.ALL)
    val selectedFilter: StateFlow<SubscriptionFilter> = _selectedFilter

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val allSubscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dirtyCount: StateFlow<Int> = subscriptionRepository.observeDirtyCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val allTransactions: StateFlow<List<TransactionEntity>> = transactionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val fxRates: StateFlow<Map<String, Double>> = allSubscriptions
        .map { fxRatesStore.currentRates() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SubscriptionFxRatesCalculator.DEFAULT_RATES,
        )

    val recurringCandidates: StateFlow<List<RecurringCandidate>> = combine(
        allTransactions,
        allSubscriptions,
        subscriptionRepository.observeDismissedCandidateKeys(),
    ) { txs, subs, dismissedKeys ->
        RecurringCandidateDetector.detect(
            transactions = txs,
            existingSubscriptions = subs,
            dismissedKeys = dismissedKeys,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismissCandidate(candidateKey: String) {
        subscriptionRepository.dismissRecurringCandidate(candidateKey)
    }

    val historyStatsBySubscriptionId: StateFlow<Map<Long, SubscriptionHistoryStats>> = combine(
        allSubscriptions,
        allTransactions,
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val filteredSubscriptions: StateFlow<List<SubscriptionEntity>> = combine(
        allSubscriptions,
        _selectedFilter,
        _searchQuery,
    ) { subs, filter, query ->
        val nowMs = System.currentTimeMillis()
        val trimmedQuery = query.trim().lowercase(Locale.ROOT)
        subs.filter { sub ->
            val matchesFilter = when (filter) {
                SubscriptionFilter.ALL -> true
                SubscriptionFilter.DUE_SOON ->
                    sub.active && SubscriptionProjection.daysUntilDue(sub.nextDueMs, nowMs) <= 7
                SubscriptionFilter.PENDING_CANCEL -> sub.active && sub.pendingCancel
                SubscriptionFilter.INACTIVE -> !sub.active
            }
            if (!matchesFilter) return@filter false
            if (trimmedQuery.isEmpty()) return@filter true
            sub.name.lowercase(Locale.ROOT).contains(trimmedQuery) ||
                sub.paymentChannel.lowercase(Locale.ROOT).contains(trimmedQuery) ||
                sub.note.lowercase(Locale.ROOT).contains(trimmedQuery)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(filter: SubscriptionFilter) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun saveSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            subscriptionRepository.save(subscription)
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

    fun reactivate(id: Long) {
        viewModelScope.launch {
            subscriptionRepository.setActive(id, true)
        }
    }

    fun softDelete(id: Long) {
        viewModelScope.launch {
            subscriptionRepository.softDelete(id)
        }
    }
}

private const val ONE_YEAR_MS = 365L * 86_400_000L
private const val ONE_WEEK_MS = 7L * 86_400_000L
private val EXCLUDED_REVIEW_STATUSES = setOf("excluded", "transfer", "refund", "possible_duplicate")

fun computeSubscriptionHistoryStats(
    subscription: SubscriptionEntity,
    transactions: List<TransactionEntity>,
    fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
    nowMs: Long = System.currentTimeMillis(),
): SubscriptionHistoryStats {
    val windowStart = nowMs - ONE_YEAR_MS
    val windowEnd = nowMs + ONE_WEEK_MS
    var count = 0
    var totalCnyCents = 0L

    for (tx in transactions) {
        if (tx.syncDeleted || tx.duplicateOfId != null) continue
        if (tx.type != "EXPENSE" || tx.category == "TRANSFER") continue
        if (tx.remoteReviewStatus in EXCLUDED_REVIEW_STATUSES) continue
        if (tx.timestamp !in windowStart..windowEnd) continue
        if (!SubscriptionProjection.merchantsMatch(subscription.name, tx.merchant)) continue

        val expectedCents: Long
        val actualCents: Long
        if (subscription.currency.equals(tx.currency, ignoreCase = true)) {
            expectedCents = subscription.amountCents
            actualCents = tx.amountCents
        } else {
            expectedCents = SubscriptionProjection.estimateCnyCents(subscription, fxRates)
            actualCents = SubscriptionFxRatesCalculator.estimateCnyCents(
                tx.amountCents,
                tx.currency,
                fxRates,
            ) ?: continue
        }
        if (expectedCents <= 0L) continue
        if (abs(actualCents - expectedCents) * 100L <= expectedCents * 5L) {
            count++
            val txCny = if (tx.currency.equals("CNY", ignoreCase = true)) {
                tx.amountCents
            } else {
                SubscriptionFxRatesCalculator.estimateCnyCents(tx.amountCents, tx.currency, fxRates) ?: 0L
            }
            totalCnyCents += txCny
        }
    }

    return SubscriptionHistoryStats(matchCount = count, totalCnyCents = totalCnyCents)
}

internal fun subscriptionCadenceLabel(cadence: String): String =
    when (cadence.lowercase(Locale.ROOT)) {
        SubscriptionCadence.WEEKLY -> "每周"
        SubscriptionCadence.MONTHLY -> "每月"
        SubscriptionCadence.QUARTERLY -> "每季"
        SubscriptionCadence.YEARLY -> "每年"
        else -> "每月"
    }

internal fun subscriptionDueCountdownLabel(
    subscription: SubscriptionEntity,
    nowMs: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    if (!subscription.active) return "已停用"
    val days = SubscriptionProjection.daysUntilDue(subscription.nextDueMs, nowMs, timeZone)
    return when {
        days < 0 -> "已逾期 ${-days} 天"
        days == 0 -> "今天扣款"
        days == 1 -> "明天扣款"
        else -> "${days}天后扣款"
    }
}

internal fun formatSubscriptionOriginalCurrency(amountCents: Long, currency: String): String {
    val code = currency.trim().uppercase(Locale.US)
    val symbol = when (code) {
        "CNY" -> "¥"
        "USD" -> "$"
        "HKD" -> "HK$"
        "EUR" -> "€"
        "JPY" -> "¥"
        "GBP" -> "£"
        else -> "$code "
    }
    return if (code == "JPY") {
        val whole = (amountCents + 50L) / 100L
        "$symbol%,d".format(Locale.US, whole)
    } else {
        "$symbol%,.2f".format(Locale.US, amountCents / 100.0)
    }
}

internal fun formatSmartCny(cents: Long): String {
    val absCents = abs(cents)
    val sign = if (cents < 0L) "−" else ""
    return if (absCents % 100L == 0L) {
        "${sign}¥${"%,d".format(Locale.CHINA, absCents / 100L)}"
    } else {
        "${sign}¥${"%,.2f".format(Locale.CHINA, absCents / 100.0)}"
    }
}
