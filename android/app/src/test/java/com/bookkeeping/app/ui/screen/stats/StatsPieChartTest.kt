package com.bookkeeping.app.ui.screen.stats

import androidx.compose.ui.geometry.Offset
import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环图的纯计算部分：扇区角度、几何、按角度命中。
 * 环外不再有引线标签，所以这里不再有「标签排布」的用例；
 * 对应地多锁了一条「选中只加厚外半径、内半径不动」，防止又画出第二圈。
 */
class StatsPieChartTest {

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
    fun zeroAmountCategoriesAreDropped() {
        val stats = listOf(
            stat(TransactionCategory.FOOD, 10_000L, 10_000L),
            stat(TransactionCategory.OTHER, 0L, 10_000L),
        )

        val slices = pieSlices(stats, 10_000L)

        assertEquals(listOf(TransactionCategory.FOOD), slices.map { it.stat.category })
        assertEquals(360f, slices[0].sweepAngle, 0.001f)
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
    fun geometryUsesTheShortSideAndKeepsTheRingCentered() {
        // 800×600 的画布：半径由短边（600）决定，左右留白因此天然对称。
        val geometry = pieChartGeometry(
            width = 800f,
            height = 600f,
            strokeWidth = 40f,
            outerGrowth = 20f,
            edgePadding = 10f,
        )

        assertEquals(400f, geometry.center.x, 0.001f)
        assertEquals(300f, geometry.center.y, 0.001f)
        assertEquals(270f, geometry.radiusOuter, 0.001f)
        assertEquals(250f, geometry.ringRadius, 0.001f)
        assertEquals(230f, geometry.radiusInner, 0.001f)
        assertEquals(290f, geometry.selectedRadiusOuter, 0.001f)
    }

    @Test
    fun geometryReservesRoomForTheSelectedSliceToGrowOutward() {
        // 选中时外半径再长 outerGrowth：环 + 突出量 + 留白正好贴住短边的边界，不会被裁。
        val geometry = pieChartGeometry(800f, 600f, 40f, 20f, 10f)

        assertEquals(600f / 2f, geometry.selectedRadiusOuter + 10f, 0.001f)
    }

    @Test
    fun geometryNeverCollapsesBelowOneStrokeWidth() {
        // 画布小到放不下环时，至少保证还能画出一条弧带，不会算出负半径。
        val geometry = pieChartGeometry(10f, 10f, 40f, 20f, 10f)

        assertTrue(geometry.radiusOuter >= 40f)
        assertTrue(geometry.radiusInner >= 0f)
    }

    @Test
    fun selectionGrowsOnlyTheOuterRadius() {
        // 选中 = 弧段变厚并向外突出：内半径不动，所以不会在环里侧留出第二道环。
        val geometry = pieChartGeometry(800f, 600f, 40f, 20f, 10f)

        assertEquals(geometry.radiusOuter - geometry.strokeWidth, geometry.radiusInner, 0.001f)
        assertEquals(geometry.radiusOuter + 20f, geometry.selectedRadiusOuter, 0.001f)
        assertEquals(
            geometry.radiusOuter - geometry.strokeWidth / 2f,
            geometry.ringRadius,
            0.001f,
        )
        // 选中弧段只往外长：内边缘和未选中时重合。
        assertEquals(geometry.radiusInner, geometry.selectedRadiusOuter - 40f - 20f, 0.001f)
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
    fun tapOnTheHoleOrOutsideTheRingClearsTheSelection() {
        val stats = listOf(stat(TransactionCategory.FOOD, 10_000L, 10_000L))
        val slices = pieSlices(stats, 10_000L)
        val geometry = pieChartGeometry(800f, 600f, 40f, 20f, 10f)
        val center = geometry.center

        // 环心（洞）与环外都算空白，点它们要回到总计。
        assertNull(pieSliceHit(slices, geometry, center, 10f))
        assertNull(
            pieSliceHit(
                slices,
                geometry,
                center + Offset(0f, geometry.selectedRadiusOuter + 40f),
                10f,
            ),
        )
        // 环上（弧带中线）则命中所属扇区。
        assertEquals(0, pieSliceHit(slices, geometry, center + Offset(0f, -geometry.ringRadius), 10f))
        // 选中的弧段会向外长出去，点那一段的外缘仍然算命中。
        assertEquals(
            0,
            pieSliceHit(slices, geometry, center + Offset(0f, -geometry.radiusOuter), 10f),
        )
    }
}
