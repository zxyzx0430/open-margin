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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.shadow
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
import java.text.DateFormat
import java.util.Date
import com.bookkeeping.app.i18n.AppLanguage

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
    val shown = ids.distinct().take(WALLET_DECK_MAX)
    return if (selected in shown) shown.filterNot { it == selected } + listOfNotNull(selected) else shown
}

internal const val WALLET_DECK_MAX = 6

/** Null is an unconfigured deck; an explicit empty list stays empty. */
internal fun walletDeckMembership(available: List<Long>, saved: List<Long>?): List<Long> =
    (saved ?: available.take(3)).distinct().filter { it in available }.take(WALLET_DECK_MAX)

internal fun walletDeckAdd(ids: List<Long>, id: Long): List<Long> =
    if (id in ids || ids.size >= WALLET_DECK_MAX) ids else ids + id

internal fun walletDeckReplace(ids: List<Long>, oldId: Long, newId: Long): List<Long> =
    if (oldId !in ids || newId in ids) ids else ids.map { if (it == oldId) newId else it }

/** A user-configurable preview; the complete management list remains below. */
@Composable
internal fun WalletCardDeck(subscriptions: List<SubscriptionEntity>, onOpenDetail: (Long) -> Unit) {
    val cards = subscriptions.take(WALLET_DECK_MAX)
    if (cards.isEmpty()) return
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(cards.map { it.id }) { if (selected !in cards.map { it.id }) selected = null }
    val order = walletDeckOrder(cards.map { it.id }, selected)
    val reduced = rememberReduceMotion()
    val density = LocalDensity.current
    val reveal = if (cards.size > 4) 48.dp else 56.dp
    val step = with(density) { reveal.toPx() }
    val maxSpread = with(density) { 20.dp.toPx() }
    var spread by remember { mutableFloatStateOf(0f) }
    val spring = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }
    val drag = rememberDraggableState { delta ->
        spread = (spread + delta * 0.35f).coerceIn(-maxSpread, maxSpread)
    }
    Box(
        Modifier.fillMaxWidth().height(184.dp + reveal * (cards.size - 1) + 20.dp),
    ) {
        cards.forEachIndexed { paletteIndex, subscription ->
            key(subscription.id) {
                val position = order.indexOf(subscription.id)
                val top = position == order.lastIndex
                val y by animateFloatAsState(position * step, QingMotionTokens.motionGesture.spec(reduced), label = "walletDeckPosition")
                Box(
                    Modifier.fillMaxWidth().height(184.dp).zIndex(position.toFloat())
                        .graphicsLayer { translationY = y + spread * position / (cards.size - 1).coerceAtLeast(1) }
                ) {
                    val deckDrag = if (top && cards.size > 1 && !reduced) Modifier.draggable(
                        drag, Orientation.Vertical,
                        onDragStarted = { settle?.cancel(); spring.stop() },
                        onDragStopped = { velocity ->
                            settle = scope.launch {
                                spring.snapTo(spread)
                                spring.animateTo(0f, QingMotionTokens.motionGesture.spec(false), initialVelocity = (velocity * 0.35f).coerceIn(-maxSpread * 10f, maxSpread * 10f)) { spread = value.coerceIn(-maxSpread, maxSpread) }
                            }
                        }) else Modifier
                    WalletSubscriptionCard(subscription, WalletSubscriptionPalette[paletteIndex % WalletSubscriptionPalette.size], top, deckDrag) {
                        if (top) onOpenDetail(subscription.id) else selected = subscription.id
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletSubscriptionCard(subscription: SubscriptionEntity, color: Color, expanded: Boolean, deckDrag: Modifier, onClick: () -> Unit) {
    val radius = QingRadiusTokens.radiusHero
    val shape = RoundedCornerShape(radius)
    val white = Color.White
    val secondary = white.copy(alpha = 0.92f)
    val cadence = tr(subscriptionCadenceLabel(subscription.cadence))
    val amount = formatSubscriptionOriginalCurrency(subscription.amountCents, subscription.currency)
    val date = remember(subscription.nextDueMs, AppLanguage.current()) {
        java.text.SimpleDateFormat(
            if (AppLanguage.current() == AppLanguage.ENGLISH) "MMM d" else "M月d日",
            if (AppLanguage.current() == AppLanguage.ENGLISH) java.util.Locale.ENGLISH else java.util.Locale.CHINA)
            .format(Date(subscription.nextDueMs))
    }
    val days = SubscriptionProjection.daysUntilDue(subscription.nextDueMs, System.currentTimeMillis())
    val countdown = when {
        days < 0 -> tr("已逾期")
        days == 0 -> tr("今天扣款")
        days == 1 -> tr("明天扣款")
        else -> tr("%d 天后扣款").format(days)
    }
    val material = remember(color) {
        Brush.linearGradient(
            0f to lerp(color, white, 0.18f),
            0.38f to lerp(color, white, 0.05f),
            0.72f to color,
            1f to lerp(color, Color.Black, 0.12f),
        )
    }
    Column(
        Modifier.fillMaxSize().then(deckDrag).qingPressFeedback(QingMotionTokens.cardPressedScale)
            .shadow(if (expanded) 5.dp else 3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.20f))
            .clip(shape).background(material)
            .drawWithContent {
                drawContent()
                val stroke = 0.5.dp.toPx()
                clipRect(bottom = radius.toPx()) {
                    drawRoundRect(white.copy(alpha = 0.14f), topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius.toPx()), style = Stroke(stroke))
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(if (expanded) 56.dp else 52.dp).padding(top = if (expanded) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SubscriptionBadgeAvatar(subscription.name, subscription.active, 30.dp, wallet = true)
            Column(Modifier.weight(1f)) {
                Text(subscription.name, color = white, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (expanded) Text(cadence, color = secondary, fontSize = 12.sp, maxLines = 1)
            }
            if (!expanded) {
                Column(horizontalAlignment = Alignment.End) {
                    AutoSizeAmountText(text = amount,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", fontSize = 16.sp),
                        modifier = Modifier.widthIn(max = 126.dp), minimumSizeSp = 14f, color = white, fontWeight = FontWeight.SemiBold)
                    Text(cadence, color = secondary, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    AutoSizeAmountText(text = amount,
                        style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum",
                            fontSize = 34.sp, letterSpacing = (-0.65).sp),
                        modifier = Modifier.fillMaxWidth(), minimumSizeSp = 18f, color = white, fontWeight = FontWeight.Bold)
                    if (subscription.paymentChannel.isNotBlank() && subscription.paymentChannel != "未指定")
                        Text(subscription.paymentChannel, color = secondary, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.widthIn(max = 112.dp), horizontalAlignment = Alignment.End) {
                    Text(tr("下次扣款"), color = secondary, fontSize = 12.sp)
                    Text(date, color = white, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(countdown, color = secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
