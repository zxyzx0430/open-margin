package com.bookkeeping.app.ui.screen.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存前疑似重复规则：纯 JVM，不碰 Room / Compose。
 *
 * 时间基准固定成 2026-11-17 12:00（相对毫秒），所有断言不依赖机器时区。
 *
 * 规则要点：
 * - 金额、方向、币种三者必须完全一致；
 * - 10 分钟内不看商户；
 * - 同商户按「这句话说了多准」分档：说了钟点 ±1 小时、只说了哪天 ±12 小时。
 */
class DuplicateRuleTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun candidate(
        id: Long = 1L,
        amountCents: Long = 3650L,
        merchant: String = "星巴克",
        timestampMs: Long = now,
        source: String = "WECHAT",
        type: String = "EXPENSE",
        currency: String = "CNY",
    ) = DuplicateCandidate(
        id = id,
        amountCents = amountCents,
        merchant = merchant,
        timestampMs = timestampMs,
        source = source,
        type = type,
        currency = currency,
    )

    private fun find(
        list: List<DuplicateCandidate>,
        amountCents: Long = 3650L,
        merchant: String = "星巴克",
        timestampMs: Long = now,
        type: String = "EXPENSE",
        currency: String = "CNY",
        precision: TimePrecision = TimePrecision.PRECISE,
    ): DuplicateCandidate? = DuplicateRule.find(
        list = list,
        amountCents = amountCents,
        type = type,
        currency = currency,
        merchant = merchant,
        timestampMs = timestampMs,
        precision = precision,
    )

    // ================================================================ 同商户：按时间精度分档

    @Test
    fun sameMerchantThirtyMinutesAgoHits() {
        assertEquals(1L, find(listOf(candidate(timestampMs = now - 30 * minute)))?.id)
    }

    @Test
    fun sameMerchantThreeHoursAgoMissesWhenClockWasSpoken() {
        // 说了具体钟点 → 只认 ±1 小时；3 小时前的同价咖啡不是同一笔。
        assertNull(
            find(listOf(candidate(timestampMs = now - 3 * hour)), precision = TimePrecision.PRECISE),
        )
    }

    @Test
    fun sameMerchantSixHoursApartHitsWhenOnlyTheDayWasSpoken() {
        // 「昨天中午」没钟点：±12 小时内都算同一天，不能漏。
        val hit = find(
            listOf(candidate(timestampMs = now - 6 * hour)),
            precision = TimePrecision.DATE_ONLY,
        )
        assertEquals(1L, hit?.id)
    }

    @Test
    fun preciseWindowIsOneHourInclusive() {
        assertEquals(1L, find(listOf(candidate(timestampMs = now - hour)))?.id)
        assertNull(find(listOf(candidate(timestampMs = now - hour - 1))))
        assertEquals(1L, find(listOf(candidate(timestampMs = now + hour)))?.id)
    }

    @Test
    fun dateOnlyWindowIsTwelveHoursInclusive() {
        val twelve = 12 * hour
        assertEquals(
            1L,
            find(listOf(candidate(timestampMs = now - twelve)), precision = TimePrecision.DATE_ONLY)?.id,
        )
        assertNull(
            find(listOf(candidate(timestampMs = now - twelve - 1)), precision = TimePrecision.DATE_ONLY),
        )
    }

    @Test
    fun noDateClaimBehavesLikePrecise() {
        assertEquals(
            1L,
            find(listOf(candidate(timestampMs = now - 50 * minute)), precision = TimePrecision.NONE)?.id,
        )
        assertNull(
            find(listOf(candidate(timestampMs = now - 2 * hour)), precision = TimePrecision.NONE),
        )
    }

    @Test
    fun dateOnlyStillMissesYesterdayAtTheSameClock() {
        // 「只说了哪天」也不能把 24 小时前的同价当重复：窗口上限是 12 小时。
        assertNull(
            find(listOf(candidate(timestampMs = now - day)), precision = TimePrecision.DATE_ONLY),
        )
    }

    // ================================================================ 不同商户

    @Test
    fun sameAmountDifferentMerchantTwoHoursApartMisses() {
        assertNull(find(listOf(candidate(timestampMs = now + 2 * hour)), merchant = "麦当劳"))
    }

    @Test
    fun sameAmountDifferentMerchantEightMinutesApartStillHits() {
        // 10 分钟内不看商户：商户可能被自动采集写成了空或者别的写法。
        assertEquals(
            1L,
            find(listOf(candidate(timestampMs = now + 8 * minute)), merchant = "麦当劳")?.id,
        )
    }

    @Test
    fun differentMerchantNeverUsesTheWideSameMerchantWindow() {
        // 就算只说了日期（±12 小时），不同商户也只看 10 分钟。
        assertNull(
            find(
                listOf(candidate(timestampMs = now - 6 * hour, merchant = "麦当劳")),
                merchant = "星巴克",
                precision = TimePrecision.DATE_ONLY,
            ),
        )
    }

    @Test
    fun tenMinuteWindowIsInclusiveAndEndsRightAfter() {
        assertEquals(
            1L,
            find(listOf(candidate(timestampMs = now + 10 * minute)), merchant = "麦当劳")?.id,
        )
        assertNull(find(listOf(candidate(timestampMs = now + 10 * minute + 1)), merchant = "麦当劳"))
    }

    // ================================================================ 金额 / 方向 / 币种

    @Test
    fun differentAmountNeverHits() {
        assertNull(find(listOf(candidate(amountCents = 3650L)), amountCents = 3651L))
        assertNull(find(listOf(candidate(amountCents = 3650L)), amountCents = 3600L))
    }

    @Test
    fun nonPositiveAmountNeverHits() {
        // 0 元、负数金额本身就该被表单拦住，不参与重复判定。
        assertNull(find(listOf(candidate(amountCents = 0L)), amountCents = 0L))
        assertNull(find(listOf(candidate(amountCents = -100L)), amountCents = -100L))
    }

    @Test
    fun sameAmountDifferentDirectionMisses() {
        // 36.5 的退款（收入）和 36.5 的支出不是同一笔。
        assertNull(find(listOf(candidate(type = "INCOME")), type = "EXPENSE"))
        assertNull(find(listOf(candidate(type = "EXPENSE")), type = "INCOME"))
    }

    @Test
    fun sameAmountDifferentCurrencyMisses() {
        // USD 36.5 和 CNY 36.5 只是数字碰巧一样。
        assertNull(find(listOf(candidate(currency = "USD")), currency = "CNY"))
        assertNull(find(listOf(candidate(currency = "CNY")), currency = "USD"))
    }

    @Test
    fun currencyComparisonIsCaseInsensitiveAndBlankMeansCny() {
        assertEquals(1L, find(listOf(candidate(currency = "cny")), currency = "CNY")?.id)
        assertEquals(1L, find(listOf(candidate(currency = "")), currency = "CNY")?.id)
    }

    @Test
    fun sameDirectionAndCurrencyStillHitsByWindow() {
        val hit = find(
            listOf(candidate(timestampMs = now - 40 * minute, type = "INCOME", currency = "USD")),
            type = "INCOME",
            currency = "USD",
        )
        assertEquals(1L, hit?.id)
    }

    @Test
    fun emptyCandidateListReturnsNull() {
        assertNull(find(emptyList()))
    }

    // ================================================================ 商户写法差异

    @Test
    fun bracketsFullWidthAndCaseDifferencesStillHit() {
        val stored = candidate(merchant = " STARBUCKS（国贸店） ", timestampMs = now - 30 * minute)
        assertEquals(1L, find(listOf(stored), merchant = "starbucks(国贸店)")?.id)
    }

    @Test
    fun shortMerchantIsContainedInLongerCapturedName() {
        // 自动采集常写成「财付通-星巴克」，用户手打「星巴克」：一方包含另一方即命中。
        val stored = candidate(merchant = "财付通-星巴克", timestampMs = now - 30 * minute)
        assertEquals(1L, find(listOf(stored), merchant = "星巴克")?.id)
    }

    @Test
    fun differentBranchesOfSameBrandDoNotMatchByMerchant() {
        // 括号里的门店名要保留：同品牌不同门店不算同商户，只剩 10 分钟窗口。
        val stored = candidate(merchant = "星巴克(望京店)", timestampMs = now - 2 * hour)
        assertNull(find(listOf(stored), merchant = "星巴克(国贸店)"))
    }

    @Test
    fun singleCharacterMerchantOnlyMatchesExactly() {
        // 1 个字的商户跟谁都像，只允许完全相等，避免把「1号店」和「11号店」判成同一家。
        assertNull(find(listOf(candidate(merchant = "A", timestampMs = now - 2 * hour)), merchant = "AB"))
        assertEquals(
            1L,
            find(listOf(candidate(merchant = "A", timestampMs = now - 30 * minute)), merchant = "ａ")?.id,
        )
    }

    // ================================================================ 空商户

    @Test
    fun blankMerchantOnlyUsesTheTenMinuteRule() {
        val twoHoursAgo = candidate(merchant = "", timestampMs = now - 2 * hour)
        val namedTwoHoursAgo = candidate(merchant = "星巴克", timestampMs = now - 2 * hour)
        assertNull(find(listOf(twoHoursAgo), merchant = ""))
        assertNull(find(listOf(namedTwoHoursAgo), merchant = "  "))
        assertEquals(
            1L,
            find(
                listOf(candidate(merchant = "", timestampMs = now + 8 * minute)),
                merchant = "",
                timestampMs = now,
            )?.id,
        )
    }

    // ================================================================ 多条命中

    @Test
    fun nearestCandidateWins() {
        val list = listOf(
            candidate(id = 1L, timestampMs = now - 50 * minute),
            candidate(id = 2L, timestampMs = now - 30 * minute),
            candidate(id = 3L, timestampMs = now + 6 * hour),
        )
        assertEquals(2L, find(list, precision = TimePrecision.DATE_ONLY)?.id)
    }

    @Test
    fun tieOnDistancePicksTheEarlierRecordThenTheSmallerId() {
        val tie = listOf(
            candidate(id = 9L, timestampMs = now + 20 * minute),
            candidate(id = 4L, timestampMs = now - 20 * minute),
        )
        assertEquals(4L, find(tie)?.id)

        val sameTime = listOf(
            candidate(id = 7L, timestampMs = now - 20 * minute),
            candidate(id = 3L, timestampMs = now - 20 * minute),
        )
        assertEquals(3L, find(sameTime)?.id)
    }

    @Test
    fun farCandidateIsIgnoredEvenWhenItIsTheOnlyOne() {
        val list = listOf(candidate(id = 5L, timestampMs = now - 3 * day))
        assertNull(find(list, precision = TimePrecision.DATE_ONLY))
    }

    // ================================================================ 粗筛窗口

    @Test
    fun candidateWindowCoversTheWidestPrecisionAndAtLeastTenMinutes() {
        // DAO 粗筛要按最宽的档取，否则「只说了哪天」的候选根本读不出来。
        assertEquals(12 * hour, DuplicateRule.candidateWindowMs(TimePrecision.DATE_ONLY))
        assertEquals(hour, DuplicateRule.candidateWindowMs(TimePrecision.PRECISE))
        assertEquals(hour, DuplicateRule.candidateWindowMs(TimePrecision.NONE))
        assertTrue(DuplicateRule.CANDIDATE_WINDOW_MS >= DuplicateRule.SAME_AMOUNT_WINDOW_MS)
    }

    // ================================================================ normalize

    @Test
    fun normalizeDropsSpacesBracketsAndPunctuation() {
        assertEquals("星巴克国贸店", DuplicateRule.normalizeMerchant(" 星巴克（国贸店） "))
        assertEquals("starbucks", DuplicateRule.normalizeMerchant(" S.T.A.R.B.U.C.K.S "))
        // 全角字母/数字按 NFKC 折成半角，跟手打的对得上。
        assertEquals("starbucks711", DuplicateRule.normalizeMerchant("ＳＴＡＲＢＵＣＫＳ ７１１"))
    }

    @Test
    fun normalizeOfPunctuationOnlyMerchantIsEmpty() {
        assertTrue(DuplicateRule.normalizeMerchant("（）· ").isEmpty())
    }

    @Test
    fun blankCurrencyNormalizesToCny() {
        assertEquals("CNY", DuplicateRule.normalizeCurrency("  "))
        assertEquals("USD", DuplicateRule.normalizeCurrency(" usd "))
    }
}
