package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.MoneyAmount
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// "新建账单"对话框
// 这是个无状态 Composable：所有数据从外部传入，所有事件通过回调传出。
// 嵌入式类比：组合逻辑电路，输入决定输出，没有内部状态。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    onDismiss: () -> Unit,
    initial: TransactionEntity? = null,
    onConfirm: (
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
    ) -> Unit,
    onConfirmAt: ((
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        timestampMs: Long,
    ) -> Unit)? = null,
) {
    // 用户输入的本地 UI 状态（对话框关闭就丢弃，不存到 ViewModel）
    var amountText by remember(initial?.id) {
        mutableStateOf(initial?.let {
            (it.amountCents / 100).toString() + "." +
                (it.amountCents % 100).toString().padStart(2,'0')
        } ?: "")
    }
    var selectedType by remember(initial?.id) {
        mutableStateOf(runCatching { TransactionType.valueOf(initial?.type ?: "EXPENSE") }
            .getOrDefault(TransactionType.EXPENSE))
    }
    var selectedCategory by remember(initial?.id) {
        mutableStateOf(runCatching { TransactionCategory.valueOf(initial?.category ?: "FOOD") }
            .getOrDefault(TransactionCategory.OTHER))
    }
    var merchant by remember(initial?.id) { mutableStateOf(initial?.merchant ?: "") }
    var note by remember(initial?.id) { mutableStateOf(initial?.note ?: "") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var occurredAtMs by remember(initial?.id) {
        mutableStateOf(initial?.timestamp ?: System.currentTimeMillis())
    }

    val amountValid = MoneyAmount.cents(amountText) != null
    val dateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        .format(Date(occurredAtMs))

    if (datePickerOpen) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = occurredAtMs)
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { selected ->
                        val calendar = Calendar.getInstance().apply {
                            timeInMillis = occurredAtMs
                            val chosen = Calendar.getInstance(
                                TimeZone.getTimeZone("UTC"),
                            ).apply { timeInMillis = selected }
                            set(Calendar.YEAR, chosen.get(Calendar.YEAR))
                            set(Calendar.MONTH, chosen.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, chosen.get(Calendar.DAY_OF_MONTH))
                        }
                        occurredAtMs = calendar.timeInMillis
                    }
                    datePickerOpen = false
                }) { Text(tr("确定")) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) { Text(tr("取消")) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("新建账单") else tr("编辑账单")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 收入 / 支出 切换
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == TransactionType.EXPENSE,
                        onClick = {
                            selectedType = TransactionType.EXPENSE
                            if (selectedCategory == TransactionCategory.SALARY) {
                                selectedCategory = TransactionCategory.FOOD
                            }
                        },
                        label = { Text(tr("支出")) }
                    )
                    FilterChip(
                        selected = selectedType == TransactionType.INCOME,
                        onClick = {
                            selectedType = TransactionType.INCOME
                            if (selectedCategory == TransactionCategory.FOOD) {
                                selectedCategory = TransactionCategory.SALARY
                            }
                        },
                        label = { Text(tr("收入")) }
                    )
                }

                if (onConfirmAt != null) {
                    OutlinedButton(
                        onClick = { datePickerOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("日期") + ": " + dateLabel)
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(tr("金额（元）")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountText.isNotEmpty() && !amountValid,
                    supportingText = {
                        if (amountText.isNotEmpty() && !amountValid) {
                            Text(tr("金额格式不正确"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 分类下拉
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = tr(selectedCategory.displayName),
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(tr("分类")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false }
                    ) {
                        TransactionCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(tr(cat.displayName)) },
                                onClick = {
                                    selectedCategory = cat
                                    categoryMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(tr("商户/对方（可选）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(tr("备注（可选）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = MoneyAmount.cents(amountText) ?: return@TextButton
                    if (onConfirmAt != null) {
                        onConfirmAt(
                            amount,
                            selectedType,
                            selectedCategory,
                            merchant,
                            note,
                            occurredAtMs,
                        )
                    } else {
                        onConfirm(amount, selectedType, selectedCategory, merchant, note)
                    }
                },
                enabled = amountValid
            ) { Text(tr("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        }
    )
}
