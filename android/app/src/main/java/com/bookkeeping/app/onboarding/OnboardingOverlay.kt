package com.bookkeeping.app.onboarding

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.GestureDetector
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Screen-space bounds and the real Compose host receiving forwarded pointer events. */
internal data class OnboardingTarget(val bounds: Rect, val host: View)

/** Reports a real control's location without adding any UI to the underlying screen. */
internal fun Modifier.onboardingTarget(host: View, report: (OnboardingTarget) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val origin = coordinates.positionOnScreen()
        report(OnboardingTarget(Rect(origin, androidx.compose.ui.geometry.Size(
            coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
        )), host))
    }

/**
 * Window-level scrim. The Dialog does not take keyboard focus from the sheet below it;
 * pointer sequences outside its own controls are delivered to the underlying Compose host.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun OnboardingOverlay(
    step: Int,
    target: OnboardingTarget?,
    title: String,
    message: String,
    skipLabel: String,
    nextLabel: String,
    dimBackground: Boolean = true,
    fingerPosition: Offset? = null,
    tryLabel: String? = null,
    onTry: (() -> Unit)? = null,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val overlayView = LocalView.current
        val primaryColor = MaterialTheme.colorScheme.primary
        val context = LocalContext.current
        val currentNext by rememberUpdatedState(onNext)
        val detector = remember(context) {
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true
                override fun onDoubleTap(event: MotionEvent): Boolean {
                    val accessibility = context.getSystemService(AccessibilityManager::class.java)
                    if (accessibility?.isTouchExplorationEnabled != true) currentNext()
                    return true
                }
            })
        }
        val window = (overlayView.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
        }
        var origin by remember { mutableStateOf(Offset.Zero) }
        var bubbleHeight by remember { mutableStateOf(0) }
        var contentHeight by remember { mutableStateOf(0) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val headerPx = with(density) { 56.dp.roundToPx() }
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
            Canvas(
                Modifier.fillMaxSize().pointerInteropFilter { event ->
                    detector.onTouchEvent(event)
                    val host = target?.host ?: return@pointerInteropFilter true
                    val hostPosition = IntArray(2)
                    host.getLocationOnScreen(hostPosition)
                    val overlayPosition = IntArray(2)
                    overlayView.getLocationOnScreen(overlayPosition)
                    val forwarded = MotionEvent.obtain(event)
                    forwarded.offsetLocation(
                        (overlayPosition[0] - hostPosition[0]).toFloat(),
                        (overlayPosition[1] - hostPosition[1]).toFloat(),
                    )
                    try { host.dispatchTouchEvent(forwarded) } finally { forwarded.recycle() }
                    true
                },
            ) {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    target?.bounds?.translate(-origin)?.let { addRect(it) }
                }
                if (dimBackground) {
                    drawPath(path, Color.Black.copy(alpha = 0.64f))
                    target?.bounds?.translate(-origin)?.let { hole ->
                        drawRect(Color.White, hole.topLeft, hole.size, style = Stroke(2.dp.toPx()))
                    }
                }
                fingerPosition?.let { point ->
                    drawCircle(Color.Black.copy(alpha = 0.3f), 17.dp.toPx(), point - origin + Offset(2.dp.toPx(), 3.dp.toPx()))
                    drawCircle(Color.White, 16.dp.toPx(), point - origin)
                    drawCircle(primaryColor, 8.dp.toPx(), point - origin)
                }
            }
            fingerPosition?.let { point ->
                Text("👆", fontSize = 32.sp, modifier = Modifier.offset {
                    IntOffset((point.x - origin.x).roundToInt(), (point.y - origin.y).roundToInt())
                })
            }
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)
                .onSizeChanged { contentHeight = it.height }) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(8) { index ->
                            Box(Modifier.size(7.dp).background(
                                if (index == step) Color.White else Color.White.copy(alpha = 0.35f),
                                CircleShape,
                            ))
                        }
                    }
                    TextButton(onClick = onSkip) { Text(skipLabel, color = Color.White) }
                }
                Box(
                    modifier = (if (dimBackground) Modifier.align(Alignment.TopCenter).offset {
                        IntOffset(0, onboardingBubbleTop(0, bubbleHeight, contentHeight, headerPx, 0))
                    } else Modifier.align(Alignment.BottomCenter))
                        .then(if (contentHeight > 0) Modifier.heightIn(max = with(density) {
                            (contentHeight - headerPx).coerceAtLeast(1).toDp()
                        }) else Modifier)
                        .onSizeChanged { bubbleHeight = it.height }
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)),
                ) {
                    if (dimBackground) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            if (tryLabel != null && onTry != null) {
                                TextButton(onClick = onTry) { Text(tryLabel) }
                            }
                            Button(onClick = onNext) { Text(nextLabel, maxLines = 1) }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onNext, modifier = Modifier.align(Alignment.End)) {
                                Text(nextLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}
