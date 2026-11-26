package com.bookkeeping.app.ui.navigation

import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.width
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.navigation.NavController
import com.bookkeeping.app.ui.theme.rememberReduceMotion
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.components.QingSheetGrabber
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import com.bookkeeping.app.ui.components.LocalQingChromeOverlap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.ui.components.QingChromeSurface
import com.bookkeeping.app.ui.theme.qingColors

import androidx.compose.runtime.CompositionLocalProvider
import com.bookkeeping.app.data.local.entity.TransactionEntity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.onboarding.OnboardingViewModel
import com.bookkeeping.app.onboarding.OnboardingWelcome
import com.bookkeeping.app.onboarding.TourState
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.i18n.tr
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.billexport.BillExport
import com.bookkeeping.app.ui.screen.billimport.BillImportExportSheet
import com.bookkeeping.app.ui.screen.billimport.BillImportViewModel
import com.bookkeeping.app.ui.screen.settings.SettingsViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bookkeeping.app.sync.PendingConflictsScreen
import com.bookkeeping.app.sync.SyncSettingsScreen
import com.bookkeeping.app.ui.screen.billimport.BillImportScreen
import com.bookkeeping.app.ui.screen.budget.BudgetScreen
import com.bookkeeping.app.ui.screen.calendar.CalendarScreen
import com.bookkeeping.app.ui.screen.diagnostics.TransactionDiagnosticsScreen
import com.bookkeeping.app.ui.screen.settings.MerchantRulesScreen
import com.bookkeeping.app.ui.screen.settings.SettingsScreen
import com.bookkeeping.app.ui.screen.stats.StatsScreen
import com.bookkeeping.app.ui.screen.stats.StatsLedgerDrilldown
import com.bookkeeping.app.ui.screen.subscriptions.SubscriptionsManageScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionDetailScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionListScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionViewModel
import com.bookkeeping.app.ui.screen.transactions.VoiceCapturePanel
import com.bookkeeping.app.ui.screen.transactions.VoiceEntryRequest

