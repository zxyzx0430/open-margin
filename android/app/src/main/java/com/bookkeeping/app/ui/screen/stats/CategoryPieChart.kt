package com.bookkeeping.app.ui.screen.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 最多给几个分片画引线标签；再多即使错开也会挤成一团，只留图例。 */
internal const val PIE_LABEL_MAX_COUNT = 6

/** 同一侧相邻两个标签的最小垂直间距（dp）。 */
internal const val PIE_LABEL_MIN_SPACING_DP = 18f

/** 选中扇区向外抽出的距离。 */
private val PIE_SELECT_OFFSET = 8.dp

/** 引线在径向上先走一小段，再折成水平线。 */
private val PIE_ELBOW_RUN = 14.dp

/** 引线末端与文字之间留的空隙。 */
private val PIE_TEXT_GAP = 4.dp

/** 引线粗细。 */
private val PIE_LEADER_WIDTH = 1.dp

/** 环的最小外半径：再小中心金额就没地方写了。 */
private val PIE_MIN_RADIUS = 52.dp

/** 环的线宽。 */
private val PIE_STROKE = 18.dp

/** 手指按偏一点也算命中：环两侧各放宽这么多。 */
private val PIE_TOUCH_TOLERANCE = 10.dp

/** 选中动画时长。 */
private const val PIE_SELECT_ANIMATION_MS = 200

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
 * 需要画引线标签的扇区：按金额取前 [maxCount] 个，再按中心角度排序。
 *
 * 现在标签走环外的引线、并且做了垂直避让，所以不再按占比门槛筛掉小分片——
 * 小的分类同样有名字可读，只要它在前 [maxCount] 名里。
 */
internal fun labeledSlices(
    slices: List<PieSlice>,
    maxCount: Int = PIE_LABEL_MAX_COUNT,
): List<PieSlice> = slices
    .sortedByDescending { it.stat.totalCents }
    .take(maxCount)
    .sortedBy { it.midAngle }

/** 百分比文案：饼图标签与图例共用，避免两处格式不一致。 */
internal fun categoryPercentText(percentage: Float): String =
    String.format(Locale.CHINA, "%.1f%%", percentage * 100f)

/** 分片落在左半还是右半：cos ≥ 0 记为右侧（1），否则左侧（-1）。 */
internal fun pieLabelSide(midAngle: Float): Int =
    if (cos(Math.toRadians(midAngle.toDouble())) >= 0.0) 1 else -1

/**
 * 标签避让：同一侧按 y 从小到大依次错开，保证相邻至少 [minGap]。
 *
 * 先自上而下推一遍，底部越界就从下往上回推，最后再夹住顶部。
 * 输入顺序即绘制顺序，输出与输入一一对应，不会交换两个标签的位置。
 */
internal fun distributeLabelYs(
    ys: List<Float>,
    minGap: Float,
    minY: Float,
    maxY: Float,
): List<Float> {
    if (ys.isEmpty()) return ys
    val low = min(minY, maxY)
    val high = max(minY, maxY)
    val out = ys.toMutableList()
    for (i in out.indices) {
        out[i] = out[i].coerceIn(low, high)
        if (i > 0) out[i] = max(out[i], out[i - 1] + minGap)
    }
    if (out.last() > high) {
        out[out.lastIndex] = high
        for (i in out.lastIndex - 1 downTo 0) {
            out[i] = min(out[i], out[i + 1] - minGap)
        }
    }
    if (out.first() < low) {
        out[0] = low
        for (i in 1..out.lastIndex) {
            out[i] = max(out[i], out[i - 1] + minGap)
        }
    }
    return out
}

/** 环与引线的几何：只由画布尺寸和最长标签宽度决定，与选中状态无关，所以直径不会跳。 */
internal data class PieChartGeometry(
    val center: Offset,
    val radiusOuter: Float,
    val ringRadius: Float,
    val strokeWidth: Float,
    val labelColumnWidth: Float,
    val elbowRun: Float,
    val textGap: Float,
)

/**
 * 计算几何：两侧各留出「最长标签 + 引线折角 + 空隙」，剩下的给环；
 * 环有最小半径，标签列因此有上限，超出部分由标签自己省略（百分比永远保留）。
 */
