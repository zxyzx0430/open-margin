package com.bookkeeping.app.ui.screen.subscriptions

import com.bookkeeping.app.ui.theme.qingPressFeedback

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.theme.QingMotionTokens
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.WalletSubscriptionPalette
import com.bookkeeping.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        enabled = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

internal fun walletDeckOrder(ids: List<Long>, selected: Long?): List<Long> {
    val shown = ids.take(3)
    return if (selected in shown) shown.filterNot { it == selected } + listOfNotNull(selected) else shown
}

/** The deck is a three-card preview; the complete management list remains below. */
@Composable
internal fun WalletCardDeck(subscriptions: List<SubscriptionEntity>, onOpenDetail: (Long) -> Unit) {
    val cards = subscriptions.take(3)
    if (cards.isEmpty()) return
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val order = walletDeckOrder(cards.map { it.id }, selected)
    val reduced = rememberReduceMotion()
    val density = LocalDensity.current
    val step = with(density) { 52.dp.toPx() }
    val maxSpread = with(density) { 20.dp.toPx() }
    var spread by remember { mutableFloatStateOf(0f) }
    val spring = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }
    val drag = rememberDraggableState { delta ->
        spread = (spread + delta * 0.35f).coerceIn(-maxSpread, maxSpread)
    }
    Box(
        Modifier.fillMaxWidth().height(176.dp + 52.dp * (cards.size - 1))
            .draggable(drag, Orientation.Vertical, enabled = cards.size > 1 && !reduced,
                onDragStarted = { settle?.cancel(); spring.stop() },
                onDragStopped = { velocity ->
                    settle = scope.launch {
                        spring.snapTo(spread)
                        spring.animateTo(0f, QingMotionTokens.motionGesture.spec(false), initialVelocity = velocity * 0.35f) { spread = value }
                    }
                }),
    ) {
        cards.forEachIndexed { paletteIndex, subscription ->
            key(subscription.id) {
                val position = order.indexOf(subscription.id)
                val top = position == order.lastIndex
                val y by animateFloatAsState(position * step, QingMotionTokens.motionGesture.spec(reduced), label = "walletDeckPosition")
                Box(
                    Modifier.fillMaxWidth().height(176.dp).zIndex(position.toFloat())
                        .graphicsLayer { translationY = y + spread * position / (cards.size - 1).coerceAtLeast(1) }
                ) {
                    WalletSubscriptionCard(subscription, WalletSubscriptionPalette[paletteIndex % WalletSubscriptionPalette.size]) {
                        if (top) onOpenDetail(subscription.id) else selected = subscription.id
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletSubscriptionCard(subscription: SubscriptionEntity, color: Color, onClick: () -> Unit) {
    val radius = QingRadiusTokens.radiusHero
    val shape = RoundedCornerShape(radius)
    val white = Color.White
    val secondary = white.copy(alpha = 0.92f)
    val cadence = tr(subscriptionCadenceLabel(subscription.cadence))
    val days = SubscriptionProjection.daysUntilDue(subscription.nextDueMs, System.currentTimeMillis())
    val countdown = when {
        days < 0 -> tr("已逾期")
        days == 0 -> tr("今天扣款")
        else -> tr("%d 天后扣款").format(days)
    }
    Column(
        Modifier.fillMaxSize().qingPressFeedback(QingMotionTokens.cardPressedScale).clip(shape).background(color)
            .drawWithContent {
                drawContent()
                val stroke = 0.5.dp.toPx()
                clipRect(bottom = radius.toPx()) {
                    drawRoundRect(white.copy(alpha = 0.10f), topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius.toPx()), style = Stroke(stroke))
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubscriptionBadgeAvatar(subscription.name, subscription.active, 28.dp)
            Text(subscription.name, Modifier.weight(1f), color = white, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AutoSizeAmountText(text = formatSubscriptionOriginalCurrency(subscription.amountCents, subscription.currency),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", fontSize = 16.sp),
                modifier = Modifier.widthIn(max = 116.dp), minimumSizeSp = 14f, color = white, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(5.dp))
        Text(countdown, color = white, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(tr("支付方式"), color = secondary, fontSize = 12.sp)
                Text(subscription.paymentChannel.ifBlank { tr("未指定") }, color = white, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f)) {
                Text(tr("账单周期"), color = secondary, fontSize = 12.sp)
                Text(cadence, color = white, fontSize = 14.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
