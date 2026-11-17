package com.bookkeeping.app.ui.screen.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Shared same-window bottom panel chrome for manual and voice transaction entry. */
@Composable
internal fun EntryPanelLayout(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    fillPanelHeight: Boolean = false,
    headerTrailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val latestDismiss by rememberUpdatedState(onDismiss)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(density) { 72.dp.toPx() }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(160),
        label = "entryPanelFade",
    )
    val rise by animateDpAsState(
        targetValue = if (visible) 0.dp else 56.dp,
        animationSpec = tween(180),
        label = "entryPanelRise",
    )

    BackHandler { latestDismiss() }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelMaxHeight = maxHeight * 0.88f
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(fade)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = interaction, indication = null) {
                    latestDismiss()
                },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Keep the panel above navigation bars and the IME, like the voice panel.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .offset { IntOffset(0, dragOffset.roundToInt() + rise.roundToPx()) },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fillPanelHeight) Modifier.fillMaxSize() else Modifier),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = rememberDraggableState { delta ->
                                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                                },
                                orientation = Orientation.Vertical,
                                onDragStopped = {
                                    if (dragOffset > dismissThresholdPx) {
                                        latestDismiss()
                                        dragOffset = 0f
                                    } else {
                                        dragOffset = 0f
                                    }
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 10.dp, bottom = 6.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            headerTrailing()
                        }
                    }
                    content()
                }
            }
        }
    }
}