internal fun pieChartGeometry(
    width: Float,
    height: Float,
    maxLabelWidth: Float,
    strokeWidth: Float,
    elbowRun: Float,
    textGap: Float,
    minRadius: Float,
    edgePadding: Float,
): PieChartGeometry {
    val halfWidth = width / 2f
    val labelBudget = min(maxLabelWidth, halfWidth * 0.34f)
    val upperRadius = max(halfWidth - labelBudget - elbowRun - textGap, minRadius)
    val radius = min(upperRadius, max(height / 2f - edgePadding, minRadius))
    val columnWidth = max(halfWidth - radius - elbowRun - textGap, 1f)
    return PieChartGeometry(
        center = Offset(halfWidth, height / 2f),
        radiusOuter = radius,
        ringRadius = max(radius - strokeWidth / 2f, 1f),
        strokeWidth = strokeWidth,
        labelColumnWidth = columnWidth,
        elbowRun = elbowRun,
        textGap = textGap,
    )
}

/** 环与画布边缘之间固定留 4dp，避免弧线贴边被裁。 */
internal val PIE_CHART_EDGE_PADDING = 4.dp

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
 * 触摸命中：先按半径判断是不是落在环上（环内外各放宽 [tolerance]），再按角度取扇区。
 * 落在环心或环外都返回 null，也就是「点空白处」。
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
    val inner = geometry.ringRadius - geometry.strokeWidth / 2f - tolerance
    val outer = geometry.radiusOuter + tolerance
    if (distance < inner || distance > outer) return null
    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    return sliceIndexAtAngle(slices, angle)
}

/** 一个标签的排好版的两段文字（分类名 + 百分比）与实测宽度。 */
private data class PieLabelLayout(
    val slice: PieSlice,
    val name: TextLayoutResult,
    val percent: TextLayoutResult,
    val width: Float,
    val height: Float,
)

