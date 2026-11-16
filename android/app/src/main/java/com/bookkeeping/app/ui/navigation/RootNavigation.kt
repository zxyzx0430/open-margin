package com.bookkeeping.app.ui.navigation

import com.bookkeeping.app.i18n.tr
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bookkeeping.app.ui.screen.budget.BudgetScreen
import com.bookkeeping.app.ui.screen.calendar.CalendarScreen
import com.bookkeeping.app.ui.screen.settings.SettingsScreen
import com.bookkeeping.app.ui.screen.stats.StatsScreen
import com.bookkeeping.app.ui.screen.transactions.TransactionListScreen

// 根 NavHost：底部 tab 切换
@Composable
fun RootNavigation() {
    val navController = rememberNavController()
    var requestBillImport by remember { mutableStateOf(false) }
    var requestAutomationSetup by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    val showLabels = LocalDensity.current.fontScale <= 1.3f

                    BottomNavItem.ALL.forEach { item ->
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
            composable(BottomNavItem.Transactions.route) {
                TransactionListScreen(
                    onSetupAutomation = {
                        requestAutomationSetup = true
                        navController.navigate(BottomNavItem.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    openBillImport = requestBillImport,
                    onBillImportOpened = { requestBillImport = false },
                )
            }
            composable(BottomNavItem.Calendar.route) { CalendarScreen() }
            composable(BottomNavItem.Stats.route) {
                StatsScreen(
                    onOpenLedger = {
                        navController.navigate(BottomNavItem.Transactions.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(BottomNavItem.Budget.route) { BudgetScreen() }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen(
                    onImportBills = {
                        requestBillImport = true
                        navController.navigate(BottomNavItem.Transactions.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    openAutomationSetup = requestAutomationSetup,
                    onAutomationOpened = { requestAutomationSetup = false },
                )
            }
        }
    }
}

private sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    data object Transactions : BottomNavItem("transactions", "账单", Icons.AutoMirrored.Filled.List)
    data object Calendar : BottomNavItem("calendar", "日历", Icons.Outlined.CalendarMonth)
    data object Stats : BottomNavItem("stats", "统计", Icons.Filled.PieChart)
    data object Budget : BottomNavItem("budget", "预算", Icons.Filled.AccountBalanceWallet)
    data object Settings : BottomNavItem("settings", "设置", Icons.Filled.Settings)

    companion object {
        val ALL = listOf(Transactions, Calendar, Stats, Budget, Settings)
    }
}