// 根 NavHost：底部 tab 切换
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RootNavigation(onOnboardingVisibilityChanged: (Boolean) -> Unit = {}) {
    val navController = rememberNavController()
    var pendingLedgerDrilldown by remember { mutableStateOf<StatsLedgerDrilldown?>(null) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // A destination listener is synchronous with navigate/pop. The Compose
    // back-stack snapshot alone can lag an incoming detail frame.
    var liveRoute by remember(navController) { mutableStateOf(navController.currentDestination?.route) }
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            liveRoute = destination.route
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    val visibleEntries by navController.visibleEntries.collectAsState()
    val visibleRoutes = visibleEntries.mapNotNull { it.destination.route }
    val reducedMotion = rememberReduceMotion()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onboarding: OnboardingViewModel = hiltViewModel()
    val transactionViewModel: TransactionViewModel = hiltViewModel()
    val tour by onboarding.state.collectAsState()
    var notificationEnabled by remember { mutableStateOf(NotificationListenerHelper.isEnabled(context)) }
    var waitingForAccess by rememberSaveable { mutableStateOf(false) }
    var leftForAccess by rememberSaveable { mutableStateOf(false) }
    var focusUpdateSettings by remember { mutableStateOf(false) }
    val postNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun finishOnboarding(skipped: Boolean) {
        onboarding.complete(skipped)
        if (currentDestination != null && currentDestination.route != BottomNavItem.Transactions.route) {
            navController.navigate(BottomNavItem.Transactions.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(tour) {
        if (tour != TourState.Loading) onOnboardingVisibilityChanged(tour == TourState.Showing)
    }
    DisposableEffect(lifecycleOwner, waitingForAccess, leftForAccess) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && waitingForAccess) leftForAccess = true
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationEnabled = NotificationListenerHelper.isEnabled(context)
                if (waitingForAccess && leftForAccess) {
                    waitingForAccess = false
                    leftForAccess = false
                    if (notificationEnabled && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Resolve the static tab list once at the top of composition. Reading it
    // lazily from inside hierarchy.any could observe a nested data object
    // before its class initializer had assigned INSTANCE (null route crash).
    var transitionEntry by remember { mutableStateOf<TransactionEntity?>(null) }
    var ledgerSharedOrigin by remember { mutableStateOf(false) }
    LaunchedEffect(currentDestination?.route) {
        val route = currentDestination?.route
        if (route != BottomNavItem.Transactions.route && route != AppRoutes.TRANSACTION_DETAIL) transitionEntry = null
    }
    val navItems = BottomNavItem.ALL
    val activeRoute = liveRoute ?: currentDestination?.route
    val tabRoutes = remember(navItems) { navItems.map { it.route }.toSet() }
    val isTabDestination = activeRoute in tabRoutes
    val detailInFlight = activeRoute == AppRoutes.TRANSACTION_DETAIL ||
        AppRoutes.TRANSACTION_DETAIL in visibleRoutes
    val showBottomBar = shouldShowMainChrome(activeRoute, visibleRoutes, tabRoutes)
    fun openTransaction(id: Long) {
        if (navController.currentDestination?.route == AppRoutes.TRANSACTION_DETAIL) return
        ledgerSharedOrigin = navController.currentDestination?.route == BottomNavItem.Transactions.route
        transitionEntry = transactionViewModel.allTransactions.value.firstOrNull { it.id == id }
        navController.navigate(AppRoutes.transactionDetail(id)) { launchSingleTop = true }
    }
    // 长按 FAB 达到录音阈值就打开面板；开着的时候禁掉 FAB 的新按压。
    var voiceSheetOpen by remember { mutableStateOf(false) }
    // 面板交回的文本。账单页消费后置回 null。
    var voiceRequest by remember { mutableStateOf<VoiceEntryRequest?>(null) }
    // 账单页与设置页共用同一个导入 / 导出底部面板
    var showBillImportSheet by remember { mutableStateOf(false) }
    val billImportViewModel: BillImportViewModel = hiltViewModel()
    val billSettingsViewModel: SettingsViewModel = hiltViewModel()
    var billExportFeedback by remember { mutableStateOf<String?>(null) }
    var billExportInProgress by remember { mutableStateOf(false) }
    val billExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            billExportInProgress = true
            billExportFeedback = null
            billSettingsViewModel.exportBills(uri) { message ->
                billExportFeedback = message
                billExportInProgress = false
            }
        }
    }

    // Full-height page canvas behind floating chrome, independent of blur tier.
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Keep the underlying tab layout stable throughout the detail transition.
    val chromeOverlap = if (isTabDestination || detailInFlight) 68.dp + navInset else 0.dp
    val canvasColor = qingColors.surfaceCanvas
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Box(Modifier.fillMaxSize()) {
            val direction = LocalLayoutDirection.current
            val bodyPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(direction), end = innerPadding.calculateEndPadding(direction),
                top = innerPadding.calculateTopPadding(),
                bottom = if (isTabDestination || detailInFlight) 0.dp else innerPadding.calculateBottomPadding(),
            )
            CompositionLocalProvider(LocalQingChromeOverlap provides chromeOverlap) {
            SharedTransitionLayout {
            val sharedScope = this
            NavHost(
                navController = navController,
                startDestination = BottomNavItem.Transactions.route,
                modifier = Modifier.fillMaxSize().padding(bodyPadding).consumeWindowInsets(innerPadding)
                    .background(canvasColor),
                enterTransition = {
                    if (reducedMotion || targetState.destination.route in tabRoutes) EnterTransition.None
                    else if (targetState.destination.route == AppRoutes.TRANSACTION_DETAIL && ledgerSharedOrigin && transitionEntry != null)
                        fadeIn(tween(DETAIL_TRANSITION_MS))
                    else slideInHorizontally(tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing)) { it / 5 }
                },
                exitTransition = {
                    if (!reducedMotion && targetState.destination.route == AppRoutes.TRANSACTION_DETAIL && ledgerSharedOrigin && transitionEntry != null)
                        fadeOut(tween(DETAIL_TRANSITION_MS), targetAlpha = 0.99f)
                    else ExitTransition.None
                },
                popEnterTransition = {
                    if (!reducedMotion && initialState.destination.route == AppRoutes.TRANSACTION_DETAIL && ledgerSharedOrigin && transitionEntry != null)
                        fadeIn(tween(DETAIL_TRANSITION_MS), initialAlpha = 0.99f)
                    else EnterTransition.None
                },
                popExitTransition = {
                    if (reducedMotion || initialState.destination.route in tabRoutes) ExitTransition.None
                    else if (initialState.destination.route == AppRoutes.TRANSACTION_DETAIL && ledgerSharedOrigin && transitionEntry != null)
                        fadeOut(tween(DETAIL_TRANSITION_MS))
                    else slideOutHorizontally(tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing)) { it / 5 }
                },
            ) {
                // No crossfade between top-level tabs. Detail alone has a
                // hierarchical transition and non-nested shared content.
                composable(
                    route = BottomNavItem.Transactions.route,
                ) {
                    val visibility = this
                    CompositionLocalProvider(LocalQingLedgerTransition provides transitionEntry?.takeIf { ledgerSharedOrigin }?.let {
                        QingLedgerTransition(sharedScope, visibility, it.id)
                    }) {
                    TransactionListScreen(
                        viewModel = transactionViewModel,
                        onOpenBillImport = { showBillImportSheet = true },
                        drilldown = pendingLedgerDrilldown,
                        onDrilldownConsumed = { pendingLedgerDrilldown = null },
                        onOpenDetail = ::openTransaction,
                        onOpenUpdateSettings = {
                            focusUpdateSettings = true
                            navController.navigate(BottomNavItem.Settings.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // 长按达到阈值：立刻打开录音面板并开始录音。面板已经开着就不再开第二个会话。
                        onVoiceEntry = {
                            if (!voiceSheetOpen) voiceSheetOpen = true
                        },
                        // 开录后手指仍按住时滑走：取消本次并关面板，不填表。
                        onVoiceCancelled = {
                            voiceSheetOpen = false
                        },
                        voiceBusy = voiceSheetOpen,
                        voiceRequest = voiceRequest,
                        onVoiceRequestConsumed = { voiceRequest = null },
                    )
                    }
                }
                composable(
                    route = BottomNavItem.Calendar.route,
                ) {
                    Box(Modifier.fillMaxSize().background(canvasColor).padding(bottom = chromeOverlap)) {
                        CalendarScreen(
                            onOpenDetail = ::openTransaction,
                            onOpenSubscriptions = { pendingCancel ->
                                navController.navigate(AppRoutes.subscriptions(pendingCancel))
                            },
                        )
                    }
                }
                composable(
                    route = BottomNavItem.Stats.route,
                ) {
                    StatsScreen(
                        onOpenLedger = { drilldown ->
                            pendingLedgerDrilldown = drilldown
                            navController.navigate(BottomNavItem.Transactions.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenTransaction = ::openTransaction,
                        onOpenOtherCleanup = { navController.navigate(AppRoutes.MERCHANT_RULES_CLEANUP) },
                        onOpenSubscriptions = { pendingCancel ->
                            navController.navigate(AppRoutes.subscriptions(pendingCancel))
                        },
                    )
                }
                // 账单页仍可打开独立导入页；设置页入口在本页弹出导入 / 导出面板。
                composable(
                    route = AppRoutes.BILL_IMPORT,
                ) {
                    BillImportScreen(onClose = { navController.popBackStack() })
                }
                composable(
                    route = AppRoutes.PENDING_CONFLICTS,
                ) {
                    PendingConflictsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = AppRoutes.TRANSACTION_DIAGNOSTICS,
                ) {
                    TransactionDiagnosticsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTransaction = { id ->
                            navController.navigate(AppRoutes.transactionDetail(id))
                        },
                    )
                }
                composable(
                    route = AppRoutes.SYNC_SETTINGS,
                ) {
                    SyncSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = AppRoutes.TRANSACTION_DETAIL,
                    arguments = listOf(
                        navArgument(AppRoutes.TRANSACTION_ID) { type = NavType.LongType },
                    ),
                ) { entry ->
                    val snapshot = transitionEntry?.takeIf { it.id == entry.arguments?.getLong(AppRoutes.TRANSACTION_ID) }
                    val visibility = this
                    val shared = snapshot?.takeIf { ledgerSharedOrigin }
                    CompositionLocalProvider(LocalQingChromeOverlap provides 0.dp,
                        LocalQingLedgerTransition provides shared?.let {
                            QingLedgerTransition(sharedScope, visibility, it.id)
                        }) {
                        val surface = Modifier.fillMaxSize().let { base ->
                            if (shared != null) base.qingLedgerContainer(shared.id) else base
                        }
                        Box(surface.background(canvasColor).padding(bottom = navInset)) {
                            TransactionDetailScreen(
                                initialEntry = snapshot,
                                transactionViewModel = transactionViewModel,
                                onBack = { navController.popBackStack() },
                                onDeleted = { navController.popBackStack() },
                            )
                        }
                    }
                }
                composable(
                    route = BottomNavItem.Budget.route,
                ) {
                    BudgetScreen(
                        onOpenLedger = { drilldown ->
                            pendingLedgerDrilldown = drilldown
                            navController.navigate(BottomNavItem.Transactions.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenSubscriptions = { pendingCancel ->
                            navController.navigate(AppRoutes.subscriptions(pendingCancel))
                        },
                    )
                }
                composable(
                    route = AppRoutes.SUBSCRIPTIONS_ROUTE,
                    arguments = listOf(
                        navArgument(AppRoutes.SUBSCRIPTIONS_PENDING_CANCEL_ARG) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { backStackEntry ->
                    val filterPendingCancel = backStackEntry.arguments
                        ?.getBoolean(AppRoutes.SUBSCRIPTIONS_PENDING_CANCEL_ARG) == true
                    SubscriptionsManageScreen(
                        initialFilterPendingCancel = filterPendingCancel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = AppRoutes.MERCHANT_RULES_ROUTE,
                    arguments = listOf(
                        navArgument(AppRoutes.MERCHANT_RULES_CLEANUP_ARG) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { backStackEntry ->
                    val openCleanup = backStackEntry.arguments
                        ?.getBoolean(AppRoutes.MERCHANT_RULES_CLEANUP_ARG) == true
                    MerchantRulesScreen(
                        onBack = { navController.popBackStack() },
                        initialOpenCleanup = openCleanup,
                    )
                }
                composable(
                    route = BottomNavItem.Settings.route,
                ) {
                    SettingsScreen(
                        onOpenMerchantRules = { navController.navigate(AppRoutes.MERCHANT_RULES) },
                        onOpenSyncSettings = { navController.navigate(AppRoutes.SYNC_SETTINGS) },
                        onOpenPendingConflicts = { navController.navigate(AppRoutes.PENDING_CONFLICTS) },
                        onOpenTransactionDiagnostics = {
                            navController.navigate(AppRoutes.TRANSACTION_DIAGNOSTICS)
                        },
                        onReplayOnboarding = onboarding::restart,
                        focusUpdateCard = focusUpdateSettings,
                        onUpdateCardFocused = { focusUpdateSettings = false },
                    )
                }
            }
            }
            }
            }
        }

        if (showBottomBar) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .drawWithContent {
                    // Last-line guard against a stale draw during a destination change.
                    if (navController.currentDestination?.route in tabRoutes) drawContent()
                }
        ) {
            QingChromeSurface(
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(60.dp).selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically) {
                    navItems.forEach { item ->
                        val selected = activeRoute == item.route
                        val tint = if (selected) qingColors.accentPrimary else qingColors.textSecondary.copy(alpha = 0.72f)
                        Box(
                            Modifier.weight(1f).height(60.dp)
                                .selectable(selected = selected, interactionSource = null, indication = null,
                                    role = Role.Tab, onClick = {
                                        if (navController.currentDestination?.route in tabRoutes &&
                                            navController.currentDestination?.route != item.route) {
                                            navController.navigate(item.route) {
                                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Option A: color alone marks selection. Center the
                            // complete icon/label group, with no reserved indicator space.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                            ) {
                                Icon(item.outlineIcon, null,
                                    modifier = Modifier.size(23.dp), tint = tint)
                                Text(tr(item.label), fontSize = 11.sp, lineHeight = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = tint, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 1.dp))
                            }
                        }
                    }
                }
            }
        }

        if (showBillImportSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                containerColor = qingColors.surfaceSheet,
                shape = RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero),
                dragHandle = { QingSheetGrabber() },
                onDismissRequest = {
                    if (!billImportViewModel.state.value.importing) {
                        showBillImportSheet = false
                        billImportViewModel.reset()
                        billExportFeedback = null
                    }
                },
                sheetState = sheetState,
            ) {
                BillImportExportSheet(
                    onClose = {
                        if (!billImportViewModel.state.value.importing) {
                            showBillImportSheet = false
                            billImportViewModel.reset()
                            billExportFeedback = null
                        }
                    },
                    onExportBills = { billExportLauncher.launch(BillExport.defaultFileName()) },
                    exportFeedback = billExportFeedback,
                    exportInProgress = billExportInProgress,
                    viewModel = billImportViewModel,
                )
            }
        }

        // 录音面板是和 FAB 同一个窗口的覆盖层（不是 Dialog）：达到长按阈值后仍允许
        // 按住时滑出取消；点面板「停止」才结束录音。
        // 面板只铺在 Scaffold 之上，录音期间底下的 tab / FAB 都点不到。
        if (voiceSheetOpen) {
            VoiceCapturePanel(
                onCancel = {
                    voiceSheetOpen = false
                },
                onSubmit = { text, attemptId ->
                    voiceSheetOpen = false
                    voiceRequest = VoiceEntryRequest(text, attemptId)
                },
                onDiagnostic = { attemptId, result, details ->
                    transactionViewModel.recordDiagnostic(
                        input = TransactionDiagnosticInput(attemptId = attemptId),
                        stage = TransactionDiagnostics.STAGE_CAPTURE,
                        result = result,
                        details = details,
                    )
                },
            )
        }
        if (tour == TourState.Showing) {
            BackHandler { finishOnboarding(skipped = true) }
            OnboardingWelcome(
                onStart = { finishOnboarding(skipped = false) },
                onSkip = { finishOnboarding(skipped = true) },
                onEnableAutomaticEntry = {
                    if (!notificationEnabled) {
                        waitingForAccess = true
                        leftForAccess = false
                        runCatching { NotificationListenerHelper.openSettings(context) }
                            .onFailure {
                                waitingForAccess = false
                                Toast.makeText(context,
                                    if (AppLanguage.current() == AppLanguage.ENGLISH) "Unable to open system settings"
                                    else "无法打开系统设置", Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                notificationAccessEnabled = notificationEnabled,
            )
        }
    }
}

internal sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector, val outlineIcon: ImageVector) {
    data object Transactions : BottomNavItem("transactions", "账单", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Outlined.List)
    data object Calendar : BottomNavItem("calendar", "日历", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth)
    data object Stats : BottomNavItem("stats", "统计", Icons.Filled.PieChart, Icons.Outlined.PieChart)
    data object Budget : BottomNavItem("budget", "预算", Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet)
    data object Settings : BottomNavItem("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings)

    companion object {
        val ALL = listOf(Transactions, Calendar, Stats, Budget, Settings)
    }
}

internal const val DETAIL_TRANSITION_MS = 220

/** Chrome belongs exclusively to the tab surface, including transition frames. */
internal fun shouldShowMainChrome(route: String?, visibleRoutes: List<String>, tabs: Set<String>): Boolean =
    route in tabs && visibleRoutes.all { it in tabs }
