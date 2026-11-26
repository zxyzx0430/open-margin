package com.bookkeeping.app.ui.screen.subscriptions

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.theme.QingMotionTokens
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.WalletSubscriptionPalette
import com.bookkeeping.app.ui.theme.qingPressFeedback
import com.bookkeeping.app.ui.theme.rememberReduceMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/**
 * Subfolio-style in-place accordion card deck.
 * - Default expanded card is the bottommost card.
 * - Tapping a collapsed card expands it in place, smoothly pushing cards below down.
 * - Tapping the already-expanded card opens its detail sheet.
 * - Long-pressing and dragging vertically reorders cards in the deck.
 */
@Composable
internal fun WalletCardDeck(
    subscriptions: List<SubscriptionEntity>,
    onReorder: (List<Long>) -> Unit = {},
    onOpenDetail: (Long) -> Unit,
) {
    val cards = subscriptions.take(WALLET_DECK_MAX)
    if (cards.isEmpty()) return

    val byId = remember(cards) { cards.associateBy { it.id } }
    var localOrder by remember(cards.map { it.id }) { mutableStateOf(cards.map { it.id }) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(localOrder) {
        if (expandedId !in localOrder) expandedId = localOrder.lastOrNull()
    }
    val activeExpandedId = expandedId?.takeIf { it in localOrder } ?: localOrder.lastOrNull()
    val expandedIndex = localOrder.indexOf(activeExpandedId).coerceAtLeast(0)

    val reduced = rememberReduceMotion()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val reveal = 54.dp
    val expandedHeight = 186.dp
    val revealPx = with(density) { reveal.toPx() }
    val expandedPx = with(density) { expandedHeight.toPx() }
    val maxCardY = with(density) { (reveal * (localOrder.size - 1).coerceAtLeast(0)).toPx() }

    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(expandedHeight + reveal * (localOrder.size - 1).coerceAtLeast(0) + 12.dp),
    ) {
        localOrder.forEachIndexed { index, subId ->
            val subscription = byId[subId] ?: return@forEachIndexed
            key(subId) {
                val isExpanded = subId == activeExpandedId
                val isDragging = draggingId == subId
                val targetY = index * revealPx +
                    if (index > expandedIndex) (expandedPx - revealPx) else 0f
                val animatedY by animateFloatAsState(
                    targetValue = targetY,
                    animationSpec = QingMotionTokens.motionGesture.spec(reduced),
                    label = "walletAccordionY",
                )
                val yOffset = if (isDragging) {
                    (dragStartY + dragDeltaY).coerceIn(0f, maxCardY + (expandedPx - revealPx))
                } else {
                    animatedY
                }
                val dragScale by animateFloatAsState(
                    targetValue = if (isDragging) 1.03f else 1f,
                    animationSpec = QingMotionTokens.motionSnappy.spec(reduced),
                    label = "walletDragScale",
                )
                val brandColor = remember(subscription.name, index) {
                    MerchantIconCatalog.forMerchant(subscription.name)?.colorArgb?.let { Color(it) }
                        ?: WalletSubscriptionPalette[index % WalletSubscriptionPalette.size]
                }
                val reorderModifier = if (localOrder.size > 1) {
                    Modifier.pointerInput(localOrder, expandedIndex) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val idx = localOrder.indexOf(subId).coerceAtLeast(0)
                                draggingId = subId
                                dragStartY = idx * revealPx +
                                    if (idx > expandedIndex) (expandedPx - revealPx) else 0f
                                dragDeltaY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDeltaY += dragAmount.y
                                val currentY = dragStartY + dragDeltaY
                                val currentIdx = localOrder.indexOf(subId)
                                if (currentIdx != -1) {
                                    val targetIdx = ((currentY + revealPx * 0.45f) / revealPx)
                                        .toInt()
                                        .coerceIn(0, localOrder.lastIndex)
                                    if (targetIdx != currentIdx) {
                                        val mutable = localOrder.toMutableList()
                                        val moved = mutable.removeAt(currentIdx)
                                        mutable.add(targetIdx, moved)
                                        localOrder = mutable
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            },
                            onDragEnd = {
                                val finalOrder = localOrder
                                draggingId = null
                                dragDeltaY = 0f
                                onReorder(finalOrder)
                            },
                            onDragCancel = {
                                draggingId = null
                                dragDeltaY = 0f
                            },
                        )
                    }
                } else {
                    Modifier
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(expandedHeight)
                        .zIndex(if (isDragging) 100f else index.toFloat())
                        .graphicsLayer {
                            translationY = yOffset
                            scaleX = dragScale
                            scaleY = dragScale
                        }
                        .then(reorderModifier),
                ) {
                    WalletSubscriptionCard(
                        subscription = subscription,
                        color = brandColor,
                        expanded = isExpanded,
                        elevated = isDragging,
                    ) {
                        if (isExpanded) {
                            onOpenDetail(subscription.id)
                        } else {
                            expandedId = subscription.id
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletSubscriptionCard(
    subscription: SubscriptionEntity,
    color: Color,
    expanded: Boolean,
    elevated: Boolean,
    onClick: () -> Unit,
) {
    val radius = QingRadiusTokens.radiusHero
    val shape = RoundedCornerShape(radius)
    val white = Color.White
    val secondary = white.copy(alpha = 0.84f)
    val cadence = tr(subscriptionCadenceLabel(subscription.cadence))
    val amount = formatSubscriptionOriginalCurrency(subscription.amountCents, subscription.currency)
    val isEnglish = AppLanguage.current() == AppLanguage.ENGLISH
    val dueDateText = remember(subscription.nextDueMs, isEnglish) {
        SimpleDateFormat("yyyy.MM.dd", if (isEnglish) Locale.ENGLISH else Locale.CHINA)
            .format(Date(subscription.nextDueMs))
    }
    val startDateText = remember(subscription.startMs, subscription.nextDueMs, isEnglish) {
        SimpleDateFormat("yyyy.MM.dd", if (isEnglish) Locale.ENGLISH else Locale.CHINA)
            .format(Date(subscription.startMs ?: subscription.nextDueMs))
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
            0f to lerp(color, white, 0.16f),
            0.46f to color,
            1f to lerp(color, Color.Black, 0.24f),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .qingPressFeedback(QingMotionTokens.cardPressedScale)
            .shadow(
                elevation = when {
                    elevated -> 14.dp
                    expanded -> 8.dp
                    else -> 4.dp
                },
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.28f),
            )
            .clip(shape)
            .background(material)
            .drawWithContent {
                // Diagonal metallic/satin sheen across the card face
                drawRect(
                    Brush.linearGradient(
                        0.0f to Color.Transparent,
                        0.38f to white.copy(alpha = 0.05f),
                        0.52f to white.copy(alpha = 0.20f),
                        0.66f to white.copy(alpha = 0.04f),
                        1.0f to Color.Transparent,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(white.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.15f),
                        radius = size.maxDimension * 0.68f,
                    ),
                )
                drawContent()
                val stroke = 0.75.dp.toPx()
                clipRect(bottom = radius.toPx()) {
                    drawRoundRect(
                        color = white.copy(alpha = 0.26f),
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius.toPx()),
                        style = Stroke(stroke),
                    )
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        // Exposed top strip (图 3 风格: 左图标 + 名称 + 周期小字，右侧留白)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SubscriptionBadgeAvatar(subscription.name, subscription.active, 32.dp, wallet = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    color = white,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = cadence,
                    color = secondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AutoSizeAmountText(
                        text = amount,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFeatureSettings = "tnum",
                            fontSize = 32.sp,
                            letterSpacing = (-0.65).sp,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        minimumSizeSp = 18f,
                        color = white,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${tr("订阅日期")} $startDateText",
                        color = secondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier.widthIn(max = 120.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(tr("下次扣款"), color = secondary, fontSize = 11.sp, maxLines = 1)
                    Text(dueDateText, color = white, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(countdown, color = secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