/**
 * 分类占比环图。
 *
 * 扇区不再贴着环写文字，改成「径向短线 + 折角 + 一行文字」的引线标注：
 * 左侧标签右对齐、右侧标签左对齐，同一侧按 y 排序后保底错开 18dp，最多标前 6 大。
 * 点扇区把它向外抽出 8dp、引线与标签变 primary、环心金额切到该分类；再点一次或点空白恢复总计。
 * 图例仍在外层，选中时外层把对应图例行底色改成 surfaceVariant。
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
    val labeled = remember(slices) { labeledSlices(slices) }
    val selectedSlice = slices.firstOrNull { it.stat.category == selectedCategory }?.stat

    val density = LocalDensity.current
    val strokePx = with(density) { PIE_STROKE.toPx() }
    val elbowPx = with(density) { PIE_ELBOW_RUN.toPx() }
    val gapPx = with(density) { PIE_TEXT_GAP.toPx() }
    val minRadiusPx = with(density) { PIE_MIN_RADIUS.toPx() }
    val nameGapPx = with(density) { 4.dp.toPx() }
    val selectOffsetPx = with(density) { PIE_SELECT_OFFSET.toPx() }
    val minSpacingPx = with(density) { PIE_LABEL_MIN_SPACING_DP.dp.toPx() }
    val edgePx = with(density) { PIE_CHART_EDGE_PADDING.toPx() }
    val tolerancePx = with(density) { PIE_TOUCH_TOLERANCE.toPx() }

    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val selectedLabelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

    // 第一遍量出最长标签的宽度（不限宽），用来决定环的大小。
    val measuredForSizing = remember(labeled, measurer, labelStyle, nameGapPx) {
        measurePieLabels(measurer, labeled, labelStyle, Float.MAX_VALUE, nameGapPx)
    }
    val maxLabelWidth = measuredForSizing.maxOfOrNull { it.width } ?: 0f

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val geometry = remember(canvasSize, maxLabelWidth, strokePx, elbowPx, gapPx, minRadiusPx, edgePx) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) {
            null
        } else {
            pieChartGeometry(
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
                maxLabelWidth = maxLabelWidth,
                strokeWidth = strokePx,
                elbowRun = elbowPx,
                textGap = gapPx,
                minRadius = minRadiusPx,
                edgePadding = edgePx,
            )
        }
    }

    // 第二遍按标签列的可用宽度裁名字（百分比不裁），超长英文名只省略名字部分。
    val labelBudget = geometry?.labelColumnWidth ?: Float.MAX_VALUE
    val labels = remember(labeled, measurer, labelStyle, nameGapPx, labelBudget) {
        measurePieLabels(measurer, labeled, labelStyle, labelBudget, nameGapPx)
    }
    val boldLabels = remember(labeled, measurer, selectedLabelStyle, nameGapPx, labelBudget) {
        measurePieLabels(measurer, labeled, selectedLabelStyle, labelBudget, nameGapPx)
    }

    // 按分类存动画状态：扇区按金额排序、标签按角度排序，两套下标不能互相套用。
    val animationByCategory: Map<TransactionCategory, State<Float>> = slices.associate { slice ->
        slice.stat.category to animateFloatAsState(
            targetValue = if (slice.stat.category == selectedCategory) 1f else 0f,
            animationSpec = tween(durationMillis = PIE_SELECT_ANIMATION_MS),
            label = "pie-slice-${slice.stat.category.name}",
        )
    }

    val trackColor = QingSurfaceVariant
    val idleTextColor = MaterialTheme.colorScheme.onSurface
    val activeColor = MaterialTheme.colorScheme.primary
    val leaderColor = MaterialTheme.colorScheme.outline
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
            val center = geom.center
            val arcDiameter = geom.ringRadius * 2f
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = center - Offset(geom.ringRadius, geom.ringRadius),
                size = Size(arcDiameter, arcDiameter),
                style = Stroke(width = geom.strokeWidth),
            )

            val sliceGap = if (slices.size > 1) 2.5f else 0f
            slices.forEachIndexed { index, slice ->
                val progress = animationByCategory[slice.stat.category]?.value ?: 0f
                val direction = sliceDirection(slice)
                val shift = direction * (selectOffsetPx * progress)
                drawArc(
                    color = sliceColors[index],
                    startAngle = slice.startAngle + sliceGap / 2f,
                    sweepAngle = (slice.sweepAngle - sliceGap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = center - Offset(geom.ringRadius, geom.ringRadius) + shift,
                    size = Size(arcDiameter, arcDiameter),
                    style = Stroke(width = geom.strokeWidth, cap = StrokeCap.Butt),
                )
            }

            drawPieLabels(
                labels = labels,
                boldLabels = boldLabels,
                animations = animationByCategory,
                geometry = geom,
                idleColor = idleTextColor,
                activeColor = activeColor,
                leaderColor = leaderColor,
                selectedCategory = selectedCategory,
                selectOffsetPx = selectOffsetPx,
                minSpacing = minSpacingPx,
                edgePadding = edgePx,
                nameGap = nameGapPx,
                leaderWidth = PIE_LEADER_WIDTH.toPx(),
            )
        }

        val innerDiameter = geometry
            ?.let { with(density) { ((it.ringRadius - it.strokeWidth / 2f) * 2f).toDp() } }
            ?: 96.dp
        // 环变小后内圈只剩 ~100dp：字号跟着内圈走，金额宁可贴到环上也不裁字。
        val amountStyle = if (innerDiameter >= 132.dp) {
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.titleMedium
        }
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AutoSizeAmountText(
                text = formatCny(selectedSlice?.totalCents ?: totalCents),
                style = amountStyle,
                color = QingPrimaryDeep,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (selectedSlice != null) {
                    "${tr(selectedSlice.category.displayName)} · ${categoryPercentText(selectedSlice.percentage)}"
                } else {
                    centerLabel
                },
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
                maxLines = 2,
            )
        }
    }
}

/** 扇区朝外的单位向量：选中时沿这个方向抽出。 */
private fun sliceDirection(slice: PieSlice): Offset {
    val radians = Math.toRadians(slice.midAngle.toDouble())
    return Offset(cos(radians).toFloat(), sin(radians).toFloat())
}

/**
 * 量好每个标签的两段文字。分类名超宽时只省略名字，百分比（数字）永远完整保留。
 */
