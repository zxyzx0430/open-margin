package com.bookkeeping.app.ui.screen.stats

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.ui.screen.transactions.LedgerFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToLong

class StatsV146Test {

    @Test
    fun accountNormalizationMergesSynonymsAndBlanks() {
        assertEquals("微信", AccountNormalization.normalize("微信"))
        assertEquals("微信", AccountNormalization.normalize("微信支付"))
        assertEquals("微信", AccountNormalization.normalize(" 微信零钱 "))
        assertEquals("微信", AccountNormalization.normalize("WeChat Pay"))
        assertEquals("支付宝", AccountNormalization.normalize("支付宝"))
        assertEquals("支付宝", AccountNormalization.normalize("支付宝余额"))
        assertEquals("支付宝", AccountNormalization.normalize("alipay"))
        assertEquals("支付宝", AccountNormalization.normalize("花呗"))
        assertEquals("支付宝", AccountNormalization.normalize("蚂蚁花呗"))
        assertEquals("支付宝", AccountNormalization.normalize("Huabei"))
        assertEquals("支付宝", AccountNormalization.normalize("Ant Credit Pay"))
        assertEquals("招商银行储蓄卡", AccountNormalization.normalize("招商银行储蓄卡"))
        assertEquals(AccountNormalization.UNASSIGNED_ACCOUNT_KEY, AccountNormalization.normalize(null))
        assertEquals(AccountNormalization.UNASSIGNED_ACCOUNT_KEY, AccountNormalization.normalize(""))
        assertEquals(AccountNormalization.UNASSIGNED_ACCOUNT_KEY, AccountNormalization.normalize("   "))
    }

    @Test
    fun accountAggregationFollowsStatsPolicyAndSortsUnassignedLast() {
        val transactions = listOf(
            tx(amountCents = 10_000L, account = null), // unassigned (largest raw amount, must sort last)
            tx(amountCents = 2_000L, account = "微信支付"),
            tx(amountCents = 1_500L, account = "微信零钱"), // merges with 微信 -> 3_500
            tx(amountCents = 5_000L, account = "支付宝余额", status = "transfer"), // included by StatsTransactionPolicy
            tx(amountCents = 4_000L, account = "招商银行"),
            // Excluded by StatsTransactionPolicy:
            tx(amountCents = 9_900L, account = "现金", currency = "USD"),
            tx(amountCents = 9_900L, account = "现金", duplicateOfId = 1L),
            tx(amountCents = 9_900L, account = "现金", status = "excluded"),
            tx(amountCents = 9_900L, account = "现金", type = TransactionType.INCOME),
        )

        val stats = AccountAggregation.accountStats(transactions, TransactionType.EXPENSE)

        assertEquals(
            listOf("支付宝", "招商银行", "微信", AccountNormalization.UNASSIGNED_ACCOUNT_KEY),
            stats.map { it.accountKey },
        )
        assertEquals(listOf(5_000L, 4_000L, 3_500L, 10_000L), stats.map { it.totalCents })
        assertEquals(listOf(1, 1, 2, 1), stats.map { it.count })
        assertFalse(stats[0].isUncategorized)
        assertTrue(stats.last().isUncategorized)
        assertEquals(1.0, stats.sumOf { it.percentage.toDouble() }, 0.0001)
    }

    @Test
    fun accountUncategorizedThresholdCollapsesOnlyAboveNinetyPercent() {
        val exactlyNinetyPercentBlank = listOf(tx(amountCents = 1_000L, account = "微信")) +
            List(9) { tx(amountCents = 500L, account = null) }
        assertEquals(0.1f, AccountAggregation.coverageRatio(exactlyNinetyPercentBlank, TransactionType.EXPENSE), 0.0001f)
        assertFalse(AccountAggregation.isMostlyUncategorized(exactlyNinetyPercentBlank, TransactionType.EXPENSE))

        val ninetyOnePercentBlank = listOf(tx(amountCents = 1_000L, account = "微信")) +
            List(10) { tx(amountCents = 500L, account = "") }
        assertTrue(AccountAggregation.coverageRatio(ninetyOnePercentBlank, TransactionType.EXPENSE) < 0.1f)
        assertTrue(AccountAggregation.isMostlyUncategorized(ninetyOnePercentBlank, TransactionType.EXPENSE))

        val allBlank = List(5) { tx(amountCents = 500L, account = null) }
        assertEquals(0f, AccountAggregation.coverageRatio(allBlank, TransactionType.EXPENSE), 0.0001f)
        assertTrue(AccountAggregation.isMostlyUncategorized(allBlank, TransactionType.EXPENSE))

        assertFalse(AccountAggregation.isMostlyUncategorized(emptyList(), TransactionType.EXPENSE))
    }

