package com.bookkeeping.app.ui.navigation

import com.bookkeeping.app.i18n.tr
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
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
import com.bookkeeping.app.ui.screen.billimport.BillImportScreen
import com.bookkeeping.app.ui.screen.budget.BudgetScreen
import com.bookkeeping.app.ui.screen.calendar.CalendarScreen
import com.bookkeeping.app.ui.screen.settings.MerchantRulesScreen
import com.bookkeeping.app.ui.screen.settings.SettingsScreen
import com.bookkeeping.app.ui.screen.stats.StatsScreen
import com.bookkeeping.app.ui.screen.stats.StatsLedgerDrilldown
import com.bookkeeping.app.ui.screen.transactions.TransactionDetailScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionListScreen
import com.bookkeeping.app.ui.screen.transactions.VoiceCaptureSheet
import com.bookkeeping.app.ui.screen.transactions.VoiceEntryRequest

// 根 NavHost：底部 tab 切换
@Composable
fun RootNavigation() {
    val navController = rememberNavController()
    var pendingLedgerDrilldown by remember { mutableStateOf<StatsLedgerDrilldown?>(null) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // Resolve the static tab list once at the top of composition. Reading it
    // lazily from inside hierarchy.any could observe a nested data object
    // before its class initializer had assigned INSTANCE (null route crash).
    val navItems = BottomNavItem.ALL
    val showBottomBar = currentDestination?.hierarchy?.any { destination ->
        navItems.any { it.route == destination.route }
    } == true
    // 长按 FAB 的录音面板：开着的时候同时禁掉 FAB 的按住动作，一个时刻只有一个会话。
    var voiceSheetOpen by remember { mutableStateOf(false) }
    // 面板交回的 Final 文本（null = 没听清）。账单页消费后置回 null。
    var voiceRequest by remember { mutableStateOf<VoiceEntryRequest?>(null) }

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
                    onOpenBillImport = { navController.navigate(AppRoutes.BILL_IMPORT) },
                    drilldown = pendingLedgerDrilldown,
                    onDrilldownConsumed = { pendingLedgerDrilldown = null },
                    onOpenDetail = { id -> navController.navigate(AppRoutes.transactionDetail(id)) },
                    // 长按满 1 秒：打开录音面板。面板已经开着就不再开第二个会话。
                    onVoiceEntry = {
                        if (!voiceSheetOpen) voiceSheetOpen = true
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
                )
            }
            // 账单导入：独立页面，从设置页或账单页进来都行；关掉就 popBackStack 回原页面。
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
                    // 导入账单走独立路由：关掉后 popBackStack 回设置页，不再切到账单 tab。
                    onImportBills = { navController.navigate(AppRoutes.BILL_IMPORT) },
                    onOpenMerchantRules = { navController.navigate(AppRoutes.MERCHANT_RULES) },
                )
            }
        }
    }

    // 录音面板本身就是模态的：铺在 Scaffold 之上，录音期间底下的 tab / FAB 都点不到。
    // onFinal 的 text 为 null 表示「说了但没听清」：表单照开、不预填，不写空备注。
    if (voiceSheetOpen) {
        VoiceCaptureSheet(
            onCancel = { voiceSheetOpen = false },
            onFinal = { text ->
                voiceSheetOpen = false
                voiceRequest = VoiceEntryRequest(text)
            },
        )
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
