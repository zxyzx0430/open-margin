package com.bookkeeping.app.ui.screen.subscriptions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionDetailSheet(
    subscription: SubscriptionEntity,
    historyStats: SubscriptionHistoryStats,
    fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
    isPaidInCurrentCycle: Boolean? = null,
    onDismiss: () -> Unit,
    onToggleAutoRenew: () -> Unit,
    onTogglePendingCancel: () -> Unit,
    onToggleNotify: () -> Unit,
    onUpdateNotifyDaysBefore: (Int) -> Unit,
    onAdvanceToNextCycle: () -> Unit,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
    onReactivate: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cnyCents = remember(subscription, fxRates) {
        SubscriptionProjection.estimateCnyCents(subscription, fxRates)
    }
    val isForeign = !subscription.currency.equals("CNY", ignoreCase = true)
    val dueDateLabel = remember(subscription.nextDueMs) {
        SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(subscription.nextDueMs))
    }
    val countdownText = remember(subscription.nextDueMs, subscription.active) {
        subscriptionDueCountdownLabel(subscription)
    }
    val daysUntilDue = remember(subscription.nextDueMs) {
        SubscriptionProjection.daysUntilDue(subscription.nextDueMs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = QingSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top summary header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                SubscriptionBadgeAvatar(
                    name = subscription.name,
                    active = subscription.active,
                    size = 48.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = subscription.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = QingText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SoftBadge(
                            text = countdownText,
                            tone = when {
                                !subscription.active -> StatusTone.NEUTRAL
                                subscription.pendingCancel -> StatusTone.WARNING
                                daysUntilDue <= 3 -> StatusTone.WARNING
                                else -> StatusTone.POSITIVE
                            },
                        )
                        if (subscription.pendingCancel) {
                            SoftBadge(text = "待取消", tone = StatusTone.WARNING)
                        }
                        if (!subscription.autoRenew) {
                            SoftBadge(text = "手动续费", tone = StatusTone.NEUTRAL)
                        }
                        if (isPaidInCurrentCycle == true) {
                            SoftBadge(text = "✓ 本期已入账", tone = StatusTone.POSITIVE)
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = tr("关闭"),
                        tint = QingTextSecondary,
                    )
                }
            }

            // Amount + cycle + matched history card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = QingPrimaryContainer.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, QingPrimary.copy(alpha = 0.14f)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = formatCny(cnyCents),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                fontWeight = FontWeight.Bold,
                                color = QingPrimaryDeep,
                            )
                            if (isForeign) {
                                Text(
                                    text = "原价 ${formatSubscriptionOriginalCurrency(subscription.amountCents, subscription.currency)}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = QingPrimaryDeep.copy(alpha = 0.75f),
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val channelSuffix = subscription.paymentChannel
                                .trim()
                                .takeIf { it.isNotEmpty() && it != "未指定" }
                                ?.let { " · $it" }
                                ?: ""
                            Text(
                                text = "${subscriptionCadenceLabel(subscription.cadence)}$channelSuffix",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = QingPrimaryDeep,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CalendarMonth,
                                    contentDescription = null,
                                    tint = QingPrimaryDeep.copy(alpha = 0.75f),
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "下次扣款 $dueDateLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = QingPrimaryDeep.copy(alpha = 0.78f),
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = QingPrimary.copy(alpha = 0.14f))

                    Text(
                        text = "近一年已匹配 ${historyStats.matchCount} 笔 · 累计 ${formatSmartCny(historyStats.totalCnyCents)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        fontWeight = FontWeight.Medium,
                        color = QingPrimaryDeep,
                    )
                }
            }

            // Direct toggles
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = QingSurfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, QingOutline),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DetailSwitchRow(
                        title = "自动续费",
                        subtitle = if (subscription.autoRenew) {
                            "到期后自动顺延至下个周期"
                        } else {
                            "需要手动确认续费"
                        },
                        checked = subscription.autoRenew,
                        onCheckedChange = { onToggleAutoRenew() },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = QingOutline,
                    )
                    DetailSwitchRow(
                        title = "准备取消",
                        subtitle = "标记为待退订，日历标橙并在通知中着重提醒",
                        checked = subscription.pendingCancel,
                        onCheckedChange = { onTogglePendingCancel() },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = QingOutline,
                    )
                    DetailSwitchRow(
                        title = "到期提醒",
                        subtitle = if (subscription.notifyEnabled) {
                            "扣款前 ${subscription.notifyDaysBefore} 天发送本地通知"
                        } else {
                            "已关闭本地通知提醒"
                        },
                        checked = subscription.notifyEnabled,
                        onCheckedChange = { onToggleNotify() },
                    )
                    if (subscription.notifyEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    tint = QingPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "提前提醒天数",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = QingTextSecondary,
                                )
                            }
                            NotifyDaysStepper(
                                days = subscription.notifyDaysBefore,
                                onChange = onUpdateNotifyDaysBefore,
                            )
                        }
                    }
                }
            }

            if (subscription.note.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = QingSurfaceVariant.copy(alpha = 0.35f),
                ) {
                    Text(
                        text = "备注：${subscription.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            // Bottom action buttons
            if (subscription.active) {
                Button(
                    onClick = {
                        onAdvanceToNextCycle()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QingPrimary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "已扣款 · 推至下期",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, QingOutline),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(tr("编辑"))
                }

                if (subscription.active) {
                    OutlinedButton(
                        onClick = {
                            onDeactivate()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, QingOutline),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PauseCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("停用")
                    }
                } else if (onReactivate != null) {
                    OutlinedButton(
                        onClick = {
                            onReactivate()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, QingPrimary.copy(alpha = 0.45f)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayCircleOutline,
                            contentDescription = null,
                            tint = QingPrimary,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("重新启用", color = QingPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = QingText,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun NotifyDaysStepper(
    days: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(QingSurface)
                .border(1.dp, QingOutline, RoundedCornerShape(8.dp))
                .clickable(enabled = days > 0) { onChange((days - 1).coerceAtLeast(0)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Remove,
                contentDescription = "减少天数",
                tint = if (days > 0) QingText else QingTextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = "${days} 天",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = QingPrimaryDeep,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(QingSurface)
                .border(1.dp, QingOutline, RoundedCornerShape(8.dp))
                .clickable(enabled = days < 30) { onChange((days + 1).coerceAtMost(30)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "增加天数",
                tint = if (days < 30) QingText else QingTextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun SubscriptionBadgeAvatar(
    name: String,
    active: Boolean,
    size: androidx.compose.ui.unit.Dp = 42.dp,
) {
    val label = name.trim().take(1).uppercase(Locale.ROOT).ifEmpty { "订" }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(if (active) QingMintSoft else QingSurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (active) QingPrimaryDeep else QingTextSecondary,
        )
    }
}
