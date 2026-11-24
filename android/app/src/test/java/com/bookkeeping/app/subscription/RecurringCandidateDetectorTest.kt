package com.bookkeeping.app.subscription

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.subscription.RecurringCandidateDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RecurringCandidateDetectorTest {
    private val tz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun tx(
        id: Long,
        merchant: String,
        amountCents: Long,
        ts: Long,
        source: String = "WECHAT",
        reviewStatus: String = "unverified",
        category: String = "SUBSCRIPTION",
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        currency = "CNY",
        type = "EXPENSE",
        category = category,
        merchant = merchant,
        source = source,
        timestamp = ts,
        remoteReviewStatus = reviewStatus,
    )

    @Test
    fun detectsMonthlyAndYearlyCandidatesAndExcludesExistingOrPlaceholders() {
        val nowMs = dateMs(2026, 11, 24)
        val txs = listOf(
            // Monthly candidate: iCloud 6.00 CNY x3
            tx(1, "Apple iCloud", 600, dateMs(2026, 9, 1)),
            tx(2, "Apple iCloud", 600, dateMs(2026, 10, 1)),
            tx(3, "Apple iCloud", 600, dateMs(2026, 11, 1)),
            // Yearly candidate: 88VIP x2
            tx(4, "淘宝 88VIP", 8800, dateMs(2025, 11, 10), source = "ALIPAY"),
            tx(5, "淘宝 88VIP", 8800, dateMs(2026, 11, 10), source = "ALIPAY"),
            // Already tracked subscription -> should be skipped
            tx(6, "Netflix", 4500, dateMs(2026, 9, 15)),
            tx(7, "Netflix", 4500, dateMs(2026, 10, 15)),
            // Generic placeholder -> should be ignored
            tx(8, "微信支付", 2000, dateMs(2026, 9, 5)),
            tx(9, "微信支付", 2000, dateMs(2026, 10, 5)),
            // Excluded -> should be ignored
            tx(10, "Refunded Club", 3000, dateMs(2026, 9, 8), reviewStatus = "excluded"),
            tx(11, "Refunded Club", 3000, dateMs(2026, 10, 8), reviewStatus = "excluded"),
        )

        val existing = listOf(
            SubscriptionEntity(
                id = 1,
                syncUuid = "existing-1",
                name = "Netflix",
                amountCents = 4500,
                currency = "CNY",
                cadence = SubscriptionCadence.MONTHLY,
                nextDueMs = dateMs(2026, 12, 15),
                updatedAtMs = nowMs,
            ),
        )

        val candidates = RecurringCandidateDetector.detect(
            transactions = txs,
            existingSubscriptions = existing,
            nowMs = nowMs,
            lookbackMonths = 14,
            timeZone = tz,
        )

        assertEquals(2, candidates.size)
        // Sorted by matchingIntervals desc: Apple iCloud has 2 monthly hits, 88VIP has 1 yearly hit.
        assertEquals("Apple iCloud", candidates[0].merchant)
        assertEquals(SubscriptionCadence.MONTHLY, candidates[0].cadence)
        assertEquals(2, candidates[0].matchingIntervals)
        assertEquals("微信", candidates[0].suggestedChannel)

        assertEquals("淘宝 88VIP", candidates[1].merchant)
        assertEquals(SubscriptionCadence.YEARLY, candidates[1].cadence)
        assertEquals(1, candidates[1].matchingIntervals)
        assertEquals("支付宝", candidates[1].suggestedChannel)
    }

    @Test
    fun returnsEmptyWhenOnlySingleOccurrenceOrNonRecurringDates() {
        val nowMs = dateMs(2026, 11, 24)
        val txs = listOf(
            tx(1, "Coffee Shop", 3200, dateMs(2026, 11, 1)),
            tx(2, "Coffee Shop", 3200, dateMs(2026, 11, 4)),
        )
        val candidates = RecurringCandidateDetector.detect(
            transactions = txs,
            nowMs = nowMs,
            timeZone = tz,
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun rejectsFrequentDailySpendNoiseAndDismissedCandidates() {
        val nowMs = dateMs(2026, 11, 24)
        val txs = listOf(
            // High-frequency charging/food: 5 visits in a few days + 1 gap of 30 days -> NOT a subscription
            tx(1, "自来充", 400, dateMs(2026, 9, 1), category = "TRANSPORT"),
            tx(2, "自来充", 400, dateMs(2026, 9, 3), category = "TRANSPORT"),
            tx(3, "自来充", 400, dateMs(2026, 9, 6), category = "TRANSPORT"),
            tx(4, "自来充", 400, dateMs(2026, 9, 10), category = "TRANSPORT"),
            tx(5, "自来充", 400, dateMs(2026, 10, 10), category = "TRANSPORT"),
            // Food with only 2 visits 30 days apart -> NOT a subscription
            tx(6, "蜜雪冰城", 600, dateMs(2026, 10, 15), category = "FOOD"),
            tx(7, "蜜雪冰城", 600, dateMs(2026, 11, 15), category = "FOOD"),
            // Genuine monthly subscription that user dismissed
            tx(8, "iCloud", 600, dateMs(2026, 9, 15), category = "ENTERTAINMENT"),
            tx(9, "iCloud", 600, dateMs(2026, 10, 15), category = "ENTERTAINMENT"),
            tx(10, "iCloud", 600, dateMs(2026, 11, 15), category = "ENTERTAINMENT"),
        )
        val key = RecurringCandidateDetector.candidateKey("iCloud", "CNY", 600)
        val candidates = RecurringCandidateDetector.detect(
            transactions = txs,
            nowMs = nowMs,
            timeZone = tz,
            dismissedKeys = setOf(key),
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun detectsFxDriftingSubscriptionAndTwoCycleStrongKeywordWhileRejectingSupermarket() {
        val nowMs = dateMs(2026, 11, 24)
        val txs = listOf(
            // FX-drifting foreign subscription (ChatGPT Plus ~¥133-134 within ±5%)
            tx(1, "OpenAI ChatGPT Plus", 13316, dateMs(2026, 9, 15), category = "ENTERTAINMENT"),
            tx(2, "OpenAI ChatGPT Plus", 13405, dateMs(2026, 10, 15), category = "ENTERTAINMENT"),
            tx(3, "OpenAI ChatGPT Plus", 13290, dateMs(2026, 11, 15), category = "ENTERTAINMENT"),
            // Strong subscription keyword with 2 monthly cycles -> accepted
            tx(4, "腾讯视频会员", 2500, dateMs(2026, 10, 10), category = "ENTERTAINMENT"),
            tx(5, "腾讯视频会员", 2500, dateMs(2026, 11, 10), category = "ENTERTAINMENT"),
            // Supermarket with 2 visits 2 days apart + 1 visit 30 days ago -> rejected
            tx(6, "喜盈盈生鲜超市", 500, dateMs(2026, 10, 20), category = "SHOPPING"),
            tx(7, "喜盈盈生鲜超市", 500, dateMs(2026, 11, 20), category = "SHOPPING"),
            tx(8, "喜盈盈生鲜超市", 500, dateMs(2026, 11, 22), category = "SHOPPING"),
        )
        val candidates = RecurringCandidateDetector.detect(
            transactions = txs,
            nowMs = nowMs,
            timeZone = tz,
        )
        assertEquals(2, candidates.size)
        assertEquals("OpenAI ChatGPT Plus", candidates[0].merchant)
        assertEquals(3, candidates[0].occurrences)
        assertEquals("腾讯视频会员", candidates[1].merchant)
        assertEquals(2, candidates[1].occurrences)
    }
}
