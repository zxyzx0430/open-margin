package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.CalendarDateText
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Composable
fun LedgerCalendarDialog(
    initialMonth: String,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onMonthSelected: (String) -> Unit,
) {
    var currentMonth by remember(initialMonth) { mutableStateOf(initialMonth) }
    var selectedStart by remember(currentMonth) { mutableStateOf<Int?>(null) }
    var selectedEnd by remember(currentMonth) { mutableStateOf<Int?>(null) }
    val parts = BudgetMonth.parse(currentMonth) ?: BudgetMonth.parse(BudgetMonth.currentKey())!!
    val monthStart = remember(currentMonth) { BudgetMonth.range(currentMonth).first }
    val monthEnd = remember(currentMonth) { BudgetMonth.range(currentMonth).second }
    val monthTransactions = remember(transactions, currentMonth) {
        transactions.filter { it.timestamp in monthStart until monthEnd && !it.syncDeleted }
    }

    Dialog(
        onDismissRequest = {
            onMonthSelected(currentMonth)
            onDismiss()
        },
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
                            text = CalendarDateText.monthHeading(parts.year, parts.month),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = tr("滑动日期选择区间，或点选单日"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        onMonthSelected(currentMonth)
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = tr("关闭"))
                    }
                }
                MonthTotalsRow(transactions = monthTransactions)
                Spacer(Modifier.height(8.dp))
                CalendarGrid(
                    year = parts.year,
                    month = parts.month,
                    transactions = monthTransactions,
                    selectedStart = selectedStart,
                    selectedEnd = selectedEnd,
                    onSelect = { start, end ->
                        selectedStart = start
                        selectedEnd = end
                    },
                    onMonthChange = { delta ->
                        currentMonth = shiftMonth(currentMonth, delta)
                    },
                )
                val rangeTransactions = remember(monthTransactions, selectedStart, selectedEnd) {
                    val start = selectedStart
                    val end = selectedEnd
                    if (start == null || end == null) emptyList()
                    else monthTransactions.filter {
                        val day = dayOf(it.timestamp)
                        day in min(start, end)..max(start, end)
                    }.sortedByDescending { it.timestamp }
                }
                val hasSelection = selectedStart != null && selectedEnd != null
                Spacer(Modifier.height(8.dp))
                PeriodSummary(
                    title = if (hasSelection) {
                        rangeLabel(parts.year, parts.month, selectedStart!!, selectedEnd!!)
                    } else {
                        tr("本月汇总")
                    },
                    transactions = if (hasSelection) rangeTransactions else monthTransactions,
                )
                if (hasSelection && rangeTransactions.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp),
                    ) {
                        items(rangeTransactions, key = { it.id }) { transaction ->
                            CalendarTransactionRow(transaction)
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MonthTotalsRow(transactions: List<TransactionEntity>) {
    val semantic = LocalQingSemanticColors.current
    val expense = transactions.filter { it.isCountedExpense() }.sumOf { it.amountCents }
    val income = transactions.filter { it.isCountedIncome() }.sumOf { it.amountCents }
    val balance = income - expense
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CalendarMetric(
            label = tr("支出"),
            value = formatCny(expense),
            color = semantic.expenseText,
            modifier = Modifier.weight(1f),
        )
        CalendarMetric(
            label = tr("收入"),
            value = formatCny(income),
            color = semantic.income,
            modifier = Modifier.weight(1f),
        )
        CalendarMetric(
            label = tr("结余"),
            value = (if (balance >= 0) "+" else "−") + formatCny(kotlin.math.abs(balance)),
            color = if (balance >= 0) semantic.income else semantic.expenseText,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CalendarMetric(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CalendarGrid(
    year: Int,
    month: Int,
    transactions: List<TransactionEntity>,
    selectedStart: Int?,
    selectedEnd: Int?,
    onSelect: (Int?, Int?) -> Unit,
    onMonthChange: (Int) -> Unit,
) {
    val daysInMonth = daysInMonth(year, month)
    val firstOffset = firstDayOffset(year, month)
    val transactedDays = remember(transactions) {
        transactions.map { dayOf(it.timestamp) }.toSet()
    }
    val localeEnglish = AppLanguage.current() == AppLanguage.ENGLISH
    val weekLabels = if (localeEnglish) {
        listOf("S", "M", "T", "W", "T", "F", "S")
    } else {
        listOf("日", "一", "二", "三", "四", "五", "六")
    }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 44.dp.toPx() }
    val gridHeight = 44.dp * 6
    var gridWidthPx by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekLabels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .onSizeChanged { size ->
                    gridWidthPx = size.width.toFloat()
                }
                .pointerInput(year, month, gridWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startDay = dayAt(down.position, gridWidthPx, rowHeightPx, firstOffset, daysInMonth)
                            ?: return@awaitEachGesture
                        var currentDay = startDay
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val day = dayAt(change.position, gridWidthPx, rowHeightPx, firstOffset, daysInMonth)
                            if (day != null) currentDay = day
                            onSelect(min(startDay, currentDay), max(startDay, currentDay))
                            change.consume()
                        }
                        if (startDay == currentDay) {
                            onSelect(startDay, startDay)
                        }
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                for (row in 0 until 6) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        for (col in 0 until 7) {
                            val cellIndex = row * 7 + col
                            val day = cellIndex - firstOffset + 1
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .background(
                                        cellBackground(day, daysInMonth, selectedStart, selectedEnd),
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (day in 1..daysInMonth) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "$day",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = cellTextColor(day, selectedStart, selectedEnd),
                                            fontWeight = if (day == selectedStart || day == selectedEnd) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                        if (day in transactedDays) {
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onMonthChange(-1) }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = tr("上个月"))
            }
            Text(
                text = CalendarDateText.monthShort(year, month),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onMonthChange(1) }) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = tr("下个月"))
            }
        }
    }
}

