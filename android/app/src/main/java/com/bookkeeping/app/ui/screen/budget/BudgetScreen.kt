package com.bookkeeping.app.ui.screen.budget

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import com.bookkeeping.app.ui.theme.QingSpacing
import com.bookkeeping.app.ui.theme.QingType
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
    val budgetLoaded by viewModel.budgetLoaded.collectAsState()

    var showEditTotal by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<TransactionCategory?>(null) }
    var pendingRemoval by remember { mutableStateOf<TransactionCategory?>(null) }
    val hasBudget = summary.budgetCents > 0L
    val isCurrentMonth = BudgetMonth.isCurrent(selectedMonth)
    val isFutureMonth = selectedMonth > BudgetMonth.currentKey()

    LaunchedEffect(selectedMonth) {
        showEditTotal = false
        showAddCategory = false
        showMonthPicker = false
        editingCategory = null
        pendingRemoval = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tr("预算"),
                        style = QingType.pageTitle,
                        fontWeight = FontWeight.Bold,
                    )
                },
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
            contentPadding = PaddingValues(
                horizontal = QingSpacing.pageGutter,
                vertical = 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(QingSpacing.sectionGap),
        ) {
            item(key = "month_selector") {
                MonthSelector(
                    month = selectedMonth,
                    onPrevious = { viewModel.setMonth(BudgetMonth.previousKey(selectedMonth)) },
                    onNext = { viewModel.setMonth(nextMonth(selectedMonth)) },
                    onPickMonth = { showMonthPicker = true },
                    onAdjust = { showEditTotal = true },
                )
            }

            if (!budgetLoaded) {
                item(key = "loading") { BudgetLoadingCard() }
            } else {
                if (!hasBudget) {
                    item(key = "budget_empty") {
                        EmptyBudgetCard(
                            isCurrentMonth = isCurrentMonth,
                            onSetBudget = { showEditTotal = true },
                        )
                    }
                } else {
                    item(key = "budget_summary") {
                        BudgetSummaryCard(summary = summary, isCurrentMonth = isCurrentMonth)
                    }
                    item(key = "pace") {
                        PaceCard(
                            summary = summary,
                            isCurrentMonth = isCurrentMonth,
                            isFutureMonth = isFutureMonth,
                        )
                    }
                }

                item(key = "category_header") {
                    SectionHeader(
                        text = tr("分类预算"),
                        action = {
                            TextButton(onClick = { showAddCategory = true }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            onRemove = { pendingRemoval = category },
                        )
                    }
                }

                if (hasBudget && isCurrentMonth) {
                    item(key = "advice") {
                        BudgetAdviceCard(
                            summary = summary,
                            budget = budget.perCategory,
                            expenseByCategory = expenseByCategory,
                        )
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        BudgetMonthPickerDialog(
            selectedMonth = selectedMonth,
            onDismiss = { showMonthPicker = false },
            onSelect = {
                viewModel.setMonth(it)
                showMonthPicker = false
            },
        )
    }

    pendingRemoval?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(tr("删除分类预算")) },
            text = {
                Text(
                    "${tr(category.displayName)}：${tr("删除后该分类的预算会被清除，已记录支出不会删除。")}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeCategoryBudget(category)
                        pendingRemoval = null
                    },
                ) {
                    Text(tr("删除"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(tr("取消"))
                }
            },
        )
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
private fun BudgetLoadingCard() {
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
private fun MonthSelector(
    month: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickMonth: () -> Unit,
    onAdjust: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(50),
                )
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
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClick = onPickMonth)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
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
private fun BudgetSummaryCard(
    summary: BudgetSummary,
    isCurrentMonth: Boolean,
) {
    val semantic = LocalQingSemanticColors.current
    val hasBudget = summary.budgetCents > 0L
    val ratio = summary.usedRatio.coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(targetValue = ratio, label = "budgetProgress")
    val over = summary.spentCents > summary.budgetCents && hasBudget

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BudgetMetric(
                    label = tr("月预算"),
                    value = if (hasBudget) formatCny(summary.budgetCents) else tr("未设置"),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                BudgetMetric(
                    label = tr("已使用"),
                    value = formatCny(summary.spentCents),
                    color = if (over) semantic.expenseText
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BudgetMetric(
                    label = tr("预算剩余"),
                    value = if (hasBudget) formatCny(summary.remainingCents) else "—",
                    color = if (over) semantic.expenseText
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                BudgetMetric(
                    label = tr("剩余天数"),
                    value = if (isCurrentMonth) {
                        "${summary.daysRemaining} ${tr("天")}"
                    } else {
                        "—"
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { animatedRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = progressColor(ratio),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                    style = QingType.metricLabel,
                    color = if (over) semantic.expenseText
                    else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    modifier = Modifier.weight(1f),
                )
                if (hasBudget) {
                    Text(
                        text = "${"%.1f".format(Locale.ROOT, summary.usedRatio * 100)}%",
                        style = QingType.metricLabel,
                        color = if (over) semantic.expenseText else semantic.primaryDeep,
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
            style = QingType.metricLabel,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            maxLines = 2,
        )
        Text(
            text = value,
            style = QingType.secondaryKpi,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 2,
            softWrap = true,
        )
    }
}

@Composable
private fun EmptyBudgetCard(
    isCurrentMonth: Boolean,
    onSetBudget: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
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
                text = if (isCurrentMonth) {
                    tr("设置本月预算后，才能查看使用进度、节奏和建议。")
                } else {
                    tr("设置该月预算后，才能查看使用进度、节奏和建议。")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onSetBudget) {
                Text(if (isCurrentMonth) tr("设置本月预算") else tr("设置该月预算"))
            }
        }
    }
}

@Composable
private fun PaceCard(
    summary: BudgetSummary,
    isCurrentMonth: Boolean,
    isFutureMonth: Boolean,
) {
    val semantic = LocalQingSemanticColors.current
    val hasBudget = summary.budgetCents > 0L
    val over = summary.projectedRemainingCents < 0L
    val text = when {
        !isCurrentMonth && isFutureMonth -> tr("该月尚未开始。")
        !isCurrentMonth -> tr("该月已结束。")
        !hasBudget -> tr("设置预算后可查看本月节奏与预测。")
        over -> "${tr("按当前节奏，本月可能超支")} ${formatCny(-summary.projectedRemainingCents)}"
        else -> "${tr("按当前节奏，本月可控，预计可结余")} ${formatCny(summary.projectedRemainingCents)}"
    }
    val icon = when {
        !isCurrentMonth -> Icons.Outlined.CalendarMonth
        over -> Icons.Outlined.Warning
        else -> Icons.Outlined.CheckCircle
    }
    val tint = when {
        !isCurrentMonth -> MaterialTheme.colorScheme.primary
        over -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val container = when {
        !isCurrentMonth -> semantic.mintSoft
        over -> MaterialTheme.colorScheme.errorContainer
        else -> semantic.mintSoft
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isCurrentMonth) tr("本月节奏") else tr("预算节奏"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val semantic = LocalQingSemanticColors.current
    val ratio = if (budgetCents > 0L) spentCents.toFloat() / budgetCents.toFloat() else 0f
    val animatedRatio by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        label = "categoryBudgetProgress",
    )
    val status = BudgetCalculations.status(budgetCents, spentCents)
    val (statusText, statusTone) = statusPresentation(status)
    val over = spentCents > budgetCents
    val percent = "%.1f".format(Locale.ROOT, ratio * 100)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onEdit)
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
                    if (status == BudgetStatus.NEAR_LIMIT || status == BudgetStatus.OVER) {
                        BudgetStatusBadge(text = statusText, tone = statusTone)
                    }
                }
                Text(
                    text = "${tr("已使用")} ${formatCny(spentCents)} / ${formatCny(budgetCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                LinearProgressIndicator(
                    progress = { animatedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = progressColor(ratio),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        text = if (over) {
                            "${tr("已超支")} ${formatCny(spentCents - budgetCents)}"
                        } else {
                            "${tr("剩余")} ${formatCny(budgetCents - spentCents)}"
                        },
                        style = QingType.metricLabel,
                        color = if (over) semantic.expenseText
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$percent%",
                        style = QingType.metricLabel,
                        color = if (over) semantic.expenseText
                        else if (ratio >= BudgetCalculations.NEAR_LIMIT_RATIO) semantic.warningText
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = tr("删除"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BudgetStatusBadge(text: String, tone: StatusTone) {
    val semantic = LocalQingSemanticColors.current
    val (background, foreground) = when (tone) {
        StatusTone.POSITIVE -> semantic.mintSoft to semantic.primaryDeep
        StatusTone.WARNING -> semantic.warningContainer to semantic.warningText
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        color = foreground,
        style = QingType.metricLabel,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalQingSemanticColors.current.mintSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun progressColor(ratio: Float): Color {
    val semantic = LocalQingSemanticColors.current
    return when {
        ratio < BudgetCalculations.NEAR_LIMIT_RATIO -> MaterialTheme.colorScheme.primary
        ratio < 1f -> semantic.warningText
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun BudgetMonthPickerDialog(
    selectedMonth: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val selectedParts = remember(selectedMonth) { BudgetMonth.parse(selectedMonth) }
    var year by remember(selectedMonth) {
        mutableStateOf(selectedParts?.year ?: Calendar.getInstance().get(Calendar.YEAR))
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
