package com.bookkeeping.app.ui.screen.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 环的线宽，也就是未选中时弧带的厚度。 */
private val PIE_STROKE = 16.dp

/** 选中扇区：外半径再向外长这么多，内半径不动——弧段变厚并向外突出。 */
internal val PIE_SELECT_GROWTH = 7.dp

/** 环与画布边缘之间固定留 4dp，避免弧线贴边被裁。 */
internal val PIE_CHART_EDGE_PADDING = 4.dp

/** 手指按偏一点也算命中：环两侧各放宽这么多。 */
private val PIE_TOUCH_TOLERANCE = 10.dp

/** 相邻扇区之间的角度缝隙，只留一条细缝，不再画任何外圈。 */
internal const val PIE_SLICE_GAP_DEGREES = 2f

/** 扇区再小也留一点可见宽度，别因为缝隙把它抹掉。 */
private const val PIE_MIN_SWEEP_DEGREES = 0.6f

/** 选中动画时长。 */
private const val PIE_SELECT_ANIMATION_MS = 200

/** 环心文字最多占内圈直径的多少，留一圈呼吸空间。 */
private const val PIE_CENTER_WIDTH_RATIO = 0.84f

/**
 * 一个扇区的几何信息。角度沿用 Canvas 的约定：-90° 在 12 点方向，顺时针增加。
 */
