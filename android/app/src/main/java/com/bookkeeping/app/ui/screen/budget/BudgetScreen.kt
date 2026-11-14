package com.bookkeeping.app.ui.screen.budget

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.budget.BudgetCalculations
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.BudgetStatus
import com.bookkeeping.app.data.budget.BudgetSummary
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.SectionHeader
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingErrorContainer
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.ui.theme.QingWarning
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(viewModel: BudgetViewModel = hiltViewModel()) {
    val budget by viewModel.budget.collectAsState()
    val expenseByCategory by viewModel.expenseByCategory.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()

    var showEditTotal by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<TransactionCategory?>(null) }

    LaunchedEffect(selectedMonth) {
        showEditTotal = false
        showAddCategory = false
        editingCategory = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(tr("预算"), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "month_selector") {
                MonthSelector(
                    month = selectedMonth,
                    onPrevious = { viewModel.setMonth(BudgetMonth.previousKey(selectedMonth)) },
                    onNext = { viewModel.setMonth(nextMonth(selectedMonth)) },
                    onAdjust = { showEditTotal = true },
                )
            }

            item(key = "budget_summary") {
                BudgetSummaryCard(summary = summary)
            }

            item(key = "pace") {
                PaceCard(summary = summary)
            }

            item(key = "category_header") {
                SectionHeader(
                    text = tr("分类预算"),
                    action = {
                        TextButton(onClick = { showAddCategory = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr("添加分类"))
                        }
                    },
                )
            }

            if (budget.perCategory.isEmpty()) {
                item(key = "category_empty") {
                    Text(
                        text = tr("还没有分类预算。点击右上角 + 添加。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                val rows = TransactionCategory.entries.filter { it in budget.perCategory }
                items(rows, key = { it.name }) { category ->
                    CategoryBudgetCard(
                        category = category,
                        budgetCents = budget.perCategory.getValue(category),
                        spentCents = expenseByCategory[category] ?: 0L,
                        onEdit = { editingCategory = category },
                        onRemove = { viewModel.removeCategoryBudget(category) },
                    )
                }
            }

            item(key = "advice") {
                BudgetAdviceCard(
                    summary = summary,
                    budget = budget.perCategory,
                    expenseByCategory = expenseByCategory,
                )
            }
        }
    }

    if (showEditTotal) {
        EditBudgetDialog(
            title = tr("设置月预算"),
            initialYuan = budget.monthlyTotalCents / 100.0,
            pickCategory = false,
            onDismiss = { showEditTotal = false },
            onConfirm = { _, yuan ->
                viewModel.setMonthlyTotal(yuan)
                showEditTotal = false
            },
        )
    }

    if (showAddCategory) {
        EditBudgetDialog(
            title = tr("添加分类预算"),
            pickCategory = true,
            excludeCategories = budget.perCategory.keys,
            onDismiss = { showAddCategory = false },
            onConfirm = { category, yuan ->
                if (category != null) viewModel.setCategoryBudget(category, yuan)
                showAddCategory = false
            },
        )
    }

    editingCategory?.let { category ->
        EditBudgetDialog(
            title = "${tr(category.displayName)} ${tr("预算")}",
            initialYuan = (budget.perCategory[category] ?: 0L) / 100.0,
            pickCategory = false,
            initialCategory = category,
            onDismiss = { editingCategory = null },
            onConfirm = { _, yuan ->
                viewModel.setCategoryBudget(category, yuan)
                editingCategory = null
            },
        )
    }
}

@Composable
private fun MonthSelector(
    month: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAdjust: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(QingSurface)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = tr("上一月"))
            }
            Text(
                text = monthLabel(month),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            IconButton(onClick = onNext) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = tr("下一月"))
            }
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(
            onClick = onAdjust,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("调整预算"))
        }
    }
}

