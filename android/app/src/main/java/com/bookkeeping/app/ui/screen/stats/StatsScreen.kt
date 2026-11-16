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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpenLedger: (StatsLedgerDrilldown) -> Unit = {},
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
    val monthlyTotals by viewModel.monthlyTotals.collectAsState()
    val periodTransactions by viewModel.periodTransactions.collectAsState()

    var showMonthPicker by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<TransactionCategory?>(null) }

    fun ledgerDrilldown(
        category: TransactionCategory? = null,
        day: Int? = null,
    ) = StatsLedgerDrilldown(
        period = period,
        year = selectedYear,
        month = BudgetMonth.parse(selectedMonth)?.month ?: 1,
        day = day,
        category = category,
        type = viewingType,
    )

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
                StatsSummaryCard(
                    totalCents = totalCents,
                    dailyAverageCents = dailyAverage,
                    balanceCents = balanceCents,
                    comparison = comparison,
                    type = viewingType,
                    period = period,
                )
            }
            item(key = "summary_note") {
                SummaryNote(type = viewingType)
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
                }
                item(key = "category_detail") {
                    CategoryDetailCard(
                        stats = stats,
                        type = viewingType,
                        onCategoryClick = { selectedCategory = it },
                    )
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

    selectedCategory?.let { category ->
        CategoryDetailDialog(
            category = category,
            month = selectedMonth,
            period = period,
            year = selectedYear,
            type = viewingType,
            transactions = periodTransactions,
            onDismiss = { selectedCategory = null },
            onOpenLedger = { onOpenLedger(ledgerDrilldown(category = category)) },
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
            text = statsText("月度", "Monthly"),
            selected = selected == StatsPeriod.MONTH,
            onClick = { onSelect(StatsPeriod.MONTH) },
            modifier = Modifier.weight(1f),
        )
        PeriodPill(
            text = statsText("全年", "Year"),
            selected = selected == StatsPeriod.YEAR,
            onClick = { onSelect(StatsPeriod.YEAR) },
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
        annual && income -> statsText("全年总收入", "Total annual income")
        annual -> statsText("全年总支出", "Total annual expenses")
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
        Text(
            text = formatCny(totalCents),
            style = MaterialTheme.typography.headlineMedium,
            color = totalColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (income) tr("日均收入") else tr("日均支出"),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedOnContainer,
                )
                Text(
                    text = formatCny(dailyAverageCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = QingPrimaryDeep,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr("结余"),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedOnContainer,
                )
                Text(
                    text = (if (balanceCents >= 0) "+" else "−") +
                        formatCny(abs(balanceCents)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (balanceCents >= 0) QingIncome else QingExpense,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (annual) {
                        statsText("较上一年", "vs last year")
                    } else {
                        tr("较上月")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedOnContainer,
                )
                ComparisonValue(comparison = comparison, type = type)
                Text(
                    text = when {
                        comparison.previousCents > 0L && annual ->
                            "${statsText("上一年", "Last year")} " +
                                formatCny(comparison.previousCents)
                        comparison.previousCents > 0L ->
                            "${tr("上月")} ${formatCny(comparison.previousCents)}"
                        annual -> statsText("上一年无数据", "No data last year")
                        else -> tr("上月无数据")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedOnContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
private fun SummaryNote(type: TransactionType) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = QingTextSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (type == TransactionType.INCOME) {
                statsText(
                    "收入包含转入、红包等实际入账，仅排除退款和未计入记录。",
                    "Income includes incoming transfers and red packets; only refunds " +
                        "and excluded records are omitted.",
                )
            } else {
                tr("不包含转账和退款，统计仅计入实际消费支出。")
            },
            style = MaterialTheme.typography.bodySmall,
            color = QingTextSecondary,
        )
    }
}

@Composable
private fun CategoryDonutCard(
    stats: List<CategoryStat>,
    totalCents: Long,
    type: TransactionType,
    onCategoryClick: (TransactionCategory) -> Unit,
) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
            )
            HorizontalDivider(color = QingOutline)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.take(6).forEach { stat ->
                    CategoryLegendRow(stat = stat, onClick = { onCategoryClick(stat.category) })
                }
                if (stats.size > 6) {
                    Text(
                        text = "+${stats.size - 6} ${tr("类")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = QingTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryLegendRow(stat: CategoryStat, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryIcon(category = stat.category, size = 24.dp)
        Text(
            text = tr(stat.category.displayName),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${"%.1f".format(Locale.CHINA, stat.percentage * 100)}%",
            style = MaterialTheme.typography.labelMedium,
            color = QingTextSecondary,
            maxLines = 1,
        )
        Text(
            text = formatCny(stat.totalCents),
            style = MaterialTheme.typography.bodyMedium,
            color = QingText,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
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
                    text = statsText("月度趋势", "Monthly trend"),
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
                    Text(
                        text = formatCny(item.totalCents),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.totalCents > 0L) amountColor else QingTextSecondary,
                        fontWeight = if (item.totalCents > 0L) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(88.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryDetailCard(
    stats: List<CategoryStat>,
    type: TransactionType,
    onCategoryClick: (TransactionCategory) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val amountColor = if (type == TransactionType.INCOME) QingIncome else QingExpense
    val totalCount = stats.sumOf { it.count }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("分类明细"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${tr("共")} ${stats.size} ${tr("类")} · $totalCount ${tr("笔")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = QingTextSecondary,
                )
            }
            Spacer(Modifier.height(4.dp))
            stats.forEachIndexed { index, stat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCategoryClick(stat.category) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CategoryIcon(category = stat.category, size = 36.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(stat.category.displayName),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${"%.1f".format(Locale.CHINA, stat.percentage * 100)}% · " +
                                "${stat.count} ${tr("笔")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = QingTextSecondary,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = formatCny(stat.totalCents),
                        style = MaterialTheme.typography.titleSmall,
                        color = amountColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                if (index != stats.lastIndex) {
                    HorizontalDivider(color = QingOutline)
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
    val annual = period == StatsPeriod.YEAR
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
                    text = when {
                        annual && type == TransactionType.INCOME ->
                            statsText("该年还没有收入", "No income recorded for this year")
                        annual ->
                            statsText("该年还没有支出", "No expenses recorded for this year")
                        type == TransactionType.INCOME -> tr("本月还没有收入")
                        else -> tr("本月还没有支出")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (annual) {
                        statsText(
                            "记账后这里会显示分类占比和每月趋势。",
                            "After you add records, category shares and the monthly " +
                                "trend will appear here.",
                        )
                    } else {
                        tr("记账后这里会显示分类占比和每日趋势。")
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
    type: TransactionType,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onOpenLedger: () -> Unit,
) {
    val rows = remember(transactions, category, type) {
        transactions
            .filter {
                it.type == type.name &&
                    it.category == category.name &&
                    it.currency == "CNY" &&
                    it.duplicateOfId == null &&
                    it.remoteReviewStatus !in setOf(
                        "excluded", "transfer", "refund", "possible_duplicate",
                    ) &&
                    (it.type == "INCOME" || it.category != "TRANSFER")
            }
            .sortedByDescending { it.timestamp }
    }
    val total = rows.sumOf { it.amountCents }
    val monthText = if (period == StatsPeriod.YEAR) {
        "$year${tr("年")}"
    } else {
        BudgetMonth.parse(month)?.let { "${it.year}${tr("年")}${it.month}${tr("月")}" } ?: month
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
                            CategoryDetailTransactionRow(transaction, type)
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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

/**
 * Stats-only bilingual strings added after the shared EnglishUiText map was
 * frozen for this change. Move these keys into EnglishUiText when that file is
 * next touched.
 */
private fun statsText(chinese: String, english: String): String =
    if (AppLanguage.current() == AppLanguage.ENGLISH) english else chinese

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