internal data class PieSlice(
    val stat: CategoryStat,
    val startAngle: Float,
    val sweepAngle: Float,
) {
    /** 扇区中心角度，归一化到 [0,360)，用于测试与调试定位。 */
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

/** 百分比文案：环心与图例共用，避免两处格式不一致。 */
internal fun categoryPercentText(percentage: Float): String =
    String.format(Locale.CHINA, "%.1f%%", percentage * 100f)

/**
 * 环的几何。整个图只由一块圆环构成：
 * [radiusOuter] 是未选中时的外半径，[strokeWidth] 是弧带厚度，
 * [outerGrowth] 是选中时外半径向外增加的最大值（内半径不变）。
 *
 * 半径取宽高里较小的一半，所以左右留白天然对称，环永远居中。
 */
internal data class PieChartGeometry(
    val center: Offset,
    val radiusOuter: Float,
    val strokeWidth: Float,
    val outerGrowth: Float,
) {
    /** 弧带中线半径，drawArc 用它定位。 */
    val ringRadius: Float get() = radiusOuter - strokeWidth / 2f

    /** 弧带内半径：选中时保持不变，扇区之间不会露出缝里的底色。 */
    val radiusInner: Float get() = radiusOuter - strokeWidth

    /** 选中扇区的外半径。 */
    val selectedRadiusOuter: Float get() = radiusOuter + outerGrowth
}

/**
 * 计算几何：半径 = 短边的一半 - 边缘留白 - 选中时的向外突出量，
 * 这样选中扇区长出去也不会被画布裁掉；宽高取 min，左右上下留白对称。
 */
internal fun pieChartGeometry(
    width: Float,
    height: Float,
    strokeWidth: Float,
    outerGrowth: Float,
    edgePadding: Float,
): PieChartGeometry {
    val halfShortSide = min(width, height) / 2f
    val radius = max(halfShortSide - edgePadding - outerGrowth, strokeWidth)
    return PieChartGeometry(
        center = Offset(width / 2f, height / 2f),
        radiusOuter = radius,
        strokeWidth = strokeWidth,
        outerGrowth = outerGrowth,
    )
}

/** 命中判定的角度部分：返回落在 [angleDegrees] 下的扇区下标。 */
internal fun sliceIndexAtAngle(slices: List<PieSlice>, angleDegrees: Float): Int? {
    if (slices.isEmpty()) return null
    val angle = ((angleDegrees % 360f) + 360f) % 360f
    slices.forEachIndexed { index, slice ->
        val start = ((slice.startAngle % 360f) + 360f) % 360f
        val delta = ((angle - start) % 360f + 360f) % 360f
        if (delta < slice.sweepAngle) return index
    }
    return null
}

/**
 * 触摸命中：先按半径判断是不是落在环上（环内外各放宽 [tolerance]，选中扇区会长出去，也算环上），
 * 再按角度取扇区。落在环心或环外都返回 null，也就是「点空白处」。
 */
internal fun pieSliceHit(
    slices: List<PieSlice>,
    geometry: PieChartGeometry,
    touch: Offset,
    tolerance: Float,
): Int? {
    val dx = touch.x - geometry.center.x
    val dy = touch.y - geometry.center.y
    val distance = hypot(dx, dy)
    val inner = geometry.radiusInner - tolerance
    val outer = geometry.selectedRadiusOuter + tolerance
    if (distance < inner || distance > outer) return null
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    return sliceIndexAtAngle(slices, angle)
}

/**
 * 分类占比环图：只画一圈圆环，环外不画引线、不写文字。
 *
 * 所有文字只在两处：环心（总计 = 总金额 + 「总支出」；选中 = 该分类金额 + 「分类名 · 百分比」）
 * 和下方图例。点扇区时该段弧的外半径 +7dp 向外突出、弧带变厚，颜色保持不变，
 * 其余扇区原样不动；200ms 过渡。再点同一段或点空白回到总计。
 * 图例的选中高亮由外层（StatsScreen）负责。
 */
@Composable
fun CategoryPieChart(
    stats: List<CategoryStat>,
    totalCents: Long,
    centerLabel: String,
    modifier: Modifier = Modifier,
    selectedCategory: TransactionCategory? = null,
    onSliceClick: (TransactionCategory?) -> Unit = {},
) {
    if (stats.isEmpty() || totalCents <= 0L) return
    val slices = remember(stats, totalCents) { pieSlices(stats, totalCents) }
    if (slices.isEmpty()) return
    val selectedSlice = slices.firstOrNull { it.stat.category == selectedCategory }?.stat

    val density = LocalDensity.current
    val strokePx = with(density) { PIE_STROKE.toPx() }
    val growthPx = with(density) { PIE_SELECT_GROWTH.toPx() }
    val edgePx = with(density) { PIE_CHART_EDGE_PADDING.toPx() }
    val tolerancePx = with(density) { PIE_TOUCH_TOLERANCE.toPx() }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val geometry = remember(canvasSize, strokePx, growthPx, edgePx) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) {
            null
        } else {
            pieChartGeometry(
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
                strokeWidth = strokePx,
                outerGrowth = growthPx,
                edgePadding = edgePx,
            )
        }
    }

    // 按分类存动画状态：扇区按金额排序，选中态切换时新旧两段各自过渡。
    val animationByCategory: Map<TransactionCategory, State<Float>> = slices.associate { slice ->
        slice.stat.category to animateFloatAsState(
            targetValue = if (slice.stat.category == selectedCategory) 1f else 0f,
            animationSpec = tween(durationMillis = PIE_SELECT_ANIMATION_MS),
            label = "pie-slice-${slice.stat.category.name}",
        )
    }

    val sliceColors = slices.map { categoryColor(it.stat.category) }
    // 手势协程会活过多次重组，选中态与回调必须取最新值，否则第二次点同一个扇区取消不掉。
    val currentSelection by rememberUpdatedState(selectedCategory)
    val currentOnSliceClick by rememberUpdatedState(onSliceClick)

    val description = remember(stats, totalCents, selectedSlice) {
        buildString {
            append(tr("分类占比"))
            append(" · ")
            append(formatCny(totalCents))
            stats.maxByOrNull { it.totalCents }?.let {
                append(" · ")
                append(tr("最高"))
                append(" ")
                append(tr(it.category.displayName))
                append(" ")
                append(formatCny(it.totalCents))
            }
            selectedSlice?.let {
                append(" · ")
                append(tr("已选中"))
                append(" ")
                append(tr(it.category.displayName))
                append(" ")
                append(formatCny(it.totalCents))
                append(" ")
                append(categoryPercentText(it.percentage))
            }
        }
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .onSizeChanged { canvasSize = it }
            .pointerInput(slices, geometry, tolerancePx) {
                val geom = geometry ?: return@pointerInput
                detectTapGestures { touch ->
                    val hit = pieSliceHit(slices, geom, touch, tolerancePx)
                    val category = hit?.let { slices[it].stat.category }
                    // 点同一个扇区或点空白（环心、环外）都回到总计。
                    currentOnSliceClick(category.takeIf { it != currentSelection })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val geom = geometry ?: return@Canvas
            // 扇区之间留一条细缝露出卡片底色，缝隙本身不画任何底色环。
            val gap = if (slices.size > 1) PIE_SLICE_GAP_DEGREES else 0f
            slices.forEachIndexed { index, slice ->
                val progress = animationByCategory[slice.stat.category]?.value ?: 0f
                val extra = growthPx * progress
                // 内半径固定、厚度增加 extra：drawArc 的中线半径随之外移 extra/2。
                val midRadius = geom.ringRadius + extra / 2f
                drawArc(
                    color = sliceColors[index],
                    startAngle = slice.startAngle + gap / 2f,
                    sweepAngle = (slice.sweepAngle - gap).coerceAtLeast(PIE_MIN_SWEEP_DEGREES),
                    useCenter = false,
                    topLeft = geom.center - Offset(midRadius, midRadius),
                    size = Size(midRadius * 2f, midRadius * 2f),
                    style = Stroke(width = geom.strokeWidth + extra, cap = StrokeCap.Butt),
                )
            }
        }

        val innerDiameter = geometry
            ?.let { with(density) { (it.radiusInner * 2f).toDp() } }
            ?: 120.dp
        val contentMaxWidth = innerDiameter * PIE_CENTER_WIDTH_RATIO
        // 内圈不够大时用小一号金额字，宁可字小也不让金额被环压住。
        val amountStyle = if (innerDiameter >= 168.dp) {
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.titleMedium
        }
        Column(
            modifier = Modifier.widthIn(max = contentMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AutoSizeAmountText(
                text = formatCny(selectedSlice?.totalCents ?: totalCents),
                style = amountStyle,
                color = QingPrimaryDeep,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (selectedSlice != null) {
                    "${tr(selectedSlice.category.displayName)} · ${categoryPercentText(selectedSlice.percentage)}"
                } else {
                    centerLabel
                },
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
