package com.bookkeeping.app.ui.screen.transactions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.CaptureMergePolicy
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.CaptureConnectionState
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.sync.SyncResultCode
import com.bookkeeping.app.sync.TransactionSyncWorker
import com.bookkeeping.app.sync.readPendingCounts
import com.bookkeeping.app.ui.components.AutomationSetupPrompt
import com.bookkeeping.app.ui.components.CategoryFilterSheet
import com.bookkeeping.app.ui.components.CategoryFilterTotal
import com.bookkeeping.app.ui.components.LedgerEmptyState
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.components.LedgerFilterRow
import com.bookkeeping.app.ui.components.MerchantAvatar
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.MonthlySummaryCard
import com.bookkeeping.app.ui.components.QuickEntryHintPrefs
import com.bookkeeping.app.ui.components.QuickEntryRule
import com.bookkeeping.app.ui.components.StatusChip
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.VoiceFab
import com.bookkeeping.app.capture.screenshot.ScreenshotImportActivity
import com.bookkeeping.app.ui.components.VoiceHintBubble
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.screen.capture.CaptureReviewCard
import com.bookkeeping.app.ui.screen.capture.CaptureReviewDialog
import com.bookkeeping.app.ui.screen.capture.CaptureReviewViewModel
import com.bookkeeping.app.ui.screen.stats.StatsLedgerDrilldown
import com.bookkeeping.app.ui.screen.stats.StatsPeriod
import com.bookkeeping.app.ui.components.SiteUpdateBanner
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.update.SiteUpdateCoordinator
import com.bookkeeping.app.update.SiteUpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    onOpenBillImport: () -> Unit = {},
    drilldown: StatsLedgerDrilldown? = null,
    onDrilldownConsumed: () -> Unit = {},
    onOpenDetail: (Long) -> Unit = {},
    // 账单页「发现新版本」横幅的「查看」：跳到设置页关于与更新卡片。
    onOpenUpdateSettings: () -> Unit = {},
    // 长按 FAB 达到录音阈值的落点由外部注入：RootNavigation 立刻打开录音面板并开始录音。
    // 账单页本身不碰麦克风、不写库；点按 + 打开手动新建面板。
    onVoiceEntry: () -> Unit = {},
    // 开录后按住时滑走取消：面板和引擎都在 RootNavigation 那层。
    onVoiceCancelled: () -> Unit = {},
    // 录音会话进行中：把 FAB 按住动作禁用，避免同一时刻开两个会话。
    voiceBusy: Boolean = false,
    // 语音面板确认交付的文本。非 null 就打开新建表单并预填。
    voiceRequest: VoiceEntryRequest? = null,
    onVoiceRequestConsumed: () -> Unit = {},
) {
    val transactions by viewModel.displayedTransactions.collectAsState()
    val ledgerLoadState by viewModel.ledgerLoadState.collectAsState()
    val allTransactions by viewModel.allTransactions.collectAsState()
    val captureReview by captureReviewViewModel.state.collectAsState()
    val periodExpense by viewModel.periodExpenseCents.collectAsState()
    val periodIncome by viewModel.periodIncomeCents.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()
    val categoryFilter by viewModel.categoryFilter.collectAsState()
    val timeFilter by viewModel.timeFilter.collectAsState()
    val periodTransactions by viewModel.periodTransactions.collectAsState()
    val hasAnyTransactions by viewModel.hasAnyTransactions.collectAsState()
    val duplicateCandidates = remember(allTransactions) {
        val byId = allTransactions.associateBy { it.id }
        allTransactions
            .filter { CaptureMergePolicy.needsManualReview(it) }
            .map { candidate ->
                TransactionViewModel.DuplicateCandidate(
                    candidate = candidate,
                    original = candidate.duplicateOfId?.let(byId::get),
                )
            }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    // 新建表单顶部「一句话记账」的预填：语音 Final 或空（点 FAB 手填）。
    var addDialogQuickEntry by remember { mutableStateOf("") }
    var addDialogDiagnosticAttemptId by remember { mutableStateOf<String?>(null) }
    var showCaptureReview by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showVoiceHint by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var activeDrilldown by remember { mutableStateOf<StatsLedgerDrilldown?>(null) }
    var exactDateFilter by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var duplicateListExpanded by remember { mutableStateOf(false) }
    var statusRefresh by remember { mutableIntStateOf(0) }
    var saveRequestSequence by remember { mutableIntStateOf(0) }
    var latestFeedbackSaveSequence by remember { mutableIntStateOf(0) }
    var scrollTargetTransactionId by remember { mutableStateOf<Long?>(null) }
    var highlightedTransactionId by remember { mutableStateOf<Long?>(null) }
    var siteUpdateBanner by remember {
        mutableStateOf<SiteUpdatePolicy.BannerState?>(null)
    }
    var updateBannerRefresh by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(drilldown) {
        val request = drilldown ?: return@LaunchedEffect
        activeDrilldown = request
        viewModel.setTypeFilter(request.type)
        viewModel.setCategoryFilter(request.category)
        if (request.day != null) {
            viewModel.setMonth(request.year, request.month)
            exactDateFilter = localDayRange(request.year, request.month, request.day)
        } else {
            exactDateFilter = null
            when (request.period) {
                StatsPeriod.MONTH -> viewModel.setMonth(request.year, request.month)
                StatsPeriod.YEAR -> viewModel.setYear(request.year)
                StatsPeriod.DAY -> viewModel.setMonth(request.year, request.month)
            }
        }
        onDrilldownConsumed()
    }
    LaunchedEffect(duplicateCandidates.isEmpty()) {
        if (duplicateCandidates.isEmpty()) duplicateListExpanded = false
    }
    // 语音面板交回 Final 文本：打开新建表单并预填。文本为 null（没听清）也开表单，
    // 只是不预填——比让用户重来一次长按更快。
    LaunchedEffect(voiceRequest) {
        val request = voiceRequest ?: return@LaunchedEffect
        addDialogQuickEntry = request.text.orEmpty()
        addDialogDiagnosticAttemptId = request.diagnosticAttemptId
        showAddDialog = true
        onVoiceRequestConsumed()
    }

    val context = LocalContext.current
    // 首次进账单页的「长按 + 开始说话」气泡：看过一次就写 seen，之后不再出现。
    val dismissVoiceHint: () -> Unit = {
        if (showVoiceHint) {
            showVoiceHint = false
            QuickEntryHintPrefs.markVoiceHintSeen(context)
        }
    }
    LaunchedEffect(Unit) {
        if (!QuickEntryHintPrefs.shouldShowVoiceHint(context)) return@LaunchedEffect
        showVoiceHint = true
        delay(QuickEntryRule.HINT_VISIBLE_MS)
        if (showVoiceHint) {
            showVoiceHint = false
            QuickEntryHintPrefs.markVoiceHintSeen(context)
        }
    }
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
                updateBannerRefresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进入账单页静默检查：距上次超过 24 小时才联网，不转圈、不阻塞 UI。
    // 测试包不联网，只按「预览更新提醒」开关渲染样例横幅。
    LaunchedEffect(updateBannerRefresh, context) {
        val packageName = context.packageName
        if (!SiteUpdatePolicy.canShowBanner(packageName)) {
            siteUpdateBanner = null
            return@LaunchedEffect
        }
        if (SiteUpdatePolicy.isOfficialPackage(packageName)) {
            withContext(Dispatchers.IO) {
                SiteUpdateCoordinator.refreshIfDue(context)
            }
        }
        siteUpdateBanner = withContext(Dispatchers.IO) {
            SiteUpdateCoordinator.bannerFor(context)
        }
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
        categoryFilter == null &&
        timeFilter == LedgerTimeFilter.ALL
    val filterActive = !noChips || exactDateFilter != null
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
    val periodLabel = activeDrilldown?.let(::drilldownPeriodLabel) ?: tr("全部账单")
    val exactDateTransactions = remember(periodTransactions, exactDateFilter) {
        exactDateFilter?.let { (start, end) ->
            periodTransactions.filter { it.timestamp >= start && it.timestamp < end }
        }.orEmpty()
    }
    val visibleTransactions = remember(transactions, exactDateFilter) {
        exactDateFilter?.let { (start, end) ->
            transactions.filter { it.timestamp >= start && it.timestamp < end }
        } ?: transactions
    }
    val visibleDayGroups = remember(visibleTransactions) {
        visibleTransactions.groupBy { dayKey(it.timestamp) }.entries.toList()
    }
    val shownExpense = if (exactDateFilter == null) periodExpense else {
        exactDateTransactions.filter {
            it.isLedgerTotalsCandidate() &&
                it.type == TransactionType.EXPENSE.name &&
                it.category != TransactionCategory.TRANSFER.name
        }.sumOf { it.amountCents }
    }
    val shownIncome = if (exactDateFilter == null) periodIncome else {
        exactDateTransactions.filter {
            it.isLedgerTotalsCandidate() && it.type == TransactionType.INCOME.name
        }.sumOf { it.amountCents }
    }
    val duplicatesIndex = 5 +
        (if (!notificationListenerEnabled || !pageRecognitionEnabled) 1 else 0) +
        (if (siteUpdateBanner != null) 1 else 0) +
        (if (captureReview.pending.isNotEmpty()) 1 else 0)
    val dayGroupsStartIndex = 6 +
        (if (!notificationListenerEnabled || !pageRecognitionEnabled) 1 else 0) +
        (if (siteUpdateBanner != null) 1 else 0) +
        (if (captureReview.pending.isNotEmpty()) 1 else 0) +
        (if (duplicateCandidates.isNotEmpty()) 1 else 0)

    LaunchedEffect(scrollTargetTransactionId, visibleDayGroups, ledgerLoadState, dayGroupsStartIndex) {
        val targetId = scrollTargetTransactionId ?: return@LaunchedEffect
        if (ledgerLoadState != LedgerLoadState.READY) return@LaunchedEffect
        val groupIndex = visibleDayGroups.indexOfFirst { (_, dayItems) ->
            dayItems.any { it.id == targetId }
        }
        if (groupIndex >= 0) {
            listState.animateScrollToItem(dayGroupsStartIndex + groupIndex)
        }
    }

    // pointerInput 只会创建一次：用 rememberUpdatedState 保证按下时调用最新的收起逻辑。
    val latestDismissVoiceHint by rememberUpdatedState(dismissVoiceHint)

    Scaffold(
        // 气泡不用遮罩，但任何一次按下都收起它：Initial 阶段只观察、不消费，
        // 这一下点击照样落到下面的列表和 FAB 上。
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                latestDismissVoiceHint()
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            // 气泡压在 FAB 上方，但不能把 FAB 顶走：FAB 始终贴右，气泡往左上展开。
            Layout(
                content = {
                    VoiceFab(
                        onTap = {
                            addDialogQuickEntry = ""
                            addDialogDiagnosticAttemptId = null
                            showAddDialog = true
                        },
                        onDoubleTap = {
                            dismissVoiceHint()
                            runCatching {
                                context.startActivity(ScreenshotImportActivity.intent(context))
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context, tr("无法打开截图导入"), android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        // 达到长按阈值：立刻打开面板并开始录音（不等松手）。
                        onLongPressStart = {
                            dismissVoiceHint()
                            onVoiceEntry()
                        },
                        // 开录后仍按住时滑走：取消本次并关面板，不填表。
                        // 面板已经打开后，手指事件会被底部面板窗口接管；
                        // 此时不能再把「事件中断」当滑出取消，否则一开录就被取消。
                        onAbort = { if (!voiceBusy) onVoiceCancelled() },
                        // 录音会话进行中（面板开着）禁用新的按压：不能开第二个会话。
                        enabled = !voiceBusy,
                    )
                    if (showVoiceHint) {
                        VoiceHintBubble(
                            text = tr("点一下记一笔 · 双击导截图 · 长按说话记账"),
                            onDismiss = dismissVoiceHint,
                        )
                    }
                },
            ) { measurables, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val fab = measurables.first().measure(loose)
                val bubble = measurables.getOrNull(1)?.measure(loose)
                val gap = 8.dp.roundToPx()
                val width = maxOf(fab.width, bubble?.width ?: 0)
                val height = fab.height + (bubble?.let { it.height + gap } ?: 0)
                layout(width, height) {
                    fab.placeRelative(width - fab.width, height - fab.height)
                    bubble?.placeRelative(width - bubble.width, 0)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    IconButton(onClick = onOpenBillImport) {
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

            if (siteUpdateBanner != null) {
                item(key = "site_update_banner") {
                    val banner = siteUpdateBanner ?: return@item
                    SiteUpdateBanner(
                        versionName = banner.versionName,
                        onView = onOpenUpdateSettings,
                        onDismiss = {
                            SiteUpdateCoordinator.dismissBanner(context, banner.versionCode)
                            siteUpdateBanner = null
                        },
                    )
                }
            }

            if (captureReview.pending.isNotEmpty() || captureReview.ignored.isNotEmpty() ||
                captureReview.storageWarningKinds.isNotEmpty() || captureReview.intakeFailureNotice != null
            ) {
                item(key = "capture_review") {
                    CaptureReviewCard(
                        count = captureReview.pending.size,
                        ignoredCount = captureReview.ignored.size,
                        storageWarningKinds = captureReview.storageWarningKinds,
                        intakeFailureNotice = captureReview.intakeFailureNotice,
                        onOpen = {
                            captureReviewViewModel.refresh()
                            showCaptureReview = true
                        },
                        onAcknowledgeStorageWarning = captureReviewViewModel::acknowledgeStorageWarning,
                        onAcknowledgeIntakeFailure = captureReviewViewModel::acknowledgeIntakeFailureNotice,
                    )
                }
            }

            item(key = "period") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = periodLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (activeDrilldown != null || exactDateFilter != null) {
                        TextButton(
                            onClick = {
                                activeDrilldown = null
                                exactDateFilter = null
                                viewModel.clearFilters()
                            },
                        ) {
                            Text(tr("全部账单"))
                        }
                    }
                }
            }

            item(key = "summary") {
                if (ledgerLoadState == LedgerLoadState.READY) {
                    MonthlySummaryCard(
                        expenseCents = shownExpense,
                        incomeCents = shownIncome,
                    )
                } else {
                    LoadingMonthlySummaryCard()
                }
            }

            item(key = "search") {
                TransactionSearchBar(
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
                            onClick = {
                                activeDrilldown = null
                                exactDateFilter = null
                                viewModel.clearFilters()
                            },
                        )
                    }
                }
            }

            if (duplicateCandidates.isNotEmpty()) {
                item(key = "duplicates") {
                    DuplicateReviewCard(
                        candidates = duplicateCandidates,
                        expanded = duplicateListExpanded,
                        onToggleExpanded = { duplicateListExpanded = !duplicateListExpanded },
                        onKeepBoth = viewModel::keepBoth,
                        onDeleteNew = { pendingDelete = it },
                    )
                }
            }

            if (ledgerLoadState != LedgerLoadState.READY) {
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
            } else if (visibleTransactions.isEmpty()) {
                item(key = "empty") {
                    LedgerEmptyState(
                        filterActive = filterActive,
                        hasAnyTransactions = hasAnyTransactions,
                        onClearFilters = {
                            activeDrilldown = null
                            exactDateFilter = null
                            viewModel.clearFilters()
                        },
                        onShowAll = {
                            activeDrilldown = null
                            exactDateFilter = null
                            viewModel.showAll()
                        },
                    )
                }
            } else {
                items(visibleDayGroups, key = { it.key }) { (dayKey, dayItems) ->
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
                        TransactionDayHeader(
                            label = dayLabel(dayKey),
                            count = valid.size,
                            expenseCents = expense,
                            incomeCents = income,
                            hasForeignOnly = foreignOnly,
                            excludedCount = dayItems.size - valid.size,
                        )
                        DayCard(
                            items = dayItems,
                            onOpenDetail = onOpenDetail,
                            scrollTargetTransactionId = scrollTargetTransactionId,
                            highlightedTransactionId = highlightedTransactionId,
                            onScrollTargetReached = { id ->
                                if (scrollTargetTransactionId == id) {
                                    scrollTargetTransactionId = null
                                    highlightedTransactionId = id
                                }
                            },
                            onHighlightFinished = { id ->
                                if (highlightedTransactionId == id) {
                                    highlightedTransactionId = null
                                }
                            },
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

    if (showCaptureReview) {
        CaptureReviewDialog(
            onDismiss = { showCaptureReview = false },
            viewModel = captureReviewViewModel,
        )
    }

    if (showAddDialog) {
        AddTransactionDialog(
            onDismiss = {
                showAddDialog = false
                addDialogQuickEntry = ""
                addDialogDiagnosticAttemptId = null
            },
            initialQuickEntry = addDialogQuickEntry,
            initialDiagnosticAttemptId = addDialogDiagnosticAttemptId,
            // 手动新建和语音预填共用这一条保存路径：先查疑似重复，再入账。
            // 方向/币种/时间精度都一起传下去：不同方向的同金额、不同币种的同数字金额
            // 不是同一笔；时间精度决定同商户窗口（1 小时 / 12 小时）。
            checkDuplicate = { transaction, diagnosticInput, precision ->
                viewModel.findPossibleDuplicate(
                    transaction = transaction,
                    diagnosticInput = diagnosticInput,
                    precision = precision,
                )
            },
            onDiagnostic = { input, stage, result, details ->
                viewModel.recordDiagnostic(input, stage, result, details)
            },
            onConfirm = { amount, type, category, merchant, note, timestampMs, account, diagnosticInput ->
                viewModel.addTransaction(
                    amountCents = amount,
                    type = type,
                    category = category,
                    merchant = merchant,
                    note = note,
                    timestampMs = timestampMs,
                    account = account,
                    diagnosticInput = diagnosticInput,
                )
            },
            onSaved = { saved ->
                saveRequestSequence += 1
                val requestSequence = saveRequestSequence
                scope.launch {
                    if (requestSequence < latestFeedbackSaveSequence) return@launch
                    latestFeedbackSaveSequence = requestSequence
                    scrollTargetTransactionId = null
                    highlightedTransactionId = null
                    val inExactDateRange = exactDateFilter?.let { (start, end) ->
                        saved.timestamp >= start && saved.timestamp < end
                    } ?: true
                    if (inExactDateRange && viewModel.isVisibleWithCurrentFilters(saved)) {
                        scrollTargetTransactionId = saved.id
                    }
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(tr("已记录"))
                }
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
}

@Composable
private fun TransactionSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = QingTextSecondary,
            )
        },
        trailingIcon = if (query.isNotBlank()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
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
                text = if (AppLanguage.current() == AppLanguage.ENGLISH) {
                    tr("商户、备注或精确金额")
                } else {
                    tr("搜索商户、备注或精确金额")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = QingTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

@Composable
private fun TransactionDayHeader(
    label: String,
    count: Int,
    expenseCents: Long,
    incomeCents: Long,
    hasForeignOnly: Boolean,
    excludedCount: Int,
) {
    val semantic = LocalQingSemanticColors.current
    val countUnit = if (AppLanguage.current() == AppLanguage.ENGLISH) tr("条") else tr("笔")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "$label · $count $countUnit",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (expenseCents > 0L) {
                    DayTotalRow(
                        label = tr("支出"),
                        amountCents = expenseCents,
                        color = semantic.expenseText,
                    )
                }
                if (incomeCents > 0L) {
                    DayTotalRow(
                        label = tr("收入"),
                        amountCents = incomeCents,
                        color = semantic.income,
                    )
                }
                if (expenseCents <= 0L && incomeCents <= 0L) {
                    Text(
                        text = if (hasForeignOnly) tr("仅外币 · 未换算") else tr("—"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
            }
        }
        if (excludedCount > 0) {
            Text(
                text = "$excludedCount ${tr("未计入")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun DayTotalRow(
    label: String,
    amountCents: Long,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AutoSizeAmountText(
            text = formatCny(amountCents),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.widthIn(min = 64.dp),
            color = color,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DayCard(
    items: List<TransactionEntity>,
    onOpenDetail: (Long) -> Unit,
    scrollTargetTransactionId: Long?,
    highlightedTransactionId: Long?,
    onScrollTargetReached: (Long) -> Unit,
    onHighlightFinished: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            items.forEachIndexed { index, transaction ->
                LedgerTransactionRow(
                    transaction = transaction,
                    timeText = timeOnlyFormat.format(Date(transaction.timestamp)),
                    scrollTargetTransactionId = scrollTargetTransactionId,
                    highlightedTransactionId = highlightedTransactionId,
                    onScrollTargetReached = onScrollTargetReached,
                    onHighlightFinished = onHighlightFinished,
                    onClick = { onOpenDetail(transaction.id) },
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

/**
 * 账单列表行：整行点击进详情页，行内不再有展开箭头和动作栏。
 *
 * 排序：商户 → 金额；副行「[扫描]/[导入] 分类 · 来源 · 时间」。
 * 审核状态只显示已确认/不计入/转账/退款这类非默认值，默认状态不占字。
 */
// LedgerTransactionRow 及配套状态/来源标注已抽离至 LedgerTransactionRow.kt，供主页与截图导入结果页共用。

@Composable
private fun DuplicateReviewCard(
    candidates: List<TransactionViewModel.DuplicateCandidate>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onKeepBoth: (TransactionEntity) -> Unit,
    onDeleteNew: (TransactionEntity) -> Unit,
) {
    val semantic = LocalQingSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, semantic.warningText.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = semantic.warningContainer,
                ) {
                    Text(
                        text = "${tr("待确认重复")} · ${candidates.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = semantic.warningText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                text = tr("这些记录金额和时间相近，但没有足够证据自动合并。请确认每笔是否真实；确认前不计入本机统计。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidates.take(if (expanded) candidates.size else 3).forEach { item ->
                val row = item.candidate
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = (if (item.original != null) tr("新记录：") else tr("待确认记录：")) +
                                    transactionSummary(row),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = item.original?.let {
                                    tr("疑似原记录：") + transactionSummary(it)
                                } ?: tr("原记录不可用（可能来自服务器）"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onKeepBoth(row) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                            ) {
                                Text(
                                    text = if (item.original != null) tr("保留两笔") else tr("确认不是重复"),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                            Surface(
                                onClick = { onDeleteNew(row) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (item.original != null) tr("删除新记录") else tr("删除此记录"),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (candidates.size > 3) {
                TextButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (expanded) tr("收起")
                        else tr("展开全部") + " ${candidates.size} " + tr("条")
                    )
                }
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
private fun LoadingMonthlySummaryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(tr("支出"), tr("收入"), tr("结余")).forEach { label ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

private fun drilldownPeriodLabel(request: StatsLedgerDrilldown): String {
    val english = AppLanguage.current() == AppLanguage.ENGLISH
    val locale = if (english) Locale.ENGLISH else Locale.CHINA
    val range = when {
        request.day != null -> {
            val date = Calendar.getInstance().apply {
                clear()
                set(request.year, request.month - 1, request.day, 12, 0, 0)
            }
            val pattern = if (english) "MMM d, yyyy" else "yyyy年M月d日"
            SimpleDateFormat(pattern, locale).format(date.time)
        }
        request.period == StatsPeriod.YEAR -> if (english) {
            request.year.toString()
        } else {
            "${request.year}年"
        }
        else -> {
            val date = Calendar.getInstance().apply {
                clear()
                set(request.year, request.month - 1, 1, 12, 0, 0)
            }
            val pattern = if (english) "MMM yyyy" else "yyyy年M月"
            SimpleDateFormat(pattern, locale).format(date.time)
        }
    }
    val category = request.category?.let { " · " + tr(it.displayName) } ?: ""
    val recordsLabel = (if (english) " " else "") + tr("账单")
    return range + category + recordsLabel
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
    // 待上传是正常现象，冲突才需要警告：两个数字分开读，不能混成一个。
    val pendingCounts = readPendingCounts(prefs)
    val conflicts = pendingCounts.conflicts
    val uploads = pendingCounts.uploads
    val lastSuccess = prefs.getLong("last_success_ms", 0L)
    val lastResult = prefs.getString("last_result", "").orEmpty()
    // Structured code wins; older installs without it fall back to keyword matching.
    val resultCode = SyncResultCode.from(prefs.getString("last_result_code", null))
    val legacyFailed = lastResult.contains("失败") ||
        lastResult.contains("异常") ||
        lastResult.contains("不可用") ||
        lastResult.contains("401") ||
        lastResult.contains("403")
    val syncFailed = when (resultCode) {
        SyncResultCode.OK -> false
        null -> legacyFailed
        else -> true
    }

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
        resultCode == SyncResultCode.BLOCKED -> {
            syncText = tr("同步已阻断")
            syncTone = StatusTone.ERROR
            syncIcon = Icons.Outlined.ErrorOutline
        }
        resultCode == SyncResultCode.AUTH -> {
            syncText = tr("授权失败")
            syncTone = StatusTone.ERROR
            syncIcon = Icons.Outlined.ErrorOutline
        }
        resultCode == SyncResultCode.CONFIG -> {
            syncText = tr("未配置")
            syncTone = StatusTone.NEUTRAL
            syncIcon = Icons.Outlined.Sync
        }
        resultCode == SyncResultCode.RETRY -> {
            syncText = tr("同步异常")
            syncTone = StatusTone.WARNING
            syncIcon = Icons.Outlined.ErrorOutline
        }
        uploads > 0 -> {
            // 还没推上去的本地修改：正常现象，不报红。
            syncText = tr("待上传 ") + uploads
            syncTone = StatusTone.NEUTRAL
            syncIcon = Icons.Outlined.Sync
        }
        resultCode == SyncResultCode.OK -> {
            syncText = tr("同步正常")
            syncTone = StatusTone.POSITIVE
            syncIcon = Icons.Outlined.CheckCircle
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

private fun localDayRange(year: Int, month: Int, day: Int): Pair<Long, Long> {
    val start = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startMs = start.timeInMillis
    start.add(Calendar.DAY_OF_MONTH, 1)
    return startMs to start.timeInMillis
}

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
