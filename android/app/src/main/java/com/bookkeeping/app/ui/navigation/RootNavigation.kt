package com.bookkeeping.app.ui.navigation

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.onboarding.OnboardingOverlay
import com.bookkeeping.app.onboarding.OnboardingPermission
import com.bookkeeping.app.onboarding.OnboardingTarget
import com.bookkeeping.app.onboarding.OnboardingViewModel
import com.bookkeeping.app.onboarding.OnboardingWelcome
import com.bookkeeping.app.onboarding.TourState
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
import com.bookkeeping.app.sync.SyncSettingsScreen
import com.bookkeeping.app.ui.screen.billimport.BillImportScreen
import com.bookkeeping.app.ui.screen.budget.BudgetScreen
import com.bookkeeping.app.ui.screen.calendar.CalendarScreen
import com.bookkeeping.app.ui.screen.settings.MerchantRulesScreen
import com.bookkeeping.app.ui.screen.settings.SettingsScreen
import com.bookkeeping.app.ui.screen.stats.StatsScreen
import com.bookkeeping.app.ui.screen.stats.StatsLedgerDrilldown
import com.bookkeeping.app.ui.screen.transactions.TransactionDetailScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionListScreen
import com.bookkeeping.app.ui.screen.transactions.VoiceCapturePanel
import com.bookkeeping.app.ui.screen.transactions.VoiceEntryRequest

