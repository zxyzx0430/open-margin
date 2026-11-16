package com.bookkeeping.app.ui.screen.transactions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.CaptureConnectionState
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.sync.TransactionSyncWorker
import com.bookkeeping.app.ui.components.AutomationSetupPrompt
import com.bookkeeping.app.ui.components.CategoryFilterSheet
import com.bookkeeping.app.ui.components.CategoryFilterTotal
import com.bookkeeping.app.ui.components.DayHeader
import com.bookkeeping.app.ui.components.LedgerEmptyState
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.components.LedgerFilterRow
import com.bookkeeping.app.ui.components.LedgerSearchBar
import com.bookkeeping.app.ui.components.MerchantRuleDialog
import com.bookkeeping.app.ui.components.MonthlySummaryCard
import com.bookkeeping.app.ui.components.StatusChip
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.TransactionRow
import com.bookkeeping.app.ui.screen.billimport.BillImportDialog
import com.bookkeeping.app.ui.screen.capture.CaptureReviewCard
import com.bookkeeping.app.ui.screen.capture.CaptureReviewDialog
import com.bookkeeping.app.ui.screen.capture.CaptureReviewViewModel
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class LedgerStatus(
    val captureText: String,
    val captureTone: StatusTone,
    val captureIcon: ImageVector,
    val syncText: String,
    val syncTone: StatusTone,
    val syncIcon: ImageVector,
)

