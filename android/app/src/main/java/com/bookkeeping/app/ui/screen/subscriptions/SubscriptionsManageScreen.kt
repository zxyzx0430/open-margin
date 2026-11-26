package com.bookkeeping.app.ui.screen.subscriptions

import com.bookkeeping.app.ui.theme.qingColors
import com.bookkeeping.app.ui.components.QingSheetGrabber
import androidx.compose.foundation.lazy.rememberLazyListState
import com.bookkeeping.app.ui.components.QingLargeTitle
import com.bookkeeping.app.ui.components.QingSegmentedControl

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import com.bookkeeping.app.ui.components.WalletCardSurface
import com.bookkeeping.app.ui.theme.QingRadiusTokens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.subscription.RecurringCandidate
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingBackground
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.ui.theme.QingWarning
import com.bookkeeping.app.ui.theme.QingWarningContainer
import kotlinx.coroutines.launch
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
    var showCandidatesSheet by remember { mutableStateOf(false) }

    val detailSubscription = remember(allSubscriptions, detailSubscriptionId) {
        detailSubscriptionId?.let { id -> allSubscriptions.firstOrNull { it.id == id } }
    }

    var walletPreferred by rememberSaveable { mutableStateOf(true) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val searchRequester = remember { FocusRequester() }
    val revealSearch = searchVisible || searchQuery.isNotBlank()
    val talkBack = rememberTouchExploration()
    val walletAvailable = !talkBack && LocalDensity.current.fontScale <= 1.3f
    val showWallet = walletPreferred && walletAvailable
    val activeSubs = remember(allSubscriptions) { allSubscriptions.filter { it.active } }
    val monthlyFixedCents = remember(activeSubs, fxRates) {
        activeSubs.sumOf { SubscriptionProjection.monthlyEquivalentCnyCents(it, fxRates) }
    }
    val dueSoonCount = remember(activeSubs) {
        val nowMs = System.currentTimeMillis()
        activeSubs.count { SubscriptionProjection.daysUntilDue(it.nextDueMs, nowMs) <= 7 }
    }

    val deckPreferences = LocalContext.current.applicationContext.getSharedPreferences("subscription_wallet_deck", android.content.Context.MODE_PRIVATE)
    var savedDeck by remember(deckPreferences) {
        mutableStateOf(deckPreferences.getString("ids", null)?.split(",")?.mapNotNull { it.toLongOrNull() })
    }
    val deckAvailable = remember(activeSubs) { activeSubs.sortedBy { it.nextDueMs } }
    val deckIds = walletDeckMembership(deckAvailable.map { it.id }, savedDeck)
    val deckCards = deckIds.mapNotNull { id -> deckAvailable.firstOrNull { it.id == id } }
    var manageDeck by rememberSaveable { mutableStateOf(false) }
    fun saveDeck(ids: List<Long>) {
        savedDeck = ids
        deckPreferences.edit().putString("ids", ids.joinToString(",")).apply()
    }
    val subscriptionsListState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun createSubscription() {
        editingSubscription = null
        candidatePrefill = null
        creatingNew = true
    }
    Scaffold(
        containerColor = QingBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            QingLargeTitle(tr("全部订阅"), subscriptionsListState, hasLeading = true, actionWidth = 96,
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("返回"))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchVisible = !revealSearch
                        if (revealSearch) viewModel.setSearchQuery("")
                        else scope.launch { subscriptionsListState.scrollToItem(0) }
                    }) {
                        Icon(if (revealSearch) Icons.Filled.Close else Icons.Outlined.Search,
                            tr(if (revealSearch) "关闭搜索" else "搜索订阅"))
                    }
                    IconButton(onClick = { walletPreferred = !walletPreferred }, enabled = walletAvailable) {
                        Icon(if (showWallet) Icons.AutoMirrored.Outlined.List else Icons.Outlined.ViewAgenda,
                            tr(if (showWallet) "切换列表" else "切换卡包"))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = subscriptionsListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary_card") {
                Column(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("月均 %s · %d 项在用").format(formatCny(monthlyFixedCents), activeSubs.size),
                        style = MaterialTheme.typography.bodySmall, color = QingTextSecondary)
                    if (dirtyCount > 0 || dueSoonCount > 0) {
                        val status = listOfNotNull(
                            if (dueSoonCount > 0) tr("%d 项即将到期").format(dueSoonCount) else null,
                            if (dirtyCount > 0) tr("%d 项待同步，联网后自动同步").format(dirtyCount) else null
                        ).joinToString(" · ")
                        Text(status, style = MaterialTheme.typography.labelSmall, color = QingTextSecondary)
                    }
                }
            }
            if (revealSearch) {
            item(key = "search_bar") {
                LaunchedEffect(searchVisible) { if (searchVisible) searchRequester.requestFocus() }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(QingRadiusTokens.radiusControl),
                    color = QingSurface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = QingTextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = tr("搜索订阅名称、支付渠道或备注"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = QingTextSecondary,
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = viewModel::setSearchQuery,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = QingText),
                                cursorBrush = SolidColor(QingPrimary),
                                modifier = Modifier.fillMaxWidth().focusRequester(searchRequester),
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = tr("清空搜索"),
                                tint = QingTextSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { viewModel.setSearchQuery("") },
                            )
                        }
                    }
                }
            }
            }
            if (showWallet && !revealSearch && activeSubs.isNotEmpty()) {
                item(key = "wallet_deck") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("卡包"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = QingTextSecondary)
                        TextButton(onClick = { manageDeck = true }) {
                            Text(tr("管理卡片") + " " + deckIds.size + "/" + WALLET_DECK_MAX)
                        }
                    }
                    if (deckCards.isEmpty()) {
                        TextButton(onClick = { manageDeck = true }) { Text(tr("添加卡片")) }
                    } else {
                        WalletCardDeck(deckCards) { detailSubscriptionId = it }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            item(key = "list_header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("所有订阅"), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = QingText)
                    IconButton(onClick = { createSubscription() }) {
                        Icon(Icons.Filled.Add, tr("新建订阅"), tint = QingPrimary)
                    }
                }
            }
            item(key = "filter_chips") {
                val filters = SubscriptionFilter.entries
                QingSegmentedControl(filters.map { tr(it.label) }, filters.indexOf(selectedFilter),
                    { viewModel.setFilter(filters[it]) }, Modifier.fillMaxWidth())
            }
            if (recurringCandidates.isNotEmpty()) {
                item(key = "recurring_candidates_pill") {
                    TextButton(onClick = { showCandidatesSheet = true }) {
                        Text(tr("%d 项待识别订阅").format(recurringCandidates.size))
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
                val groups = filteredSubscriptions.groupBy {
                    when {
                        !it.active -> "paused"
                        SubscriptionProjection.daysUntilDue(it.nextDueMs, System.currentTimeMillis()) <= 7 -> "due"
                        else -> "active"
                    }
                }
                listOf("active", "due", "paused").forEach { group ->
                    val subscriptions = groups[group].orEmpty()
                    if (subscriptions.isNotEmpty()) item(key = "wallet_group_$group") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when(group) {
                                "active" -> tr("活跃订阅")
                                "due" -> tr("即将到期")
                                else -> tr("已暂停")
                            }, style = MaterialTheme.typography.labelMedium, color = QingTextSecondary)
                            WalletCardSurface(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    subscriptions.forEachIndexed { index, sub ->
                                        if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp), thickness = 0.5.dp, color = QingOutline)
                                        SubscriptionCompactCard(sub, fxRates) { detailSubscriptionId = sub.id }
                                    }
                                }
                            }
                        }
                    }
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
                        .padding(top = 4.dp, bottom = 12.dp),
                )
            }
        }
    }

    if (manageDeck) {
        WalletDeckManageSheet(deckAvailable, deckIds, onChange = ::saveDeck, onDismiss = { manageDeck = false })
    }
    if (showCandidatesSheet) {
        RecurringCandidatesSheet(
            candidates = recurringCandidates,
            onDismiss = { showCandidatesSheet = false },
            onDismissCandidate = { candidate ->
                val key = candidate.candidateKey.ifBlank {
                    com.bookkeeping.app.data.subscription.RecurringCandidateDetector.candidateKey(
                        candidate.merchant,
                        candidate.currency,
                        candidate.amountCents,
                    )
                }
                viewModel.dismissCandidate(key)
            },
            onConvert = { candidate ->
                showCandidatesSheet = false
                editingSubscription = null
                candidatePrefill = candidate
                creatingNew = true
            },
        )
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
            onDelete = { viewModel.softDelete(sub.id) },
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
private fun RecurringCandidateEntryPill(
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = QingSurface,
        border = BorderStroke(1.dp, QingPrimary.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                text = "发现 $count 项疑似自动续费",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = QingText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看详情",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = QingPrimary,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = QingPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringCandidatesSheet(
    candidates: List<RecurringCandidate>,
    onDismiss: () -> Unit,
    onDismissCandidate: (RecurringCandidate) -> Unit,
    onConvert: (RecurringCandidate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFmt = remember { SimpleDateFormat("M月d日", Locale.CHINA) }

    ModalBottomSheet(
        dragHandle = { QingSheetGrabber() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = qingColors.surfaceSheet,
        shape = RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "疑似自动续费",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = QingText,
                    )
                    Text(
                        text = "核对匹配的历史扣款日期，可一键转为订阅或永久忽略",
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭"),
                        tint = QingTextSecondary,
                    )
                }
            }

            if (candidates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "已全部处理完成",
                        style = MaterialTheme.typography.bodyMedium,
                        color = QingTextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(candidates, key = { "${it.merchant}|${it.currency}|${it.amountCents}" }) { candidate ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = QingBackground,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = candidate.merchant,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = QingText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${formatSubscriptionOriginalCurrency(candidate.amountCents, candidate.currency)} / ${subscriptionCadenceLabel(candidate.cadence).removePrefix("每")}",
                                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                        color = QingPrimaryDeep,
                                    )
                                }

                                val datesText = remember(candidate.matchedTimestampsMs) {
                                    if (candidate.matchedTimestampsMs.isEmpty()) {
                                        "最近扣款：${dateFmt.format(Date(candidate.lastSeenMs))}"
                                    } else {
                                        "匹配扣款日：" + candidate.matchedTimestampsMs
                                            .takeLast(6)
                                            .joinToString(" · ") { dateFmt.format(Date(it)) }
                                    }
                                }
                                Text(
                                    text = "共命中 ${candidate.occurrences} 笔 · $datesText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = QingTextSecondary,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { onDismissCandidate(candidate) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, QingOutline),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    ) {
                                        Text(
                                            text = "忽略 / 不是订阅",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = QingTextSecondary,
                                        )
                                    }
                                    Button(
                                        onClick = { onConvert(candidate) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = QingPrimary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    ) {
                                        Text(
                                            text = "转为订阅",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
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

    Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubscriptionBadgeAvatar(
                name = subscription.name,
                active = subscription.active,
                size = 42.dp,
            )

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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = QingSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp),
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
                text = "点右下角 + 添加会员、AI 工具、话费或云服务，清晰掌握每月续费排期",
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
