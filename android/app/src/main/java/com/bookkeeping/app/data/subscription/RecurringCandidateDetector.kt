package com.bookkeeping.app.data.subscription

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
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
    val matchedTimestampsMs: List<Long> = emptyList(),
    val candidateKey: String = "",
)

/**
 * Pure detector ported & hardened from backend/features.py `recurring_candidates`.
 *
 * Requires consistent cadence intervals (not sporadic/daily food or charging
 * spending that happens to have 2 dates 30 days apart) and supports persistent
 * user dismissal via [dismissedKeys].
 */
object RecurringCandidateDetector {
    private const val DAY_MS = 86_400_000L
    private const val MAX_MONTHLY_STALE_DAYS = 65L
    private const val MAX_YEARLY_STALE_DAYS = 400L

    private val IGNORED_MERCHANTS = setOf(
        "",
        "unknown",
        "未知",
        "未知商户",
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

    /** Categories where 2 isolated visits ~30 days apart are almost never a subscription. */
    private val DAILY_SPEND_CATEGORIES = setOf("FOOD", "SHOPPING", "TRANSPORT")

    fun candidateKey(merchant: String, currency: String, amountCents: Long): String =
        "${normalizeMerchant(merchant)}|${currency.uppercase(Locale.US)}|$amountCents"

    fun detect(
        transactions: List<TransactionEntity>,
        existingSubscriptions: List<SubscriptionEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        lookbackMonths: Int = 12,
        timeZone: TimeZone = TimeZone.getDefault(),
        dismissedKeys: Set<String> = emptySet(),
    ): List<RecurringCandidate> {
        val earliestMs = nowMs - lookbackMonths.coerceIn(2, 60) * 31L * DAY_MS
        val todayDay = localEpochDay(nowMs, timeZone)
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
            val cKey = "${key.normalizedMerchant}|${key.currency}|${key.amountCents}"
            if (cKey in dismissedKeys) continue

            // Deduplicate same-day repeats while keeping latest timestamp per civil day.
            val byDay = linkedMapOf<Long, TransactionEntity>()
            for (r in records) {
                byDay[localEpochDay(r.timestamp, timeZone)] = r
            }
            val uniqueDays = byDay.keys.sorted()
            if (uniqueDays.size < 2) continue

            val deltas = (0 until uniqueDays.lastIndex).map { uniqueDays[it + 1] - uniqueDays[it] }
            val monthlyHits = deltas.count { it in 26L..35L }
            val annualHits = deltas.count { it in 350L..380L }
            if (monthlyHits == 0 && annualHits == 0) continue

            val latest = records.last()
            val lastSeenDay = uniqueDays.last()
            val daysSinceLast = todayDay - lastSeenDay

            val isMonthly = if (monthlyHits >= annualHits && monthlyHits > 0) {
                val ratio = monthlyHits.toDouble() / deltas.size.toDouble()
                val shortBursts = deltas.count { it < 15L }
                val avgDelta = deltas.average()
                val recentEnough = daysSinceLast <= MAX_MONTHLY_STALE_DAYS
                val twoOccurrenceDayMatch = if (uniqueDays.size == 2) {
                    val d1 = dayOfMonth(byDay[uniqueDays[0]]!!.timestamp, timeZone)
                    val d2 = dayOfMonth(byDay[uniqueDays[1]]!!.timestamp, timeZone)
                    abs(d1 - d2) <= 3 && latest.category !in DAILY_SPEND_CATEGORIES
                } else {
                    true
                }
                recentEnough &&
                    ratio >= 0.6 &&
                    avgDelta >= 22.0 &&
                    shortBursts <= 1 &&
                    twoOccurrenceDayMatch
            } else {
                false
            }

            val isYearly = if (!isMonthly && annualHits > 0) {
                daysSinceLast <= MAX_YEARLY_STALE_DAYS &&
                    uniqueDays.size <= 3 &&
                    deltas.all { it >= 300L }
            } else {
                false
            }

            if (!isMonthly && !isYearly) continue
            val cadence = if (isMonthly) SubscriptionCadence.MONTHLY else SubscriptionCadence.YEARLY

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
                    occurrences = uniqueDays.size,
                    matchingIntervals = max(monthlyHits, annualHits),
                    lastSeenMs = lastSeenMs,
                    suggestedNextDueMs = nextDue,
                    suggestedChannel = inferChannel(latest),
                    matchedTimestampsMs = uniqueDays.mapNotNull { byDay[it]?.timestamp },
                    candidateKey = cKey,
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

    private fun dayOfMonth(timestampMs: Long, timeZone: TimeZone): Int =
        Calendar.getInstance(timeZone).apply { timeInMillis = timestampMs }.get(Calendar.DAY_OF_MONTH)

    private fun localEpochDay(timestampMs: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMs
        }
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
