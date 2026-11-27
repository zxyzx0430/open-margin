package com.bookkeeping.app.ui.components

import kotlin.math.roundToInt
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * 金额字号分档：金额一个数字都不能藏，宽度不够时只缩字号、绝不用省略号。
 *
 * Compose BOM 2024.10.01 对应 compose-foundation 1.7.x，还没有
 * `TextAutoSize` / `BasicText(autoSize = ...)`，因此退回按字符数分档的实现：
 * 12 字符起每档缩 3sp，18 字符以上再缩一档，下限 [MIN_FONT_SIZE_SP]。
 * 取 12 字符作为第一档，是因为各页金额原本的字号（11–28sp）已经能把常见金额
 * （≤11 字符，如 ¥26,825.14）排下；从 22sp 基准看，档位就是 22 / 19 / 16 / 13sp，
 * 与设计给的档位一致，其他基准按同样步进收缩。
 */
internal val LocalAmountTextMeasurer = staticCompositionLocalOf<TextMeasurer?> { null }

object AmountTextSizing {
    /** 低于 11sp 金额就难读了，到此为止。 */
    const val MIN_FONT_SIZE_SP = 11f
    private const val STEP_SP = 3f

    /** 超过 11 字符才开始缩，一档 3sp。 */
    fun stepFor(charCount: Int): Int = when {
        charCount <= 11 -> 0
        charCount <= 14 -> 1
        charCount <= 18 -> 2
        else -> 3
    }

    /** 按字符数分档收缩后的字号：不小于 [MIN_FONT_SIZE_SP]。 */
    fun fontSizeSp(baseSizeSp: Float, charCount: Int): Float =
        (baseSizeSp - STEP_SP * stepFor(charCount)).coerceAtLeast(MIN_FONT_SIZE_SP)
}

/**
 * 单行金额文本：按字符数自动缩小字号，永不出现省略号。
 *
 * 只改字号、沿用 [style] 的行高，所以字号变化不会撑高卡片或改变排版。
 */
/** Measure against the actual parent constraints. Ordinary amounts take the
 * direct text path; unusually wide amounts are fitted during the same layout,
 * without an onTextLayout state write or extra composition frame.
 */
@Composable
fun AutoSizeAmountText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    minimumSizeSp: Float = AmountTextSizing.MIN_FONT_SIZE_SP,
) {
    val base = if (style.fontSize.isSp) style.fontSize.value else 16f
    val floor = minimumSizeSp.coerceAtMost(base)
    val initial = AmountTextSizing.fontSizeSp(base, text.length).coerceAtLeast(floor)
    val measurer = LocalAmountTextMeasurer.current ?: rememberTextMeasurer(cacheSize = 4)
    val resolvedColor = if (color != Color.Unspecified) color
        else if (style.color != Color.Unspecified) style.color else LocalContentColor.current
    val textStyle = style.copy(
        fontSize = initial.sp,
        fontFeatureSettings = "tnum",
        fontWeight = fontWeight ?: style.fontWeight,
        textAlign = textAlign ?: style.textAlign,
    )
    val annotated = remember(text) { AnnotatedString(text) }
    val cache = remember(annotated, textStyle, floor, measurer) { AmountLayoutResult() }
    Layout(
        content = {},
        modifier = modifier.semantics { this.text = annotated }.drawWithContent {
            cache.result?.let { drawText(it, color = resolvedColor) }
        },
    ) { _, constraints ->
        val width = constraints.maxWidth
        fun measure(sp: Float, bounds: Constraints) = measurer.measure(
            text = annotated,
            style = textStyle.copy(fontSize = sp.sp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            constraints = bounds,
        )
        var font = initial
        var result = measure(font, constraints)
        // A normal value is measured once. An overflow is corrected in this
        // measure phase, not in a later recomposition.
        if (result.didOverflowWidth && width > 0 && width != Constraints.Infinity) {
            val natural = measure(font, Constraints()).size.width
            font = fitAmountFontSize(font, floor, width, natural)
            result = measure(font, constraints)
            // Never use ellipsis for monetary values. Extremely constrained
            // layouts may need a size below the preferred floor.
            if (result.didOverflowWidth) {
                val naturalFloor = measure(font, Constraints()).size.width.coerceAtLeast(1)
                font = (font * width.toFloat() / naturalFloor * 0.97f).coerceIn(1f, font)
                result = measure(font, constraints)
            }
        }
        cache.result = result
        layout(
            constraints.constrainWidth(result.size.width),
            constraints.constrainHeight(result.size.height),
            alignmentLines = mapOf(FirstBaseline to result.firstBaseline.roundToInt(),
                LastBaseline to result.lastBaseline.roundToInt()),
        ) {}
    }
}

private class AmountLayoutResult(var result: TextLayoutResult? = null)

internal fun fitAmountFontSize(current: Float, floor: Float, available: Int, intrinsic: Int): Float {
    if (intrinsic <= 0 || available <= 0) return floor
    return (current * available.toFloat() / intrinsic * 0.98f)
        .coerceAtMost(current - 0.5f).coerceAtLeast(floor)
}
