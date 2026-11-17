package com.bookkeeping.app.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr

/**
 * 账单页右下角的「+」：点一下是手动新建账单，按住满 1 秒说话记账。
 *
 * 时序（时间常量都在 [QuickEntryRule]，改那里就是改这里）：
 * - 按下：图标立刻从「+」换成麦克风，进度弧从 0 开始，在 1000ms 内正好扫满一圈；
 * - 500ms：轻震一次（TextHandleMove），提示「再坚持一下」；
 * - 1000ms：重震一次（LongPress），进度弧满格，表示已经「上膛」；
 * - 松手（按住 ≥1s）：回调 [onVoiceStart]，本次按住只触发一次；
 * - 提前松手（<1s）：取消计时、进度弧回弹，按普通点击回调 [onTap]；
 * - 手指滑出超过两倍触摸阈值：整段放弃，既不录音也不算点击；
 * - 系统取消（来电、多指抢占等）：同上，只收弧不触发。
 *
 * 进度弧画在 FAB 外侧，整体（含外圈）宽度比 FAB 直径大 12dp，
 * 仍在 Scaffold 为 FAB 留的 16dp 边距之内，所以不会被裁掉。
 */
@Composable
fun VoiceFab(
    onTap: () -> Unit,
    onVoiceStart: () -> Unit,
    modifier: Modifier = Modifier,
    fabSize: Dp = 56.dp,
    ringWidth: Dp = 2.5.dp,
    ringGap: Dp = 3.dp,
    // 录音会话进行中由调用方置 false：按压手势和读屏动作一起失效，避免同一时刻开两个会话。
    enabled: Boolean = true,
) {
    val haptics = rememberUpdatedState(LocalHapticFeedback.current)
    val tapAction = rememberUpdatedState(onTap)
    val voiceAction = rememberUpdatedState(onVoiceStart)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val description = tr("新建账单，长按说话记账")
    val tapLabel = tr("新建账单")
    val voiceLabel = tr("语音记账")

    var pressing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressing) 0.94f else 1f,
        label = "voiceFabPressScale",
    )

    // 计时与进度弧：用真实时钟推进，掉帧也不会把「按满 1 秒」拖成更久。
    // 满 1 秒只做重震 + 满格，不在这里触发：真正开始说话是在松手那一刻。
    LaunchedEffect(pressing) {
        if (!pressing) {
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
        var armed = false
        while (true) {
            withFrameNanos { }
            // 松手后立即退出，避免松手与满 1 秒撞在同一帧时还多推一帧进度。
            if (!pressing) return@LaunchedEffect
            val elapsed = SystemClock.uptimeMillis() - startMs
            progress = QuickEntryRule.ringProgress(elapsed)
            if (!ticked && QuickEntryRule.shouldTick(elapsed)) {
                ticked = true
                haptics.value.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            if (!armed && QuickEntryRule.isLongEnough(elapsed)) {
                armed = true
                progress = 1f
                haptics.value.performHapticFeedback(HapticFeedbackType.LongPress)
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
                .alpha(if (enabled) 1f else 0.5f)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(primary)
                .pointerInput(enabled) {
                    // 面板开着（会话进行中）时整段手势不装：按住既不录音也不算点击。
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startMs = SystemClock.uptimeMillis()
                        val downPosition = down.position
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
                                    // 手指已经滑出 FAB：整段放弃，不能把划走当成录音。
                                    cancelled = true
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            // 抬手、滑出、被系统取消都要停表：不能留下一条还在走的进度弧。
                            pressing = false
                        }
                        val elapsed = SystemClock.uptimeMillis() - startMs
                        when {
                            cancelled -> Unit
                            // 松手触发：按住够 1 秒才算语音，否则当普通点击。
                            QuickEntryRule.isLongEnough(elapsed) -> voiceAction.value()
                            else -> tapAction.value()
                        }
                    }
                }
                // TalkBack：读屏用户长按走 onLongClick 动作，不依赖计时手势。
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    if (enabled) {
                        onClick(label = tapLabel) {
                            tapAction.value()
                            true
                        }
                        onLongClick(label = voiceLabel) {
                            voiceAction.value()
                            true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // 按住时图标换成麦克风：一眼就能看出「现在是说话，不是新建」。
                imageVector = if (pressing) Icons.Filled.Mic else Icons.Filled.Add,
                contentDescription = null,
                tint = onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
