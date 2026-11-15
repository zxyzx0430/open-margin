package com.bookkeeping.app.ui.screen.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.MonthComparison
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.SectionHeader
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import com.bookkeeping.app.ui.theme.QingSpacing
import com.bookkeeping.app.ui.theme.QingType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpenLedger: () -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.categoryStats.collectAsState()
    val totalCents by viewModel.totalCents.collectAsState()
    val viewingType by viewModel.viewingType.collectAsState()
    val dailyTotals by viewModel.dailyTotals.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val dailyAverage by viewModel.dailyAverageCents.collectAsState()
    val comparison by viewModel.previousComparison.collectAsState()
    val statsLoaded by viewModel.statsLoaded.collectAsState()

    var showMonthPicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Text(
                        text = tr("统计"),
                        style = QingType.pageTitle,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    MonthPickerButton(
                        month = selectedMonth,
                        onClick = { showMonthPicker = true },
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = QingSpacing.pageGutter,
                vertical = QingSpacing.itemGap,
            ),
            verticalArrangement = Arrangement.spacedBy(QingSpacing.sectionGap),
        ) {
            item(key = "type_toggle") {
                TypeToggle(
                    selected = viewingType,
                    onSelect = viewModel::setViewingType,
                )
            }
            if (!statsLoaded) {
                item(key = "loading") { LoadingCard() }
            } else {
                item(key = "summary") {
                    StatsSummaryCard(
                        totalCents = totalCents,
                        dailyAverageCents = dailyAverage,
                        comparison = comparison,
                        type = viewingType,
                        isCurrentMonth = BudgetMonth.isCurrent(selectedMonth),
                    )
                }
                item(key = "summary_note") {
                    SummaryNote(type = viewingType)
                }

                if (stats.isEmpty()) {
                    item(key = "empty") {
                        EmptyStatsCard(
                            type = viewingType,
                            isCurrentMonth = BudgetMonth.isCurrent(selectedMonth),
                            onOpenLedger = onOpenLedger,
                        )
                    }
                } else {
                    item(key = "donut") {
                        CategoryDonutCard(
                            stats = stats,
                            totalCents = totalCents,
                            type = viewingType,
                        )
                    }
                    item(key = "daily_trend") {
                        DailyTrendCard(
                            dailyTotals = dailyTotals,
                            dailyAverageCents = dailyAverage,
                            monthKey = selectedMonth,
                        )
                    }
                    item(key = "category_detail") {
                        CategoryDetailCard(stats = stats, type = viewingType)
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
        )
        TypePill(
            text = tr("收入"),
            selected = selected == TransactionType.INCOME,
            income = true,
            onClick = { onSelect(TransactionType.INCOME) },
        )
    }
}

@Composable
private fun LoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TypePill(
    text: String,
    selected: Boolean,
    income: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(background)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
private fun StatsSummaryCard(
    totalCents: Long,
    dailyAverageCents: Long,
    comparison: MonthComparison,
    type: TransactionType,
    isCurrentMonth: Boolean,
) {
    val income = type == TransactionType.INCOME
    val semantic = LocalQingSemanticColors.current
    val totalLabel = when {
        income && isCurrentMonth -> tr("本月总收入")
        income -> tr("该月总收入")
        isCurrentMonth -> tr("本月总支出")
        else -> tr("该月总支出")
    }
    val totalColor = if (income) semantic.income else semantic.expense
    val totalText = formatCny(totalCents)
    val totalStyle = when {
        totalText.length > 15 -> QingType.secondaryKpi.copy(fontSize = 20.sp)
        totalText.length > 12 -> QingType.secondaryKpi.copy(fontSize = 24.sp)
        else -> QingType.primaryKpi
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = totalLabel,
            style = QingType.metricLabel,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
        Text(
            text = totalText,
            style = totalStyle,
            color = totalColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (income) tr("日均收入") else tr("日均支出"),
                    style = QingType.metricLabel,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Text(
                    text = formatCny(dailyAverageCents),
                    style = QingType.secondaryKpi,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr("较上月"),
                    style = QingType.metricLabel,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                ComparisonValue(comparison = comparison, type = type)
                Text(
                    text = if (comparison.previousCents > 0L) {
                        "${tr("上月")} ${formatCny(comparison.previousCents)}"
                    } else {
                        tr("上月无数据")
                    },
                    style = QingType.metricLabel,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ComparisonValue(comparison: MonthComparison, type: TransactionType) {
    val semantic = LocalQingSemanticColors.current
    val percent = comparison.percentChange
    if (percent == null) {
        Text(
            text = "—",
            style = QingType.secondaryKpi,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            fontWeight = FontWeight.SemiBold,
        )
        return
    }
    val improved = if (type == TransactionType.EXPENSE) percent <= 0f else percent >= 0f
    val color = if (improved) semantic.income else semantic.expenseText
    Text(
        text = (if (percent >= 0f) "↑ " else "↓ ") +
            String.format(Locale.CHINA, "%.1f%%", abs(percent)),
        style = QingType.secondaryKpi,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun SummaryNote(type: TransactionType) {
    val text = if (type == TransactionType.INCOME) {
        tr("不包含转账和退款，统计仅计入实际收入。")
    } else {
        tr("不包含转账和退款，统计仅计入实际消费支出。")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryDonutCard(
    stats: List<CategoryStat>,
    totalCents: Long,
    type: TransactionType,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(QingSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.take(5).forEach { stat ->
                    CategoryLegendRow(stat = stat)
                }
                if (stats.size > 5) {
                    Text(
                        text = "+${stats.size - 5} ${tr("类")}",
                        style = QingType.metricLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryLegendRow(stat: CategoryStat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryIcon(category = stat.category, size = 24.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tr(stat.category.displayName),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${"%.1f".format(Locale.CHINA, stat.percentage * 100)}%",
                style = QingType.metricLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatCny(stat.totalCents),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DailyTrendCard(
    dailyTotals: List<DailyTotal>,
    dailyAverageCents: Long,
    monthKey: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(QingSpacing.cardPadding),
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
                    style = QingType.metricLabel,
                    color = LocalQingSemanticColors.current.primaryDeep,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(LocalQingSemanticColors.current.mintSoft)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
            DailyBarChart(
                dailyTotals = dailyTotals,
                monthKey = monthKey,
                dayText = { day -> dayLabel(monthKey, day) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CategoryDetailCard(
    stats: List<CategoryStat>,
    type: TransactionType,
) {
    val semantic = LocalQingSemanticColors.current
    val amountColor = if (type == TransactionType.INCOME) semantic.income else semantic.expenseText
    val totalCount = stats.sumOf { it.count }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(QingSpacing.cardPadding)) {
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
                    style = QingType.metricLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            stats.forEachIndexed { index, stat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CategoryIcon(category = stat.category, size = 38.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(stat.category.displayName),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${"%.1f".format(Locale.CHINA, stat.percentage * 100)}% · " +
                                "${stat.count} ${tr("笔")}",
                            style = QingType.metricLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatCny(stat.totalCents),
                        style = MaterialTheme.typography.titleSmall,
                        color = amountColor,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        softWrap = false,
                    )
                }
                if (index != stats.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyStatsCard(
    type: TransactionType,
    isCurrentMonth: Boolean,
    onOpenLedger: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = when {
                    !isCurrentMonth -> tr("该月没有数据")
                    type == TransactionType.INCOME -> tr("本月没有计入统计的收入（退款/转账已排除）")
                    else -> tr("本月还没有支出")
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenLedger) {
                Text(tr("去记一笔"))
            }
        }
    }
}

@Composable
private fun MonthPickerButton(
    month: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = monthLabel(month),
            style = MaterialTheme.typography.labelMedium,
            color = LocalQingSemanticColors.current.primaryDeep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun MonthPickerDialog(
    selectedMonth: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val selectedParts = remember(selectedMonth) { BudgetMonth.parse(selectedMonth) }
    var year by remember(selectedMonth) {
        mutableIntStateOf(selectedParts?.year ?: Calendar.getInstance().get(Calendar.YEAR))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("选择月份")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { year-- }) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = tr("上一年"))
                    }
                    Text(
                        text = "$year",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { year++ }) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = tr("下一年"))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onSelect(BudgetMonth.currentKey()) }) {
                        Text(tr("回到本月"))
                    }
                }
                for (row in 0 until 4) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (col in 0 until 3) {
                            val month = row * 3 + col + 1
                            val key = BudgetMonth.keyOf(year, month)
                            val selected = key == selectedMonth
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(key) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = monthCellLabel(year, month),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        },
    )
}

private fun monthCellLabel(year: Int, month: Int): String {
    if (AppLanguage.current() != AppLanguage.ENGLISH) return "${month}月"
    val cal = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
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
