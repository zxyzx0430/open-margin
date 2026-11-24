package com.bookkeeping.app.data.subscription

import com.bookkeeping.app.data.local.dao.SubscriptionDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SubscriptionMonthOccurrence(
    val subscription: SubscriptionEntity,
    val dueMs: Long,
    val isPaidInCycle: Boolean,
)

@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val transactionDao: TransactionDao,
    private val fxRates: SubscriptionFxRates,
) {
    fun observeActive(): Flow<List<SubscriptionEntity>> = subscriptionDao.observeActive()

    fun observeAll(): Flow<List<SubscriptionEntity>> = subscriptionDao.observeAllNonDeleted()

    fun observeDirtyCount(): Flow<Int> = subscriptionDao.observeDirtyCount()

    suspend fun getAll(): List<SubscriptionEntity> = subscriptionDao.getAllNonDeleted()

    suspend fun getActive(): List<SubscriptionEntity> = subscriptionDao.getActiveNonDeleted()

    suspend fun getById(id: Long): SubscriptionEntity? = subscriptionDao.getById(id)

    suspend fun save(
        subscription: SubscriptionEntity,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val normalizedDue = SubscriptionProjection.startOfDayMs(subscription.nextDueMs, timeZone)
        val cnyEstimate = fxRates.estimateCnyCents(subscription.amountCents, subscription.currency)
        val uuid = subscription.syncUuid.ifBlank { UUID.randomUUID().toString() }
        return subscriptionDao.upsert(
            subscription.copy(
                syncUuid = uuid,
                nextDueMs = normalizedDue,
                cnyEstimateCents = cnyEstimate,
                updatedAtMs = nowMs,
                deletedAtMs = null,
                dirty = true,
            ),
        )
    }

    suspend fun setAutoRenew(
        id: Long,
        autoRenew: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = subscriptionDao.getById(id) ?: return
        subscriptionDao.upsert(
            current.copy(autoRenew = autoRenew, updatedAtMs = nowMs, dirty = true),
        )
    }

    suspend fun setPendingCancel(
        id: Long,
        pendingCancel: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = subscriptionDao.getById(id) ?: return
        subscriptionDao.upsert(
            current.copy(pendingCancel = pendingCancel, updatedAtMs = nowMs, dirty = true),
        )
    }

    suspend fun setNotifySettings(
        id: Long,
        enabled: Boolean,
        daysBefore: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = subscriptionDao.getById(id) ?: return
        subscriptionDao.upsert(
            current.copy(
                notifyEnabled = enabled,
                notifyDaysBefore = daysBefore.coerceIn(0, 365),
                updatedAtMs = nowMs,
                dirty = true,
            ),
        )
    }

    suspend fun setActive(
        id: Long,
        active: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = subscriptionDao.getById(id) ?: return
        subscriptionDao.upsert(
            current.copy(active = active, updatedAtMs = nowMs, dirty = true),
        )
    }

    suspend fun softDelete(id: Long, nowMs: Long = System.currentTimeMillis()) {
        subscriptionDao.softDelete(id = id, deletedAtMs = nowMs, updatedAtMs = nowMs)
    }

    suspend fun markPaidAndAdvance(
        id: Long,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): SubscriptionEntity? {
        val current = subscriptionDao.getById(id) ?: return null
        val anchorDay = SubscriptionProjection.dayOfMonth(current.startMs ?: current.nextDueMs, timeZone)
        val nextDue = SubscriptionProjection.advanceDueMs(
            dueMs = current.nextDueMs,
            cadence = current.cadence,
            anchorDayOfMonth = anchorDay,
            timeZone = timeZone,
        )
        val updated = current.copy(
            nextDueMs = nextDue,
            updatedAtMs = nowMs,
            dirty = true,
        )
        subscriptionDao.upsert(updated)
        return updated
    }

    suspend fun advanceOverdueAutoRenew(
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Int {
        val todayStartMs = SubscriptionProjection.startOfDayMs(nowMs, timeZone)
        val activeSubs = subscriptionDao.getActiveNonDeleted()
        var advanced = 0
        for (sub in activeSubs) {
            if (!sub.autoRenew) continue
            val dueStartMs = SubscriptionProjection.startOfDayMs(sub.nextDueMs, timeZone)
            if (dueStartMs < todayStartMs) {
                val newDue = SubscriptionProjection.advancePast(
                    nextDueMs = dueStartMs,
                    cadence = sub.cadence,
                    referenceMs = todayStartMs,
                    startMs = sub.startMs,
                    timeZone = timeZone,
                )
                if (newDue > dueStartMs) {
                    subscriptionDao.upsert(
                        sub.copy(
                            nextDueMs = newDue,
                            updatedAtMs = nowMs,
                            dirty = true,
                        ),
                    )
                    advanced++
                }
            }
        }
        return advanced
    }

    suspend fun matchAndAdvanceOnCapturedTransaction(
        transaction: TransactionEntity,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): SubscriptionEntity? {
        val rates = fxRates.currentRates()
        val activeSubs = subscriptionDao.getActiveNonDeleted()
        val matched = activeSubs.firstOrNull { sub ->
            SubscriptionProjection.matchesTransaction(sub, transaction, rates, timeZone)
        } ?: return null
        val anchorDay = SubscriptionProjection.dayOfMonth(matched.startMs ?: matched.nextDueMs, timeZone)
        val nextDue = SubscriptionProjection.advanceDueMs(
            dueMs = matched.nextDueMs,
            cadence = matched.cadence,
            anchorDayOfMonth = anchorDay,
            timeZone = timeZone,
        )
        val updated = matched.copy(
            nextDueMs = nextDue,
            updatedAtMs = nowMs,
            dirty = true,
        )
        subscriptionDao.upsert(updated)
        return updated
    }

    suspend fun detectRecurringCandidates(
        months: Int = 12,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<RecurringCandidate> {
        val txs = transactionDao.observeAll().first()
        val subs = subscriptionDao.getAllNonDeleted()
        return RecurringCandidateDetector.detect(
            transactions = txs,
            existingSubscriptions = subs,
            nowMs = nowMs,
            lookbackMonths = months,
            timeZone = timeZone,
        )
    }

    suspend fun projectMonth(
        year: Int,
        month: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<SubscriptionMonthOccurrence> {
        val activeSubs = subscriptionDao.getActiveNonDeleted()
        val txs = transactionDao.observeAll().first()
        val rates = fxRates.currentRates()
        val out = mutableListOf<SubscriptionMonthOccurrence>()
        for (sub in activeSubs) {
            val dueDates = SubscriptionProjection.occurrencesInMonth(sub, year, month, timeZone)
            for (dueMs in dueDates) {
                val paid = txs.any { tx ->
                    SubscriptionProjection.matchesTransaction(
                        subscription = sub.copy(nextDueMs = dueMs),
                        transaction = tx,
                        fxRates = rates,
                        timeZone = timeZone,
                    )
                }
                out.add(SubscriptionMonthOccurrence(sub, dueMs, paid))
            }
        }
        return out.sortedBy { it.dueMs }
    }

    suspend fun forecast12Months(
        startYear: Int,
        startMonth: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): TwelveMonthForecast = SubscriptionProjection.forecast12Months(
        subscriptions = subscriptionDao.getActiveNonDeleted(),
        startYear = startYear,
        startMonth = startMonth,
        fxRates = fxRates.currentRates(),
        timeZone = timeZone,
    )

    suspend fun monthlyEquivalentTotalCnyCents(): Long {
        val rates = fxRates.currentRates()
        return subscriptionDao.getActiveNonDeleted()
            .sumOf { SubscriptionProjection.monthlyEquivalentCnyCents(it, rates) }
    }

    suspend fun pendingCancelMonthlySavingsCnyCents(): Long {
        val rates = fxRates.currentRates()
        return subscriptionDao.getActiveNonDeleted()
            .filter { it.pendingCancel }
            .sumOf { SubscriptionProjection.monthlyEquivalentCnyCents(it, rates) }
    }
}
