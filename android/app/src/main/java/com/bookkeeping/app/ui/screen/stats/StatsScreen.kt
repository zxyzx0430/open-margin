package com.bookkeeping.app.ui.screen.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.MonthComparison
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
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
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpenLedger: (StatsLedgerDrilldown) -> Unit = {},
    onOpenTransaction: (Long) -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.categoryStats.collectAsState()
    val totalCents by viewModel.totalCents.collectAsState()
    val balanceCents by viewModel.balanceCents.collectAsState()
    val viewingType by viewModel.viewingType.collectAsState()
    val dailyTotals by viewModel.dailyTotals.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val dailyAverage by viewModel.dailyAverageCents.collectAsState()
    val comparison by viewModel.previousComparison.collectAsState()
    val period by viewModel.period.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val selectedDaySummary by viewModel.selectedDaySummary.collectAsState()
    val monthlyTotals by viewModel.monthlyTotals.collectAsState()
    val periodTransactions by viewModel.periodTransactions.collectAsState()

    var showMonthPicker by remember { mutableStateOf(false) }
    var showDayPicker by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<TransactionCategory?>(null) }

    fun ledgerDrilldown(
        category: TransactionCategory? = null,
        day: Int? = null,
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
        )
    }

    Scaffold(
        containerColor = QingBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = QingBackground,
                    titleContentColor = QingText,
                ),
                title = {
                    Text(
                        text = tr("统计"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    when (period) {
                        StatsPeriod.MONTH -> MonthPickerButton(
                            month = selectedMonth,
                            onClick = { showMonthPicker = true },
                        )
                        StatsPeriod.YEAR -> YearStepper(
                            year = selectedYear,
                            onPrevious = { viewModel.shiftYear(-1) },
                            onNext = { viewModel.shiftYear(1) },
                        )
                        StatsPeriod.DAY -> DateStepper(
                            date = selectedDate,
                            onPrevious = { viewModel.shiftDay(-1) },
                            onNext = { viewModel.shiftDay(1) },
                            onClick = { showDayPicker = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "period_toggle") {
                PeriodToggle(
                    selected = period,
                    onSelect = viewModel::setPeriod,
                )
            }
            item(key = "type_toggle") {
                TypeToggle(
                    selected = viewingType,
                    onSelect = viewModel::setViewingType,
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
                when (period) {
                    StatsPeriod.MONTH -> item(key = "daily_trend") {
                        DailyTrendCard(
                            dailyTotals = dailyTotals,
                            dailyAverageCents = dailyAverage,
                            monthKey = selectedMonth,
                            onOpenLedger = { day ->
                                onOpenLedger(ledgerDrilldown(day = day))
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
        val initialUtc = remember(selectedDate) {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(selectedDate.year, selectedDate.month - 1, selectedDate.day)
            }.timeInMillis
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showDayPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = millis
                            }
                            viewModel.setDate(
                                StatsDate(
                                    year = calendar.get(Calendar.YEAR),
                                    month = calendar.get(Calendar.MONTH) + 1,
                                    day = calendar.get(Calendar.DAY_OF_MONTH),
                                ),
                            )
                        }
                        showDayPicker = false
                    },
                ) { Text(tr("确定")) }
            },
            dismissButton = {
                TextButton(onClick = { showDayPicker = false }) { Text(tr("取消")) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
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
            onOpenTransaction = { id ->
                // 先导航、再关面板：导航是同一帧完成的（详情路由无转场动画），
                // 面板跟着统计页一起离开组合，中间不会露出「面板已关、详情还没来」
                // 的统计页帧。返回时统计页重新组合，面板状态已丢，不会自己弹回来。
                onOpenTransaction(id)
                selectedCategory = null
            },
        )
    }
}

@Composable
private fun TypeToggle(
    selected: TransactionType,
    onSelect: (TransactionType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TypePill(
            text = tr("支出"),
            selected = selected == TransactionType.EXPENSE,
            income = false,
            onClick = { onSelect(TransactionType.EXPENSE) },
            modifier = Modifier.weight(1f),
        )
        TypePill(
            text = tr("收入"),
            selected = selected == TransactionType.INCOME,
            income = true,
            onClick = { onSelect(TransactionType.INCOME) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TypePill(
    text: String,
    selected: Boolean,
    income: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else QingTextSecondary
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) QingPrimary else QingSurface)
            .border(1.dp, if (selected) QingPrimary else QingOutline, shape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (income) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PeriodToggle(
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PeriodPill(
            text = tr("月度"),
            selected = selected == StatsPeriod.MONTH,
            onClick = { onSelect(StatsPeriod.MONTH) },
            modifier = Modifier.weight(1f),
        )
        PeriodPill(
            text = tr("全年"),
            selected = selected == StatsPeriod.YEAR,
            onClick = { onSelect(StatsPeriod.YEAR) },
            modifier = Modifier.weight(1f),
        )
        PeriodPill(
            text = tr("按日"),
            selected = selected == StatsPeriod.DAY,
            onClick = { onSelect(StatsPeriod.DAY) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PeriodPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else QingTextSecondary
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) QingPrimary else QingSurface)
            .border(1.dp, if (selected) QingPrimary else QingOutline, shape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

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
    val totalColor = if (income) QingIncome else QingExpense
    val mutedOnContainer = QingPrimaryDeep.copy(alpha = 0.72f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(QingPrimaryContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = totalLabel,
            style = MaterialTheme.typography.labelMedium,
            color = mutedOnContainer,
        )
        AutoSizeAmountText(
            text = formatCny(totalCents),
            style = MaterialTheme.typography.headlineMedium,
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
                    color = QingPrimaryDeep,
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
                    color = if (balanceCents >= 0) QingIncome else QingExpense,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = QingPrimaryContainer),
        border = BorderStroke(1.dp, QingOutline),
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
                    color = QingExpense,
                    modifier = Modifier.weight(1f),
                )
                DaySummaryMetric(
                    label = tr("当日收入"),
                    value = if (summary.isLoaded) formatCny(summary.incomeCents) else "—",
                    color = QingIncome,
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
                    color = if (summary.balanceCents >= 0) QingIncome else QingExpense,
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
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
            HorizontalDivider(color = QingOutline)
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(12.dp))
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
            modifier = Modifier.widthIn(min = 48.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        AutoSizeAmountText(
            text = formatCny(stat.totalCents),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(min = 84.dp),
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
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
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
                    text = tr("每日趋势"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${tr("日均")} ${formatCny(dailyAverageCents)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = QingPrimaryDeep,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(QingMintSoft)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
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
    val shape = RoundedCornerShape(20.dp)
    val amountColor = if (type == TransactionType.INCOME) QingIncome else QingExpense
    val maxCents = monthlyTotals.maxOfOrNull { it.totalCents }?.coerceAtLeast(1L) ?: 1L
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
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
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = QingSurface),
                    border = BorderStroke(1.dp, QingOutline),
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
                            color = if (type == TransactionType.INCOME) QingIncome else QingExpense,
                        )
                    }
                }
                Button(
                    onClick = onOpenLedger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text(tr("在账单中查看"))
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
            color = if (type == TransactionType.EXPENSE) QingExpense else QingIncome,
            fontWeight = FontWeight.SemiBold,
        )
    }
    HorizontalDivider(color = QingOutline)
}

@Composable
private fun MonthPickerButton(
    month: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(QingSurface)
            .border(1.dp, QingOutline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = QingPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = monthLabel(month),
            style = MaterialTheme.typography.labelMedium,
            color = QingPrimaryDeep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
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
private fun DateStepper(
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
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(QingSurface)
            .border(1.dp, QingOutline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = tr("前一天"),
                tint = if (canGoPrevious) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = statsDateLabel(date),
            style = MaterialTheme.typography.labelMedium,
            color = QingPrimaryDeep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = tr("后一天"),
                tint = if (canGoNext) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun YearStepper(
    year: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val canGoPrevious = year > StatsViewModel.MIN_YEAR
    val canGoNext = year < StatsViewModel.MAX_YEAR
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(QingSurface)
            .border(1.dp, QingOutline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = tr("上一年"),
                tint = if (canGoPrevious) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = yearLabel(year),
            style = MaterialTheme.typography.labelMedium,
            color = QingPrimaryDeep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = tr("下一年"),
                tint = if (canGoNext) QingPrimary else QingTextSecondary.copy(alpha = 0.38f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
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