// 根 NavHost：底部 tab 切换
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RootNavigation(onOnboardingVisibilityChanged: (Boolean) -> Unit = {}) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onboarding: OnboardingViewModel = hiltViewModel()
    val tour by onboarding.state.collectAsState()
    val step = (tour as? TourState.Showing)?.step
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    var target by remember(step) { mutableStateOf<OnboardingTarget?>(null) }
    var pageTarget by remember { mutableStateOf<OnboardingTarget?>(null) }
    var voiceTry by remember(step) { mutableStateOf(false) }
    var demoFinger by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var onboardingPulse by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var waitingForNotificationAccess by remember { mutableStateOf(false) }
    val english = AppLanguage.current() == AppLanguage.ENGLISH
    fun finishTour(skip: Boolean) {
        if (skip) onboarding.skip() else onboarding.complete()
        onboardingPulse += 1
        if (currentDestination != null) {
            navController.navigate(BottomNavItem.Transactions.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
        Toast.makeText(context, if (english) "Try adding a transaction" else "先记一笔试试", Toast.LENGTH_SHORT).show()
    }
    val postNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        finishTour(skip = false)
    }
    DisposableEffect(lifecycleOwner, waitingForNotificationAccess, step) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForNotificationAccess && step == 7) {
                waitingForNotificationAccess = false
                if (NotificationListenerHelper.isEnabled(context) && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    postNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    finishTour(skip = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tour) {
        if (tour !is TourState.Loading) onOnboardingVisibilityChanged(tour !is TourState.Hidden)
    }
    LaunchedEffect(step, currentDestination?.route) {
        val route = when (step) {
            3 -> BottomNavItem.Calendar.route
            4 -> BottomNavItem.Stats.route
            5 -> BottomNavItem.Budget.route
            0, 1, 2, 6, 7 -> BottomNavItem.Transactions.route
            else -> null
        }
        if (route != null && currentDestination != null && currentDestination.route != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    BackHandler(enabled = step != null) { onboarding.back() }
    fun reportPosition(coordinates: LayoutCoordinates, host: View) {
        target = OnboardingTarget(Rect(coordinates.positionOnScreen(), Size(
            coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
        )), host)
    }
    var pendingLedgerDrilldown by remember { mutableStateOf<StatsLedgerDrilldown?>(null) }
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

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        pageTarget = OnboardingTarget(Rect(coordinates.positionOnScreen(), Size(
            coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
        )), view)
    }) {
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
                        onOpenBillImport = { showBillImportSheet = true },
                        drilldown = pendingLedgerDrilldown,
                        onDrilldownConsumed = { pendingLedgerDrilldown = null },
                        onOpenDetail = { id -> navController.navigate(AppRoutes.transactionDetail(id)) },
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
                        onboardingAddOpen = step == 1,
                        onOnboardingPanelPositioned = ::reportPosition,
                        onOnboardingPanelDismiss = onboarding::back,
                        onOnboardingSaved = onboarding::next,
                        onboardingPulse = onboardingPulse,
                    )
                }
                composable(
                    route = BottomNavItem.Calendar.route,
                    popEnterTransition = { EnterTransition.None },
                ) {
                    CalendarScreen(onboardingDemo = step == 3, onDemoFinger = { demoFinger = it }, onOpenDetail = { id ->
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
                ) { BudgetScreen() }
                composable(AppRoutes.MERCHANT_RULES) {
                    MerchantRulesScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = BottomNavItem.Settings.route,
                    popEnterTransition = { EnterTransition.None },
                ) {
                    SettingsScreen(
                        onOpenMerchantRules = { navController.navigate(AppRoutes.MERCHANT_RULES) },
                        onOpenSyncSettings = { navController.navigate(AppRoutes.SYNC_SETTINGS) },
                        onReplayOnboarding = onboarding::restart,
                    )
                }
            }
        }

        if (showBillImportSheet || step == 6) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    if (!billImportViewModel.state.value.importing) {
                        showBillImportSheet = false
                        billImportViewModel.reset()
                        billExportFeedback = null
                        if (step == 6) onboarding.back()
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
                            if (step == 6) onboarding.back()
                        }
                    },
                    onExportBills = { billExportLauncher.launch(BillExport.defaultFileName()) },
                    exportFeedback = billExportFeedback,
                    exportInProgress = billExportInProgress,
                    viewModel = billImportViewModel,
                    onPanelPositioned = if (step == 6) ::reportPosition else null,
                )
            }
        }

        // 录音面板是和 FAB 同一个窗口的覆盖层（不是 Dialog）：达到长按阈值后仍允许
        // 按住时滑出取消；点面板「停止」才结束录音。
        // 面板只铺在 Scaffold 之上，录音期间底下的 tab / FAB 都点不到。
        if (voiceSheetOpen || step == 2) {
            VoiceCapturePanel(
                onCancel = {
                    voiceSheetOpen = false
                    if (step == 2) onboarding.back()
                },
                onSubmit = { text ->
                    voiceSheetOpen = false
                    voiceRequest = VoiceEntryRequest(text)
                    if (step == 2) onboarding.back()
                },
                startRequested = step != 2 || voiceTry,
                onPanelPositioned = if (step == 2) ::reportPosition else null,
            )
        }
        when (val current = tour) {
            is TourState.Resume -> AlertDialog(
                onDismissRequest = {},
                title = { Text(if (english) "Continue the tour?" else "继续引导吗？") },
                confirmButton = { TextButton(onClick = onboarding::resume) { Text(if (english) "Continue" else "继续") } },
                dismissButton = { TextButton(onClick = { finishTour(true) }) { Text(if (english) "Skip" else "跳过") } },
            )
            is TourState.Showing -> when (current.step) {
                0 -> OnboardingWelcome(onStart = onboarding::next, onSkip = { finishTour(true) })
                7 -> OnboardingPermission(
                    onOpen = {
                        if (NotificationListenerHelper.isEnabled(context)) {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                postNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else finishTour(false)
                        } else {
                            waitingForNotificationAccess = true
                            NotificationListenerHelper.openSettings(context)
                        }
                    },
                    onLater = { finishTour(false) },
                )
                else -> {
                    val labels = if (english) listOf("", "Add a transaction", "Voice entry", "Calendar", "Statistics", "Budget", "Import bills")
                                 else listOf("", "新建账单", "语音记账", "日历", "统计", "预算", "导入账单")
                    val messages = if (english) listOf("", "Write a sentence to fill in the details.", "Try voice entry when you're ready.", "Select a range to see your spending.", "See where your money goes.", "See how much of your budget remains.", "Import WeChat or Alipay CSV / XLSX files.")
                                   else listOf("", "写一句话，自动填入账单信息。", "准备好了可以试试语音记账。", "拖选日期，查看这段时间的花费。", "看看钱都花在哪里。", "看看离预算上限还有多远。", "导入微信、支付宝 CSV 或 XLSX 账单。")
                    val selectedTarget = if (current.step in 3..5) pageTarget else target
                    if (selectedTarget != null) OnboardingOverlay(
                        step = current.step, target = selectedTarget,
                        title = labels[current.step], message = messages[current.step],
                        skipLabel = if (english) "Skip" else "跳过",
                        nextLabel = if (english) "Next" else "下一步",
                        dimBackground = current.step !in 3..5,
                        fingerPosition = if (current.step == 3) demoFinger else null,
                        tryLabel = if (current.step == 2 && !voiceTry) (if (english) "Try it" else "试一下") else null,
                        onTry = if (current.step == 2 && !voiceTry) ({ voiceTry = true }) else null,
                        onSkip = { finishTour(true) }, onNext = onboarding::next,
                    )
                }
            }
            else -> Unit
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