@Composable
fun TransactionListScreen(
    viewModel: TransactionViewModel = hiltViewModel(),
    captureReviewViewModel: CaptureReviewViewModel = hiltViewModel(),
    openBillImport: Boolean = false,
    onBillImportOpened: () -> Unit = {},
    onSetupAutomation: () -> Unit = {},
) {
    val transactions by viewModel.displayedTransactions.collectAsState()
    val ledgerLoaded by viewModel.ledgerLoaded.collectAsState()
    val duplicateCandidates by viewModel.duplicateCandidates.collectAsState()
    val captureReview by captureReviewViewModel.state.collectAsState()
    val periodExpense by viewModel.periodExpenseCents.collectAsState()
    val periodIncome by viewModel.periodIncomeCents.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()
    val categoryFilter by viewModel.categoryFilter.collectAsState()
    val timeFilter by viewModel.timeFilter.collectAsState()
    val periodTransactions by viewModel.periodTransactions.collectAsState()
    val hasAnyTransactions by viewModel.hasAnyTransactions.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showBillImport by remember { mutableStateOf(false) }
    var showCaptureReview by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var ruleTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var statusRefresh by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(openBillImport) {
        if (openBillImport) {
            showBillImport = true
            onBillImportOpened()
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationListenerEnabled by remember {
        mutableStateOf(NotificationListenerHelper.isEnabled(context))
    }
    var pageRecognitionEnabled by remember {
        mutableStateOf(isPageRecognitionEnabled(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationListenerEnabled = NotificationListenerHelper.isEnabled(context)
                pageRecognitionEnabled = isPageRecognitionEnabled(context)
                statusRefresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val status by produceState(
        initialValue = readLedgerStatus(context),
        context,
        statusRefresh,
    ) {
        while (true) {
            value = readLedgerStatus(context)
            captureReviewViewModel.refresh()
            delay(30_000)
        }
    }

    val noChips = searchQuery.isBlank() &&
        typeFilter == null &&
        categoryFilter == null
    val filterActive = !noChips
    val categoryTotals = remember(periodTransactions, typeFilter) {
        periodTransactions
            .filter { transaction ->
                transaction.isLedgerTotalsCandidate() &&
                    (typeFilter == null || transaction.type == typeFilter!!.name) &&
                    (typeFilter != TransactionType.EXPENSE || transaction.category != "TRANSFER")
            }
            .groupBy {
                runCatching { TransactionCategory.valueOf(it.category) }
                    .getOrDefault(TransactionCategory.OTHER)
            }
            .mapValues { (_, list) ->
                CategoryFilterTotal(
                    count = list.size,
                    amountCents = list.sumOf { it.amountCents },
                )
            }
    }
    val periodLabel = tr("全部账单")
    val duplicatesIndex = 5 +
        (if (!notificationListenerEnabled || !pageRecognitionEnabled) 1 else 0) +
        (if (captureReview.pending.isNotEmpty()) 1 else 0)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = tr("新建账单"))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tr("清衡"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        when {
                            duplicateCandidates.isNotEmpty() -> {
                                scope.launch { listState.animateScrollToItem(duplicatesIndex) }
                            }
                            captureReview.pending.isNotEmpty() -> {
                                captureReviewViewModel.refresh()
                                showCaptureReview = true
                            }
                            else -> openAppNotificationSettings(context)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsNone,
                            contentDescription = if (
                                duplicateCandidates.isNotEmpty() ||
                                captureReview.pending.isNotEmpty()
                            ) tr("待确认提醒") else tr("通知设置"),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { showBillImport = true }) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = tr("导入账单"),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            item(key = "status") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.clickable {
                            if (!notificationListenerEnabled) {
                                NotificationListenerHelper.openSettings(context)
                            } else {
                                statusRefresh++
                            }
                        },
                    ) {
                        StatusChip(
                            text = status.captureText,
                            tone = status.captureTone,
                            icon = status.captureIcon,
                        )
                    }
                    StatusChip(
                        text = status.syncText,
                        tone = status.syncTone,
                        icon = status.syncIcon,
                    )
                    if (duplicateCandidates.isNotEmpty()) {
                        Box(
                            modifier = Modifier.clickable {
                                scope.launch { listState.animateScrollToItem(duplicatesIndex) }
                            },
                        ) {
                            StatusChip(
                                text = tr("待确认重复 ") + duplicateCandidates.size,
                                tone = StatusTone.WARNING,
                                icon = Icons.Outlined.ErrorOutline,
                            )
                        }
                    }
                }
            }

            if (!notificationListenerEnabled || !pageRecognitionEnabled) {
                item(key = "notification_banner") {
                    PermissionBanner(
                        title = tr("自动记账设置未完成"),
                        body = tr("缺少通知使用权或页面识别；点击补全后，再到设置页做一次检查。"),
                        onClick = {
                            if (!notificationListenerEnabled) {
                                NotificationListenerHelper.openSettings(context)
                            } else {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                    )
                                }
                            }
                        },
                        error = true,
                    )
                }
            }

            if (captureReview.pending.isNotEmpty()) {
                item(key = "capture_review") {
                    CaptureReviewCard(
                        count = captureReview.pending.size,
                        onOpen = {
                            captureReviewViewModel.refresh()
                            showCaptureReview = true
                        },
                    )
                }
            }

            item(key = "period") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = periodLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tr("全部时间的支出与收入"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "summary") {
                MonthlySummaryCard(
                    expenseCents = periodExpense,
                    incomeCents = periodIncome,
                )
            }

            item(key = "search") {
                LedgerSearchBar(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                )
            }

            item(key = "filters") {
                LedgerFilterRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    LedgerFilterChip(
                        label = categoryFilter?.let { tr(it.displayName) } ?: tr("分类"),
                        selected = categoryFilter != null,
                        onClick = { showCategorySheet = true },
                    )
                    LedgerFilterChip(
                        label = tr("支出"),
                        selected = typeFilter == TransactionType.EXPENSE,
                        onClick = {
                            viewModel.setTypeFilter(
                                if (typeFilter == TransactionType.EXPENSE) null
                                else TransactionType.EXPENSE
                            )
                        },
                    )
                    LedgerFilterChip(
                        label = tr("收入"),
                        selected = typeFilter == TransactionType.INCOME,
                        onClick = {
                            viewModel.setTypeFilter(
                                if (typeFilter == TransactionType.INCOME) null
                                else TransactionType.INCOME
                            )
                        },
                    )
                    if (filterActive) {
                        LedgerFilterChip(
                            label = tr("清空"),
                            selected = false,
                            onClick = viewModel::clearFilters,
                        )
                    }
                }
            }

            if (duplicateCandidates.isNotEmpty()) {
                item(key = "duplicates") {
                    DuplicateReviewCard(
                        candidates = duplicateCandidates,
                        onKeepBoth = viewModel::keepBoth,
                        onDeleteNew = { pendingDelete = it },
                    )
                }
            }

            if (!ledgerLoaded) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (transactions.isEmpty()) {
                item(key = "empty") {
                    LedgerEmptyState(
                        filterActive = filterActive,
                        hasAnyTransactions = hasAnyTransactions,
                        onClearFilters = viewModel::clearFilters,
                        onShowAll = viewModel::showAll,
                    )
                }
            } else {
                val groups = transactions.groupBy { dayKey(it.timestamp) }
                items(groups.entries.toList(), key = { it.key }) { (dayKey, dayItems) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val valid = dayItems.filter {
                            it.currency == "CNY" &&
                                it.duplicateOfId == null &&
                                it.remoteReviewStatus !in setOf(
                                    "excluded",
                                    "transfer",
                                    "refund",
                                    "possible_duplicate",
                                )
                        }
                        val expense = valid
                            .filter { it.type == "EXPENSE" && it.category != "TRANSFER" }
                            .sumOf { it.amountCents }
                        val income = valid
                            .filter { it.type == "INCOME" }
                            .sumOf { it.amountCents }
                        val foreignOnly = valid.isEmpty() && dayItems.any { it.currency != "CNY" }
                        DayHeader(
                            label = dayLabel(dayKey),
                            count = valid.size,
                            expenseCents = expense,
                            incomeCents = income,
                            hasForeignOnly = foreignOnly,
                            excludedCount = dayItems.size - valid.size,
                        )
                        DayCard(
                            items = dayItems,
                            onEdit = { editing = it },
                            onDelete = { pendingDelete = it },
                            onReview = viewModel::setReviewStatus,
                            onAddCategoryRule = { ruleTarget = it },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("删除这笔交易？")) },
            text = {
                Text(
                    tr("删除将在电脑或 VPS 在线后双向同步；其他设备也会删除该笔交易。若只是误识别或退款，建议在网站标记为不计入或退款。")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry)
                    pendingDelete = null
                }) {
                    Text(tr("确认删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("取消")) }
            },
        )
    }

    editing?.let { entry ->
        AddTransactionDialog(
            initial = entry,
            onDismiss = { editing = null },
            onConfirm = { amount, type, category, merchant, note, timestampMs ->
                viewModel.updateTransaction(
                    entry,
                    amount,
                    type,
                    category,
                    merchant,
                    note,
                    timestampMs,
                )
                editing = null
            },
        )
    }

    if (showBillImport) {
        BillImportDialog(onDismiss = { showBillImport = false })
    }

    if (showCaptureReview) {
        CaptureReviewDialog(
            onDismiss = { showCaptureReview = false },
            viewModel = captureReviewViewModel,
        )
    }

    if (showAddDialog) {
        AddTransactionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { amount, type, category, merchant, note, timestampMs ->
                viewModel.addTransaction(
                    amount,
                    type,
                    category,
                    merchant,
                    note,
                    timestampMs,
                )
                showAddDialog = false
            },
        )
    }

    if (showCategorySheet) {
        CategoryFilterSheet(
            selected = categoryFilter,
            totals = categoryTotals,
            onSelect = { category ->
                viewModel.setCategoryFilter(category)
                showCategorySheet = false
            },
            onDismiss = { showCategorySheet = false },
        )
    }

    ruleTarget?.let { entry ->
        MerchantRuleDialog(
            merchant = entry.merchant,
            onDismiss = { ruleTarget = null },
            onSave = { merchant, category ->
                viewModel.saveMerchantRule(merchant, category)
                ruleTarget = null
            },
            initialCategory = runCatching {
                TransactionCategory.valueOf(entry.category)
            }.getOrNull(),
        )
    }
}

