package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun MonthPickerDialog(
    selectedMonth: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    calendarPickerActions: Boolean = false,
) {
    val selectedParts = remember(selectedMonth) { BudgetMonth.parse(selectedMonth) }
    var year by remember(selectedMonth) {
        mutableIntStateOf(selectedParts?.year ?: Calendar.getInstance().get(Calendar.YEAR))
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = tr("选择月份"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            else QingSurfaceVariant
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
                                            QingTextSecondary
                                        },
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = {
                            onSelect(BudgetMonth.currentKey())
                            if (calendarPickerActions) onDismiss()
                        },
                    ) {
                        Text(
                            if (calendarPickerActions && AppLanguage.current() == AppLanguage.ENGLISH) {
                                tr("本月")
                            } else {
                                tr("回到本月")
                            },
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(tr("取消"))
                    }
                }
            }
        }
    }
}

fun monthLabel(key: String): String {
    val parts = BudgetMonth.parse(key) ?: return key
    val cal = Calendar.getInstance().apply {
        clear()
        set(parts.year, parts.month - 1, 1)
    }
    val english = AppLanguage.current() == AppLanguage.ENGLISH
    val pattern = if (english) "MMM yyyy" else "yyyy年M月"
    val locale = if (english) Locale.ENGLISH else Locale.CHINA
    return SimpleDateFormat(pattern, locale).format(cal.time)
}

private fun monthCellLabel(year: Int, month: Int): String {
    if (AppLanguage.current() != AppLanguage.ENGLISH) return "${month}月"
    val cal = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }
    return SimpleDateFormat("MMM", Locale.ENGLISH).format(cal.time)
}
