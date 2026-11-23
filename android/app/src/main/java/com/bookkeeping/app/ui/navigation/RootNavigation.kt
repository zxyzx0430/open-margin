package com.bookkeeping.app.ui.navigation

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
import com.bookkeeping.app.ui.screen.transactions.TransactionDetailScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionListScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionViewModel
import com.bookkeeping.app.ui.screen.transactions.VoiceCapturePanel
import com.bookkeeping.app.ui.screen.transactions.VoiceEntryRequest

// 根 NavHost：底部 tab 切换
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RootNavigation(onOnboardingVisibilityChanged: (Boolean) -> Unit = {}) {
    val navController = rememberNavController()
    var pendingLedgerDrilldown by remember { mutableStateOf<StatsLedgerDrilldown?>(null) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
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
    val navItems = BottomNavItem.ALL
    val showBottomBar = currentDestination?.hierarchy?.any { destination ->
        navItems.any { it.route == destination.route }
    } == true
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        val showLabels = LocalDensity.current.fontScale <= 1.3f

                        navItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            val label: (@Composable () -> Unit)? = if (showLabels) {
                                {
                                    Text(
                                        text = tr(item.label),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                null
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        item.icon,
                                        contentDescription = tr(item.label),
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                label = label,
                                alwaysShowLabel = showLabels,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = BottomNavItem.Transactions.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                // 叶子页（详情、导入）是瞬时切换的，所以从叶子页返回 tab 时也必须瞬时，
                // 否则会先淡入一帧空白再出现内容。tab 之间仍然保留默认的淡入淡出。
                composable(
                    route = BottomNavItem.Transactions.route,
                    popEnterTransition = { EnterTransition.None },
                ) {
                    TransactionListScreen(
                        viewModel = transactionViewModel,
                        onOpenBillImport = { showBillImportSheet = true },
                        drilldown = pendingLedgerDrilldown,
                        onDrilldownConsumed = { pendingLedgerDrilldown = null },
                        onOpenDetail = { id -> navController.navigate(AppRoutes.transactionDetail(id)) },
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
                composable(
                    route = BottomNavItem.Calendar.route,
                    popEnterTransition = { EnterTransition.None },
                ) {
                    CalendarScreen(onOpenDetail = { id ->
                        navController.navigate(AppRoutes.transactionDetail(id))
                    })
                }
                composable(
                    route = BottomNavItem.Stats.route,
                    popEnterTransition = { EnterTransition.None },
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
                        onOpenTransaction = { id -> navController.navigate(AppRoutes.transactionDetail(id)) },
                        onOpenOtherCleanup = { navController.navigate(AppRoutes.MERCHANT_RULES_CLEANUP) },
                    )
                }
                // 账单页仍可打开独立导入页；设置页入口在本页弹出导入 / 导出面板。
                composable(
                    route = AppRoutes.BILL_IMPORT,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    BillImportScreen(onClose = { navController.popBackStack() })
                }
                composable(
                    route = AppRoutes.PENDING_CONFLICTS,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    PendingConflictsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = AppRoutes.TRANSACTION_DIAGNOSTICS,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
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
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    SyncSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = AppRoutes.TRANSACTION_DETAIL,
                    arguments = listOf(
                        navArgument(AppRoutes.TRANSACTION_ID) { type = NavType.LongType },
                    ),
                    // 详情是叶子页。navigation-compose 默认是 700ms 交叉淡入淡出，
                    // 从「分类明细」弹层点进来时，弹层已经关掉、详情还只有很低的透明度，
                    // 露出来的就是统计页 —— 这就是用户看到的「闪一下」。这里改成瞬时切换：
                    // 点下去同一帧就是详情页，返回也是同一帧回到原页面。
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    TransactionDetailScreen(
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack() },
                    )
                }
                composable(
                    route = BottomNavItem.Budget.route,
                    popEnterTransition = { EnterTransition.None },
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
                    popEnterTransition = { EnterTransition.None },
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

        if (showBillImportSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
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

internal sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    data object Transactions : BottomNavItem("transactions", "账单", Icons.AutoMirrored.Filled.List)
    data object Calendar : BottomNavItem("calendar", "日历", Icons.Outlined.CalendarMonth)
    data object Stats : BottomNavItem("stats", "统计", Icons.Filled.PieChart)
    data object Budget : BottomNavItem("budget", "预算", Icons.Filled.AccountBalanceWallet)
    data object Settings : BottomNavItem("settings", "设置", Icons.Filled.Settings)

    companion object {
        val ALL = listOf(Transactions, Calendar, Stats, Budget, Settings)
    }
}
