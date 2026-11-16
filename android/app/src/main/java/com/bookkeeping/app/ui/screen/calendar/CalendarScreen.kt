package com.bookkeeping.app.ui.screen.calendar

import android.content.Context
import android.icu.util.ChineseCalendar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val CALENDAR_PREFS = "calendar_view_prefs"
private const val KEY_HEATMAP = "heatmap"
private const val KEY_WEEK_START_MONDAY = "week_start_monday"
private const val KEY_LUNAR = "lunar"

private enum class HeatmapMode(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    NET("净额"),
    OFF("关闭"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsState()
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(CALENDAR_PREFS, Context.MODE_PRIVATE)
    }
    var heatmap by remember {
        mutableStateOf(
            runCatching {
                HeatmapMode.valueOf(prefs.getString(KEY_HEATMAP, HeatmapMode.EXPENSE.name)!!)
            }.getOrDefault(HeatmapMode.EXPENSE),
        )
    }
    var weekStartsMonday by remember {
        mutableStateOf(prefs.getBoolean(KEY_WEEK_START_MONDAY, false))
    }
    var showLunar by remember { mutableStateOf(prefs.getBoolean(KEY_LUNAR, true)) }
    var monthKey by remember { mutableStateOf(BudgetMonth.currentKey()) }
    val today = remember {
        val cal = Calendar.getInstance()
        CalendarDate(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }
    var rangeStart by remember { mutableStateOf<CalendarDate?>(today) }
    var rangeEnd by remember { mutableStateOf<CalendarDate?>(today) }
    var menuExpanded by remember { mutableStateOf(false) }

    val parts = BudgetMonth.parse(monthKey) ?: BudgetMonth.parse(BudgetMonth.currentKey())!!
    val (monthStart, monthEnd) = remember(monthKey) { BudgetMonth.range(monthKey) }
    val monthTransactions = remember(transactions, monthKey) {
        transactions.filter { it.timestamp in monthStart until monthEnd && !it.syncDeleted }
    }
    val selectedTransactions = remember(transactions, rangeStart, rangeEnd) {
        val start = rangeStart
        val end = rangeEnd
        if (start == null || end == null) emptyList()
        else transactions.filter {
            !it.syncDeleted && dateOf(it.timestamp) in start..end
        }.sortedByDescending { it.timestamp }
    }

    fun save() {
        prefs.edit()
            .putString(KEY_HEATMAP, heatmap.name)
            .putBoolean(KEY_WEEK_START_MONDAY, weekStartsMonday)
            .putBoolean(KEY_LUNAR, showLunar)
            .apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MonthHeader(
            monthKey = monthKey,
            onPrevious = { monthKey = shiftMonth(monthKey, -1) },
            onNext = { monthKey = shiftMonth(monthKey, 1) },
            menu = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = tr("日历设置"))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(tr("热力背景：") + heatmap.label) },
                            onClick = {
                                heatmap = HeatmapMode.entries[(heatmap.ordinal + 1) % HeatmapMode.entries.size]
                                save()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    tr("周起始日：") +
                                        if (weekStartsMonday) tr("周一") else tr("周日"),
                                )
                            },
                            onClick = {
                                weekStartsMonday = !weekStartsMonday
                                save()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("农历：") + if (showLunar) tr("显示") else tr("隐藏")) },
                            onClick = {
                                showLunar = !showLunar
                                save()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("清除选择")) },
                            onClick = {
                                rangeStart = null
                                rangeEnd = null
                                menuExpanded = false
                            },
                        )
                    }
                }
            },
        )
        Text(
            text = tr("左右滑动切月 · 拖到边缘可跨月 · 点起点后切月点终点也可跨月"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MonthTotalsRow(monthTransactions)
        CalendarMonthGrid(
            year = parts.year,
            month = parts.month,
            transactions = monthTransactions,
            weekStartsMonday = weekStartsMonday,
            showLunar = showLunar,
            heatmap = heatmap,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            onSelect = { start, end ->
                val existingStart = rangeStart
                val existingEnd = rangeEnd
                if (existingStart != null && existingEnd != null &&
                    existingStart == existingEnd &&
                    (start.year != existingStart.year || start.month != existingStart.month)
                ) {
                    rangeStart = if (existingStart <= end) existingStart else end
                    rangeEnd = if (existingStart <= end) end else existingStart
                } else {
                    rangeStart = start
                    rangeEnd = end
                }
            },
            onEdge = { forward ->
                val nextKey = shiftMonth(monthKey, if (forward) 1 else -1)
                val nextParts = BudgetMonth.parse(nextKey)
                if (nextParts != null) {
                    val edgeDay = if (forward) 1 else daysInMonth(nextParts.year, nextParts.month)
                    val edgeDate = CalendarDate(nextParts.year, nextParts.month, edgeDay)
                    val anchor = rangeStart
                    if (anchor == null) {
                        rangeStart = edgeDate
                        rangeEnd = edgeDate
                    } else {
                        rangeStart = if (anchor <= edgeDate) anchor else edgeDate
                        rangeEnd = if (anchor <= edgeDate) edgeDate else anchor
                    }
                    monthKey = nextKey
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        SelectionSummary(
            start = rangeStart,
            end = rangeEnd,
            transactions = selectedTransactions,
            monthFallback = monthTransactions,
        )
        if (selectedTransactions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(selectedTransactions, key = { it.id }) { transaction ->
                    CalendarTransactionRow(transaction)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (rangeStart != null) {
                        tr("这一天你还没有任何记账")
                    } else {
                        tr("滑动日期选择区间，或点选单日")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    monthKey: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total > 80f) onPrevious()
                        else if (total < -80f) onNext()
                    },
                    onHorizontalDrag = { _, dragAmount -> total += dragAmount },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = tr("上个月"))
        }
        Text(
            text = monthLabel(monthKey),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = tr("下个月"))
        }
        menu()
    }
}

@Composable
private fun MonthTotalsRow(transactions: List<TransactionEntity>) {
    val semantic = LocalQingSemanticColors.current
    val expense = transactions.filter { it.countsAsExpense() }.sumOf { it.amountCents }
    val income = transactions.filter { it.countsAsIncome() }.sumOf { it.amountCents }
    val net = income - expense
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CalendarMetric(tr("支出"), formatCny(expense), semantic.expenseText, Modifier.weight(1f))
        CalendarMetric(tr("收入"), formatCny(income), semantic.income, Modifier.weight(1f))
        CalendarMetric(
            tr("结余"),
            (if (net >= 0) "+" else "−") + formatCny(abs(net)),
            if (net >= 0) semantic.income else semantic.expenseText,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun CalendarMetric(
    label: String,
    value: String,
    color: Color,
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
private fun CalendarMonthGrid(
    year: Int,
    month: Int,
    transactions: List<TransactionEntity>,
    weekStartsMonday: Boolean,
    showLunar: Boolean,
    heatmap: HeatmapMode,
    rangeStart: CalendarDate?,
    rangeEnd: CalendarDate?,
    onSelect: (CalendarDate, CalendarDate) -> Unit,
    onEdge: (Boolean) -> Unit,
) {
    val daysInMonth = daysInMonth(year, month)
    val firstOffset = firstDayOffset(year, month, weekStartsMonday)
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 36.dp.toPx() }
    var gridWidthPx by remember { mutableStateOf(0f) }
    val byDay = remember(transactions) { transactions.groupBy { dayOf(it.timestamp) } }
    val heatValues = remember(byDay, heatmap) {
        byDay.mapValues { (_, list) ->
            when (heatmap) {
                HeatmapMode.EXPENSE -> list.filter { it.countsAsExpense() }.sumOf { it.amountCents }
                HeatmapMode.INCOME -> list.filter { it.countsAsIncome() }.sumOf { it.amountCents }
                HeatmapMode.NET -> {
                    val income = list.filter { it.countsAsIncome() }.sumOf { it.amountCents }
                    val expense = list.filter { it.countsAsExpense() }.sumOf { it.amountCents }
                    abs(income - expense)
                }
                HeatmapMode.OFF -> 0L
            }
        }
    }
    val heatMax = heatValues.values.maxOrNull() ?: 0L
    val labels = if (weekStartsMonday) {
        if (AppLanguage.current() == AppLanguage.ENGLISH) listOf("M", "T", "W", "T", "F", "S", "S")
        else listOf("一", "二", "三", "四", "五", "六", "日")
    } else {
        if (AppLanguage.current() == AppLanguage.ENGLISH) listOf("S", "M", "T", "W", "T", "F", "S")
        else listOf("日", "一", "二", "三", "四", "五", "六")
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp * 6)
                .onSizeChanged { gridWidthPx = it.width.toFloat() }
                .pointerInput(year, month, gridWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val first = dayAt(
                            down.position,
                            gridWidthPx,
                            rowHeightPx,
                            firstOffset,
                            daysInMonth,
                        ) ?: return@awaitEachGesture
                        var current = first
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.position.x > gridWidthPx + 8f) {
                                onEdge(true)
                                change.consume()
                                break
                            }
                            if (change.position.x < -8f) {
                                onEdge(false)
                                change.consume()
                                break
                            }
                            val day = dayAt(
                                change.position,
                                gridWidthPx,
                                rowHeightPx,
                                firstOffset,
                                daysInMonth,
                            )
                            if (day != null) current = day
                            onSelect(
                                CalendarDate(year, month, min(first, current)),
                                CalendarDate(year, month, max(first, current)),
                            )
                            change.consume()
                        }
                        if (first == current) {
                            val date = CalendarDate(year, month, first)
                            onSelect(date, date)
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                for (row in 0 until 6) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                    ) {
                        for (col in 0 until 7) {
                            val day = row * 7 + col - firstOffset + 1
                            val inMonth = day in 1..daysInMonth
                            val date = if (inMonth) CalendarDate(year, month, day) else null
                            val inRange = date != null && rangeStart != null && rangeEnd != null &&
                                date >= rangeStart && date <= rangeEnd
                            val isEdge = date != null &&
                                (date == rangeStart || date == rangeEnd)
                            val heat = if (inMonth && heatMax > 0) {
                                (heatValues[day] ?: 0L).toFloat() / heatMax.toFloat()
                            } else {
                                0f
                            }
                            val base = when (heatmap) {
                                HeatmapMode.INCOME -> MaterialTheme.colorScheme.primary
                                HeatmapMode.EXPENSE -> MaterialTheme.colorScheme.error
                                HeatmapMode.NET -> MaterialTheme.colorScheme.primary
                                HeatmapMode.OFF -> Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isEdge -> MaterialTheme.colorScheme.primary
                                            inRange -> MaterialTheme.colorScheme.primaryContainer
                                            else -> Color.Transparent
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (inMonth) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "$day",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isEdge || inRange) FontWeight.Bold else FontWeight.Normal,
                                            color = when {
                                                isEdge -> MaterialTheme.colorScheme.onPrimary
                                                inRange -> MaterialTheme.colorScheme.onPrimaryContainer
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        if (showLunar) {
                                            Text(
                                                text = lunarLabel(year, month, day),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                                                color = when {
                                                    isEdge -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                                    inRange -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    if (heat > 0f && !isEdge && !inRange) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = 3.dp)
                                                .width(18.dp)
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(
                                                    base.copy(alpha = 0.55f + 0.40f * heat),
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
}

@Composable
private fun SelectionSummary(
    start: CalendarDate?,
    end: CalendarDate?,
    transactions: List<TransactionEntity>,
    monthFallback: List<TransactionEntity>,
) {
    val semantic = LocalQingSemanticColors.current
    val hasSelection = start != null && end != null
    val source = if (hasSelection) transactions else monthFallback
    val expense = source.filter { it.countsAsExpense() }.sumOf { it.amountCents }
    val income = source.filter { it.countsAsIncome() }.sumOf { it.amountCents }
    val net = income - expense
    val title = when {
        !hasSelection -> tr("本月汇总")
        start == end -> formatDate(start)
        start!!.year == end!!.year && start.month == end.month ->
            if (AppLanguage.current() == AppLanguage.ENGLISH) {
                "${start.month}/${start.day}–${end.day}"
            } else {
                "${start.month}月${start.day}日–${end.day}日"
            }
        else -> formatDate(start) + "–" + formatDate(end!!)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                    text = "${source.size} ${tr("笔")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "${tr("支出")} ${formatCny(expense)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.expenseText,
                )
                Text(
                    text = "${tr("收入")} ${formatCny(income)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.income,
                )
                Text(
                    text = "${tr("结余")} " +
                        (if (net >= 0) "+" else "−") + formatCny(abs(net)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalendarTransactionRow(transaction: TransactionEntity) {
    val expense = transaction.type == "EXPENSE"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(transaction.timestamp)),
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
                text = transaction.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            fontWeight = FontWeight.SemiBold,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun TransactionEntity.countsAsExpense(): Boolean =
    type == "EXPENSE" && category != "TRANSFER" && currency == "CNY" &&
        !syncDeleted && duplicateOfId == null &&
        remoteReviewStatus !in setOf("excluded", "transfer", "refund", "possible_duplicate")

private fun TransactionEntity.countsAsIncome(): Boolean =
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

private fun daysInMonth(year: Int, month: Int): Int =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

private fun firstDayOffset(year: Int, month: Int, weekStartsMonday: Boolean): Int {
    val dayOfWeek = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.get(Calendar.DAY_OF_WEEK)
    val first = if (weekStartsMonday) Calendar.MONDAY else Calendar.SUNDAY
    return (dayOfWeek - first + 7) % 7
}

private data class CalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CalendarDate> {
    override fun compareTo(other: CalendarDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })
}

private fun dateOf(timestamp: Long): CalendarDate =
    Calendar.getInstance().apply { timeInMillis = timestamp }.let { cal ->
        CalendarDate(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

private fun formatDate(date: CalendarDate): String =
    if (AppLanguage.current() == AppLanguage.ENGLISH) {
        "${date.year}/${date.month}/${date.day}"
    } else {
        "${date.year}年${date.month}月${date.day}日"
    }

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

private fun lunarLabel(year: Int, month: Int, day: Int): String {
    return runCatching {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }
        val chinese = ChineseCalendar().apply { timeInMillis = cal.timeInMillis }
        val lunarDay = chinese.get(Calendar.DAY_OF_MONTH)
        val lunarMonth = chinese.get(Calendar.MONTH) + 1
        if (lunarDay == 1) {
            val names = arrayOf(
                "正月", "二月", "三月", "四月", "五月", "六月",
                "七月", "八月", "九月", "十月", "冬月", "腊月",
            )
            names[(lunarMonth - 1).coerceIn(0, 11)]
        } else {
            val names = arrayOf(
                "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
                "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
                "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
            )
            names[(lunarDay - 1).coerceIn(0, 29)]
        }
    }.getOrDefault("")
}
