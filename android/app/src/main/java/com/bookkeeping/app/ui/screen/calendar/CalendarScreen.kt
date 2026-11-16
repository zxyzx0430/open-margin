package com.bookkeeping.app.ui.screen.calendar

import android.content.Context
import android.icu.util.ChineseCalendar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.screen.stats.StatsTransactionPolicy
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private const val CALENDAR_PREFS = "calendar_view_prefs"
private const val KEY_HEATMAP = "heatmap"
private const val KEY_WEEK_START_MONDAY = "week_start_monday"
private const val KEY_LUNAR = "lunar"

private enum class HeatmapMode(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    NET("结余"),
    OFF("关闭"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    onOpenDetail: (Long) -> Unit = {},
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
    var monthKey by rememberSaveable { mutableStateOf(BudgetMonth.currentKey()) }
    var today by rememberSaveable(stateSaver = CalendarDateSaver) {
        mutableStateOf(currentCalendarDate())
    }
    var rangeStart by rememberSaveable(stateSaver = NullableCalendarDateSaver) {
        mutableStateOf<CalendarDate?>(null)
    }
    var rangeEnd by rememberSaveable(stateSaver = NullableCalendarDateSaver) {
        mutableStateOf<CalendarDate?>(null)
    }
    var hasManuallyNavigatedMonth by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val latestToday = rememberUpdatedState(today)
    val latestMonthKey = rememberUpdatedState(monthKey)
    val latestManualNavigation = rememberUpdatedState(hasManuallyNavigatedMonth)
    DisposableEffect(lifecycleOwner) {
        fun refreshToday() {
            val previousToday = latestToday.value
            val refreshedToday = currentCalendarDate()
            if (refreshedToday != previousToday) {
                today = refreshedToday
                val previousTodayMonth = BudgetMonth.keyOf(previousToday.year, previousToday.month)
                val refreshedTodayMonth = BudgetMonth.keyOf(refreshedToday.year, refreshedToday.month)
                if (!latestManualNavigation.value && latestMonthKey.value == previousTodayMonth &&
                    previousTodayMonth != refreshedTodayMonth
                ) {
                    monthKey = refreshedTodayMonth
                    rangeStart = null
                    rangeEnd = null
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshToday()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            refreshToday()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun navigateMonth(nextKey: String, manual: Boolean = true) {
        if (nextKey != monthKey) {
            monthKey = nextKey
            rangeStart = null
            rangeEnd = null
            if (manual) hasManuallyNavigatedMonth = true
        }
    }

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
            // 顶部状态栏留白由外层 Scaffold 的 innerPadding 负责，这里再加会翻倍
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("日历"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        MonthHeader(
            monthKey = monthKey,
            onPrevious = { navigateMonth(shiftMonth(monthKey, -1)) },
            onNext = { navigateMonth(shiftMonth(monthKey, 1)) },
            menu = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = tr("日历设置"))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        HeatmapMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text((if (mode == heatmap) "✓ " else "") + tr("热力背景：") + tr(mode.label))
                                },
                                onClick = {
                                    heatmap = mode
                                    save()
                                    menuExpanded = false
                                },
                            )
                        }
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
            text = tr("点选单日 · 长按日期拖动选区间，拖到空白处可继续跨月"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
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
                rangeStart = start
                rangeEnd = end
            },
            onCrossMonth = { targetKey, anchor ->
                // 跨月拖动不调用 navigateMonth：它会把区间清空，而这里必须保留
                // 长按的那个起点，只把终点落到目标月最靠近起点的一天。
                val target = BudgetMonth.parse(targetKey)
                if (target != null) {
                    monthKey = targetKey
                    hasManuallyNavigatedMonth = true
                    val boundary = CalendarDate(
                        target.year,
                        target.month,
                        rangeEndDayIn(anchor, target.year, target.month),
                    )
                    rangeStart = minOf(anchor, boundary)
                    rangeEnd = maxOf(anchor, boundary)
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        SelectionSummary(
            start = rangeStart,
            end = rangeEnd,
            transactions = selectedTransactions,
            monthFallback = monthTransactions,
            onClear = {
                rangeStart = null
                rangeEnd = null
            },
        )
        if (selectedTransactions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(selectedTransactions, key = { it.id }) { transaction ->
                    CalendarTransactionRow(transaction) { onOpenDetail(transaction.id) }
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
                    text = when {
                        rangeStart == null || rangeEnd == null -> tr("长按日期拖动选择区间，或点选单日")
                        rangeStart == rangeEnd -> tr("这一天你还没有任何记账")
                        else -> tr("这段时间你还没有任何记账")
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
    val expense = transactions
        .filter { StatsTransactionPolicy.matches(it, TransactionType.EXPENSE) }
        .sumOf { it.amountCents }
    val income = transactions
        .filter { StatsTransactionPolicy.matches(it, TransactionType.INCOME) }
        .sumOf { it.amountCents }
    val net = income - expense
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
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
        AutoSizeAmountText(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold,
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
    onCrossMonth: (targetMonthKey: String, anchor: CalendarDate) -> Unit,
) {
    val daysInMonth = daysInMonth(year, month)
    val firstOffset = firstDayOffset(year, month, weekStartsMonday)
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 44.dp.toPx() }
    val crossMonthDragPx = with(density) { 56.dp.toPx() }
    val reverseCrossDragPx = with(density) { 24.dp.toPx() }
    var gridWidthPx by remember { mutableStateOf(0f) }
    // 手势协程的 key 只有尺寸：跨月会重组，但手指还没松，协程不能重启。
    // 因此月份、日偏移这些易变值统一从最新状态里读。
    val latestYear = rememberUpdatedState(year)
    val latestMonth = rememberUpdatedState(month)
    val latestOffset = rememberUpdatedState(firstOffset)
    val latestDays = rememberUpdatedState(daysInMonth)
    val latestWeekStartsMonday = rememberUpdatedState(weekStartsMonday)
    val latestOnSelect = rememberUpdatedState(onSelect)
    val latestOnCrossMonth = rememberUpdatedState(onCrossMonth)
    val byDay = remember(transactions) { transactions.groupBy { dayOf(it.timestamp) } }
    val heatValues = remember(byDay, heatmap) {
        byDay.mapValues { (_, list) ->
            when (heatmap) {
                HeatmapMode.EXPENSE -> list
                    .filter { StatsTransactionPolicy.matches(it, TransactionType.EXPENSE) }
                    .sumOf { it.amountCents }
                HeatmapMode.INCOME -> list
                    .filter { StatsTransactionPolicy.matches(it, TransactionType.INCOME) }
                    .sumOf { it.amountCents }
                HeatmapMode.NET -> {
                    val income = list
                        .filter { StatsTransactionPolicy.matches(it, TransactionType.INCOME) }
                        .sumOf { it.amountCents }
                    val expense = list
                        .filter { StatsTransactionPolicy.matches(it, TransactionType.EXPENSE) }
                        .sumOf { it.amountCents }
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
                .height(44.dp * 6)
                .onSizeChanged { gridWidthPx = it.width.toFloat() }
                .pointerInput(gridWidthPx, rowHeightPx, crossMonthDragPx, reverseCrossDragPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val anchorYear = latestYear.value
                        val anchorMonth = latestMonth.value
                        val anchorDay = dayAt(
                            down.position,
                            gridWidthPx,
                            rowHeightPx,
                            latestOffset.value,
                            latestDays.value,
                        ) ?: return@awaitEachGesture
                        val anchor = CalendarDate(anchorYear, anchorMonth, anchorDay)
                        // 跨月只属于「长按进入区间选择后拖到左右边缘」：
                        // 普通点按选单日，普通横滑不切月也不改选中。
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            if (currentEvent.changes.all { !it.pressed }) {
                                latestOnSelect.value(anchor, anchor)
                            }
                            return@awaitEachGesture
                        }
                        latestOnSelect.value(anchor, anchor)
                        // 跨月后手指还在屏幕上，这里用局部变量跟着切换视角月份，
                        // 否则要等下一次重组才能按新月份算日期。
                        var viewYear = anchorYear
                        var viewMonth = anchorMonth
                        var viewOffset = latestOffset.value
                        var viewDays = latestDays.value
                        // 已经跨进过的月份，按顺序记着：往回拖时逐层退回。
                        val crossedMonths = mutableListOf<Pair<String, Boolean>>()
                        var forwardTravelPx = 0f
                        var reverseTravelPx = 0f
                        // 刚翻过一个月就先把「空白格触发」关掉，避免手指还停在空白里连翻。
                        var blankFlipArmed = true
                        var lastX = longPress.position.x
                        var lastDay = anchorDay
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            // 上一次跨月是往右翻的，就往左退回；反之亦然。
                            // 反向移动够 24dp 才退一个月，手指在边缘抖动不会反复翻。
                            val lastCross = crossedMonths.lastOrNull()
                            val reversing = lastCross != null &&
                                (if (lastCross.second) dx < 0f else dx > 0f)
                            if (reversing) {
                                reverseTravelPx += abs(dx)
                                if (reverseTravelPx >= reverseCrossDragPx) {
                                    crossedMonths.removeAt(crossedMonths.lastIndex)
                                    reverseTravelPx = 0f
                                    forwardTravelPx = 0f
                                    // 退回来时手指仍停在空白格里，先关掉触发，避免立刻又翻过去。
                                    blankFlipArmed = false
                                    val targetKey = crossedMonths.lastOrNull()?.first
                                        ?: BudgetMonth.keyOf(anchorYear, anchorMonth)
                                    val target = BudgetMonth.parse(targetKey)
                                    if (target != null) {
                                        viewYear = target.year
                                        viewMonth = target.month
                                        viewOffset = firstDayOffset(
                                            viewYear, viewMonth, latestWeekStartsMonday.value,
                                        )
                                        viewDays = monthDayCount(viewYear, viewMonth)
                                        lastDay = rangeEndDayIn(anchor, viewYear, viewMonth)
                                        latestOnCrossMonth.value(targetKey, anchor)
                                    }
                                }
                                change.consume()
                                continue
                            }
                            if (dx != 0f) reverseTravelPx = 0f
                            // 手指拖进本月「末尾空白格 / 开头空白格」就跨月：
                            // 往右进尾部空白翻下月（终点 1 号），往左进头部空白翻上月（终点月末）。
                            // 不能只看第 0/6 列——像 11 月 30 日在第 6 行第 0 列，
                            // 往右拖是穿过一行空白，永远到不了第 6 列。
                            val blankDir = blankDirectionAt(
                                change.position, gridWidthPx, rowHeightPx, viewOffset, viewDays,
                            )
                            if (blankDir != 0) {
                                forwardTravelPx += abs(dx)
                                // 回到日期格后重新允许；若一直停在空白里，拖满 56dp 也允许再翻。
                                val enough = blankFlipArmed || forwardTravelPx >= crossMonthDragPx
                                if (enough) {
                                    forwardTravelPx = 0f
                                    reverseTravelPx = 0f
                                    blankFlipArmed = false
                                    val forward = blankDir > 0
                                    val nextKey = shiftMonth(
                                        BudgetMonth.keyOf(viewYear, viewMonth),
                                        if (forward) 1 else -1,
                                    )
                                    val next = BudgetMonth.parse(nextKey)
                                    if (next != null) {
                                        viewYear = next.year
                                        viewMonth = next.month
                                        viewOffset = firstDayOffset(
                                            viewYear, viewMonth, latestWeekStartsMonday.value,
                                        )
                                        viewDays = monthDayCount(viewYear, viewMonth)
                                        lastDay = rangeEndDayIn(anchor, viewYear, viewMonth)
                                        crossedMonths.add(nextKey to forward)
                                        latestOnCrossMonth.value(nextKey, anchor)
                                    }
                                }
                            } else {
                                blankFlipArmed = true
                                forwardTravelPx = 0f
                                val day = dayAt(
                                    change.position,
                                    gridWidthPx,
                                    rowHeightPx,
                                    viewOffset,
                                    viewDays,
                                )
                                if (day != null && day != lastDay) {
                                    lastDay = day
                                    val current = CalendarDate(viewYear, viewMonth, day)
                                    latestOnSelect.value(
                                        minOf(anchor, current),
                                        maxOf(anchor, current),
                                    )
                                }
                            }
                            change.consume()
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                for (row in 0 until 6) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
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
                                heatmapLevel(heatValues[day] ?: 0L, heatMax)
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
                                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
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
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 8.sp,
                                                    lineHeight = 10.sp,
                                                ),
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
                                                .background(base.copy(alpha = heat)),
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
    onClear: () -> Unit,
) {
    val semantic = LocalQingSemanticColors.current
    val hasSelection = start != null && end != null
    val source = if (hasSelection) transactions else monthFallback
    val includedExpenses = source.filter {
        StatsTransactionPolicy.matches(it, TransactionType.EXPENSE)
    }
    val includedIncomes = source.filter {
        StatsTransactionPolicy.matches(it, TransactionType.INCOME)
    }
    val expense = includedExpenses.sumOf { it.amountCents }
    val income = includedIncomes.sumOf { it.amountCents }
    val includedCount = includedExpenses.size + includedIncomes.size
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
                    text = "$includedCount ${tr("笔")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasSelection) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(tr("清除"), style = MaterialTheme.typography.labelMedium)
                    }
                }
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
private fun CalendarTransactionRow(transaction: TransactionEntity, onClick: () -> Unit) {
    val expense = transaction.type == TransactionType.EXPENSE.name
    val type = when (transaction.type) {
        TransactionType.EXPENSE.name -> TransactionType.EXPENSE
        TransactionType.INCOME.name -> TransactionType.INCOME
        else -> null
    }
    val includedInStats = type?.let { StatsTransactionPolicy.matches(transaction, it) } == true
    val excludedReason = if (includedInStats) {
        null
    } else {
        when {
            transaction.remoteReviewStatus == "refund" -> tr("退款")
            transaction.remoteReviewStatus == "possible_duplicate" ||
                transaction.duplicateOfId != null -> tr("待确认重复")
            transaction.remoteReviewStatus == "transfer" ||
                (expense && transaction.category == "TRANSFER") -> tr("转账")
            else -> tr("不计入")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            excludedReason?.let { reason ->
                Text(
                    text = reason,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/** 手指落在第几列（0=第一天那列，6=最后一天那列），拖出网格时按边界列算。 */
private fun columnAt(x: Float, gridWidthPx: Float): Int =
    if (gridWidthPx <= 0f) 0 else (x / (gridWidthPx / 7f)).toInt().coerceIn(0, 6)

/**
 * 手指落在本月日期格返回 0；落在月初之前的空白格返回 -1；落在月末之后的空白格返回 1。
 * 跨月判断靠这个，而不是「第 0 / 第 6 列」——月末最后一天可能就在第 0 列。
 */
private fun blankDirectionAt(
    position: Offset,
    gridWidthPx: Float,
    rowHeightPx: Float,
    firstOffset: Int,
    daysInMonth: Int,
): Int {
    if (gridWidthPx <= 0f || rowHeightPx <= 0f) return 0
    val cellWidth = gridWidthPx / 7f
    val col = floor(position.x / cellWidth).toInt()
    val row = floor(position.y / rowHeightPx).toInt()
    if (row < 0) return 0
    if (col < 0) return -1
    if (col > 6) return 1
    val day = row * 7 + col - firstOffset + 1
    return when {
        day < 1 -> -1
        day > daysInMonth -> 1
        else -> 0
    }
}

/** 跨月时区间终点取目标月里离起点最近的一天：更早的月取月末，更晚的月取月初。 */
private fun rangeEndDayIn(anchor: CalendarDate, year: Int, month: Int): Int {
    val last = daysInMonth(year, month)
    if (anchor.year == year && anchor.month == month) return anchor.day.coerceIn(1, last)
    return if (CalendarDate(year, month, last) < anchor) last else 1
}

private fun daysInMonth(year: Int, month: Int): Int =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

/** 手势协程里用：避开 CalendarMonthGrid 里同名的本地 `daysInMonth` 变量。 */
private fun monthDayCount(year: Int, month: Int): Int = daysInMonth(year, month)

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

private val CalendarDateSaver = Saver<CalendarDate, String>(
    save = { date -> "${date.year}-${date.month}-${date.day}" },
    restore = { value -> parseCalendarDate(value) ?: currentCalendarDate() },
)

private val NullableCalendarDateSaver = Saver<CalendarDate?, String>(
    save = { date -> date?.let { "${it.year}-${it.month}-${it.day}" } ?: "" },
    restore = { value -> parseCalendarDate(value) },
)

private fun parseCalendarDate(value: String): CalendarDate? {
    if (value.isEmpty()) return null
    val parts = value.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (year < 1) return null
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    return CalendarDate(year, month, day)
}

private fun currentCalendarDate(): CalendarDate =
    Calendar.getInstance().let { cal ->
        CalendarDate(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

private fun heatmapLevel(value: Long, maximum: Long): Float {
    if (value <= 0L || maximum <= 0L) return 0f
    val ratio = value.toDouble() / maximum.toDouble()
    return when {
        ratio <= 0.25 -> 0.25f
        ratio <= 0.5 -> 0.5f
        ratio <= 0.75 -> 0.75f
        else -> 1f
    }
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