private fun measurePieLabels(
    measurer: TextMeasurer,
    labeled: List<PieSlice>,
    style: TextStyle,
    nameWidthBudget: Float,
    nameGap: Float,
): List<PieLabelLayout> = labeled.map { slice ->
    val percent = measurer.measure(
        text = AnnotatedString(categoryPercentText(slice.stat.percentage)),
        style = style,
        maxLines = 1,
        softWrap = false,
    )
    val nameMax = (nameWidthBudget - percent.size.width - nameGap).toInt().coerceAtLeast(1)
    val name = measurer.measure(
        text = AnnotatedString(tr(slice.stat.category.displayName)),
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = nameMax),
    )
    PieLabelLayout(
        slice = slice,
        name = name,
        percent = percent,
        width = name.size.width + nameGap + percent.size.width,
        height = max(name.size.height, percent.size.height).toFloat(),
    )
}

/**
 * 画引线与文字：从扇区外缘径向出一小段，折成水平线，末端一行「分类名 百分比」。
 * 选中扇区的引线加粗并转为 primary，标签同时加粗变色，颜色随 200ms 动画插值。
 */
private fun DrawScope.drawPieLabels(
    labels: List<PieLabelLayout>,
    boldLabels: List<PieLabelLayout>,
    animations: Map<TransactionCategory, State<Float>>,
    geometry: PieChartGeometry,
    idleColor: Color,
    activeColor: Color,
    leaderColor: Color,
    selectedCategory: TransactionCategory?,
    selectOffsetPx: Float,
    minSpacing: Float,
    edgePadding: Float,
    nameGap: Float,
    leaderWidth: Float,
) {
    if (labels.isEmpty()) return
    val center = geometry.center
    val bendRadius = geometry.radiusOuter + geometry.elbowRun

    // 每侧按基准 y 从小到大排好后统一避让，保证顺序不乱。
    val sides = labels.map { pieLabelSide(it.slice.midAngle) }
    val baseYs = labels.map { label ->
        val radians = Math.toRadians(label.slice.midAngle.toDouble())
        center.y + sin(radians).toFloat() * bendRadius
    }
    val placedY = FloatArray(labels.size)
    listOf(1, -1).forEach { side ->
        val indices = labels.indices.filter { sides[it] == side }.sortedBy { baseYs[it] }
        if (indices.isEmpty()) return@forEach
        val maxHeight = indices.maxOf { labels[it].height }
        val adjusted = distributeLabelYs(
            ys = indices.map { baseYs[it] },
            minGap = max(minSpacing, maxHeight),
            minY = edgePadding + maxHeight / 2f,
            maxY = size.height - edgePadding - maxHeight / 2f,
        )
        indices.forEachIndexed { i, labelIndex -> placedY[labelIndex] = adjusted[i] }
    }

    labels.forEachIndexed { index, label ->
        val progress = animations[label.slice.stat.category]?.value ?: 0f
        val selected = label.slice.stat.category == selectedCategory
        val drawn = if (selected) boldLabels.getOrNull(index) ?: label else label
        val lineColor = lerp(leaderColor, activeColor, progress)
        val textColor = lerp(idleColor, activeColor, progress)
        val side = sides[index]
        val y = placedY[index]
        val radians = Math.toRadians(label.slice.midAngle.toDouble())
        val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())

        val start = center + direction * (geometry.radiusOuter + selectOffsetPx * progress)
        val bend = center + direction * bendRadius
        val textEdgeX = center.x + side * (bendRadius + geometry.textGap)
        drawLine(
            color = lineColor,
            start = start,
            end = bend,
            strokeWidth = leaderWidth * (1f + progress),
        )
        drawLine(
            color = lineColor,
            start = bend,
            end = Offset(textEdgeX - side * geometry.textGap / 2f, y),
            strokeWidth = leaderWidth * (1f + progress),
        )

        val left = if (side > 0) textEdgeX else textEdgeX - drawn.width
        val top = y - drawn.height / 2f
        val nameTop = top + (drawn.height - drawn.name.size.height) / 2f
        drawText(
            textLayoutResult = drawn.name,
            color = textColor,
            topLeft = Offset(left, nameTop),
        )
        drawText(
            textLayoutResult = drawn.percent,
            color = textColor,
            topLeft = Offset(
                x = left + drawn.name.size.width + nameGap,
                y = top + (drawn.height - drawn.percent.size.height) / 2f,
            ),
        )
    }
}
