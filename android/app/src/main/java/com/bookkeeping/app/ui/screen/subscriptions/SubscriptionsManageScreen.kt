package com.bookkeeping.app.ui.screen.subscriptions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.subscription.RecurringCandidate
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingBackground
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.ui.theme.QingWarning
import com.bookkeeping.app.ui.theme.QingWarningContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsManageScreen(
    initialFilterPendingCancel: Boolean = false,
    onBack: () -> Unit,
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(initialFilterPendingCancel) {
        if (initialFilterPendingCancel) {
            viewModel.setFilter(SubscriptionFilter.PENDING_CANCEL)
        }
    }

    val allSubscriptions by viewModel.allSubscriptions.collectAsState()
    val filteredSubscriptions by viewModel.filteredSubscriptions.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val dirtyCount by viewModel.dirtyCount.collectAsState()
    val recurringCandidates by viewModel.recurringCandidates.collectAsState()
    val historyStatsById by viewModel.historyStatsBySubscriptionId.collectAsState()
    val fxRates by viewModel.fxRates.collectAsState()

    var detailSubscriptionId by remember { mutableStateOf<Long?>(null) }
    var editingSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var candidatePrefill by remember { mutableStateOf<RecurringCandidate?>(null) }

    val detailSubscription = remember(allSubscriptions, detailSubscriptionId) {
        detailSubscriptionId?.let { id -> allSubscriptions.firstOrNull { it.id == id } }
    }

    Scaffold(
        containerColor = QingBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "全部订阅",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("返回"),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            editingSubscription = null
                            candidatePrefill = null
                            creatingNew = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = QingPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "新建",
                            color = QingPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = QingBackground,
                    titleContentColor = QingText,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (dirtyCount > 0) {
                item(key = "dirty_sync_banner") {
                    DirtySyncBanner(dirtyCount = dirtyCount)
                }
            }

            if (recurringCandidates.isNotEmpty()) {
                item(key = "recurring_candidates_banner") {
                    RecurringCandidateBanner(
                        candidates = recurringCandidates,
                        onConvert = { candidate ->
                            editingSubscription = null
                            candidatePrefill = candidate
                            creatingNew = true
                        },
                    )
                }
            }

            item(key = "search_bar") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = QingTextSecondary,
                        )
                    },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = tr("清空搜索"),
                                    tint = QingTextSecondary,
                                )
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = {
                        Text(
                            text = "搜索订阅名称、支付渠道或备注",
                            style = MaterialTheme.typography.bodyMedium,
                            color = QingTextSecondary,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = QingSurface,
                        unfocusedContainerColor = QingSurface,
                        focusedBorderColor = QingPrimary,
                        unfocusedBorderColor = QingOutline,
                        cursorColor = QingPrimary,
                    ),
                )
            }

            item(key = "filter_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SubscriptionFilter.entries.forEach { filter ->
                        LedgerFilterChip(
                            label = filter.label,
                            selected = selectedFilter == filter,
                            onClick = { viewModel.setFilter(filter) },
                        )
                    }
                }
            }

            if (filteredSubscriptions.isEmpty()) {
                item(key = "empty_subscriptions") {
                    EmptySubscriptionsCard(
                        selectedFilter = selectedFilter,
                        hasSearchQuery = searchQuery.isNotBlank(),
                        onAddNew = {
                            editingSubscription = null
                            candidatePrefill = null
                            creatingNew = true
                        },
                    )
                }
            } else {
                items(filteredSubscriptions, key = { it.id }) { sub ->
                    SubscriptionCompactCard(
                        subscription = sub,
                        fxRates = fxRates,
                        onClick = { detailSubscriptionId = sub.id },
                    )
                }
            }

            item(key = "footer_disclaimer") {
                Text(
                    text = "订阅仅用于排期提醒，实际支出以账单流水为准，不会重复计入总支出",
                    style = MaterialTheme.typography.labelSmall,
                    color = QingTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 18.dp),
                )
            }
        }
    }

    detailSubscription?.let { sub ->
        val stats = historyStatsById[sub.id] ?: SubscriptionHistoryStats()
        SubscriptionDetailSheet(
            subscription = sub,
            historyStats = stats,
            fxRates = fxRates,
            onDismiss = { detailSubscriptionId = null },
            onToggleAutoRenew = { viewModel.toggleAutoRenew(sub.id) },
            onTogglePendingCancel = { viewModel.togglePendingCancel(sub.id) },
            onToggleNotify = { viewModel.toggleNotify(sub.id) },
            onUpdateNotifyDaysBefore = { days -> viewModel.setNotifyDaysBefore(sub.id, days) },
            onAdvanceToNextCycle = { viewModel.advanceToNextCycle(sub.id) },
            onEdit = {
                detailSubscriptionId = null
                candidatePrefill = null
                editingSubscription = sub
                creatingNew = true
            },
            onDeactivate = { viewModel.deactivate(sub.id) },
            onReactivate = { viewModel.reactivate(sub.id) },
        )
    }

    if (creatingNew) {
        EditSubscriptionSheet(
            existing = editingSubscription,
            candidatePrefill = candidatePrefill,
            fxRates = fxRates,
            onDismiss = {
                creatingNew = false
                editingSubscription = null
                candidatePrefill = null
            },
            onSave = { saved ->
                viewModel.saveSubscription(saved)
            },
            onDelete = if (editingSubscription != null) {
                { id -> viewModel.softDelete(id) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun DirtySyncBanner(dirtyCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = QingWarningContainer,
        border = BorderStroke(1.dp, QingWarning.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
                tint = QingWarning,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "$dirtyCount 项待同步，联网后自动同步",
                style = MaterialTheme.typography.labelMedium,
                color = QingWarning,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RecurringCandidateBanner(
    candidates: List<RecurringCandidate>,
    onConvert: (RecurringCandidate) -> Unit,
) {
    val top = candidates.first()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = QingPrimaryContainer),
        border = BorderStroke(1.dp, QingPrimary.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = QingPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "发现 ${candidates.size} 项疑似自动续费",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = QingPrimaryDeep,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { onConvert(top) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "转为订阅",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                candidates.take(6).forEach { candidate ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onConvert(candidate) },
                        shape = RoundedCornerShape(12.dp),
                        color = QingSurface.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, QingPrimary.copy(alpha = 0.16f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = candidate.merchant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = QingText,
                                maxLines = 1,
                            )
                            Text(
                                text = "${formatSubscriptionOriginalCurrency(candidate.amountCents, candidate.currency)}/${subscriptionCadenceLabel(candidate.cadence).removePrefix("每")}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                color = QingPrimaryDeep,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCompactCard(
    subscription: SubscriptionEntity,
    fxRates: Map<String, Double>,
    onClick: () -> Unit,
) {
    val cnyCents = remember(subscription, fxRates) {
        SubscriptionProjection.estimateCnyCents(subscription, fxRates)
    }
    val isForeign = !subscription.currency.equals("CNY", ignoreCase = true)
    val countdown = remember(subscription.nextDueMs, subscription.active) {
        subscriptionDueCountdownLabel(subscription)
    }
    val cadenceText = remember(subscription.cadence) {
        subscriptionCadenceLabel(subscription.cadence)
    }
    val channelSuffix = remember(subscription.paymentChannel) {
        subscription.paymentChannel.trim()
            .takeIf { it.isNotEmpty() && it != "未指定" }
            ?.let { " · $it" }
            ?: ""
    }
    val dueShortDate = remember(subscription.nextDueMs) {
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(subscription.nextDueMs))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (subscription.active) 1f else 0.72f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubscriptionBadgeAvatar(
                name = subscription.name,
                active = subscription.active,
                size = 42.dp,
            )

            // Middle: strict 2-line layout
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = QingText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!subscription.active) {
                        SoftBadge(text = "已停用", tone = StatusTone.NEUTRAL)
                    } else if (subscription.pendingCancel) {
                        SoftBadge(text = "待取消", tone = StatusTone.WARNING)
                    } else if (!subscription.autoRenew) {
                        SoftBadge(text = "手动", tone = StatusTone.NEUTRAL)
                    }
                }
                Text(
                    text = "$countdown · $cadenceText$channelSuffix",
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Right: fixed 2-line layout with tabular numerals
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formatCny(cnyCents),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = FontWeight.Bold,
                    color = QingText,
                    maxLines = 1,
                )
                Text(
                    text = if (isForeign) {
                        "原价 ${formatSubscriptionOriginalCurrency(subscription.amountCents, subscription.currency)}"
                    } else {
                        dueShortDate
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = QingTextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptySubscriptionsCard(
    selectedFilter: SubscriptionFilter,
    hasSearchQuery: Boolean,
    onAddNew: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    hasSearchQuery -> "没有匹配的订阅"
                    selectedFilter == SubscriptionFilter.PENDING_CANCEL -> "暂无准备取消的订阅"
                    selectedFilter == SubscriptionFilter.DUE_SOON -> "近期 7 天内没有待扣续费"
                    selectedFilter == SubscriptionFilter.INACTIVE -> "暂无已停用的订阅"
                    else -> "还没有记录任何周期订阅"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = QingText,
            )
            Text(
                text = "添加视频会员、AI 工具、话费或云服务，清晰掌握每月固定续费排期",
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
                textAlign = TextAlign.Center,
            )
            if (selectedFilter == SubscriptionFilter.ALL && !hasSearchQuery) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(
                    onClick = onAddNew,
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("添加第一条订阅")
                }
            }
        }
    }
}