@Composable
private fun DayCard(
    items: List<TransactionEntity>,
    onEdit: (TransactionEntity) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onReview: (TransactionEntity, String) -> Unit,
    onAddCategoryRule: (TransactionEntity) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            items.forEachIndexed { index, transaction ->
                TransactionRow(
                    transaction = transaction,
                    timeText = timeOnlyFormat.format(Date(transaction.timestamp)),
                    onEdit = { onEdit(transaction) },
                    onDelete = { onDelete(transaction) },
                    onReview = { status -> onReview(transaction, status) },
                    onAddCategoryRule = { onAddCategoryRule(transaction) },
                )
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateReviewCard(
    candidates: List<TransactionViewModel.DuplicateCandidate>,
    onKeepBoth: (TransactionEntity) -> Unit,
    onDeleteNew: (TransactionEntity) -> Unit,
) {
    val semantic = LocalQingSemanticColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = semantic.warningContainer),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr("待确认重复") + "（" + candidates.size + "）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = semantic.warningText,
            )
            Text(
                text = tr("这些记录金额和时间相近，但没有足够证据自动合并。请确认每笔是否真实；确认前不计入本机统计。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidates.take(3).forEach { item ->
                val row = item.candidate
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = (if (item.original != null) tr("新记录：") else tr("待确认记录：")) +
                            transactionSummary(row),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = item.original?.let {
                            tr("疑似原记录：") + transactionSummary(it)
                        } ?: tr("原记录不可用（可能来自服务器）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onKeepBoth(row) }) {
                            Text(if (item.original != null) tr("保留两笔") else tr("确认不是重复"))
                        }
                        TextButton(onClick = { onDeleteNew(row) }) {
                            Text(
                                if (item.original != null) tr("删除新记录") else tr("删除此记录"),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (candidates.size > 3) {
                Text(
                    text = tr("还有 ") + (candidates.size - 3) + tr(" 笔待确认。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun transactionSummary(row: TransactionEntity): String {
    val merchant = row.merchant.ifBlank { row.source }
    val amount = "%.2f".format(Locale.CHINA, row.amountCents / 100.0)
    return row.currency + " " + amount + " · " + merchant + " · " +
        timeOnlyFormat.format(Date(row.timestamp))
}

@Composable
private fun PermissionBanner(
    title: String,
    body: String,
    onClick: () -> Unit,
    error: Boolean,
) {
    val semantic = LocalQingSemanticColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) semantic.errorContainer else semantic.warningContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer
                        else semantic.warningText,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun readLedgerStatus(context: Context): LedgerStatus {
    val health = CaptureHealth.snapshot(context, KeepAliveService.startedAtMs)
    val captureText: String
    val captureTone: StatusTone
    val captureIcon: ImageVector
    when (health.state) {
        CaptureConnectionState.CONNECTED -> {
            captureText = tr("采集中")
            captureTone = StatusTone.POSITIVE
            captureIcon = Icons.Outlined.Bolt
        }
        CaptureConnectionState.PERMISSION_OFF -> {
            captureText = tr("采集未授权")
            captureTone = StatusTone.ERROR
            captureIcon = Icons.Outlined.ErrorOutline
        }
        CaptureConnectionState.DISCONNECTED -> {
            captureText = tr("采集断开")
            captureTone = StatusTone.ERROR
            captureIcon = Icons.Outlined.ErrorOutline
        }
        CaptureConnectionState.UNKNOWN -> {
            captureText = tr("采集确认中")
            captureTone = StatusTone.NEUTRAL
            captureIcon = Icons.Outlined.Sync
        }
    }

    val prefs = context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
    val syncEnabled = prefs.getBoolean("enabled", false)
    val conflicts = prefs.getInt("pending_sync_conflicts", 0)
    val lastSuccess = prefs.getLong("last_success_ms", 0L)
    val lastResult = prefs.getString("last_result", "").orEmpty()
    val syncFailed = lastResult.contains("失败") ||
        lastResult.contains("异常") ||
        lastResult.contains("不可用") ||
        lastResult.contains("401") ||
        lastResult.contains("403")

    val syncText: String
    val syncTone: StatusTone
    val syncIcon: ImageVector
    when {
        !syncEnabled -> {
            syncText = tr("同步未开启")
            syncTone = StatusTone.NEUTRAL
            syncIcon = Icons.Outlined.Sync
        }
        conflicts > 0 -> {
            syncText = tr("同步待处理 ") + conflicts
            syncTone = StatusTone.WARNING
            syncIcon = Icons.Outlined.ErrorOutline
        }
        lastSuccess > 0L && !syncFailed -> {
            syncText = tr("同步正常")
            syncTone = StatusTone.POSITIVE
            syncIcon = Icons.Outlined.CheckCircle
        }
        else -> {
            syncText = tr("同步异常")
            syncTone = StatusTone.WARNING
            syncIcon = Icons.Outlined.ErrorOutline
        }
    }

    return LedgerStatus(
        captureText = captureText,
        captureTone = captureTone,
        captureIcon = captureIcon,
        syncText = syncText,
        syncTone = syncTone,
        syncIcon = syncIcon,
    )
}

private const val PAGE_RECOGNITION_COMPONENT =
    "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

private fun isPageRecognitionEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/$PAGE_RECOGNITION_COMPONENT"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

// === 时间分组辅助 ===

private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val timeOnlyFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val monthDayFormat = SimpleDateFormat("MM月dd日", Locale.CHINA)
private val englishMonthDayFormat = SimpleDateFormat("MMM d", Locale.ENGLISH)

private fun dayKey(timestampMs: Long): String = dayKeyFormat.format(Date(timestampMs))

private fun dayLabel(dayKey: String): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayKey = dayKeyFormat.format(today.time)
    val yesterdayKey = dayKeyFormat.format(Date(today.timeInMillis - 86_400_000L))
    return when (dayKey) {
        todayKey -> tr("今天")
        yesterdayKey -> tr("昨天")
        else -> {
            runCatching {
                val date = dayKeyFormat.parse(dayKey)!!
                if (AppLanguage.current() == AppLanguage.ENGLISH) {
                    englishMonthDayFormat.format(date)
                } else {
                    monthDayFormat.format(date)
                }
            }.getOrDefault(dayKey)
        }
    }
}
