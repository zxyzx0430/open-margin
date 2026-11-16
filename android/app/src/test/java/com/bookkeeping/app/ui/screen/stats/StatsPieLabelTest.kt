package com.bookkeeping.app.ui.screen.stats

import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 饼图扇区与标签的纯计算部分：几何、6% 阈值、最多 6 个标签、标签按角度排序。
 * 这些规则决定了「哪个颜色是什么」能不能被读出来，所以单独锁住。
 */
class StatsPieLabelTest {

    private fun stat(category: TransactionCategory, cents: Long, total: Long) = CategoryStat(
        category = category,
        totalCents = cents,
        count = 1,
        percentage = cents.toFloat() / total.toFloat(),
    )

    @Test
    fun slicesStartAtTwelveOClockAndFollowAmountShare() {
        val stats = listOf(
            stat(TransactionCategory.FOOD, 6_000L, 10_000L),
            stat(TransactionCategory.HOUSING, 4_000L, 10_000L),
        )

        val slices = pieSlices(stats, 10_000L)

        assertEquals(2, slices.size)
        assertEquals(-90f, slices[0].startAngle, 0.001f)
        assertEquals(216f, slices[0].sweepAngle, 0.01f)
        assertEquals(126f, slices[1].startAngle, 0.01f)
        assertEquals(144f, slices[1].sweepAngle, 0.01f)
    }

    @Test
    fun slicesBelowSixPercentGetNoLabel() {
        val stats = listOf(
            stat(TransactionCategory.FOOD, 5_000L, 10_000L),
            stat(TransactionCategory.HOUSING, 4_410L, 10_000L),
            stat(TransactionCategory.OTHER, 590L, 10_000L),
        )

        val labeled = labeledSlices(pieSlices(stats, 10_000L))

        assertEquals(
            listOf(TransactionCategory.FOOD, TransactionCategory.HOUSING),
            labeled.map { it.stat.category },
        )
    }

    @Test
    fun labelsCapAtSixLargestSlices() {
        val total = 100_000L
        val stats = listOf(
            TransactionCategory.FOOD to 20_000L,
            TransactionCategory.TRANSPORT to 18_000L,
            TransactionCategory.SHOPPING to 16_000L,
            TransactionCategory.HOUSING to 14_000L,
            TransactionCategory.ENTERTAINMENT to 12_000L,
            TransactionCategory.HEALTH to 11_000L,
            TransactionCategory.OTHER to 9_000L,
        ).map { (category, cents) -> stat(category, cents, total) }

        val labeled = labeledSlices(pieSlices(stats, total))

        assertEquals(PIE_LABEL_MAX_COUNT, labeled.size)
        assertTrue(labeled.none { it.stat.category == TransactionCategory.OTHER })
        val angles = labeled.map { it.midAngle }
        assertEquals(angles.sorted(), angles)
    }

    @Test
    fun midAngleIsNormalizedForSlicesCrossingTwelveOClock() {
        val stats = listOf(
            stat(TransactionCategory.FOOD, 9_900L, 10_000L),
            stat(TransactionCategory.OTHER, 100L, 10_000L),
        )

        val slices = pieSlices(stats, 10_000L)

        assertTrue(slices.isNotEmpty())
        assertTrue(slices.all { it.midAngle >= 0f && it.midAngle < 360f })
    }

    @Test
    fun percentTextKeepsOneDecimalForChartAndLegend() {
        assertEquals("38.2%", categoryPercentText(0.382f))
        assertEquals("6.0%", categoryPercentText(0.0596f))
    }
}
