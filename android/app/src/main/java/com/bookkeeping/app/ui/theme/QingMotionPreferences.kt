package com.bookkeeping.app.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal fun reduceMotionForScale(scale: Float): Boolean = scale == 0f

@Composable
internal fun observeReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readScale() = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
    var reduced by remember(resolver) { mutableStateOf(reduceMotionForScale(readScale())) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = reduceMotionForScale(readScale())
            }
        }
        val registered = runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }.isSuccess
        reduced = reduceMotionForScale(readScale())
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** Observe the initial down without consuming pointer events. Scrolling and
 * the existing clickable/accessibility actions retain ownership of the gesture. */
fun Modifier.qingPressFeedback(pressedScale: Float, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val reduced = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed && !reduced) pressedScale else 1f,
        QingMotionTokens.motionPress.spec(reduced),
        label = "qingPointerDownScale",
    )
    this.pointerInput(haptics) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            pressed = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        (change.pressed && change.isConsumed)) {
                        pressed = false
                    } else if (!change.pressed) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        pressed = false
                    }
                } while (pressed)
            } finally {
                pressed = false
            }
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }
}

val LocalQingReduceMotion = staticCompositionLocalOf { false }
@Composable fun rememberReduceMotion(): Boolean = LocalQingReduceMotion.current
