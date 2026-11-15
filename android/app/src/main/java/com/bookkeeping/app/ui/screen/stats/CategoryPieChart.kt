package com.bookkeeping.app.ui.screen.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.formatCny
import java.util.Locale

/**
 * Donut category chart. The legend lives in the parent so it can use the
 * shared category icons and align with the target screen.
 */
@Composable
fun CategoryPieChart(
    stats: List<CategoryStat>,
    totalCents: Long,
    centerLabel: String,
    modifier: Modifier = Modifier,
) {
    if (stats.isEmpty() || totalCents <= 0L) return
    val description = tr("分类占比") + "：" + stats.joinToString("，") { stat ->
        "${tr(stat.category.displayName)} ${
            "%.1f".format(Locale.CHINA, stat.percentage * 100)
        }% ${formatCny(stat.totalCents)}"
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 18.dp.toPx()
            val diameter = (size.minDimension - strokeWidth).coerceAtLeast(1f)
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            var startAngle = -90f
            val gap = if (stats.size > 1) 2.5f else 0f
            stats.forEach { stat ->
                val sweep = (stat.totalCents.toFloat() / totalCents.toFloat()) * 360f
                if (sweep > 0f) {
                    drawArc(
                        color = categoryColor(stat.category),
                        startAngle = startAngle + gap / 2f,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    )
                }
                startAngle += sweep
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatCny(totalCents),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
