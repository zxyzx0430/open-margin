package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.CalendarDateText
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.screen.transactions.TransactionEditRules
import java.util.Calendar
import java.util.Locale

enum class DateTimePickerTab { DATE, TIME }

/**
 * 清衡自定义日期时间选择器：替代安卓原生 Material3 DatePicker / TimePicker。
 *
 * 单张 20dp 圆角白卡内完成日期 + 时间切换，避免连续弹两个原生大弹窗：
 * - 顶部：标题 + 当前选中时间实时预览 + 「今天 / 昨天」快捷切换；
 * - 中部：日期（7 列极简月历）与时间（双列时/分列表 + 快捷分钟）双标签切换；
 * - 底部：未来时间校验提示 + 取消 / 确定。
 */
@Composable
fun LedgerDateTimePickerDialog(
    initialTimestampMs: Long,
    initialTab: DateTimePickerTab = DateTimePickerTab.DATE,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initialCal = remember(initialTimestampMs) {
        Calendar.getInstance().apply { timeInMillis = initialTimestampMs }
    }
    var selectedYear by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.YEAR)) }
    var selectedMonth by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.MONTH) + 1) }
    var selectedDay by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.DAY_OF_MONTH)) }
    var displayYear by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.YEAR)) }
    var displayMonth by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.MONTH) + 1) }
    var selectedHour by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.HOUR_OF_DAY)) }
    var selectedMinute by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.MINUTE)) }
    var activeTab by remember(initialTimestampMs, initialTab) { mutableStateOf(initialTab) }

    val candidateTimestampMs = remember(
        selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute,
    ) {
        Calendar.getInstance().apply {
            clear()
            set(selectedYear, selectedMonth - 1, selectedDay, selectedHour, selectedMinute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val timeError = TransactionEditRules.timestampError(
        candidateTimestampMs,
        System.currentTimeMillis(),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 头部：标题 + 实时时间预览
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("选择发生时间"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "%04d-%02d-%02d  %02d:%02d".format(
                                Locale.CHINA,
                                selectedYear,
                                selectedMonth,
                                selectedDay,
                                selectedHour,
                                selectedMinute,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickDayPill(
                            label = tr("今天"),
                            onClick = {
                                val today = Calendar.getInstance()
                                selectedYear = today.get(Calendar.YEAR)
                                selectedMonth = today.get(Calendar.MONTH) + 1
                                selectedDay = today.get(Calendar.DAY_OF_MONTH)
                                displayYear = selectedYear
                                displayMonth = selectedMonth
                            },
                        )
                        QuickDayPill(
                            label = tr("昨天"),
                            onClick = {
                                val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                                selectedYear = yesterday.get(Calendar.YEAR)
                                selectedMonth = yesterday.get(Calendar.MONTH) + 1
                                selectedDay = yesterday.get(Calendar.DAY_OF_MONTH)
                                displayYear = selectedYear
                                displayMonth = selectedMonth
                            },
                        )
                    }
                }

                // 日期 / 时间双段切换
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PickerSegmentTab(
                        label = tr("日期"),
                        selected = activeTab == DateTimePickerTab.DATE,
                        onClick = { activeTab = DateTimePickerTab.DATE },
                        modifier = Modifier.weight(1f),
                    )
                    PickerSegmentTab(
                        label = tr("时间"),
                        selected = activeTab == DateTimePickerTab.TIME,
                        onClick = { activeTab = DateTimePickerTab.TIME },
                        modifier = Modifier.weight(1f),
                    )
                }

                when (activeTab) {
                    DateTimePickerTab.DATE -> {
                        CompactDateCalendar(
                            displayYear = displayYear,
                            displayMonth = displayMonth,
                            selectedYear = selectedYear,
                            selectedMonth = selectedMonth,
                            selectedDay = selectedDay,
                            onMonthShift = { delta ->
                                val cal = Calendar.getInstance().apply {
                                    set(displayYear, displayMonth - 1, 1)
                                    add(Calendar.MONTH, delta)
                                }
                                displayYear = cal.get(Calendar.YEAR)
                                displayMonth = cal.get(Calendar.MONTH) + 1
                            },
                            onSelectDay = { year, month, day ->
                                selectedYear = year
                                selectedMonth = month
                                selectedDay = day
                            },
                        )
                    }
                    DateTimePickerTab.TIME -> {
                        CompactTimeSelector(
                            selectedHour = selectedHour,
                            selectedMinute = selectedMinute,
                            onSelectHour = { selectedHour = it },
                            onSelectMinute = { selectedMinute = it },
                        )
                    }
                }

                if (timeError != null) {
                    Text(
                        text = tr(timeError),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(tr("取消"))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(candidateTimestampMs) },
                        enabled = timeError == null,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(tr("确定"))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickDayPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PickerSegmentTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CompactDateCalendar(
    displayYear: Int,
    displayMonth: Int,
    selectedYear: Int,
    selectedMonth: Int,
    selectedDay: Int,
    onMonthShift: (Int) -> Unit,
    onSelectDay: (Int, Int, Int) -> Unit,
) {
    val today = remember { Calendar.getInstance() }
    val todayYear = today.get(Calendar.YEAR)
    val todayMonth = today.get(Calendar.MONTH) + 1
    val todayDay = today.get(Calendar.DAY_OF_MONTH)

    val monthMeta = remember(displayYear, displayMonth) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(displayYear, displayMonth - 1, 1)
        }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Monday = 0 .. Sunday = 6
        val firstDayOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        firstDayOffset to daysInMonth
    }
    val (firstOffset, daysInMonth) = monthMeta
    val weekdayLabels = if (AppLanguage.current() == AppLanguage.ENGLISH) {
        listOf("M", "T", "W", "T", "F", "S", "S")
    } else {
        listOf("一", "二", "三", "四", "五", "六", "日")
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onMonthShift(-1) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = tr("上个月"),
                )
            }
            Text(
                text = CalendarDateText.monthHeading(displayYear, displayMonth),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = { onMonthShift(1) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = tr("下个月"),
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { dayName ->
                Text(
                    text = dayName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val totalCells = firstOffset + daysInMonth
        val rows = (totalCells + 6) / 7
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (col in 0 until 7) {
                        val dayNum = row * 7 + col - firstOffset + 1
                        if (dayNum !in 1..daysInMonth) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1.15f))
                        } else {
                            val isSelected = displayYear == selectedYear &&
                                displayMonth == selectedMonth &&
                                dayNum == selectedDay
                            val isToday = displayYear == todayYear &&
                                displayMonth == todayMonth &&
                                dayNum == todayDay
                            val isFuture = displayYear > todayYear ||
                                (displayYear == todayYear && displayMonth > todayMonth) ||
                                (displayYear == todayYear && displayMonth == todayMonth && dayNum > todayDay)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.15f)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    )
                                    .then(
                                        if (isToday && !isSelected) {
                                            Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                                CircleShape,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable(enabled = !isFuture) {
                                        onSelectDay(displayYear, displayMonth, dayNum)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = dayNum.toString(),
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
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
private fun CompactTimeSelector(
    selectedHour: Int,
    selectedMinute: Int,
    onSelectHour: (Int) -> Unit,
    onSelectMinute: (Int) -> Unit,
) {
    val hourListState = rememberLazyListState()
    val minuteListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        hourListState.scrollToItem((selectedHour - 2).coerceAtLeast(0))
        minuteListState.scrollToItem((selectedMinute - 2).coerceAtLeast(0))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tr("时"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    state = hourListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(184.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(24) { hour ->
                        TimeNumberCell(
                            text = "%02d".format(Locale.CHINA, hour),
                            selected = hour == selectedHour,
                            onClick = { onSelectHour(hour) },
                        )
                    }
                }
            }
            Text(
                text = ":",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tr("分"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    state = minuteListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(184.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(60) { minute ->
                        TimeNumberCell(
                            text = "%02d".format(Locale.CHINA, minute),
                            selected = minute == selectedMinute,
                            onClick = { onSelectMinute(minute) },
                        )
                    }
                }
            }
        }

        // 常用分钟快捷点选
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0, 15, 30, 45).forEach { m ->
                QuickDayPill(
                    label = ":%02d".format(Locale.CHINA, m),
                    onClick = { onSelectMinute(m) },
                )
            }
            Spacer(Modifier.weight(1f))
            QuickDayPill(
                label = tr("现在"),
                onClick = {
                    val now = Calendar.getInstance()
                    onSelectHour(now.get(Calendar.HOUR_OF_DAY))
                    onSelectMinute(now.get(Calendar.MINUTE))
                },
            )
        }
    }
}

@Composable
private fun TimeNumberCell(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