    @Test
    fun topFiveAccountsLimitLeavesRemainingCount() {
        val transactions = (1..7).map { idx ->
            tx(amountCents = idx * 1_000L, account = "账户$idx")
        }
        val stats = AccountAggregation.accountStats(transactions, TransactionType.EXPENSE)
        assertEquals(7, stats.size)
        val topFive = stats.take(AccountAggregation.DEFAULT_VISIBLE_LIMIT)
        assertEquals(listOf("账户7", "账户6", "账户5", "账户4", "账户3"), topFive.map { it.accountKey })
        assertEquals(2, stats.size - topFive.size)
    }

    @Test
    fun accountDrilldownFilterMatchesNormalizedSynonymsAndBlanks() {
        val wechatLegacy = tx(amountCents = 1_200L, account = "微信支付")
        val wechatWallet = tx(amountCents = 1_200L, account = "微信零钱")
        val alipay = tx(amountCents = 1_200L, account = "支付宝")
        val blank = tx(amountCents = 1_200L, account = null)
        val spaces = tx(amountCents = 1_200L, account = "   ")

        assertTrue(AccountNormalization.matchesFilter(wechatLegacy.account, "微信"))
        assertTrue(AccountNormalization.matchesFilter(wechatWallet.account, "微信"))
        assertFalse(AccountNormalization.matchesFilter(alipay.account, "微信"))
        assertFalse(AccountNormalization.matchesFilter(blank.account, "微信"))

        assertTrue(AccountNormalization.matchesFilter(blank.account, AccountNormalization.UNASSIGNED_ACCOUNT_KEY))
        assertTrue(AccountNormalization.matchesFilter(spaces.account, AccountNormalization.UNASSIGNED_ACCOUNT_KEY))
        assertFalse(AccountNormalization.matchesFilter(wechatLegacy.account, AccountNormalization.UNASSIGNED_ACCOUNT_KEY))

        val huabeiTx = tx(amountCents = 1_200L, account = "花呗")
        assertTrue(AccountNormalization.matchesFilter(huabeiTx.account, "支付宝"))
        assertEquals("支付宝", AccountNormalization.effectiveAccount(huabeiTx))

        val noteHuabei = tx(amountCents = 1_200L, account = null, source = "OTHER", note = "[自动支付] 花呗扣款")
        assertEquals("支付宝", AccountNormalization.effectiveAccount(noteHuabei))

        assertTrue(
            LedgerFilter.matchesDisplayed(
                transaction = wechatLegacy,
                query = "",
                type = TransactionType.EXPENSE,
                category = null,
                startMs = null,
                endMs = null,
                account = "微信",
            ),
        )
        assertFalse(
            LedgerFilter.matchesDisplayed(
                transaction = alipay,
                query = "",
                type = TransactionType.EXPENSE,
                category = null,
                startMs = null,
                endMs = null,
                account = "微信",
            ),
        )
    }

    @Test
    fun subscriptionStatsComputesMonthlyAveragePeakAndPendingSavings() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val subs = listOf(
            // Monthly: 25.00 CNY/mo (pending cancel, manual renew)
            sub(
                id = 1L,
                name = "腾讯视频",
                amountCents = 2_500L,
                cadence = SubscriptionCadence.MONTHLY,
                nextDueMs = dateMs(2026, 12, 10, tz),
                autoRenew = false,
                pendingCancel = true,
            ),
            // Yearly: 1200.00 CNY/yr = 100.00 CNY/mo (due in 2027-03 -> drives peak month!)
            sub(
                id = 2L,
                name = "家庭云盘年费",
                amountCents = 120_000L,
                cadence = SubscriptionCadence.YEARLY,
                nextDueMs = dateMs(2027, 3, 15, tz),
                autoRenew = true,
                pendingCancel = false,
            ),
            // Quarterly USD: $30 -> 3000 * 7.25 = 21750 cents/quarter -> 7250 cents/mo (pending cancel)
            sub(
                id = 3L,
                name = "Cloud IDE",
                amountCents = 3_000L,
                currency = "USD",
                cnyEstimateCents = 21_750L,
                cadence = SubscriptionCadence.QUARTERLY,
                nextDueMs = dateMs(2026, 12, 5, tz),
                autoRenew = false,
                pendingCancel = true,
            ),
            // Inactive subscription: must be ignored
            sub(
                id = 4L,
                name = "已停用服务",
                amountCents = 99_900L,
                cadence = SubscriptionCadence.MONTHLY,
                nextDueMs = dateMs(2026, 12, 1, tz),
                active = false,
            ),
            // Soft-deleted subscription: must be ignored
            sub(
                id = 5L,
                name = "已删除服务",
                amountCents = 99_900L,
                cadence = SubscriptionCadence.MONTHLY,
                nextDueMs = dateMs(2026, 12, 1, tz),
                deletedAtMs = 1_000L,
            ),
        )

