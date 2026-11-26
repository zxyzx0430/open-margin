package com.bookkeeping.app.ui.components

import com.bookkeeping.app.ui.theme.rememberReduceMotion
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import com.bookkeeping.app.ui.theme.QingMotionTokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 账单页右下角的「+」：点一下手动新建账单，**按住满 0.75 秒立刻开始录音**。
 *
 * 时序（常量由 [QuickEntryRule] 统一管理）：
 * - 单击：280ms 双击窗口结束后，打开手动记账；
 * - 双击：直接进入截图批量导入；
 * - 按住 100ms 后才切换麦克风及绘制进度弧，750ms 时立即开录音；
 * - 500ms 轻震，750ms 重震；滑出取消逻辑保持不变。
 *
 * 为什么手势装在 `pointerInput(Unit)` 上而不是 `pointerInput(enabled)`：满 0.75 秒时面板会打开，
 * 上层同时把 `enabled` 置 false（禁止再开第二个会话）。如果手势跟着 key 重建，正在按着的
 * 这一段会被取消；固定 key 保证滑出取消仍可收到。
 *
 * 进度弧画在 FAB 外侧，整体（含外圈）宽度比 FAB 直径大 12dp，
 * 仍在 Scaffold 为 FAB 留的 16dp 边距之内，所以不会被裁掉。
 */
