package com.bookkeeping.app.ui.screen.budget

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookkeeping.app.ui.components.rememberQingTitleCollapseState
import com.bookkeeping.app.ui.components.QingLargeTitle
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import com.bookkeeping.app.ui.components.LocalQingChromeOverlap
import com.bookkeeping.app.ui.components.QingConfirmationSheet
import com.bookkeeping.app.ui.components.WalletCardSurface
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.QingTypeTokens
import com.bookkeeping.app.ui.theme.qingColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
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
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.stats.StatsLedgerDrilldown
import com.bookkeeping.app.ui.screen.stats.StatsPeriod
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.MonthPickerDialog
import com.bookkeeping.app.ui.components.SectionHeader
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.components.monthLabel
import com.bookkeeping.app.ui.theme.QingErrorContainer
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingOutline
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
fun BudgetScreen(
    onOpenLedger: (StatsLedgerDrilldown) -> Unit = {},
    onOpenSubscriptions: (Boolean) -> Unit = {},
    viewModel: BudgetViewModel = hiltViewModel(),
) {
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val expenseByCategory by viewModel.expenseByCategory.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val previousMonthReference by viewModel.previousMonthExpenseReference.collectAsStateWithLifecycle()
    val monthlyLockedSubscriptionCents by viewModel.monthlyLockedSubscriptionCents.collectAsStateWithLifecycle()
    val previousMonthExpenseCents = previousMonthReference
        .takeIf { it.targetMonth == selectedMonth }?.amountCents ?: 0L

    var showEditTotal by remember { mutableStateOf(false) }
    var suggestedTotalYuan by remember { mutableStateOf<Double?>(null) }
    var showAddCategory by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<TransactionCategory?>(null) }
    var pendingRemoveCategory by remember { mutableStateOf<TransactionCategory?>(null) }

    LaunchedEffect(selectedMonth) {
        suggestedTotalYuan = null
        showEditTotal = false
        showAddCategory = false
        showMonthPicker = false
        editingCategory = null
        pendingRemoveCategory = null
    }

    val currentMonth = BudgetMonth.currentKey()
    val isCurrentMonth = selectedMonth == currentMonth
    val isFutureMonth = selectedMonth > currentMonth
    val hasBudget = summary.budgetCents > 0L

    val budgetListState = rememberLazyListState()
    val titleCollapse = rememberQingTitleCollapseState(budgetListState)
    Scaffold(
        modifier = Modifier.nestedScroll(titleCollapse.connection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { QingLargeTitle(tr("预算"), titleCollapse) },
    ) { innerPadding ->
        LazyColumn(
            state = budgetListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp + LocalQingChromeOverlap.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "month_selector") {
                MonthSelector(
                    month = selectedMonth,
                    onPrevious = { viewModel.setMonth(BudgetMonth.previousKey(selectedMonth)) },
                    onNext = { viewModel.setMonth(nextMonth(selectedMonth)) },
                    onPickMonth = { showMonthPicker = true },
                    onAdjust = { suggestedTotalYuan = null; showEditTotal = true },
                )
            }

            item(key = "budget_summary") {
                BudgetSummaryCard(
                    summary = summary,
                    isCurrentMonth = isCurrentMonth,
                    isFutureMonth = isFutureMonth,
                    monthlyLockedSubscriptionCents = monthlyLockedSubscriptionCents,
                    onOpenSubscriptions = { onOpenSubscriptions(false) },
                    onSetBudget = { suggestedTotalYuan = null; showEditTotal = true },
                )
            }

            if (!hasBudget && previousMonthExpenseCents > 0L) {
                item(key = "previous_month_budget_reference") {
                    TextButton(
                        onClick = {
                            suggestedTotalYuan = previousMonthExpenseCents / 100.0
                            showEditTotal = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = tr("参考上月支出 %s 填入预算")
                                .format(formatCny(previousMonthExpenseCents)),
                        )
                    }
                }
            }

            item(key = "category_header") {
                SectionHeader(
                    text = tr("分类预算"),
                    action = {
                        TextButton(onClick = { showAddCategory = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                tr("添加分类"),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
            }

            item(key = "category_list") {
                val rows = TransactionCategory.entries.filter { it in budget.perCategory }
                if (rows.isEmpty()) {
                    Text(
                        text = tr("还没有分类预算，点击上方“添加分类”开始。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                    )
                } else {
                    WalletCardSurface(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            rows.forEachIndexed { index, category ->
                                CategoryBudgetRow(
                                    category = category,
                                    budgetCents = budget.perCategory.getValue(category),
                                    spentCents = expenseByCategory[category] ?: 0L,
                                    onEdit = { editingCategory = category },
                                    onViewLedger = {
                                        BudgetMonth.parse(selectedMonth)?.let { month ->
                                            onOpenLedger(
                                                StatsLedgerDrilldown(
                                                    period = StatsPeriod.MONTH,
                                                    year = month.year,
                                                    month = month.month,
                                                    category = category,
                                                    type = TransactionType.EXPENSE,
                                                ),
                                            )
                                        }
                                    },
                                    onRemove = { pendingRemoveCategory = category },
                                )
                                if (index != rows.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        thickness = 0.5.dp,
                                        color = qingColors.strokeHairline,
                                    )
                                }
                            }
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

    pendingRemoveCategory?.let { category ->
        QingConfirmationSheet(
            onDismissRequest = { pendingRemoveCategory = null },
            title = { Text(tr("删除分类预算？")) },
            text = { Text("${tr(category.displayName)}${tr("的预算将被清除，已产生的支出记录不会删除。")}") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeCategoryBudget(category)
                    pendingRemoveCategory = null
                }) {
                    Text(tr("删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveCategory = null }) { Text(tr("取消")) }
            },
        )
    }

    if (showEditTotal) {
        EditBudgetDialog(
            title = tr("设置月预算"),
            initialYuan = suggestedTotalYuan ?: budget.monthlyTotalCents / 100.0,
            pickCategory = false,
            onDismiss = { showEditTotal = false },
            onConfirm = { _, yuan ->
                viewModel.setMonthlyTotal(yuan)
                suggestedTotalYuan = null
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
    onPickMonth: () -> Unit,
    onAdjust: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = tr("上一月"))
        }
        Text(
            text = monthLabel(month),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(QingRadiusTokens.radiusControl))
                .clickable(role = Role.Button, onClick = onPickMonth)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = tr("下一月"))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAdjust) {
            Text(
                tr("调整预算"),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BudgetSummaryCard(
    summary: BudgetSummary,
    isCurrentMonth: Boolean,
    isFutureMonth: Boolean,
    monthlyLockedSubscriptionCents: Long,
    onOpenSubscriptions: () -> Unit,
    onSetBudget: () -> Unit,
) {
    val hasBudget = summary.budgetCents > 0L
    val ratio = summary.usedRatio.coerceIn(0f, 1f)
    val over = summary.spentCents > summary.budgetCents && hasBudget
    val colors = qingColors

    WalletCardSurface(hero = true, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (!hasBudget) {
                Text(
                    text = if (isCurrentMonth) tr("本月还没有预算") else tr("该月还没有预算"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = tr("设置预算后可查看使用进度和预测，分类预算仍然可选。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                Button(
                    onClick = onSetBudget,
                    shape = RoundedCornerShape(QingRadiusTokens.radiusControl),
                ) {
                    Text(tr("设置本月预算"))
                }
                return@Column
            }
            Text(
                text = if (over) tr("本月已超支") else tr("本月剩余"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            AutoSizeAmountText(
                text = formatCny(if (over) summary.spentCents - summary.budgetCents else summary.remainingCents),
                style = QingTypeTokens.heroAmount,
                fontWeight = FontWeight.Bold,
                color = if (over) colors.semanticExpense else colors.textPrimary,
            )
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(QingRadiusTokens.radiusPill)),
                color = progressColor(ratio),
                trackColor = colors.accentPrimary.copy(alpha = 0.12f),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tail = when {
                    !isCurrentMonth && isFutureMonth -> tr("尚未开始")
                    !isCurrentMonth -> tr("该月已结束")
                    else -> "${tr("剩")} ${summary.daysRemaining} ${tr("天")}"
                }
                Text(
                    text = "${tr("已用")} ${formatCny(summary.spentCents)} / ${formatCny(summary.budgetCents)} · $tail",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${"%.1f".format(Locale.ROOT, summary.usedRatio * 100)}%",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = progressColor(ratio),
                )
            }
            if (monthlyLockedSubscriptionCents > 0L || isCurrentMonth) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = 0.5.dp,
                    color = colors.strokeHairline,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (monthlyLockedSubscriptionCents > 0L) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(QingRadiusTokens.radiusBadge))
                                .clickable(role = Role.Button, onClick = onOpenSubscriptions)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tr("含周期续费"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${formatCny(monthlyLockedSubscriptionCents)} ›",
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Medium,
                                color = colors.accentPrimary,
                            )
                        }
                    }
                    if (isCurrentMonth) {
                        val paceOver = summary.projectedRemainingCents < 0L
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (paceOver) tr("按当前节奏预计超支") else tr("按当前节奏预计结余"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatCny(if (paceOver) -summary.projectedRemainingCents else summary.projectedRemainingCents),
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Medium,
                                color = if (paceOver) colors.semanticExpense else colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBudgetRow(
    category: TransactionCategory,
    budgetCents: Long,
    spentCents: Long,
    onEdit: () -> Unit,
    onViewLedger: () -> Unit,
    onRemove: () -> Unit,
) {
    val ratio = if (budgetCents > 0L) spentCents.toFloat() / budgetCents.toFloat() else 0f
    val status = BudgetCalculations.status(budgetCents, spentCents)
    val (statusText, statusTone) = statusPresentation(status)
    val over = spentCents > budgetCents
    val percent = "%.1f".format(Locale.ROOT, ratio * 100)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onViewLedger)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(category = category, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr(category.displayName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        softWrap = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (status != BudgetStatus.NORMAL && status != BudgetStatus.UNSET) {
                        SoftBadge(text = statusText, tone = statusTone)
                    }
                }
                Text(
                    text = "${tr("已使用")} ${formatCny(spentCents)} / ${formatCny(budgetCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                    softWrap = true,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = tr("编辑分类预算"),
                    tint = QingTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
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
        LinearProgressIndicator(
            progress = { ratio.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color = progressColor(ratio),
            trackColor = QingSurfaceVariant,
            drawStopIndicator = {},
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
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