        val state = SubscriptionStatsCalculator.compute(
            activeSubscriptions = subs,
            startYear = 2026,
            startMonth = 12,
            fxRates = SubscriptionFxRatesCalculator.DEFAULT_RATES,
            recurringCandidateCount = 2,
            timeZone = tz,
        )

        // Monthly equivalents: 2500 + 10000 + 7250 = 19750
        assertEquals(3, state.activeCount)
        assertEquals(19_750L, state.monthlyTotalCnyCents)
        assertEquals((19_750.0 / 3.0).roundToLong(), state.averageMonthlyCnyCents)
        assertEquals("家庭云盘年费", state.mostExpensiveName)
        assertEquals(10_000L, state.mostExpensiveMonthlyCnyCents)
        assertEquals(2, state.manualRenewalCount)
        assertEquals(2, state.pendingCancelCount)
        assertEquals(2_500L + 7_250L, state.pendingCancelMonthlySavingsCents)
        assertEquals(2, state.recurringCandidateCount)

        assertEquals(12, state.forecast12Months.size)
        // Peak month is 2027-03: 腾讯视频 (2500) + 家庭云盘年费 (120000) + Cloud IDE quarterly hit (21750) = 144250
        val peak = state.peakMonth
        assertEquals(2027, peak?.year)
        assertEquals(3, peak?.month)
        assertEquals(144_250L, peak?.totalCnyCents)
    }

    @Test
    fun subscriptionStatsEmptyStateReturnsZeroesAndTwelveZeroMonths() {
        val state = SubscriptionStatsCalculator.compute(
            activeSubscriptions = emptyList(),
            startYear = 2026,
            startMonth = 11,
            recurringCandidateCount = 1,
        )
        assertEquals(0, state.activeCount)
        assertEquals(0L, state.monthlyTotalCnyCents)
        assertEquals(0L, state.averageMonthlyCnyCents)
        assertNull(state.mostExpensiveName)
        assertEquals(0L, state.mostExpensiveMonthlyCnyCents)
        assertEquals(0, state.manualRenewalCount)
        assertEquals(0, state.pendingCancelCount)
        assertEquals(0L, state.pendingCancelMonthlySavingsCents)
        assertEquals(12, state.forecast12Months.size)
        assertNull(state.peakMonth)
        assertEquals(1, state.recurringCandidateCount)
        assertTrue(state.isLoaded)
    }

    @Test
    fun compactDateLabelOmitsCurrentYearAndFormatsOtherYears() {
        assertEquals(
            "11月24日",
            compactStatsDateLabel(StatsDate(2026, 11, 24), currentYear = 2026, english = false),
        )
        assertEquals(
            "Nov 24",
            compactStatsDateLabel(StatsDate(2026, 11, 24), currentYear = 2026, english = true),
        )
        assertEquals(
            "25/11/24",
            compactStatsDateLabel(StatsDate(2025, 11, 24), currentYear = 2026, english = false),
        )
        assertEquals(
            "27/01/05",
            compactStatsDateLabel(StatsDate(2027, 1, 5), currentYear = 2026, english = true),
        )
    }

    private fun tx(
        amountCents: Long,
        account: String?,
        type: TransactionType = TransactionType.EXPENSE,
        currency: String = "CNY",
        status: String = "confirmed",
        duplicateOfId: Long? = null,
    ): TransactionEntity = TransactionEntity(
        amountCents = amountCents,
        type = type.name,
        category = TransactionCategory.FOOD.name,
        source = TransactionSource.MANUAL.name,
        account = account,
        timestamp = 1_700_000_000_000L,
        currency = currency,
        remoteReviewStatus = status,
        duplicateOfId = duplicateOfId,
    )

    private fun sub(
        id: Long,
        name: String,
        amountCents: Long,
        cadence: String,
        nextDueMs: Long,
        currency: String = "CNY",
        cnyEstimateCents: Long? = null,
        autoRenew: Boolean = true,
        pendingCancel: Boolean = false,
        active: Boolean = true,
        deletedAtMs: Long? = null,
    ): SubscriptionEntity = SubscriptionEntity(
        id = id,
        syncUuid = "sub-$id",
        name = name,
        amountCents = amountCents,
        currency = currency,
        cnyEstimateCents = cnyEstimateCents,
        cadence = cadence,
        nextDueMs = nextDueMs,
        autoRenew = autoRenew,
        pendingCancel = pendingCancel,
        active = active,
        updatedAtMs = 1_700_000_000_000L,
        deletedAtMs = deletedAtMs,
    )

    private fun dateMs(year: Int, month: Int, day: Int, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