@Composable
fun VoiceFab(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
    fabSize: Dp = 56.dp,
    ringWidth: Dp = 2.5.dp,
    ringGap: Dp = 3.dp,
    // 录音会话进行中由调用方置 false：不允许再起一段；已经按住的那一段不受影响。
    enabled: Boolean = true,
) {
    val haptics = rememberUpdatedState(LocalHapticFeedback.current)
    val tapAction = rememberUpdatedState(onTap)
    val doubleTapAction = rememberUpdatedState(onDoubleTap)
    val startAction = rememberUpdatedState(onLongPressStart)
    val abortAction = rememberUpdatedState(onAbort)
    val enabledState = rememberUpdatedState(enabled)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val description = tr("点一下记一笔，双击导截图，长按说话记账")
    val doubleTapLabel = tr("截图批量导入")
    val tapLabel = tr("新建账单")
    val voiceLabel = tr("语音记账")

    var pressing by remember { mutableStateOf(false) }
    var holdVisualStarted by remember { mutableStateOf(false) }
    // 满 0.75 秒已经开录：抬手只结束按压，不结束录音。
    var armed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val reduceMotion = rememberReduceMotion()
    val pressScale by animateFloatAsState(
        targetValue = if (pressing) QingMotionTokens.cardPressedScale else 1f,
        animationSpec = QingMotionTokens.motionPress.spec(reduceMotion),
        label = "voiceFabPressScale",
    )

    // 计时与进度弧：用真实时钟推进，掉帧也不会把「按满 0.75 秒」拖成更久。
    // 满 0.75 秒这一帧就回调 onLongPressStart（开始录音 + 开面板），然后退出循环把进度留在满格。
    LaunchedEffect(pressing) {
        if (!pressing) {
            holdVisualStarted = false
            val from = progress
            if (from > 0f) {
                val startFrame = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val fraction =
                        ((now - startFrame) / 1_000_000f / QuickEntryRule.RING_RELEASE_MS)
                            .coerceIn(0f, 1f)
                    progress = from * (1f - fraction)
                    if (fraction >= 1f) break
                }
                progress = 0f
            }
            return@LaunchedEffect
        }
        val startMs = SystemClock.uptimeMillis()
        var ticked = false
        while (true) {
            withFrameNanos { }
            // 松手后立即退出，避免松手与满 0.75 秒撞在同一帧时还多推一帧进度。
            if (!pressing) return@LaunchedEffect
            val elapsed = SystemClock.uptimeMillis() - startMs
            holdVisualStarted = elapsed >= QuickEntryRule.HOLD_VISUAL_DELAY_MS
            progress = QuickEntryRule.visualRingProgress(elapsed)
            if (!ticked && QuickEntryRule.shouldTick(elapsed)) {
                ticked = true
                haptics.value.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            if (!armed && QuickEntryRule.isLongEnough(elapsed)) {
                armed = true
                progress = 1f
                haptics.value.performHapticFeedback(HapticFeedbackType.LongPress)
                startAction.value()
                return@LaunchedEffect
            }
        }
    }

    Box(
        modifier = modifier.size(fabSize + (ringWidth + ringGap) * 2),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (progress > 0.001f) {
                val stroke = ringWidth.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset(
                    x = (size.width - diameter) / 2f,
                    y = (size.height - diameter) / 2f,
                )
                val arcSize = Size(diameter, diameter)
                // 轨道随按下淡入，满格时也不会比进度弧更抢眼。
                drawArc(
                    color = primary.copy(alpha = 0.20f * (progress * 4f).coerceIn(0f, 1f)),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = primary,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(fabSize)
                .scale(pressScale)
                .alpha(if (enabled || pressing) 1f else 0.5f)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(primary)
                // key 固定为 Unit：面板打开会把 enabled 置 false，但已经开始的按压手势不能重建，
                // 否则仍按住时的滑出取消会收不到。
                .pointerInput(Unit) {
                    coroutineScope {
                    // 同一个指针协程内等待第二次点击；离开页面时延迟任务自动取消。
                    var pendingTapJob: Job? = null
                    var lastTapReleaseMs = -1L
                    var lastTapPosition: Offset? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabledState.value) return@awaitEachGesture
                        down.consume()
                        val previousPosition = lastTapPosition
                        val secondTap = pendingTapJob?.isActive == true &&
                            previousPosition != null &&
                            QuickEntryRule.isSecondTap(
                                firstReleaseMs = lastTapReleaseMs,
                                secondDownMs = down.uptimeMillis,
                                distancePx = (down.position - previousPosition).getDistance(),
                                maxDistancePx = viewConfiguration.touchSlop * 2f,
                            )
                        if (secondTap) {
                            pendingTapJob?.cancel()
                            pendingTapJob = null
                        }

                        val downPosition = down.position
                        armed = false
                        holdVisualStarted = false
                        pressing = true
                        var cancelled = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val drift = (change.position - downPosition).getDistance()
                                if (drift > viewConfiguration.touchSlop * 2f) {
                                    cancelled = true
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            pressing = false
                        }
                        when (QuickEntryRule.releaseAction(armed = armed, cancelled = cancelled)) {
                            HoldRelease.TAP -> {
                                if (secondTap) {
                                    doubleTapAction.value()
                                    lastTapReleaseMs = -1L
                                    lastTapPosition = null
                                } else {
                                    lastTapReleaseMs = SystemClock.uptimeMillis()
                                    lastTapPosition = downPosition
                                    pendingTapJob?.cancel()
                                    pendingTapJob = launch {
                                        delay(QuickEntryRule.DOUBLE_TAP_WINDOW_MS)
                                        tapAction.value()
                                    }
                                }
                            }
                            HoldRelease.ABORT_RECORDING -> abortAction.value()
                            HoldRelease.NOTHING -> Unit
                        }
                        armed = false
                    }
                    }
                }
                // TalkBack：读屏用户长按走 onLongClick 动作，不依赖计时手势；
                // 开录之后用面板上的「停止」结束，不需要一直按住。
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    if (enabled) {
                        onClick(label = tapLabel) {
                            tapAction.value()
                            true
                        }
                        onLongClick(label = voiceLabel) {
                            startAction.value()
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction(doubleTapLabel) {
                                doubleTapAction.value()
                                true
                            },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // 按住时图标换成麦克风：一眼就能看出「现在是说话，不是新建」。
                imageVector = if ((pressing && holdVisualStarted) || armed) Icons.Filled.Mic else Icons.Filled.Add,
                contentDescription = null,
                tint = onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
