package com.bookkeeping.app.ui.screen.stats

import androidx.compose.ui.geometry.Offset
import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 饼图的纯计算部分：几何、前 6 名标签、引线左右分侧、同侧避让、按角度命中。
 * 这些规则决定了「哪个颜色是什么、点了会选中谁」能不能被读出来，所以单独锁住。
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
    fun smallSlicesKeepTheirLabelsViaLeaderLines() {
        // 引线在环外并且做了避让，所以小分片不再被 6% 门槛挡掉，只要在前 6 名就有标签。
        val stats = listOf(
            stat(TransactionCategory.FOOD, 5_000L, 10_000L),
            stat(TransactionCategory.HOUSING, 4_410L, 10_000L),
            stat(TransactionCategory.OTHER, 590L, 10_000L),
        )

        val labeled = labeledSlices(pieSlices(stats, 10_000L))

        assertEquals(
            listOf(TransactionCategory.FOOD, TransactionCategory.HOUSING, TransactionCategory.OTHER),
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

    @Test
    fun labelsSplitLeftAndRightByMidAngle() {
        // 0° 在 3 点方向（右），180° 在 9 点方向（左），90° 在 6 点（正下方）归右侧。
        assertEquals(1, pieLabelSide(0f))
        assertEquals(1, pieLabelSide(45f))
        assertEquals(1, pieLabelSide(90f))
        assertEquals(-1, pieLabelSide(180f))
        assertEquals(-1, pieLabelSide(225f))
        assertEquals(-1, pieLabelSide(270f))
    }

    @Test
    fun labelYsKeepOrderAndMinimumSpacing() {
        // 三个几乎重叠的 y 值：输出必须单调、间距不小于 18、且顺序不变。
        val adjusted = distributeLabelYs(
            ys = listOf(100f, 102f, 104f),
            minGap = 18f,
            minY = 0f,
            maxY = 400f,
        )

        assertEquals(listOf(100f, 118f, 136f), adjusted)
    }

    @Test
    fun labelYsPushBackUpWhenTheyRunPastTheBottom() {
        // 底部放不下时从下往上回推，最后一个贴住 maxY，前面的仍然按顺序散开。
        val adjusted = distributeLabelYs(
            ys = listOf(90f, 95f, 100f, 105f),
            minGap = 20f,
            minY = 0f,
            maxY = 130f,
        )

        assertEquals(130f, adjusted.last(), 0.001f)
        adjusted.zipWithNext().forEach { (a, b) -> assertEquals(20f, b - a, 0.001f) }
        assertTrue(adjusted.first() >= 0f)
    }

    @Test
    fun geometryKeepsTheRingInTheMiddleAndLeavesRoomForLabels() {
        val geometry = pieChartGeometry(
            width = 800f,
            height = 600f,
            maxLabelWidth = 160f,
            strokeWidth = 40f,
            elbowRun = 30f,
            textGap = 10f,
            minRadius = 140f,
            edgePadding = 10f,
        )

        assertEquals(400f, geometry.center.x, 0.001f)
        assertEquals(300f, geometry.center.y, 0.001f)
        // 环 + 引线 + 空隙 + 标签列正好占满半宽。
        val used = geometry.radiusOuter + geometry.elbowRun + geometry.textGap + geometry.labelColumnWidth
        assertEquals(400f, used, 0.5f)
        // 标签列吃掉半宽的 34%，剩下的才是环，环不会被长标签挤成一条线。
        assertEquals(136f, geometry.labelColumnWidth, 0.5f)
        assertTrue(geometry.radiusOuter >= 140f)
    }

    @Test
    fun geometryNeverShrinksTheRingBelowTheMinimum() {
        // 标签特别长时先保证环的可读尺寸，多出来的文字交给名字自己省略。
        val geometry = pieChartGeometry(
            width = 800f,
            height = 600f,
            maxLabelWidth = 900f,
            strokeWidth = 40f,
            elbowRun = 30f,
            textGap = 10f,
            minRadius = 140f,
            edgePadding = 10f,
        )

        assertTrue(geometry.radiusOuter >= 140f)
        assertTrue(geometry.labelColumnWidth >= 1f)
    }

    @Test
    fun geometryIsIndependentOfSelectionState() {
        // 同一个画布 + 同一批标签 → 同一份几何，环直径不会因为选中而变。
        val first = pieChartGeometry(700f, 600f, 200f, 40f, 30f, 10f, 140f, 10f)
        val second = pieChartGeometry(700f, 600f, 200f, 40f, 30f, 10f, 140f, 10f)
        assertEquals(first, second)
    }

    @Test
    fun tapAngleHitsTheSliceUnderTheFinger() {
        val stats = listOf(
            stat(TransactionCategory.FOOD, 6_000L, 10_000L),
            stat(TransactionCategory.HOUSING, 4_000L, 10_000L),
        )
        val slices = pieSlices(stats, 10_000L)

        // 第一片：-90° 到 126°（即 270° → 126° 顺时针）。
        assertEquals(0, sliceIndexAtAngle(slices, -90f))
        assertEquals(0, sliceIndexAtAngle(slices, 0f))
        assertEquals(0, sliceIndexAtAngle(slices, 126f - 0.5f))
        assertEquals(1, sliceIndexAtAngle(slices, 180f))
        assertEquals(1, sliceIndexAtAngle(slices, 270f - 0.5f))
    }

    @Test
    fun tapOutsideTheRingRingClearsTheSelection() {
        val stats = listOf(stat(TransactionCategory.FOOD, 10_000L, 10_000L))
        val slices = pieSlices(stats, 10_000L)
        val geometry = pieChartGeometry(800f, 600f, 160f, 40f, 30f, 10f, 140f, 10f)
        val center = geometry.center

        // 环心（洞）与环外都算空白，点它们要回到总计。
        assertNull(pieSliceHit(slices, geometry, center, 10f))
        assertNull(pieSliceHit(slices, geometry, center + Offset(0f, geometry.radiusOuter + 60f), 10f))
        // 环上则命中所属扇区。
        assertEquals(0, pieSliceHit(slices, geometry, center + Offset(0f, -geometry.ringRadius), 10f))
    }
}
