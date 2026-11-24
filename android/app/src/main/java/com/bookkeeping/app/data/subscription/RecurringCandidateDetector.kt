package com.bookkeeping.app.data.subscription

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

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
 * Industrial-grade recurring subscription detector modeled on open-source
 * personal finance engines:
 * 1. Groups by (normalizedMerchant, currency) and clusters amounts within ±5%
 *    tolerance so foreign-currency FX fluctuations (e.g. USD -> CNY) still match.
 * 2. Evaluates interval mean & standard deviation (monthly: mean 27..34d, sigma <= 4d;
 *    yearly: mean 350..380d, sigma <= 10d) to reject irregular supermarket/food visits.
 * 3. Tiered threshold: strong subscription keywords allow 2 occurrences;
 *    general / daily-spend merchants require >= 3 consistent cycles.
 * 4. Staleness guard & persistent dismissal via [dismissedKeys].
 */
object RecurringCandidateDetector {
    private const val DAY_MS = 86_400_000L
    private const val MAX_MONTHLY_STALE_DAYS = 65L
    private const val MAX_YEARLY_STALE_DAYS = 400L
    private const val AMOUNT_CLUSTER_TOLERANCE = 0.05 // ±5%
    private const val MAX_AMOUNT_CV = 0.05

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

    private val DAILY_SPEND_CATEGORIES = setOf("FOOD", "SHOPPING", "TRANSPORT", "HEALTH")

    /** Merchants/notes with explicit subscription signals can match after 2 consistent cycles. */
    private val STRONG_SUBSCRIPTION_KEYWORDS = listOf(
        "会员", "vip", "连续包月", "自动续费", "订阅", "包年", "包季",
        "icloud", "apple one", "apple music", "chatgpt", "openai", "claude", "anthropic",
        "cursor", "github", "copilot", "netflix", "spotify", "youtube", "disney",
        "88vip", "话费", "宽带", "中国移动", "中国联通", "中国电信",
        "百度网盘", "夸克网盘", "阿里云盘", "腾讯视频", "爱奇艺", "优酷", "芒果tv",
        "网易云", "qq音乐", "喜马拉雅", "wps", "notion", "keep", "健身房", "乐刻",
    )

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

        data class MerchantKey(
            val normalizedMerchant: String,
            val currency: String,
        )

        val byMerchant = linkedMapOf<MerchantKey, MutableList<TransactionEntity>>()
        for (tx in filtered) {
            val normalized = normalizeMerchant(tx.merchant)
            if (normalized in IGNORED_MERCHANTS) continue
            val key = MerchantKey(normalized, tx.currency.uppercase(Locale.US))
            byMerchant.getOrPut(key) { mutableListOf() }.add(tx)
        }

        val activeOrExistingSubs = existingSubscriptions.filter { it.deletedAtMs == null }
        val candidates = mutableListOf<RecurringCandidate>()