@Composable
private fun BudgetSummaryCard(summary: BudgetSummary) {
    val hasBudget = summary.budgetCents > 0L
    val ratio = summary.usedRatio.coerceIn(0f, 1f)
    val over = summary.spentCents > summary.budgetCents && hasBudget

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QingPrimaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BudgetMetric(
                    label = tr("月预算"),
                    value = if (hasBudget) formatCny(summary.budgetCents) else tr("未设置"),
                    color = QingPrimaryDeep,
                    modifier = Modifier.weight(1f),
                )
                BudgetMetric(
                    label = tr("已使用"),
                    value = formatCny(summary.spentCents),
                    color = if (over) MaterialTheme.colorScheme.error else QingPrimaryDeep,
                    modifier = Modifier.weight(1f),
                )
                BudgetMetric(
                    label = tr("预算剩余"),
                    value = if (hasBudget) formatCny(summary.remainingCents) else "—",
                    color = if (over) MaterialTheme.colorScheme.error else QingPrimaryDeep,
                    modifier = Modifier.weight(1f),
                )
                BudgetMetric(
                    label = tr("剩余天数"),
                    value = "${summary.daysRemaining} ${tr("天")}",
                    color = QingPrimaryDeep,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = progressColor(ratio),
                trackColor = QingSurfaceVariant,
                drawStopIndicator = {},
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    text = if (over) {
                        "${tr("已超支")} ${formatCny(summary.spentCents - summary.budgetCents)}"
                    } else if (hasBudget) {
                        "${tr("已使用")} ${"%.1f".format(Locale.ROOT, summary.usedRatio * 100)}%"
                    } else {
                        tr("设置预算后可查看本月节奏与预测。")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (over) MaterialTheme.colorScheme.error else QingTextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (hasBudget) {
                    Text(
                        text = "${"%.1f".format(Locale.ROOT, summary.usedRatio * 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = progressColor(ratio),
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = QingTextSecondary,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PaceCard(summary: BudgetSummary) {
    val hasBudget = summary.budgetCents > 0L
    val over = summary.projectedRemainingCents < 0L
    val text = when {
        !hasBudget -> tr("设置预算后可查看本月节奏与预测。")
        over -> "${tr("按当前节奏，本月可能超支")} ${formatCny(-summary.projectedRemainingCents)}"
        else -> "${tr("按当前节奏，本月可控，预计可结余")} ${formatCny(summary.projectedRemainingCents)}"
    }
    val icon = if (over) Icons.Outlined.Warning else Icons.Outlined.CheckCircle
    val tint = if (over) MaterialTheme.colorScheme.error else QingPrimary

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (over) QingErrorContainer else QingMintSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr("本月节奏"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun CategoryBudgetCard(
    category: TransactionCategory,
    budgetCents: Long,
    spentCents: Long,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val ratio = if (budgetCents > 0L) spentCents.toFloat() / budgetCents.toFloat() else 0f
    val status = BudgetCalculations.status(budgetCents, spentCents)
    val (statusText, statusTone) = statusPresentation(status)
    val over = spentCents > budgetCents
    val percent = "%.1f".format(Locale.ROOT, ratio * 100)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIcon(category = category, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr(category.displayName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    SoftBadge(text = statusText, tone = statusTone)
                }
                Text(
                    text = "${tr("已使用")} ${formatCny(spentCents)} / ${formatCny(budgetCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                LinearProgressIndicator(
                    progress = { ratio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = progressColor(ratio),
                    trackColor = QingSurfaceVariant,
                    drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        text = if (over) {
                            "${tr("已超支")} ${formatCny(spentCents - budgetCents)}"
                        } else {
                            "${tr("剩余")} ${formatCny(budgetCents - spentCents)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (over) MaterialTheme.colorScheme.error else QingTextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = progressColor(ratio),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = tr("删除"),
                    tint = QingTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BudgetAdviceCard(
    summary: BudgetSummary,
    budget: Map<TransactionCategory, Long>,
    expenseByCategory: Map<TransactionCategory, Long>,
) {
    val hasBudget = summary.budgetCents > 0L
    val worst = if (hasBudget) {
        budget.entries
            .mapNotNull { (category, cents) ->
                if (cents <= 0L) null
                else Triple(category, cents, expenseByCategory[category] ?: 0L)
            }
            .maxByOrNull { (_, cents, spent) -> spent.toFloat() / cents.toFloat() }
    } else {
        null
    }
    val text = when {
        !hasBudget -> tr("设置预算后可查看本月节奏与预测。")
        worst == null -> tr("本月分类预算整体可控。")
        else -> {
            val item = requireNotNull(worst)
            val (category, cents, spent) = item
            val ratio = spent.toFloat() / cents.toFloat()
            val remaining = (cents - spent).coerceAtLeast(0L)
            val daily = if (summary.daysRemaining > 0) remaining / summary.daysRemaining else 0L
            when {
                ratio >= 1f -> "${tr(category.displayName)} ${tr("已超预算")} ${formatCny(spent - cents)}，${tr("建议调整预算或减少后续支出。")}"
                ratio >= BudgetCalculations.NEAR_LIMIT_RATIO -> {
                    "${tr(category.displayName)} ${tr("支出偏快")}（${tr("已用")} ${"%.1f".format(Locale.ROOT, ratio * 100)}%），" +
                        "${tr("建议后续控制在日均 ≤")} ${formatCny(daily)}。"
                }
                else -> tr("本月分类预算整体可控。")
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(QingMintSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = QingPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr("预算建议"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun statusPresentation(status: BudgetStatus): Pair<String, StatusTone> = when (status) {
    BudgetStatus.UNSET -> tr("未设置") to StatusTone.NEUTRAL
    BudgetStatus.NORMAL -> tr("正常") to StatusTone.POSITIVE
    BudgetStatus.NEAR_LIMIT -> tr("接近超支") to StatusTone.WARNING
    BudgetStatus.OVER -> tr("已超预算") to StatusTone.ERROR
}

@Composable
private fun progressColor(ratio: Float): Color = when {
    ratio < BudgetCalculations.NEAR_LIMIT_RATIO -> QingPrimary
    ratio < 1f -> QingWarning
    else -> MaterialTheme.colorScheme.error
}

private fun nextMonth(key: String): String {
    val parts = BudgetMonth.parse(key) ?: return key
    val year = if (parts.month == 12) parts.year + 1 else parts.year
    val month = if (parts.month == 12) 1 else parts.month + 1
    return BudgetMonth.keyOf(year, month)
}

@Composable
private fun monthLabel(key: String): String {
    val parts = BudgetMonth.parse(key) ?: return key
    val english = AppLanguage.current() == AppLanguage.ENGLISH
    val calendar = Calendar.getInstance().apply {
        clear()
        set(parts.year, parts.month - 1, 1)
    }
    val locale = if (english) Locale.ENGLISH else Locale.CHINA
    val pattern = if (english) "MMM yyyy" else "yyyy年M月"
    return SimpleDateFormat(pattern, locale).format(calendar.time)
}