@Composable
private fun PeriodSummary(title: String, transactions: List<TransactionEntity>) {
    val semantic = LocalQingSemanticColors.current
    val expense = transactions.filter { it.isCountedExpense() }.sumOf { it.amountCents }
    val income = transactions.filter { it.isCountedIncome() }.sumOf { it.amountCents }
    val balance = income - expense
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${transactions.size} ${tr("笔")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "${tr("支出")} ${formatCny(expense)}",
                color = semantic.expenseText,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "${tr("收入")} ${formatCny(income)}",
                color = semantic.income,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "${tr("结余")} " +
                    (if (balance >= 0) "+" else "−") + formatCny(kotlin.math.abs(balance)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CalendarTransactionRow(transaction: TransactionEntity) {
    val expense = transaction.type == "EXPENSE"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = timeLabel(transaction.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.merchant.ifBlank { tr("未知商户") },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = transaction.category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = (if (expense) "−" else "+") + formatCny(transaction.amountCents),
            style = MaterialTheme.typography.titleSmall,
            color = if (expense) {
                LocalQingSemanticColors.current.expenseText
            } else {
                LocalQingSemanticColors.current.income
            },
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

private fun TransactionEntity.isCountedExpense(): Boolean =
    type == "EXPENSE" && category != "TRANSFER" && currency == "CNY" &&
        !syncDeleted && duplicateOfId == null &&
        remoteReviewStatus !in setOf("excluded", "transfer", "refund", "possible_duplicate")

private fun TransactionEntity.isCountedIncome(): Boolean =
    type == "INCOME" && currency == "CNY" &&
        !syncDeleted && duplicateOfId == null &&
        remoteReviewStatus !in setOf("excluded", "transfer", "refund", "possible_duplicate")

private fun dayAt(
    position: Offset,
    gridWidthPx: Float,
    rowHeightPx: Float,
    firstOffset: Int,
    daysInMonth: Int,
): Int? {
    if (gridWidthPx <= 0f || rowHeightPx <= 0f) return null
    val col = (position.x / (gridWidthPx / 7f)).toInt().coerceIn(0, 6)
    val row = (position.y / rowHeightPx).toInt().coerceIn(0, 5)
    val day = row * 7 + col - firstOffset + 1
    return day.takeIf { it in 1..daysInMonth }
}

@Composable
private fun cellBackground(
    day: Int,
    daysInMonth: Int,
    start: Int?,
    end: Int?,
): androidx.compose.ui.graphics.Color {
    if (day !in 1..daysInMonth) return androidx.compose.ui.graphics.Color.Transparent
    if (start == null || end == null) return androidx.compose.ui.graphics.Color.Transparent
    return if (day in min(start, end)..max(start, end)) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
}

@Composable
private fun cellTextColor(day: Int, start: Int?, end: Int?): androidx.compose.ui.graphics.Color =
    if (start != null && end != null && day in min(start, end)..max(start, end)) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

private fun daysInMonth(year: Int, month: Int): Int =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

private fun firstDayOffset(year: Int, month: Int): Int =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.get(Calendar.DAY_OF_WEEK) - 1

private fun dayOf(timestamp: Long): Int =
    Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.DAY_OF_MONTH)

private fun shiftMonth(monthKey: String, delta: Int): String {
    val parts = BudgetMonth.parse(monthKey) ?: return monthKey
    val cal = Calendar.getInstance().apply {
        clear()
        set(parts.year, parts.month - 1, 1)
        add(Calendar.MONTH, delta)
    }
    return BudgetMonth.keyOf(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
}

private fun rangeLabel(year: Int, month: Int, start: Int, end: Int): String =
    CalendarDateText.sameMonthRange(year, month, start, end)

private fun timeLabel(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
