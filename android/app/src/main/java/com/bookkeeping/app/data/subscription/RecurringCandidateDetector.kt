package com.bookkeeping.app.data.subscription

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

data class RecurringCandidate(
    val merchant: String,
    val currency: String,
    val amountCents: Long,
    val cadence: String,
    val occurrences: Int,
    val matchingIntervals: Int,
    val lastSeenMs: Long,
    val suggestedNextDueMs: Long,
    val suggestedChannel: String,
)

/**
 * Pure detector ported from backend/features.py `recurring_candidates`.
 * Scans local transactions to suggest recurring merchant + amount patterns
 * that are not yet tracked in subscriptions.
 */
object RecurringCandidateDetector {
    private const val DAY_MS = 86_400_000L

    private val IGNORED_MERCHANTS = setOf(
        "",
        "unknown",
        "未知",
        "其他",
        "微信支付",
        "支付宝",
    )

    private val IGNORED_REVIEW_STATUSES = setOf(
        "excluded",
        "transfer",
        "refund",
        "possible_duplicate",
    )

    fun detect(
        transactions: List<TransactionEntity>,
        existingSubscriptions: List<SubscriptionEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        lookbackMonths: Int = 12,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<RecurringCandidate> {
        val earliestMs = nowMs - lookbackMonths.coerceIn(2, 60) * 31L * DAY_MS
        val filtered = transactions
            .asSequence()
            .filter { !it.syncDeleted && it.duplicateOfId == null }
            .filter { it.type == "EXPENSE" && it.category != "TRANSFER" }
            .filter { it.remoteReviewStatus !in IGNORED_REVIEW_STATUSES }
            .filter { it.timestamp >= earliestMs && it.amountCents > 0L }
            .sortedBy { it.timestamp }
            .toList()

        data class GroupKey(
            val normalizedMerchant: String,
            val currency: String,
            val amountCents: Long,
        )

        val grouped = linkedMapOf<GroupKey, MutableList<TransactionEntity>>()
        for (tx in filtered) {
            val normalized = normalizeMerchant(tx.merchant)
            if (normalized in IGNORED_MERCHANTS) continue
            val key = GroupKey(normalized, tx.currency.uppercase(Locale.US), tx.amountCents)
            grouped.getOrPut(key) { mutableListOf() }.add(tx)
        }

        val activeOrExistingSubs = existingSubscriptions.filter { it.deletedAtMs == null }
        val candidates = mutableListOf<RecurringCandidate>()

        for ((key, records) in grouped) {
            if (records.size < 2) continue
            val uniqueDays = records
                .map { localEpochDay(it.timestamp, timeZone) }
                .distinct()
                .sorted()
            if (uniqueDays.size < 2) continue

            var monthlyHits = 0
            var annualHits = 0
            for (i in 0 until uniqueDays.lastIndex) {
                val delta = uniqueDays[i + 1] - uniqueDays[i]
                if (delta in 25L..35L) monthlyHits++
                if (delta in 350L..380L) annualHits++
            }
            if (monthlyHits == 0 && annualHits == 0) continue

            val latest = records.last()
            val cadence = if (monthlyHits >= annualHits) {
                SubscriptionCadence.MONTHLY
            } else {
                SubscriptionCadence.YEARLY
            }

            val alreadyTracked = activeOrExistingSubs.any { sub ->
                sub.currency.equals(key.currency, ignoreCase = true) &&
                    sub.amountCents == key.amountCents &&
                    SubscriptionProjection.merchantsMatch(sub.name, latest.merchant)
            }
            if (alreadyTracked) continue

            val lastSeenMs = records.maxOf { it.timestamp }
            val nextDue = SubscriptionProjection.advancePast(
                nextDueMs = SubscriptionProjection.advanceDueMs(lastSeenMs, cadence, timeZone = timeZone),
                cadence = cadence,
                referenceMs = nowMs,
                startMs = lastSeenMs,
                timeZone = timeZone,
            )

            candidates.add(
                RecurringCandidate(
                    merchant = latest.merchant.trim(),
                    currency = key.currency,
                    amountCents = key.amountCents,
                    cadence = cadence,
                    occurrences = records.size,
                    matchingIntervals = max(monthlyHits, annualHits),
                    lastSeenMs = lastSeenMs,
                    suggestedNextDueMs = nextDue,
                    suggestedChannel = inferChannel(latest),
                ),
            )
        }

        return candidates.sortedWith(
            compareByDescending<RecurringCandidate> { it.matchingIntervals }
                .thenByDescending { it.lastSeenMs },
        )
    }

    private fun normalizeMerchant(raw: String): String =
        raw.replace(Regex("\\s+"), "").lowercase(Locale.ROOT)

    private fun localEpochDay(timestampMs: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMs
        }
        // Civil day index (Gregorian calendar) via UTC-equivalent millis of local Y/M/D.
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        }
        return utc.timeInMillis / DAY_MS
    }

    private fun inferChannel(tx: TransactionEntity): String {
        val account = tx.account?.trim().orEmpty()
        if (account.isNotBlank()) return account
        return when (tx.source) {
            "WECHAT" -> "微信"
            "ALIPAY" -> "支付宝"
            "BANK_SMS" -> "银行卡"
            else -> ""
        }
    }
}
