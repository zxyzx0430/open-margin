package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

internal fun largeTitleProgress(index: Int, offset: Int, threshold: Float, reduced: Boolean): Float {
    val value = if (index > 0) 1f else (offset / threshold.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return if (reduced) if (value >= 1f) 1f else 0f else value
}

/** Fixed-measurement chrome: scroll updates only graphics-layer properties. */
@Composable
fun QingLargeTitle(
    title: String,
    state: LazyListState,
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    hasLeading: Boolean = false,
    actionWidth: Int = 0,
) {
    val colors = qingColors
    val reduced = rememberReduceMotion()
    val density = LocalDensity.current
    val threshold = with(density) { 42.dp.toPx() }
    val shift = with(density) { 8.dp.toPx() }
    val progress by remember(state, threshold, reduced) {
        derivedStateOf { largeTitleProgress(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, threshold, reduced) }
    }
    val compact by remember(state, threshold, reduced) { derivedStateOf { progress >= 0.5f } }
    val side = maxOf(if (hasLeading) 48 else 16, actionWidth).dp
    Box(Modifier.fillMaxWidth().height(56.dp).background(colors.surfaceCanvas)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.56).sp,
            color = colors.textPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart)
                .padding(start = if (hasLeading) 48.dp else 16.dp, end = (actionWidth + 12).dp)
                .then(if (compact) Modifier.clearAndSetSemantics {} else Modifier.semantics { heading() })
                .graphicsLayer { alpha = 1f - progress; translationY = -shift * progress })
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = side)
                .then(if (compact) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {})
                .graphicsLayer { alpha = progress; translationY = shift * (1f - progress) })
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, content = leading)
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.5.dp)
            .graphicsLayer { alpha = progress }.background(colors.strokeHairline))
    }
}
