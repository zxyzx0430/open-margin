package com.bookkeeping.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
@Composable
fun AutoSizeAmountText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    val base = if (style.fontSize.isSp) style.fontSize.value else 16f
    val initialSp = AmountTextSizing.fontSizeSp(base, text.length)
    var fontSizeSp by remember(text, base) { mutableFloatStateOf(initialSp) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style.copy(fontSize = fontSizeSp.sp),
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if ((result.didOverflowWidth || result.hasVisualOverflow) &&
                fontSizeSp > AmountTextSizing.MIN_FONT_SIZE_SP
            ) {
                fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(AmountTextSizing.MIN_FONT_SIZE_SP)
            }
        },
    )
}
