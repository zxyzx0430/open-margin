package com.bookkeeping.app.ui.components

import androidx.compose.ui.unit.constrainHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.ui.theme.qingColors
import com.bookkeeping.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

internal fun largeTitleProgress(index: Int, offset: Int, threshold: Float, reduced: Boolean): Float {
    val value = if (index > 0) 1f else (offset / threshold.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return if (reduced) if (value >= 1f) 1f else 0f else value
}

internal fun titleConsume(fraction: Float, delta: Float, travel: Float): Pair<Float, Float> {
    val distance = travel.coerceAtLeast(1f)
    val next = (fraction - delta / distance).coerceIn(0f, 1f)
    return next to ((fraction - next) * distance)
}

/** A single physical collapsing header; nested scroll consumes only its travel. */
@Stable
class QingTitleCollapseState internal constructor(
    private val state: MutableFloatState,
    private val travel: Float,
    internal val reduced: Boolean,
) {
    val fraction: Float get() = state.floatValue
    internal fun setFraction(value: Float) { state.floatValue = value.coerceIn(0f, 1f) }
    private fun consume(delta: Float): Offset {
        val (next, consumed) = titleConsume(fraction, delta, travel)
        setFraction(next)
        return Offset(0f, consumed)
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f) consume(available.y) else Offset.Zero
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0f) consume(available.y) else Offset.Zero

    }
}

internal fun qingTitleExpandedHeight(hasLeading: Boolean): Int = if (hasLeading) 104 else 76

@Composable
fun rememberQingTitleCollapseState(list: LazyListState, hasLeading: Boolean = false): QingTitleCollapseState {
    val reduced = rememberReduceMotion()
    val travel = with(LocalDensity.current) { (qingTitleExpandedHeight(hasLeading) - 52).dp.toPx() }
    val stored = rememberSaveable { mutableFloatStateOf(if (list.firstVisibleItemIndex > 0) 1f else 0f) }
    val collapse = remember(stored, travel, reduced) { QingTitleCollapseState(stored, travel, reduced) }
    LaunchedEffect(list, collapse) {
        var wasAway = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
            .collect { (index, offset, scrolling) ->
                val away = index > 0 || offset > 0
                if (index > 0) collapse.setFraction(1f)
                if (!away && wasAway && !scrolling) collapse.setFraction(0f)
                if (!scrolling || away) wasAway = away
            }
    }
    return collapse
}

/** Main pages: 76dp expanded heading with inline actions; child pages with back navigation: 104dp. Compact: 52dp.
 * Fraction is read only during measure/draw except the coarse semantics switch.
 */
@Composable
fun QingLargeTitle(
    title: String,
    state: QingTitleCollapseState,
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    hasLeading: Boolean = false,
    actionWidth: Int = 0,
) {
    val colors = qingColors
    val compact by remember(state) { derivedStateOf { state.fraction >= 0.5f } }
    val side = maxOf(if (hasLeading) 48 else 16, actionWidth).dp
    Layout(
        modifier = Modifier.fillMaxWidth().clipToBounds().background(colors.surfaceCanvas),
        content = {
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp,
                    color = colors.textPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = if (hasLeading) 20.dp else maxOf(20, actionWidth).dp)
                        .then(if (compact) Modifier.clearAndSetSemantics {} else Modifier.semantics { heading() })
                        .graphicsLayer { alpha = if (state.reduced) { if (state.fraction < 0.5f) 1f else 0f } else if (hasLeading) 1f - state.fraction else (1f - state.fraction * 2f).coerceIn(0f, 1f) })
            }
            Box(Modifier.fillMaxWidth().height(52.dp).then(if (hasLeading) Modifier.background(colors.surfaceCanvas) else Modifier)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = side)
                        .then(if (compact) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {})
                        .graphicsLayer { alpha = if (state.reduced) { if (state.fraction >= 0.5f) 1f else 0f } else if (hasLeading) state.fraction else (state.fraction * 2f - 1f).coerceIn(0f, 1f) })
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, content = leading)
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.5.dp)
                    .graphicsLayer { alpha = if (state.reduced) { if (state.fraction >= 0.5f) 1f else 0f } else state.fraction }.background(colors.strokeHairline))
            }
        },
    ) { measurables, constraints ->
        val row = 52.dp.roundToPx()
        val rowConstraints = constraints.copy(minHeight = row, maxHeight = row)
        val large = measurables[0].measure(rowConstraints)
        val small = measurables[1].measure(rowConstraints)
        val travel = (qingTitleExpandedHeight(hasLeading) - 52).dp.toPx()
        val remaining = (travel * (1f - state.fraction)).roundToInt()
        layout(constraints.maxWidth, constraints.constrainHeight(row + remaining)) {
            if (hasLeading) {
                large.placeRelative(0, remaining)
                small.placeRelative(0, 0)
            } else {
                // No permanently empty action row on root pages.
                val centerY = remaining / 2
                large.placeRelative(0, centerY)
                small.placeRelative(0, centerY)
            }
        }
    }
}
