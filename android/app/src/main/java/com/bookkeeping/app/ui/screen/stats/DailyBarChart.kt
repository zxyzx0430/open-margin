package com.bookkeeping.app.ui.screen.stats

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingMint
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingTextSecondary
import kotlin.math.min

/**
 * Rounded daily bar chart with a tappable day tooltip. The default selection
 * is the highest day so the card shows useful context without interaction.
 */
@Composable
fun DailyBarChart(
    dailyTotals: List<DailyTotal>,
    monthKey: String,
    dayText: (Int) -> String,
    modifier: Modifier = Modifier,
    barColor: Color = QingMint,
    selectedColor: Color = QingPrimary,
) {
    if (dailyTotals.isEmpty()) return

    val maxCents = dailyTotals.maxOfOrNull { it.totalCents }?.coerceAtLeast(1L) ?: 1L
    var selectedDay by remember(dailyTotals) {
        mutableIntStateOf(dailyTotals.maxByOrNull { it.totalCents }?.day ?: -1)
    }
    val outlineColor = QingOutline
    val labelColor = QingTextSecondary
    val tooltipColor = QingPrimaryDeep
    val tooltipTextColor = QingSurface
    val axisTextSize = 9.sp.value

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(dailyTotals) {
                detectTapGestures { position ->
                    val leftPad = 34.dp.toPx()
                    val rightPad = 4.dp.toPx()
                    val chartWidth = size.width.toFloat() - leftPad - rightPad
                    if (chartWidth <= 0f || dailyTotals.isEmpty()) return@detectTapGestures
                    val slot = chartWidth / dailyTotals.size
                    val index = ((position.x - leftPad) / slot).toInt()
                        .coerceIn(0, dailyTotals.lastIndex)
                    if (position.x >= leftPad && position.x <= leftPad + chartWidth) {
                        selectedDay = dailyTotals[index].day
                    }
                }
            },
    ) {
        val leftPad = 34.dp.toPx()
        val rightPad = 4.dp.toPx()
        val topPad = 22.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
        val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
        val slot = chartWidth / dailyTotals.size
        val barWidth = (slot * 0.62f).coerceAtLeast(2f)
        val gridPaint = Paint().apply {
            color = outlineColor.toArgb()
            strokeWidth = 1.dp.toPx()
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = labelColor.toArgb()
            textSize = axisTextSize * density
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        val dayPaint = Paint().apply {
            color = labelColor.toArgb()
            textSize = axisTextSize * density
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // Horizontal gridlines with amount labels.
        for (i in 0..4) {
            val y = topPad + chartHeight * (i / 4f)
            drawLine(
                color = outlineColor.copy(alpha = 0.7f),
                start = Offset(leftPad, y),
                end = Offset(leftPad + chartWidth, y),
                strokeWidth = gridPaint.strokeWidth,
            )
            val value = maxCents * (4 - i) / 4
            drawContext.canvas.nativeCanvas.drawText(
                "¥${value / 100}",
                leftPad - 5.dp.toPx(),
                y + 3.dp.toPx(),
                textPaint,
            )
        }

        dailyTotals.forEachIndexed { index, item ->
            val x = leftPad + index * slot + (slot - barWidth) / 2f
            val ratio = item.totalCents.toFloat() / maxCents.toFloat()
            val barHeight = (ratio * chartHeight).coerceAtLeast(
                if (item.totalCents > 0L) 2.dp.toPx() else 0f,
            )
            val top = topPad + chartHeight - barHeight
            if (barHeight > 0f) {
                val radius = min(barWidth / 2f, barHeight / 2f)
                drawRoundRect(
                    color = if (item.day == selectedDay) selectedColor else barColor,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            if (item.day == 1 || item.day % 5 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    item.day.toString(),
                    x + barWidth / 2f,
                    size.height - 2.dp.toPx(),
                    dayPaint,
                )
            }
        }

        val selected = dailyTotals.firstOrNull {
            it.day == selectedDay && it.totalCents > 0L
        }
        if (selected != null) {
            val index = dailyTotals.indexOf(selected)
            val x = leftPad + index * slot + (slot - barWidth) / 2f
            val ratio = selected.totalCents.toFloat() / maxCents.toFloat()
            val barHeight = (ratio * chartHeight).coerceAtLeast(2.dp.toPx())
            val top = topPad + chartHeight - barHeight
            val tooltipText = "${dayText(selected.day)} ${formatCny(selected.totalCents)}"
            val tooltipPaint = Paint().apply {
                color = tooltipTextColor.toArgb()
                textSize = 10.sp.value * density
                isAntiAlias = true
                textAlign = Paint.Align.LEFT
            }
            val textWidth = tooltipPaint.measureText(tooltipText)
            val horizontalPadding = 8.dp.toPx()
            val tooltipHeight = 24.dp.toPx()
            val tooltipWidth = textWidth + horizontalPadding * 2f
            val tooltipLeft = (x + barWidth / 2f - tooltipWidth / 2f)
                .coerceIn(leftPad, (size.width - rightPad - tooltipWidth).coerceAtLeast(leftPad))
            val tooltipTop = (top - tooltipHeight - 6.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(
                color = tooltipColor,
                topLeft = Offset(tooltipLeft, tooltipTop),
                size = Size(tooltipWidth, tooltipHeight),
                cornerRadius = CornerRadius(tooltipHeight / 2f, tooltipHeight / 2f),
            )
            drawContext.canvas.nativeCanvas.drawText(
                tooltipText,
                tooltipLeft + horizontalPadding,
                tooltipTop + tooltipHeight * 0.68f,
                tooltipPaint,
            )
        }
    }
}
