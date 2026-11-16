package com.bookkeeping.app.ui.screen.stats

import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.data.local.entity.TransactionCategory
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类配色是读懂饼图的前提：任意两类撞色，扇区和图例就分不出来了。
 *
 * 旧配色里 SHOPPING #9C7BD6 与 TRANSFER #8E7DD1 的 RGB 距离只有 15，几乎是同一块紫。
 * 这里锁住「两两最小色差」和「白底上不过亮」，以后再加分类或调色就不会又撞回去。
 */
class CategoryColorPaletteTest {

    /** 两两 RGB 欧氏距离的下限（0–441）。当前最接近的一对约 50，留出余量取 40。 */
    private val minPairDistance = 40f

    /** 相对亮度上限：再亮在白底上就糊了（相当于与纯白的对比度约 1.55:1）。 */
    private val maxLuminance = 0.62f

    private fun rgb(color: Color): FloatArray =
        floatArrayOf(color.red * 255f, color.green * 255f, color.blue * 255f)

    private fun distance(first: Color, second: Color): Float {
        val a = rgb(first)
        val b = rgb(second)
        return sqrt(
            (a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2),
        )
    }

    private fun relativeLuminance(color: Color): Float {
        fun channel(value: Float): Float =
            if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }

    @Test
    fun everyCategoryHasItsOwnPaletteColor() {
        // 表里缺一项时 categoryColor 会静默回落到兜底色，那时图例就会出现两个相同的灰。
        assertEquals(TransactionCategory.entries.toSet(), CATEGORY_PALETTE.keys)
    }

    @Test
    fun anyTwoCategoryColorsAreFarEnoughApart() {
        val colors = TransactionCategory.entries.associateWith { categoryColor(it) }
        var closest = Float.MAX_VALUE
        var closestPair = ""
        TransactionCategory.entries.forEachIndexed { index, first ->
            for (second in TransactionCategory.entries.drop(index + 1)) {
                val gap = distance(colors.getValue(first), colors.getValue(second))
                if (gap < closest) {
                    closest = gap
                    closestPair = "$first / $second"
                }
            }
        }

        assertTrue(
            "分类颜色太接近（$closestPair 距离 ${"%.1f".format(closest)} < $minPairDistance）",
            closest >= minPairDistance,
        )
    }

    @Test
    fun theFourEasilyConfusedCategoriesStayFarApart() {
        // 购物 / 住房 / 转账 / 其他：旧配色里购物与转账、住房与保险都撞在一起，单独再锁一道。
        val groups = listOf(
            TransactionCategory.SHOPPING,
            TransactionCategory.HOUSING,
            TransactionCategory.TRANSFER,
            TransactionCategory.OTHER,
        )
        groups.forEachIndexed { index, first ->
            for (second in groups.drop(index + 1)) {
                val gap = distance(categoryColor(first), categoryColor(second))
                assertTrue(
                    "$first 与 $second 的颜色距离只有 ${"%.1f".format(gap)}",
                    gap >= 60f,
                )
            }
        }
    }

    @Test
    fun categoryColorsStayReadableOnAWhiteBackground() {
        TransactionCategory.entries.forEach { category ->
            val luminance = relativeLuminance(categoryColor(category))
            assertTrue(
                "$category 在白底上太浅（相对亮度 ${"%.2f".format(luminance)}）",
                luminance <= maxLuminance,
            )
        }
    }
}
