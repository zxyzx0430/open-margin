package com.bookkeeping.app.ui.components

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingMotionTokens
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors
import com.bookkeeping.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A window-level modal with an interruptible handle spring. No delay on save or selection. */
@Composable
fun QingGestureSheet(
    onDismissRequest: () -> Unit,
    canDismiss: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestCanDismiss by rememberUpdatedState(canDismiss)
    val reduced = rememberReduceMotion()
    val colors = qingColors
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { if (latestCanDismiss) latestDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false, dismissOnBackPress = true, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val threshold = with(LocalDensity.current) { 96.dp.toPx() }
            val velocityThreshold = with(LocalDensity.current) { 650.dp.toPx() }
            val entryDistance = with(LocalDensity.current) { 96.dp.toPx() }
            var panelHeight by remember { mutableFloatStateOf(0f) }
            var displacement by remember { mutableFloatStateOf(if (reduced) 0f else entryDistance) }
            val animator = remember { Animatable(displacement) }
            var settleJob by remember { mutableStateOf<Job?>(null) }
            fun settle(target: Float, velocity: Float = 0f, close: Boolean = false) {
                settleJob?.cancel()
                settleJob = scope.launch {
                    animator.snapTo(displacement)
                    animator.animateTo(target, QingMotionTokens.motionGesture.spec(reduced),
                        initialVelocity = if (reduced) 0f else velocity) {
                        displacement = value.coerceAtLeast(0f)
                    }
                    if (close && latestCanDismiss) latestDismiss()
                    else if (close) settle(0f)
                }
            }
            LaunchedEffect(reduced) { settle(0f) }
            val dragState = rememberDraggableState { delta ->
                displacement = (displacement + delta).coerceIn(0f, heightPx)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(enabled = canDismiss, onClickLabel = tr("关闭"), onClick = latestDismiss))
            Column(
                Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                    .onSizeChanged { panelHeight = it.height.toFloat() }
                    .graphicsLayer { translationY = displacement }
                    .clip(RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero))
                    .background(colors.surfaceSheet),
            ) {
                Box(Modifier.fillMaxWidth().height(24.dp)
                    .draggable(dragState, Orientation.Vertical, enabled = canDismiss,
                        startDragImmediately = displacement > 0f,
                        onDragStarted = { settleJob?.cancel(); animator.stop() },
                        onDragStopped = { velocity ->
                            val close = latestCanDismiss && (displacement > threshold || velocity > velocityThreshold)
                            settle(if (close) panelHeight.coerceAtLeast(displacement) else 0f, velocity, close)
                        })
                    .semantics { contentDescription = tr("下滑关闭面板") },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(QingRadiusTokens.radiusPill))
                        .background(colors.textSecondary.copy(alpha = 0.28f)))
                }
                content()
            }
        }
    }
}
