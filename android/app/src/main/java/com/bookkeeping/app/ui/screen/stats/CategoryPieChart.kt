package com.bookkeeping.app.ui.screen.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** 扇区标签的最低占比：低于 6% 的分片太小，标签会挤成一团，只留图例。 */
internal const val PIE_LABEL_MIN_PERCENTAGE = 0.06f

/** 最多给几个分片画标签；再多即使错位也会互相压住。 */
internal const val PIE_LABEL_MAX_COUNT = 6

/**
 * 一个扇区的几何信息。角度沿用 Canvas 的约定：-90° 在 12 点方向，顺时针增加。
 */
internal data class PieSlice(
    val stat: CategoryStat,
    val startAngle: Float,
    val sweepAngle: Float,
) {
    /** 扇区中心角度，归一化到 [0,360)，用来排序标签、判断先画谁。 */
    val midAngle: Float
        get() = ((startAngle + sweepAngle / 2f) % 360f + 360f) % 360f
}

/** 按金额占比把分类统计换算成扇区；金额为 0 的分片直接丢掉。 */
internal fun pieSlices(stats: List<CategoryStat>, totalCents: Long): List<PieSlice> {
    if (stats.isEmpty() || totalCents <= 0L) return emptyList()
    val total = totalCents.toFloat()
    var start = -90f
    val slices = ArrayList<PieSlice>(stats.size)
    stats.forEach { stat ->
        val sweep = stat.totalCents.toFloat() / total * 360f
        if (sweep > 0f) slices += PieSlice(stat = stat, startAngle = start, sweepAngle = sweep)
        start += sweep
    }
    return slices
}

/**
 * 需要画标签的扇区：占比 ≥ [minPercentage]，最多 [maxCount] 个（取金额最大的几个），
 * 最后按中心角度排序，方便画的时候按相邻顺序做错位。
 */
internal fun labeledSlices(
    slices: List<PieSlice>,
    minPercentage: Float = PIE_LABEL_MIN_PERCENTAGE,
    maxCount: Int = PIE_LABEL_MAX_COUNT,
): List<PieSlice> = slices
    .asSequence()
    .filter { it.stat.percentage >= minPercentage }
    .sortedByDescending { it.stat.totalCents }
    .take(maxCount)
    .sortedBy { it.midAngle }
    .toList()

/** 百分比文案：饼图标签与图例共用，避免两处格式不一致。 */
internal fun categoryPercentText(percentage: Float): String =
    String.format(Locale.CHINA, "%.1f%%", percentage * 100f)

/**
 * Donut category chart. The legend lives in the parent so it can use the
 * shared category icons and align with the target screen.
 *
 * 分片颜色单独看没有意义，所以占比 ≥6% 的扇区在外侧直接写「分类名 + 百分比」，
 * 文案用 onSurface（不靠颜色传达信息）。标签之间做了矩形碰撞检测，
 * 撞上就把标签往外推一行，推两次还撞就跳过——宁可少一个标签，也不叠在一起。
 */
@Composable
fun CategoryPieChart(
    stats: List<CategoryStat>,
    totalCents: Long,
    centerLabel: String,
    modifier: Modifier = Modifier,
) {
    if (stats.isEmpty() || totalCents <= 0L) return
    val largest = stats.maxByOrNull { it.totalCents }
    val trackColor = QingSurfaceVariant
    val labelColor = QingText
    val description = "分类占比：${stats.size} 类，总计 ${formatCny(totalCents)}" +
        (largest?.let { "，最高 ${it.category.displayName} ${formatCny(it.totalCents)}" } ?: "")
    val slices = remember(stats, totalCents) { pieSlices(stats, totalCents) }
    val labeled = remember(slices) { labeledSlices(slices) }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
    )
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    // 标签文案在组合期算好，draw 里只做测量和排版。
    val labelTexts = labeled.map { slice ->
        slice to "${tr(slice.stat.category.displayName)} ${categoryPercentText(slice.stat.percentage)}"
    }
    // 用真实测量出的行高做外圈留白：只按字号估算会让 12 点方向的标签被卡片边缘压住。
    val labelProbe = labelTexts.firstOrNull()?.second ?: "100%"
    val labelLineHeight = remember(textMeasurer, labelStyle, labelProbe) {
        textMeasurer.measure(
            text = AnnotatedString(labelProbe),
            style = labelStyle,
            maxLines = 1,
            softWrap = false,
        ).size.height.toFloat()
    }

    Box(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 18.dp.toPx()
            val labelGap = 4.dp.toPx()
            // 外圈之外整行留给标签：一个标签高度 + 间隙 + 2dp 余量，
            // 这样 12 点 / 6 点方向的标签也完整落在画布内，不会被裁掉。
            val labelReserve = labelLineHeight + labelGap + 2.dp.toPx()
            val outerRadius = (size.minDimension / 2f - labelReserve)
                .coerceAtLeast(strokeWidth)
            val ringRadius = (outerRadius - strokeWidth / 2f).coerceAtLeast(1f)
            val diameter = ringRadius * 2f
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)
            val center = Offset(size.width / 2f, size.height / 2f)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            val gap = if (slices.size > 1) 2.5f else 0f
            slices.forEach { slice ->
                drawArc(
                    color = categoryColor(slice.stat.category),
                    startAngle = slice.startAngle + gap / 2f,
                    sweepAngle = (slice.sweepAngle - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
            }
            val placed = ArrayList<Rect>(labelTexts.size)
            labelTexts.forEach { (slice, text) ->
                val layout = textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = labelStyle,
                    maxLines = 1,
                    softWrap = false,
                )
                val width = layout.size.width.toFloat()
                val height = layout.size.height.toFloat()
                val radians = Math.toRadians(slice.midAngle.toDouble())
                val directionX = cos(radians).toFloat()
                val directionY = sin(radians).toFloat()
                var radius = outerRadius + labelGap + height / 2f
                // 相邻扇区的标签角度太近，就往外错开一行；最多三次，仍冲突则放弃这个标签。
                var bounds: Rect? = null
                for (attempt in 0 until 3) {
                    val left = (center.x + directionX * radius - width / 2f)
                        .coerceIn(0f, (size.width - width).coerceAtLeast(0f))
                    val top = (center.y + directionY * radius - height / 2f)
                        .coerceIn(0f, (size.height - height).coerceAtLeast(0f))
                    val candidate = Rect(left, top, left + width, top + height)
                    if (placed.none { it.inflate(1.dp.toPx()).overlaps(candidate) }) {
                        bounds = candidate
                        break
                    }
                    radius += height + labelGap
                }
                bounds?.let { rect ->
                    placed += rect
                    drawText(
                        textLayoutResult = layout,
                        color = labelColor,
                        topLeft = Offset(rect.left, rect.top),
                    )
                }
            }
        }
        Column(
            // 标签环占掉了画布的一部分，中心金额的可写宽度要尽量贴住内圈，
            // 否则「¥128,825.14」这档金额会比改动前更早开始缩字号。
            modifier = Modifier.padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AutoSizeAmountText(
                text = formatCny(totalCents),
                style = MaterialTheme.typography.titleLarge,
                color = QingPrimaryDeep,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
            )
        }
    }
}
