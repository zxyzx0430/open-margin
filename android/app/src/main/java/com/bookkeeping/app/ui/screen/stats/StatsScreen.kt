package com.bookkeeping.app.ui.screen.stats

import com.bookkeeping.app.ui.components.rememberQingTitleCollapseState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.bookkeeping.app.ui.components.QingSegmentedControl

import androidx.compose.foundation.lazy.rememberLazyListState

import com.bookkeeping.app.ui.components.QingLargeTitle

import com.bookkeeping.app.ui.components.LocalQingChromeOverlap

import com.bookkeeping.app.ui.components.WalletCardSurface
import com.bookkeeping.app.ui.theme.QingAmountIncome
import com.bookkeeping.app.ui.theme.QingAmountExpense
import com.bookkeeping.app.ui.theme.QingTypeTokens
import com.bookkeeping.app.ui.theme.qingColors
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.MonthComparison
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.subscription.MonthlyForecastPoint
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.MonthPickerDialog
import com.bookkeeping.app.ui.components.SectionHeader
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingBackground
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingMint
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpenLedger: (StatsLedgerDrilldown) -> Unit = {},
    onOpenTransaction: (Long) -> Unit = {},
    onOpenOtherCleanup: () -> Unit = {},
    onOpenSubscriptions: (filterPendingCancel: Boolean) -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val period by viewModel.period.collectAsState()
    val isFinancial = selectedTab != StatsTab.SUBSCRIPTION
    val core = if (isFinancial) viewModel.coreUiState.collectAsState().value else viewModel.coreUiState.value
    val stats = core.categories
    val accountStats = core.accounts
    val isAccountMostlyUncategorized = core.mostlyUncategorized
    val subscriptionStats = if (!isFinancial) viewModel.subscriptionStats.collectAsState().value else viewModel.subscriptionStats.value
    val totalCents = core.totalCents
    val balanceCents = core.balanceCents
    val viewingType by viewModel.viewingType.collectAsState()
    val dailyTotals = if (isFinancial && period == StatsPeriod.MONTH) viewModel.dailyTotals.collectAsState().value else emptyList()
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val dailyAverage = if (isFinancial) viewModel.dailyAverageCents.collectAsState().value else viewModel.dailyAverageCents.value
    val comparison = if (isFinancial) viewModel.previousComparison.collectAsState().value else viewModel.previousComparison.value
    val selectedYear by viewModel.selectedYear.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val selectedDaySummary = if (isFinancial && period == StatsPeriod.DAY) viewModel.selectedDaySummary.collectAsState().value else viewModel.selectedDaySummary.value
    val monthlyTotals = if (isFinancial && period == StatsPeriod.YEAR) viewModel.monthlyTotals.collectAsState().value else emptyList()
    val periodTransactions = core.rows

    var showMonthPicker by remember { mutableStateOf(false) }
    var showDayPicker by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<TransactionCategory?>(null) }
    var selectedTrendDay by remember(selectedMonth, viewingType, period) { mutableStateOf<Int?>(null) }
    var accountsExpanded by remember(selectedMonth, selectedYear, selectedDate, viewingType, period) {
        mutableStateOf(false)
    }

    fun ledgerDrilldown(
        category: TransactionCategory? = null,
        day: Int? = null,
        account: String? = null,
    ): StatsLedgerDrilldown {
        val selectedMonthNumber = BudgetMonth.parse(selectedMonth)?.month ?: 1
        val drillDate = when {
            period == StatsPeriod.DAY -> selectedDate
            day != null -> StatsDate(selectedYear, selectedMonthNumber, day)
            else -> null
        }
        return StatsLedgerDrilldown(
            period = period,
            year = drillDate?.year ?: selectedYear,
            month = drillDate?.month ?: selectedMonthNumber,
            day = drillDate?.day,
            category = category,
            type = viewingType,
            account = account,
        )
    }

    val statsListState = rememberLazyListState()
    val titleCollapse = rememberQingTitleCollapseState(statsListState)
    Scaffold(
        modifier = Modifier.nestedScroll(titleCollapse.connection),
        containerColor = QingBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { QingLargeTitle(tr("统计"), titleCollapse) },
    ) { innerPadding ->
        LazyColumn(
            state = statsListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + LocalQingChromeOverlap.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "stats_tab_bar") {
                StatsTabBar(
                    selected = selectedTab,
                    onSelect = viewModel::setSelectedTab,
                )
            }

            if (selectedTab != StatsTab.SUBSCRIPTION) {
                item(key = "compact_period_bar") {
                    CompactPeriodFilterBar(
                        period = period,
                        selectedMonth = selectedMonth,
                        selectedYear = selectedYear,
                        selectedDate = selectedDate,
                        onSelectPeriod = viewModel::setPeriod,
                        onOpenMonthPicker = { showMonthPicker = true },
                        onPreviousYear = { viewModel.shiftYear(-1) },
                        onNextYear = { viewModel.shiftYear(1) },
                        onPreviousDay = { viewModel.shiftDay(-1) },
                        onNextDay = { viewModel.shiftDay(1) },
                        onOpenDayPicker = { showDayPicker = true },
                    )
                }
                item(key = "summary") {
                    if (period == StatsPeriod.DAY) {
                        DayStatsSummaryCard(
                            summary = selectedDaySummary,
                        )
                    } else {
                        StatsSummaryCard(
                            totalCents = totalCents,
                            dailyAverageCents = dailyAverage,
                            balanceCents = balanceCents,
                            comparison = comparison,
                            type = viewingType,
                            period = period,
                        )
                    }
                }
                if (stats.isEmpty()) {
                    item(key = "empty") {
                        EmptyStatsCard(
                            type = viewingType,
                            period = period,
                            onOpenLedger = { onOpenLedger(ledgerDrilldown()) },
                        )
                    }
                } else {
                    item(key = "donut") {
                        CategoryDonutCard(
                            stats = stats,
                            totalCents = totalCents,
                            type = viewingType,
                            onCategoryClick = { selectedCategory = it },
                        )
                    }
                    if (isAccountMostlyUncategorized) {
                        item(key = "account_distribution_hint") {
                            CollapsedAccountDistributionHint()
                        }
                    } else if (accountStats.isNotEmpty()) {
                        item(key = "account_distribution") {
                            AccountDistributionCard(
                                accountStats = accountStats,
                                type = viewingType,
                                expanded = accountsExpanded,
                                onToggleExpanded = { accountsExpanded = !accountsExpanded },
                                onAccountClick = { accountKey ->
                                    onOpenLedger(ledgerDrilldown(account = accountKey))
                                },
                            )
                        }
                    }
                    when (period) {
                        StatsPeriod.MONTH -> item(key = "daily_trend") {
                            DailyTrendCard(
                                dailyTotals = dailyTotals,
                                dailyAverageCents = dailyAverage,
                                monthKey = selectedMonth,
                                onOpenLedger = { day ->
                                    selectedTrendDay = day
                                },
                            )
                        }
                        StatsPeriod.YEAR -> item(key = "monthly_trend") {
                            MonthlyTrendCard(
                                monthlyTotals = monthlyTotals,
                                year = selectedYear,
                                type = viewingType,
                            )
                        }
                        StatsPeriod.DAY -> Unit
                    }
                }
            } else {
                if (subscriptionStats.activeCount == 0) {
                    item(key = "subscription_empty") {
                        SubscriptionEmptyGuideCard(
                            recurringCandidateCount = subscriptionStats.recurringCandidateCount,
                            onDiscoverClick = { onOpenSubscriptions(false) },
                            onAddClick = { onOpenSubscriptions(false) },
                        )
                    }
                } else {
                    item(key = "subscription_summary") {
                        SubscriptionSummaryCard(
                            state = subscriptionStats,
                            onOpenAllSubscriptions = { onOpenSubscriptions(false) },
                        )
                    }
                    if (subscriptionStats.pendingCancelCount > 0) {
                        item(key = "subscription_pending_cancel") {
                            SubscriptionPendingCancelBanner(
                                pendingCancelCount = subscriptionStats.pendingCancelCount,
                                monthlySavingsCents = subscriptionStats.pendingCancelMonthlySavingsCents,
                                onClick = { onOpenSubscriptions(true) },
                            )
                        }
                    }
                    item(key = "subscription_forecast") {
                        SubscriptionForecastCard(
                            forecast = subscriptionStats.forecast12Months,
                            peakMonth = subscriptionStats.peakMonth,
                        )
                    }
                    if (subscriptionStats.topSubscriptions.isNotEmpty()) {
                        item(key = "subscription_ranking") {
                            SubscriptionRankingCard(
                                items = subscriptionStats.topSubscriptions,
                                onOpenAll = { onOpenSubscriptions(false) },
                            )
                        }
                    }
                    if (subscriptionStats.channelBreakdown.isNotEmpty()) {
                        item(key = "subscription_channel_breakdown") {
                            SubscriptionBreakdownCard(
                                title = bilingual("按扣款渠道分布", "By Payment Channel"),
                                items = subscriptionStats.channelBreakdown,
                            )
                        }
                    }
                    if (subscriptionStats.cadenceBreakdown.isNotEmpty()) {
                        item(key = "subscription_cadence_breakdown") {
                            SubscriptionBreakdownCard(
                                title = bilingual("按周期分布", "By Billing Cycle"),
                                items = subscriptionStats.cadenceBreakdown,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            selectedMonth = selectedMonth,
            onDismiss = { showMonthPicker = false },
            onSelect = {
                viewModel.setMonth(it)
                showMonthPicker = false
            },
        )
    }

    if (showDayPicker) {
        val initialLocalMs = remember(selectedDate) {
            Calendar.getInstance().apply {
                clear()
                set(selectedDate.year, selectedDate.month - 1, selectedDate.day, 12, 0, 0)
            }.timeInMillis
        }
        com.bookkeeping.app.ui.components.LedgerDateTimePickerDialog(
            initialTimestampMs = initialLocalMs,
            dateOnly = true,
            onDismiss = { showDayPicker = false },
            onConfirm = { pickedMs ->
                val calendar = Calendar.getInstance().apply { timeInMillis = pickedMs }
                viewModel.setDate(
                    StatsDate(
                        year = calendar.get(Calendar.YEAR),
                        month = calendar.get(Calendar.MONTH) + 1,
                        day = calendar.get(Calendar.DAY_OF_MONTH),
                    ),
                )
                showDayPicker = false
            },
        )
    }

    selectedCategory?.let { category ->
        CategoryDetailDialog(
            category = category,
            month = selectedMonth,
            period = period,
            year = selectedYear,
            selectedDate = selectedDate,
            type = viewingType,
            transactions = periodTransactions,
            onDismiss = { selectedCategory = null },
            onOpenLedger = { onOpenLedger(ledgerDrilldown(category = category)) },
            onOpenOtherCleanup = if (category == TransactionCategory.OTHER) {
                {
                    onOpenOtherCleanup()
                    selectedCategory = null
                }
            } else {
                null
            },
            onOpenTransaction = { id ->
                // 先导航、再关面板：导航是同一帧完成的（详情路由无转场动画），
                // 面板跟着统计页一起离开组合，中间不会露出「面板已关、详情还没来」
                // 的统计页帧。返回时统计页重新组合，面板状态已丢，不会自己弹回来。
                onOpenTransaction(id)
                selectedCategory = null
            },
        )
    }

    selectedTrendDay?.let { day ->
        DayDetailDialog(
            month = selectedMonth,
            day = day,
            type = viewingType,
            transactions = periodTransactions,
            onDismiss = { selectedTrendDay = null },
            onOpenTransaction = { id ->
                onOpenTransaction(id)
                selectedTrendDay = null
            },
        )
    }
}

@Composable
private fun StatsTabBar(selected: StatsTab, onSelect: (StatsTab) -> Unit) {
    val options = listOf(StatsTab.EXPENSE, StatsTab.INCOME, StatsTab.SUBSCRIPTION)
    QingSegmentedControl(listOf(tr("支出"), tr("收入"), tr("订阅")), options.indexOf(selected),
        { onSelect(options[it]) }, Modifier.fillMaxWidth())
}

@Composable
private fun CompactPeriodFilterBar(
    period: StatsPeriod,
    selectedMonth: String,
    selectedYear: Int,
    selectedDate: StatsDate,
    onSelectPeriod: (StatsPeriod) -> Unit,
    onOpenMonthPicker: () -> Unit,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenDayPicker: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactPeriodPillGroup(
            selected = period,
            onSelect = onSelectPeriod,
        )
        Spacer(Modifier.width(8.dp))
        when (period) {
            StatsPeriod.MONTH -> CompactMonthPickerButton(
                month = selectedMonth,
                onClick = onOpenMonthPicker,
            )
            StatsPeriod.YEAR -> CompactYearStepper(
                year = selectedYear,
                onPrevious = onPreviousYear,
                onNext = onNextYear,
            )
            StatsPeriod.DAY -> CompactDateStepper(
                date = selectedDate,
                onPrevious = onPreviousDay,
                onNext = onNextDay,
                onClick = onOpenDayPicker,
            )
        }
    }
}

@Composable
private fun CompactPeriodPillGroup(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    val options = listOf(StatsPeriod.DAY, StatsPeriod.MONTH, StatsPeriod.YEAR)
    QingSegmentedControl(listOf(tr("日"), tr("月"), tr("年")), options.indexOf(selected),
        { onSelect(options[it]) }, Modifier.width(132.dp))
}

@Composable
private fun CollapsedAccountDistributionHint() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = QingSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AccountBalanceWallet,
                contentDescription = null,
                tint = QingTextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = bilingual(
                    "按账户分布 · 当期超 90% 账单未填写账户，已收起",
                    "By account · Over 90% of bills lack an account",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = QingTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AccountDistributionCard(
    accountStats: List<AccountStat>,
    type: TransactionType,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAccountClick: (String) -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val hasMore = accountStats.size > AccountAggregation.DEFAULT_VISIBLE_LIMIT
    val visibleStats = if (expanded || !hasMore) {
        accountStats
    } else {
        accountStats.take(AccountAggregation.DEFAULT_VISIBLE_LIMIT)
    }
    val remainingCount = (accountStats.size - AccountAggregation.DEFAULT_VISIBLE_LIMIT).coerceAtLeast(0)
    val maxTotalCents = accountStats.maxOfOrNull { it.totalCents }?.coerceAtLeast(1L) ?: 1L
    val activeBarColor = if (type == TransactionType.INCOME) QingIncome else QingPrimary

    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(bilingual("按账户分布", "Account Distribution"))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                visibleStats.forEach { stat ->
                    val barColor = if (stat.isUncategorized) {
                        QingTextSecondary.copy(alpha = 0.42f)
                    } else {
                        activeBarColor
                    }
                    val fillFraction = if (maxTotalCents > 0L && stat.totalCents > 0L) {
                        (stat.totalCents.toFloat() / maxTotalCents.toFloat()).coerceIn(0.03f, 1f)
                    } else {
                        0f
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) { onAccountClick(stat.accountKey) }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = accountDisplayLabel(stat),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (stat.isUncategorized) FontWeight.Normal else FontWeight.Medium,
                                color = if (stat.isUncategorized) QingTextSecondary else QingText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${stat.count}${bilingual("笔", "")} · ${categoryPercentText(stat.percentage)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = QingTextSecondary,
                                maxLines = 1,
                            )
                            AutoSizeAmountText(
                                text = formatCny(stat.totalCents),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (stat.isUncategorized) QingTextSecondary else QingText,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(min = 72.dp, max = 96.dp),
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = QingTextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(QingSurfaceVariant),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (fillFraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fillFraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(50))
                                        .background(barColor),
                                )
                            }
                        }
                    }
                }
            }
            if (hasMore) {
                HorizontalDivider(color = QingOutline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onToggleExpanded)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (expanded) {
                            bilingual("收起", "Collapse")
                        } else {
                            bilingual("展开其余 $remainingCount 个账户", "Show $remainingCount more")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = QingPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = QingPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionEmptyGuideCard(
    recurringCandidateCount: Int,
    onDiscoverClick: () -> Unit,
    onAddClick: () -> Unit,
) {
    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(50))
                    .background(QingMintSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.EventRepeat,
                    contentDescription = null,
                    tint = QingPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                text = bilingual("还没有在用周期订阅", "No active subscriptions yet"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = QingText,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (recurringCandidateCount > 0) {
                    bilingual(
                        "已从历史账单探测到 $recurringCandidateCount 项疑似周期扣费，管理后即可折算月均固定成本并预测 12 个月续费峰值。",
                        "Detected $recurringCandidateCount likely recurring bills. Track them to view monthly fixed costs and 12-month renewal peaks.",
                    )
                } else {
                    bilingual(
                        "记录房租、会员、云服务等周期扣费后，这里会自动折算月均固定成本并预测未来 12 个月续费峰值。",
                        "Add memberships, rent, or cloud plans to see your monthly fixed costs and 12-month renewal forecast.",
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
                textAlign = TextAlign.Center,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAddClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = bilingual("添加第一条订阅", "Add first subscription"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onDiscoverClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = qingColors.surfaceElevated,
                        contentColor = qingColors.textPrimary,
                    ),
                ) {
                    Text(
                        text = if (recurringCandidateCount > 0) {
                            bilingual(
                                "从历史账单自动发现（$recurringCandidateCount）",
                                "Discover from bills ($recurringCandidateCount)",
                            )
                        } else {
                            bilingual("从历史账单自动发现", "Discover from bills")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionSummaryCard(
    state: SubscriptionStatsState,
    onOpenAllSubscriptions: () -> Unit,
) {
    val mutedOnContainer = QingPrimaryDeep.copy(alpha = 0.72f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(QingPrimaryContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = bilingual("月均固定成本", "Monthly Fixed Cost"),
                style = MaterialTheme.typography.labelMedium,
                color = mutedOnContainer,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onOpenAllSubscriptions)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bilingual("全部订阅", "All subscriptions"),
                    style = MaterialTheme.typography.labelMedium,
                    color = QingPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = QingPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        AutoSizeAmountText(
            text = formatCny(state.monthlyTotalCnyCents),
            style = MaterialTheme.typography.headlineMedium,
            color = QingPrimaryDeep,
            fontWeight = FontWeight.Bold,
        )
        HorizontalDivider(
            color = QingPrimaryDeep.copy(alpha = 0.12f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SummaryMetric(
                label = bilingual("在用订阅", "Active"),
                modifier = Modifier.weight(1f),
            ) {
                AutoSizeAmountText(
                    text = bilingual("${state.activeCount} 项", "${state.activeCount}"),
                    style = MaterialTheme.typography.titleMedium,
                    color = QingPrimaryDeep,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                )
            }
            SummaryMetric(
                label = bilingual("折合年费", "Annualized"),
                modifier = Modifier.weight(1f),
            ) {
                AutoSizeAmountText(
                    text = formatCny(state.monthlyTotalCnyCents * 12L),
                    style = MaterialTheme.typography.titleMedium,
                    color = QingPrimaryDeep,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                )
            }
            SummaryMetric(
                label = bilingual("最高月费", "Highest/mo"),
                modifier = Modifier.weight(1f),
            ) {
                AutoSizeAmountText(
                    text = if (state.mostExpensiveMonthlyCnyCents > 0L) {
                        formatCny(state.mostExpensiveMonthlyCnyCents)
                    } else {
                        "—"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = QingPrimaryDeep,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun SubscriptionStructureGrid(
    state: SubscriptionStatsState,
) {
    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = bilingual("订阅结构", "Subscription Breakdown"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = QingText,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SubscriptionStructureCell(
                    label = bilingual("在用周期订阅", "Active Subscriptions"),
                    value = "${state.activeCount} ${bilingual("项", "")}".trim(),
                    modifier = Modifier.weight(1f),
                )
                SubscriptionStructureCell(
                    label = bilingual("平均每项月费", "Avg Monthly Cost"),
                    value = formatCny(state.averageMonthlyCnyCents),
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = QingOutline.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SubscriptionStructureCell(
                    label = bilingual("最高月费订阅", "Highest Monthly"),
                    value = formatCny(state.mostExpensiveMonthlyCnyCents),
                    subValue = state.mostExpensiveName,
                    modifier = Modifier.weight(1f),
                )
                SubscriptionStructureCell(
                    label = bilingual("需手动续费", "Manual Renewal"),
                    value = "${state.manualRenewalCount} ${bilingual("项", "")}".trim(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SubscriptionStructureCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subValue: String? = null,
) {
    Column(
        modifier = modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = QingTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AutoSizeAmountText(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = QingText,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
        )
        if (!subValue.isNullOrBlank()) {
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = QingPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SubscriptionPendingCancelBanner(
    pendingCancelCount: Int,
    monthlySavingsCents: Long,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = QingWarningContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Savings,
                contentDescription = null,
                tint = QingWarning,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = bilingual(
                    "已标记 $pendingCancelCount 项准备取消，退订后每月可省 ${formatCny(monthlySavingsCents)}",
                    "$pendingCancelCount marked to cancel · save ${formatCny(monthlySavingsCents)}/mo",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = QingWarning,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = QingWarning,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SubscriptionRankingCard(
    items: List<SubscriptionRankItem>,
    onOpenAll: () -> Unit,
) {
    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = bilingual("订阅月费排行", "Top Subscriptions (Monthly)"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = QingText,
                )
                Text(
                    text = bilingual("管理全部 ›", "Manage ›"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = QingPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenAll)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = QingText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${item.cadenceLabel} · ${item.channel}" +
                                    if (item.pendingCancel) " · 待取消" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.pendingCancel) QingWarning else QingTextSecondary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${formatCny(item.monthlyCnyCents)}/月",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.SemiBold,
                                color = QingText,
                            )
                            Text(
                                text = "${(item.percentage * 100f).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = QingTextSecondary,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(QingSurfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.percentage.coerceIn(0.03f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (item.pendingCancel) QingWarning else QingPrimary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionBreakdownCard(
    title: String,
    items: List<SubscriptionBreakdownItem>,
) {
    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = QingText,
            )
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${item.label}（${item.count} 项）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = QingText,
                        )
                        Text(
                            text = "${formatCny(item.monthlyCnyCents)}/月 · ${(item.percentage * 100f).toInt()}%",
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = QingPrimaryDeep,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(QingSurfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.percentage.coerceIn(0.03f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(QingPrimary.copy(alpha = 0.78f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionForecastCard(
    forecast: List<MonthlyForecastPoint>,
    peakMonth: MonthlyForecastPoint?,
) {
    if (forecast.isEmpty()) return
    val defaultSelectedIndex = remember(forecast, peakMonth) {
        if (peakMonth != null) {
            forecast.indexOfFirst { it.year == peakMonth.year && it.month == peakMonth.month }
                .coerceAtLeast(0)
        } else {
            0
        }
    }
    var selectedIndex by remember(forecast) { mutableIntStateOf(defaultSelectedIndex) }
    val selectedPoint = forecast.getOrNull(selectedIndex)

    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = bilingual("未来 12 个月续费预测", "12-Month Renewal Forecast"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = QingText,
                )
                if (peakMonth != null) {
                    Text(
                        text = bilingual(
                            "峰值：${peakMonth.year}年${peakMonth.month}月 ${formatCny(peakMonth.totalCnyCents)}",
                            "Peak: ${yearMonthShortLabel(peakMonth.year, peakMonth.month)} ${formatCny(peakMonth.totalCnyCents)}",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingWarning,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(QingWarningContainer)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            SubscriptionForecastBarChart(
                forecast = forecast,
                peakMonth = peakMonth,
                selectedIndex = selectedIndex,
                onSelectIndex = { selectedIndex = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp),
            )
            if (selectedPoint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(QingMintSoft.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = bilingual(
                            "${selectedPoint.year}年${selectedPoint.month}月 · ${selectedPoint.occurrenceCount} 笔续费",
                            "${yearMonthShortLabel(selectedPoint.year, selectedPoint.month)} · ${selectedPoint.occurrenceCount} renewals",
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = QingPrimaryDeep,
                    )
                    AutoSizeAmountText(
                        text = formatCny(selectedPoint.totalCnyCents),
                        style = MaterialTheme.typography.labelLarge,
                        color = QingPrimaryDeep,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionForecastBarChart(
    forecast: List<MonthlyForecastPoint>,
    peakMonth: MonthlyForecastPoint?,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (forecast.isEmpty()) return
    val maxCents = forecast.maxOfOrNull { it.totalCentsOrZero() }?.coerceAtLeast(1L) ?: 1L
    val outlineColor = QingOutline
    val labelColor = QingTextSecondary
    val normalBarColor = QingPrimary.copy(alpha = 0.28f)
    val selectedBarColor = QingPrimary
    val peakBarColor = QingPrimaryDeep
    val chartDesc = bilingual("未来 12 个月续费预测柱状图", "12-month subscription renewal forecast chart")

    Canvas(
        modifier = modifier
            .semantics { contentDescription = chartDesc }
            .pointerInput(forecast) {
                detectTapGestures { position ->
                    val leftPad = 38.dp.toPx()
                    val rightPad = 6.dp.toPx()
                    val chartWidth = size.width.toFloat() - leftPad - rightPad
                    if (chartWidth <= 0f) return@detectTapGestures
                    if (position.x in leftPad..(leftPad + chartWidth)) {
                        val slot = chartWidth / forecast.size
                        val idx = ((position.x - leftPad) / slot).toInt().coerceIn(0, forecast.lastIndex)
                        onSelectIndex(idx)
                    }
                }
            },
    ) {
        val leftPad = 38.dp.toPx()
        val rightPad = 6.dp.toPx()
        val topPad = 12.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
        val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
        val slot = chartWidth / forecast.size
        val barWidth = (slot * 0.56f).coerceAtLeast(6f)

        val axisPaint = Paint().apply {
            color = labelColor.toArgb()
            textSize = 9.sp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        val monthPaint = Paint().apply {
            color = labelColor.toArgb()
            textSize = 9.sp.toPx()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        for (i in 0..3) {
            val y = topPad + chartHeight * (i / 3f)
            drawLine(
                color = outlineColor.copy(alpha = 0.7f),
                start = Offset(leftPad, y),
                end = Offset(leftPad + chartWidth, y),
                strokeWidth = 1.dp.toPx(),
            )
            val value = maxCents * (3 - i) / 3
            drawContext.canvas.nativeCanvas.drawText(
                "¥${value / 100}",
                leftPad - 5.dp.toPx(),
                y + 3.dp.toPx(),
                axisPaint,
            )
        }

        forecast.forEachIndexed { index, point ->
            val x = leftPad + index * slot + (slot - barWidth) / 2f
            val ratio = point.totalCnyCents.toFloat() / maxCents.toFloat()
            val barHeight = (ratio * chartHeight).coerceAtLeast(
                if (point.totalCnyCents > 0L) 3.dp.toPx() else 0f,
            )
            val top = topPad + chartHeight - barHeight
            val isPeak = peakMonth != null &&
                point.year == peakMonth.year &&
                point.month == peakMonth.month
            val isSelected = index == selectedIndex
            val color = if (isSelected) selectedBarColor else normalBarColor
            if (barHeight > 0f) {
                val radius = min(barWidth / 2f, barHeight / 2f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            if (index % 2 == 0 || isPeak || isSelected) {
                drawContext.canvas.nativeCanvas.drawText(
                    monthShortLabel(point.month),
                    x + barWidth / 2f,
                    size.height - 3.dp.toPx(),
                    monthPaint,
                )
            }
        }
    }
}

private fun MonthlyForecastPoint.totalCentsOrZero(): Long = totalCnyCents.coerceAtLeast(0L)

@Composable
private fun StatsSummaryCard(
    totalCents: Long,
    dailyAverageCents: Long,
    balanceCents: Long,
    comparison: MonthComparison,
    type: TransactionType,
    period: StatsPeriod,
) {
    val income = type == TransactionType.INCOME
    val annual = period == StatsPeriod.YEAR
    val totalLabel = when {
        annual && income -> tr("全年总收入")
        annual -> tr("全年总支出")
        income -> tr("本月总收入")
        else -> tr("本月总支出")
    }
    val totalColor = if (income) QingAmountIncome else QingAmountExpense
    val mutedOnContainer = qingColors.textSecondary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = totalLabel,
            style = MaterialTheme.typography.labelMedium,
            color = mutedOnContainer,
        )
        AutoSizeAmountText(
            text = formatCny(totalCents),
            style = QingTypeTokens.heroAmount,
            color = totalColor,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SummaryMetric(
                label = if (income) tr("日均收入") else tr("日均支出"),
                modifier = Modifier.weight(1f),
            ) {
                AutoSizeAmountText(
                    text = formatCny(dailyAverageCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = qingColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                )
            }
            SummaryMetric(
                label = tr("结余"),
                modifier = Modifier.weight(1f),
            ) {
                AutoSizeAmountText(
                    text = (if (balanceCents >= 0) "+" else "−") + formatCny(abs(balanceCents)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (balanceCents >= 0) QingAmountIncome else QingAmountExpense,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SummaryMetric(
                label = if (annual) tr("较上一年") else tr("较上月"),
                modifier = Modifier.weight(1f),
            ) {
                ComparisonValue(comparison = comparison, type = type)
            }
            SummaryMetric(
                label = if (annual) tr("上一年金额") else tr("上月金额"),
                modifier = Modifier.weight(1f),
            ) {
                if (comparison.previousCents > 0L) {
                    AutoSizeAmountText(
                        text = formatCny(comparison.previousCents),
                        style = MaterialTheme.typography.titleMedium,
                        color = mutedOnContainer,
                        textAlign = TextAlign.Start,
                    )
                } else {
                    Text(
                        text = if (annual) tr("上一年无数据") else tr("上月无数据"),
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedOnContainer,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 汇总卡的单个指标：标签在上、金额在下。
 *
 * 原来是三列平分，结余列只有 ~87dp，`−¥16,310.76` 这类 11 字符金额按字符数分档
 * 不会缩字号，直接被父容器裁掉。改成两行两列后每列 ~138dp，金额完整可见。
 */
@Composable
private fun SummaryMetric(
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = QingPrimaryDeep.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        value()
    }
}

@Composable
private fun DayStatsSummaryCard(
    summary: DayStatsSummary,
) {
    val balance = (if (summary.balanceCents >= 0) "+" else "−") +
        formatCny(abs(summary.balanceCents))
    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
        hero = true,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DaySummaryMetric(
                    label = tr("当日支出"),
                    value = if (summary.isLoaded) formatCny(summary.expenseCents) else "—",
                    color = QingAmountExpense,
                    modifier = Modifier.weight(1f),
                )
                DaySummaryMetric(
                    label = tr("当日收入"),
                    value = if (summary.isLoaded) formatCny(summary.incomeCents) else "—",
                    color = QingAmountIncome,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DaySummaryMetric(
                    label = tr("当日结余"),
                    value = if (summary.isLoaded) balance else "—",
                    color = if (summary.balanceCents >= 0) QingAmountIncome else QingAmountExpense,
                    modifier = Modifier.weight(1f),
                )
                DaySummaryMetric(
                    label = tr("当日交易笔数"),
                    value = if (summary.isLoaded) "${summary.transactionCount} ${tr("笔")}" else "—",
                    color = QingPrimaryDeep,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DaySummaryMetric(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = QingPrimaryDeep.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AutoSizeAmountText(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ComparisonValue(comparison: MonthComparison, type: TransactionType) {
    val percent = comparison.percentChange
    if (percent == null) {
        Text(
            text = "—",
            style = MaterialTheme.typography.titleMedium,
            color = QingTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        return
    }
    val improved = if (type == TransactionType.EXPENSE) percent <= 0f else percent >= 0f
    val color = if (improved) QingIncome else QingExpense
    Text(
        text = (if (percent >= 0f) "↑ " else "↓ ") +
            String.format(Locale.CHINA, "%.1f%%", abs(percent)),
        style = MaterialTheme.typography.titleMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun CategoryDonutCard(
    stats: List<CategoryStat>,
    totalCents: Long,
    type: TransactionType,
    onCategoryClick: (TransactionCategory) -> Unit,
) {
    // 环图与图例共用同一个选中分类：点扇区高亮、点图例行去账单，两者互不抢状态。
    var selectedCategory by remember(stats) { mutableStateOf<TransactionCategory?>(null) }
    // 分类明细已经合并进图例，所以图例一次性展示全部，不再折叠。
    val visibleStats = stats
    WalletCardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(tr("分类占比"))
            CategoryPieChart(
                stats = stats,
                totalCents = totalCents,
                centerLabel = if (type == TransactionType.INCOME) tr("总收入") else tr("总支出"),
                // 环外不再有引线与标签，高度按圆环本体给：短边一半减去留白就是半径。
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                selectedCategory = selectedCategory,
                onSliceClick = { selectedCategory = it },
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visibleStats.forEach { stat ->
                    CategoryLegendRow(
                        stat = stat,
                        selected = stat.category == selectedCategory,
                        onClick = { onCategoryClick(stat.category) },
                    )
                }
            }
        }
    }
}

/**
 * 图例行：色块 + 分类名 + 百分比 + 金额。
 *
 * 色块用的是 [categoryColor]，和扇区同一份颜色映射，不在这里另算一遍，
 * 否则图例颜色和饼图对不上时用户就只能靠猜。选中分类时整行铺一层 surfaceVariant，
 * 环图与图例互相指认。
 */
@Composable
private fun CategoryLegendRow(stat: CategoryStat, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) QingSurfaceVariant else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(categoryColor(stat.category)),
        )
        Text(
            text = tr(stat.category.displayName),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = categoryPercentText(stat.percentage),
            style = MaterialTheme.typography.labelMedium,
            color = QingTextSecondary,
            modifier = Modifier.widthIn(min = 48.dp, max = 52.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        AutoSizeAmountText(
            text = formatCny(stat.totalCents),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(min = 84.dp, max = 96.dp),
            color = QingText,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DailyTrendCard(
    dailyTotals: List<DailyTotal>,
    dailyAverageCents: Long,
    monthKey: String,
    onOpenLedger: (Int) -> Unit,
) {
    var selectedDay by remember(dailyTotals, monthKey) {
        mutableIntStateOf(dailyTotals.maxByOrNull { it.totalCents }?.day ?: 1)
    }
    WalletCardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("每日趋势"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(QingMintSoft)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = tr("日均"),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingPrimaryDeep,
                        maxLines = 1,
                    )
                    AutoSizeAmountText(
                        text = formatCny(dailyAverageCents),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingPrimaryDeep,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(max = 96.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .pointerInput(dailyTotals, monthKey) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            var tappedPosition: Offset? = null
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    if (!change.isConsumed) tappedPosition = change.position
                                    break
                                }
                            }
                            val up = tappedPosition ?: return@awaitEachGesture
                            val leftPad = 34.dp.toPx()
                            val rightPad = 4.dp.toPx()
                            val chartWidth = size.width.toFloat() - leftPad - rightPad
                            if (chartWidth <= 0f || dailyTotals.isEmpty()) return@awaitEachGesture
                            if (up.x !in leftPad..(leftPad + chartWidth)) return@awaitEachGesture
                            val slot = chartWidth / dailyTotals.size
                            val index = ((up.x - leftPad) / slot).toInt()
                                .coerceIn(0, dailyTotals.lastIndex)
                            selectedDay = dailyTotals[index].day
                        }
                    },
            ) {
                DailyBarChart(
                    dailyTotals = dailyTotals,
                    monthKey = monthKey,
                    dayText = { day -> dayLabel(monthKey, day) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            TextButton(
                onClick = { onOpenLedger(selectedDay) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(tr("查看") + " " + dayLabel(monthKey, selectedDay) + " " + tr("账单"))
            }
        }
    }
}

@Composable
private fun MonthlyTrendCard(
    monthlyTotals: List<MonthlyTotal>,
    year: Int,
    type: TransactionType,
) {
    if (monthlyTotals.isEmpty()) return
    val shape = RoundedCornerShape(22.dp)
    val amountColor = if (type == TransactionType.INCOME) QingAmountIncome else QingAmountExpense
    val maxCents = monthlyTotals.maxOfOrNull { it.totalCents }?.coerceAtLeast(1L) ?: 1L
    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("月度趋势"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = yearLabel(year),
                    style = MaterialTheme.typography.labelSmall,
                    color = QingPrimaryDeep,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(QingMintSoft)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
            monthlyTotals.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = monthShortLabel(item.month),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingTextSecondary,
                        modifier = Modifier.width(34.dp),
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(QingSurfaceVariant),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (item.totalCents > 0L) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(
                                        item.totalCents.toFloat() / maxCents.toFloat()
                                    )
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(amountColor),
                            )
                        }
                    }
                    AutoSizeAmountText(
                        text = formatCny(item.totalCents),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.totalCents > 0L) amountColor else QingTextSecondary,
                        fontWeight = if (item.totalCents > 0L) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(88.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStatsCard(
    type: TransactionType,
    period: StatsPeriod,
    onOpenLedger: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    WalletCardSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(QingMintSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                    contentDescription = null,
                    tint = QingPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (period) {
                        StatsPeriod.DAY -> if (type == TransactionType.INCOME) {
                            tr("这一天还没有收入")
                        } else {
                            tr("这一天还没有支出")
                        }
                        StatsPeriod.MONTH -> if (type == TransactionType.INCOME) {
                            tr("本月还没有收入")
                        } else {
                            tr("本月还没有支出")
                        }
                        StatsPeriod.YEAR -> if (type == TransactionType.INCOME) {
                            tr("该年还没有收入")
                        } else {
                            tr("该年还没有支出")
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (period) {
                        StatsPeriod.DAY -> tr("记账后这里会显示当天的分类构成。")
                        StatsPeriod.MONTH -> tr("记账后这里会显示分类占比和每日趋势。")
                        StatsPeriod.YEAR -> tr("记账后这里会显示分类占比和每月趋势。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
                Button(
                    onClick = onOpenLedger,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(tr("去记一笔"))
                }
            }
        }
    }
}

@Composable
private fun CategoryDetailDialog(
    category: TransactionCategory,
    month: String,
    period: StatsPeriod,
    year: Int,
    selectedDate: StatsDate,
    type: TransactionType,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onOpenLedger: () -> Unit,
    onOpenOtherCleanup: (() -> Unit)? = null,
    onOpenTransaction: (Long) -> Unit,
) {
    val rows = remember(transactions, category, type) {
        transactions
            .filter {
                it.type == type.name &&
                    it.category == category.name &&
                    it.currency == "CNY" &&
                    it.duplicateOfId == null &&
                    it.remoteReviewStatus !in setOf(
                        "excluded", "possible_duplicate", "duplicate",
                    )
            }
            .sortedByDescending { it.timestamp }
    }
    val total = rows.sumOf { it.amountCents }
    val monthText = when (period) {
        StatsPeriod.DAY -> statsDateLabel(selectedDate)
        StatsPeriod.YEAR -> yearLabel(year)
        StatsPeriod.MONTH -> monthLabel(month)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryIcon(category = category, size = 40.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    ) {
                        Text(
                            text = tr(category.displayName),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "$monthText · ${rows.size} ${tr("笔")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = QingTextSecondary,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = tr("关闭"))
                    }
                }
                WalletCardSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (type == TransactionType.INCOME) {
                                tr("该分类收入")
                            } else {
                                tr("该分类支出")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = QingTextSecondary,
                        )
                        Text(
                            text = formatCny(total),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (type == TransactionType.INCOME) QingAmountIncome else QingAmountExpense,
                        )
                    }
                }
                if (onOpenOtherCleanup != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onOpenOtherCleanup,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = qingColors.surfaceElevated,
                                contentColor = qingColors.textPrimary,
                            ),
                        ) {
                            Text(
                                text = tr("批量整理其他分类"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = onOpenLedger,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = tr("在账单中查看"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = onOpenLedger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(min = 46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(tr("在账单中查看"))
                    }
                }
                if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tr("该分类暂无记录"), color = QingTextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp),
                        contentPadding = PaddingValues(bottom = 48.dp),
                    ) {
                        items(rows, key = { it.id }) { transaction ->
                            CategoryDetailTransactionRow(
                                transaction = transaction,
                                type = type,
                                onClick = { onOpenTransaction(transaction.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayDetailDialog(
    month: String,
    day: Int,
    type: TransactionType,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    val rows = remember(transactions, day, type) {
        val calendar = Calendar.getInstance()
        transactions
            .filter {
                calendar.timeInMillis = it.timestamp
                calendar.get(Calendar.DAY_OF_MONTH) == day &&
                    it.type == type.name &&
                    it.currency == "CNY" &&
                    it.duplicateOfId == null &&
                    it.remoteReviewStatus !in setOf(
                        "excluded", "possible_duplicate", "duplicate",
                    )
            }
            .sortedByDescending { it.timestamp }
    }
    val total = rows.sumOf { it.amountCents }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dayLabel(month, day),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${rows.size} ${tr("笔")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = QingTextSecondary,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = tr("关闭"))
                    }
                }
                WalletCardSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (type == TransactionType.INCOME) tr("收入") else tr("支出"),
                            style = MaterialTheme.typography.labelMedium,
                            color = QingTextSecondary,
                        )
                        Text(
                            text = formatCny(total),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (type == TransactionType.INCOME) QingAmountIncome else QingAmountExpense,
                        )
                    }
                }
                if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tr("暂无记录"), color = QingTextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp),
                        contentPadding = PaddingValues(bottom = 48.dp),
                    ) {
                        items(rows, key = { it.id }) { transaction ->
                            val category = remember(transaction.category) {
                                runCatching { TransactionCategory.valueOf(transaction.category) }
                                    .getOrDefault(TransactionCategory.OTHER)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = tr("查看账单详情"),
                                        onClick = { onOpenTransaction(transaction.id) },
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CategoryIcon(category = category, size = 32.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = transaction.merchant.ifBlank { tr("未知商户") },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = listOfNotNull(
                                        tr(category.displayName),
                                        SimpleDateFormat("HH:mm", Locale.CHINA)
                                            .format(Date(transaction.timestamp)),
                                        transaction.note.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ")
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = QingTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = (if (type == TransactionType.EXPENSE) "−" else "+") +
                                        formatCny(transaction.amountCents),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (type == TransactionType.EXPENSE) QingAmountExpense else QingAmountIncome,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDetailTransactionRow(
    transaction: TransactionEntity,
    type: TransactionType,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = tr("查看账单详情"),
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(Date(transaction.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = QingTextSecondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.merchant.ifBlank { tr("未知商户") },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = transaction.note,
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = (if (type == TransactionType.EXPENSE) "−" else "+") +
                formatCny(transaction.amountCents),
            style = MaterialTheme.typography.titleSmall,
            color = if (type == TransactionType.EXPENSE) QingAmountExpense else QingAmountIncome,
            fontWeight = FontWeight.SemiBold,
        )
    }
    HorizontalDivider(color = QingOutline)
}

@Composable
private fun CompactMonthPickerButton(
    month: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(QingSurface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = QingPrimary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = monthLabel(month),
            style = MaterialTheme.typography.labelMedium,
            color = QingPrimaryDeep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = QingPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CompactDateStepper(
    date: StatsDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val canGoPrevious = date.year > StatsViewModel.MIN_YEAR
    val canGoNext = date.year < StatsViewModel.MAX_YEAR
    Row(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(QingSurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = tr("前一天"),
                tint = if (canGoPrevious) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = compactStatsDateLabel(date),
            style = MaterialTheme.typography.labelMedium,
            color = QingPrimaryDeep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 6.dp),
        )
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = tr("后一天"),
                tint = if (canGoNext) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun CompactYearStepper(
    year: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val canGoPrevious = year > StatsViewModel.MIN_YEAR
    val canGoNext = year < StatsViewModel.MAX_YEAR
    Row(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(QingSurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = tr("上一年"),
                tint = if (canGoPrevious) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = yearLabel(year),
            style = MaterialTheme.typography.labelMedium,
            color = QingPrimaryDeep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = tr("下一年"),
                tint = if (canGoNext) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

private fun bilingual(zh: String, en: String): String =
    if (AppLanguage.current() == AppLanguage.ENGLISH) en else zh

private fun accountDisplayLabel(stat: AccountStat): String {
    if (AppLanguage.current() != AppLanguage.ENGLISH) return stat.displayName
    return when (stat.accountKey) {
        AccountNormalization.UNASSIGNED_ACCOUNT_KEY -> "Uncategorized Account"
        "微信" -> "WeChat"
        "支付宝" -> "Alipay"
        "现金" -> "Cash"
        "银行卡" -> "Bank Card"
        else -> stat.displayName
    }
}

private fun yearMonthShortLabel(year: Int, month: Int): String {
    if (AppLanguage.current() != AppLanguage.ENGLISH) return "${year}年${month}月"
    val cal = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
    }
    return SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(cal.time)
}

internal fun compactStatsDateLabel(
    date: StatsDate,
    currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    english: Boolean = AppLanguage.current() == AppLanguage.ENGLISH,
): String {
    if (date.year != currentYear) {
        return "%02d/%02d/%02d".format(Locale.US, date.year % 100, date.month, date.day)
    }
    if (!english) {
        return "${date.month}月${date.day}日"
    }
    val calendar = Calendar.getInstance().apply {
        clear()
        set(date.year, date.month - 1, date.day, 12, 0, 0)
    }
    return SimpleDateFormat("MMM d", Locale.ENGLISH).format(calendar.time)
}

private fun yearLabel(year: Int): String =
    if (AppLanguage.current() == AppLanguage.ENGLISH) year.toString() else "${year}年"

private fun monthShortLabel(month: Int): String {
    if (AppLanguage.current() != AppLanguage.ENGLISH) return "${month}月"
    val cal = Calendar.getInstance().apply {
        clear()
        set(Calendar.MONTH, month - 1)
    }
    return SimpleDateFormat("MMM", Locale.ENGLISH).format(cal.time)
}

private fun monthLabel(key: String): String {
    val parts = BudgetMonth.parse(key) ?: return key
    val cal = Calendar.getInstance().apply {
        clear()
        set(parts.year, parts.month - 1, 1)
    }
    val pattern = if (AppLanguage.current() == AppLanguage.ENGLISH) "MMM yyyy" else "yyyy年M月"
    val locale = if (AppLanguage.current() == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.CHINA
    return SimpleDateFormat(pattern, locale).format(cal.time)
}

private fun dayLabel(monthKey: String, day: Int): String {
    val parts = BudgetMonth.parse(monthKey) ?: return day.toString()
    return if (AppLanguage.current() == AppLanguage.ENGLISH) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(parts.year, parts.month - 1, 1)
        }
        val month = SimpleDateFormat("MMM", Locale.ENGLISH).format(cal.time)
        "$month $day"
    } else {
        "${parts.month}月${day}日"
    }
}

private fun statsDateLabel(date: StatsDate): String {
    val calendar = Calendar.getInstance().apply {
        clear()
        set(date.year, date.month - 1, date.day, 12, 0, 0)
    }
    val english = AppLanguage.current() == AppLanguage.ENGLISH
    val pattern = if (english) "MMM d, yyyy" else "yyyy年M月d日"
    val locale = if (english) Locale.ENGLISH else Locale.CHINA
    return SimpleDateFormat(pattern, locale).format(calendar.time)
}