        for ((mKey, allRecords) in byMerchant) {
            if (allRecords.size < 2) continue

            // Cluster records by ±5% amount tolerance so FX-drifting subscriptions stay grouped
            // while unrelated prices at the same merchant stay separated.
            val clusters = clusterByAmount(allRecords)

            for (records in clusters) {
                if (records.size < 2) continue

                val byDay = linkedMapOf<Long, TransactionEntity>()
                for (r in records.sortedBy { it.timestamp }) {
                    byDay[localEpochDay(r.timestamp, timeZone)] = r
                }
                val uniqueDays = byDay.keys.sorted()
                if (uniqueDays.size < 2) continue

                val dedupRecords = uniqueDays.mapNotNull { byDay[it] }
                val amounts = dedupRecords.map { it.amountCents.toDouble() }
                val meanAmount = amounts.average()
                if (meanAmount <= 0.0) continue
                val amountStd = stdDev(amounts, meanAmount)
                if (amountStd / meanAmount > MAX_AMOUNT_CV) continue

                val deltas = (0 until uniqueDays.lastIndex).map { (uniqueDays[it + 1] - uniqueDays[it]).toDouble() }
                val meanDelta = deltas.average()
                val deltaStd = stdDev(deltas, meanDelta)
                val monthlyHits = deltas.count { it in 26.0..35.0 }
                val annualHits = deltas.count { it in 350.0..380.0 }
                if (monthlyHits == 0 && annualHits == 0) continue

                val latest = dedupRecords.last()
                val lastSeenDay = uniqueDays.last()
                val daysSinceLast = todayDay - lastSeenDay
                val hasStrongSignal = hasStrongSubscriptionSignal(latest.merchant, latest.note)
                val isDailyCategory = latest.category in DAILY_SPEND_CATEGORIES && !hasStrongSignal
                val hasShortBurst = deltas.any { it < 20.0 }

                val isMonthly = if (monthlyHits >= annualHits && monthlyHits > 0) {
                    val recentEnough = daysSinceLast <= MAX_MONTHLY_STALE_DAYS
                    val statsConsistent = meanDelta in 27.0..34.0 && deltaStd <= 4.0 && !hasShortBurst
                    val enoughSamples = when {
                        uniqueDays.size >= 3 -> true
                        hasStrongSignal -> {
                            val d1 = dayOfMonth(dedupRecords[0].timestamp, timeZone)
                            val d2 = dayOfMonth(dedupRecords[1].timestamp, timeZone)
                            abs(d1 - d2) <= 3
                        }
                        else -> false // General & daily-spend merchants require >= 3 cycles
                    }
                    val dailyGuard = !isDailyCategory || (uniqueDays.size >= 3 && deltaStd <= 3.0)
                    recentEnough && statsConsistent && enoughSamples && dailyGuard
                } else {
                    false
                }

                val isYearly = if (!isMonthly && annualHits > 0) {
                    daysSinceLast <= MAX_YEARLY_STALE_DAYS &&
                        uniqueDays.size <= 3 &&
                        meanDelta in 350.0..380.0 &&
                        deltaStd <= 10.0
                } else {
                    false
                }

                if (!isMonthly && !isYearly) continue
                val cadence = if (isMonthly) SubscriptionCadence.MONTHLY else SubscriptionCadence.YEARLY
                val repAmountCents = latest.amountCents
                val cKey = candidateKey(latest.merchant, mKey.currency, repAmountCents)
                if (cKey in dismissedKeys) continue

                val alreadyTracked = activeOrExistingSubs.any { sub ->
                    val amountWithin5Pct = abs(sub.amountCents - repAmountCents) <=
                        max(100L, (repAmountCents * 0.05).roundToLong())
                    sub.currency.equals(mKey.currency, ignoreCase = true) &&
                        amountWithin5Pct &&
                        SubscriptionProjection.merchantsMatch(sub.name, latest.merchant)
                }
                if (alreadyTracked) continue

                val lastSeenMs = dedupRecords.maxOf { it.timestamp }
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
                        currency = mKey.currency,
                        amountCents = repAmountCents,
                        cadence = cadence,
                        occurrences = uniqueDays.size,
                        matchingIntervals = max(monthlyHits, annualHits),
                        lastSeenMs = lastSeenMs,
                        suggestedNextDueMs = nextDue,
                        suggestedChannel = inferChannel(latest),
                        matchedTimestampsMs = dedupRecords.map { it.timestamp },
                        candidateKey = cKey,
                    ),
                )
            }
        }

        return candidates.sortedWith(
            compareByDescending<RecurringCandidate> { it.matchingIntervals }
                .thenByDescending { it.lastSeenMs },
        )
    }

    private fun clusterByAmount(records: List<TransactionEntity>): List<List<TransactionEntity>> {
        val sorted = records.sortedBy { it.amountCents }
        val clusters = mutableListOf<MutableList<TransactionEntity>>()
        for (tx in sorted) {
            val match = clusters.firstOrNull { cluster ->
                val anchor = cluster.first().amountCents
                abs(tx.amountCents - anchor) <= anchor * AMOUNT_CLUSTER_TOLERANCE
            }
            if (match != null) {
                match.add(tx)
            } else {
                clusters.add(mutableListOf(tx))
            }
        }
        return clusters
    }

    private fun stdDev(values: List<Double>, mean: Double): Double {
        if (values.size <= 1) return 0.0
        val variance = values.sumOf { v ->
            val d = v - mean
            d * d
        } / values.size.toDouble()
        return sqrt(variance)
    }

    private fun hasStrongSubscriptionSignal(merchant: String, note: String): Boolean {
        val hay = "${merchant.lowercase(Locale.ROOT)} ${note.lowercase(Locale.ROOT)}"
        return STRONG_SUBSCRIPTION_KEYWORDS.any { hay.contains(it) }
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
