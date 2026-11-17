package com.bookkeeping.app.ui.screen.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存前疑似重复规则：纯 JVM，不碰 Room / Compose。
 *
 * 时间基准固定成 2026-11-17 12:00，所有断言用相对毫秒，不依赖机器时区。
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
    ) = DuplicateCandidate(
        id = id,
        amountCents = amountCents,
        merchant = merchant,
        timestampMs = timestampMs,
        source = source,
    )

    // ================================================================ 同商户 ±1 天

    @Test
    fun sameAmountSameMerchantWithinOneDayHits() {
        val hit = DuplicateRule.find(listOf(candidate()), 3650L, "星巴克", now + 20 * hour)
        assertEquals(1L, hit?.id)
    }

    @Test
    fun sameAmountSameMerchantExactlyOneDayStillHits() {
        // 边界：正好 24 小时算「前后 1 天内」，不放过跨天的同一笔。
        val hit = DuplicateRule.find(listOf(candidate()), 3650L, "星巴克", now + day)
        assertEquals(1L, hit?.id)
    }

    @Test
    fun sameAmountSameMerchantJustOverOneDayMisses() {
        assertNull(DuplicateRule.find(listOf(candidate()), 3650L, "星巴克", now + day + 1))
    }

    @Test
    fun sameMerchantEarlierThanOneDayAlsoHits() {
        // 补录昨天的账：候选在未来方向也算，看的是绝对时间差。
        val hit = DuplicateRule.find(listOf(candidate()), 3650L, "星巴克", now - 20 * hour)
        assertEquals(1L, hit?.id)
    }

    // ================================================================ 不同商户

    @Test
    fun sameAmountDifferentMerchantTwoHoursApartMisses() {
        assertNull(DuplicateRule.find(listOf(candidate()), 3650L, "麦当劳", now + 2 * hour))
    }

    @Test
    fun sameAmountDifferentMerchantTenMinutesApartStillHits() {
        // 10 分钟内不看商户：商户可能被自动采集写成了空或者别的写法。
        val hit = DuplicateRule.find(listOf(candidate()), 3650L, "麦当劳", now + 8 * minute)
        assertEquals(1L, hit?.id)
    }

    @Test
    fun tenMinuteWindowIsInclusiveAndEndsRightAfter() {
        assertEquals(1L, DuplicateRule.find(listOf(candidate()), 3650L, "麦当劳", now + 10 * minute)?.id)
        assertNull(DuplicateRule.find(listOf(candidate()), 3650L, "麦当劳", now + 10 * minute + 1))
    }

    // ================================================================ 金额

    @Test
    fun differentAmountNeverHits() {
        assertNull(DuplicateRule.find(listOf(candidate(amountCents = 3650L)), 3651L, "星巴克", now))
        assertNull(DuplicateRule.find(listOf(candidate(amountCents = 3650L)), 3600L, "星巴克", now))
    }

    @Test
    fun nonPositiveAmountNeverHits() {
        // 0 元、负数金额本身就该被表单拦住，不参与重复判定。
        assertNull(DuplicateRule.find(listOf(candidate(amountCents = 0L)), 0L, "星巴克", now))
        assertNull(DuplicateRule.find(listOf(candidate(amountCents = -100L)), -100L, "星巴克", now))
    }

    @Test
    fun emptyCandidateListReturnsNull() {
        assertNull(DuplicateRule.find(emptyList(), 3650L, "星巴克", now))
    }

    // ================================================================ 商户写法差异

    @Test
    fun bracketsFullWidthAndCaseDifferencesStillHit() {
        val stored = candidate(merchant = " STARBUCKS（国贸店） ")
        val hit = DuplicateRule.find(listOf(stored), 3650L, "starbucks(国贸店)", now + 3 * hour)
        assertEquals(1L, hit?.id)
    }

    @Test
    fun shortMerchantIsContainedInLongerCapturedName() {
        // 自动采集常写成「财付通-星巴克」，用户手打「星巴克」：一方包含另一方即命中。
        val stored = candidate(merchant = "财付通-星巴克")
        val hit = DuplicateRule.find(listOf(stored), 3650L, "星巴克", now + 2 * hour)
        assertEquals(1L, hit?.id)
    }

    @Test
    fun differentBranchesOfSameBrandDoNotMatchByMerchant() {
        // 括号里的门店名要保留：同品牌不同门店不算同商户，只剩 10 分钟窗口。
        val stored = candidate(merchant = "星巴克(望京店)")
        assertNull(DuplicateRule.find(listOf(stored), 3650L, "星巴克(国贸店)", now + 2 * hour))
    }

    @Test
    fun singleCharacterMerchantOnlyMatchesExactly() {
        // 1 个字的商户跟谁都像，只允许完全相等，避免把「1号店」和「11号店」判成同一家。
        val stored = candidate(merchant = "A")
        assertNull(DuplicateRule.find(listOf(stored), 3650L, "AB", now + 2 * hour))
        assertEquals(1L, DuplicateRule.find(listOf(stored), 3650L, "ａ", now + 2 * hour)?.id)
    }

    // ================================================================ 空商户

    @Test
    fun blankMerchantOnlyUsesTheTenMinuteRule() {
        assertNull(DuplicateRule.find(listOf(candidate(merchant = "")), 3650L, "", now + 2 * hour))
        assertNull(DuplicateRule.find(listOf(candidate(merchant = "星巴克")), 3650L, "  ", now + 2 * hour))
        assertEquals(1L, DuplicateRule.find(listOf(candidate(merchant = "")), 3650L, "", now + 8 * minute)?.id)
    }

    // ================================================================ 多条命中

    @Test
    fun nearestCandidateWins() {
        val list = listOf(
            candidate(id = 1L, timestampMs = now - 20 * hour),
            candidate(id = 2L, timestampMs = now - 30 * minute),
            candidate(id = 3L, timestampMs = now + 6 * hour),
        )
        assertEquals(2L, DuplicateRule.find(list, 3650L, "星巴克", now)?.id)
    }

    @Test
    fun tieOnDistancePicksTheEarlierRecordThenTheSmallerId() {
        val tie = listOf(
            candidate(id = 9L, timestampMs = now + hour),
            candidate(id = 4L, timestampMs = now - hour),
        )
        assertEquals(4L, DuplicateRule.find(tie, 3650L, "星巴克", now)?.id)

        val sameTime = listOf(
            candidate(id = 7L, timestampMs = now - hour),
            candidate(id = 3L, timestampMs = now - hour),
        )
        assertEquals(3L, DuplicateRule.find(sameTime, 3650L, "星巴克", now)?.id)
    }

    @Test
    fun farCandidateIsIgnoredEvenWhenItIsTheOnlyOne() {
        val list = listOf(candidate(id = 5L, timestampMs = now - 3 * day))
        assertNull(DuplicateRule.find(list, 3650L, "星巴克", now))
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
}
