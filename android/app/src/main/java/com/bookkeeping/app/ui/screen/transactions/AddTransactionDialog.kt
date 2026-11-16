package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.MoneyAmount
import com.bookkeeping.app.ui.components.QuickEntryBar
import com.bookkeeping.app.ui.components.QuickEntryRule
import com.bookkeeping.app.voice.VoiceTransactionParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// "新建账单"对话框
// 所有数据从外部传入、所有事件通过回调传出；表单状态只在对话框存活期间存在。
// 顶部那句「一句话记账」只做预填（解析结果写进表单），保存仍然走 onConfirm。
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
        timestamp: Long,
    ) -> Unit,
    // 语音识别结果带进来的原文：为空就是普通新建。填了就在打开时先解析一次，
    // 让 ASR 那边只管把文本传进来，不用重复写「填哪个字段」的逻辑。
    initialQuickEntry: String = "",
) {
    // 用户输入的本地 UI 状态（对话框关闭就丢弃，不存到 ViewModel）
    var amountText by remember(initial?.id) {
        mutableStateOf(initial?.let {
            (it.amountCents / 100).toString() + "." +
                (it.amountCents % 100).toString().padStart(2,'0')
        } ?: "")
    }
    var amountInputError by remember(initial?.id) { mutableStateOf<String?>(null) }
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
    var timestamp by remember(initial?.id) {
        mutableLongStateOf(initial?.timestamp ?: System.currentTimeMillis())
    }
    var showDatePicker by remember { mutableStateOf(false) }
    // 一句话记账的原文：解析失败要能改两个字重提，所以提交后不清空。
    var quickEntry by remember(initial?.id, initialQuickEntry) { mutableStateOf(initialQuickEntry) }
    var quickEntryFocused by remember { mutableStateOf(false) }
    var quickEntryNotice by remember(initial?.id) { mutableStateOf<QuickEntryNotice?>(null) }

    // 解析一句话 → 回填字段。只写解析出来的那一部分，解析不出的保持原值。
    val applyQuickEntry: (String) -> QuickEntryPrefill = { raw ->
        val prefill = QuickEntryPrefill.from(
            VoiceTransactionParser.parse(raw, System.currentTimeMillis()),
            System.currentTimeMillis(),
        )
        prefill.amountText?.let {
            amountText = it
            amountInputError = null
        }
        prefill.type?.let { selectedType = it }
        prefill.category?.let { selectedCategory = it }
        prefill.merchant?.let { merchant = it }
        prefill.note?.let { note = it }
        prefill.timestampMs?.let { timestamp = it }
        quickEntryNotice = prefill.notice
        prefill
    }

    // 语音/上一轮结果直接带进来的原文：打开就填好，用户只需核对。
    LaunchedEffect(initial?.id, initialQuickEntry) {
        if (QuickEntryRule.canSubmit(initialQuickEntry)) applyQuickEntry(initialQuickEntry)
    }

    val amountIsNegative = amountText.contains('-') || amountText.contains('−') ||
        (amountText.toDoubleOrNull()?.let { it < 0.0 } == true)
    val amountError = when {
        amountIsNegative -> tr("金额不能为负数，请用支出/收入切换方向")
        amountInputError != null -> amountInputError
        amountText.isNotEmpty() && MoneyAmount.cents(amountText) == null -> tr("请输入有效金额")
        else -> null
    }
    val amountValid = amountError == null && MoneyAmount.cents(amountText) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("新建账单") else tr("编辑账单")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 一句话记账：可以不填；填了就在回车（键盘「完成」）或离开输入框时解析成下面的字段。
                // 失焦补解析只在表单还没被手工填过时做，免得把用户刚改的金额/商户冲掉。
                QuickEntryBar(
                    value = quickEntry,
                    onValueChange = {
                        quickEntry = it
                        if (quickEntryNotice != null) quickEntryNotice = null
                    },
                    onSubmit = { raw -> applyQuickEntry(raw) },
                    warning = quickEntryNotice?.let { tr(it.message) },
                    fieldModifier = Modifier.onFocusChanged { state ->
                        if (quickEntryFocused && !state.isFocused) {
                            val formUntouched =
                                amountText.isBlank() && merchant.isBlank() && note.isBlank()
                            if (formUntouched && QuickEntryRule.canSubmit(quickEntry)) {
                                applyQuickEntry(quickEntry)
                            }
                        }
                        quickEntryFocused = state.isFocused
                    },
                )

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

                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${tr("日期")}：${formatDate(timestamp)}")
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        if (raw.contains('-') || raw.contains('−')) {
                            // Keep the previous amount, but surface the rejected negative input.
                            amountInputError = tr("金额不能为负数，请用支出/收入切换方向")
                        } else {
                            val candidate = raw.filter { c -> c.isDigit() || c == '.' }
                            if (candidate.hasMoreThanTwoFractionDigits()) {
                                // Reject the edit rather than silently dropping extra decimal digits.
                                amountInputError = tr("金额最多保留两位小数")
                            } else {
                                amountText = candidate
                                amountInputError = null
                            }
                        }
                    },
                    label = { Text(tr("金额（元）")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountError != null,
                    supportingText = {
                        if (amountError != null) {
                            Text(amountError, color = MaterialTheme.colorScheme.error)
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
                        onDismissRequest = { categoryMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 320.dp),
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
                    // 手填了金额：直接存，别让一句话里的旧内容覆盖手填。
                    val typed = MoneyAmount.cents(amountText)
                    if (typed != null) {
                        onConfirm(typed, selectedType, selectedCategory, merchant, note, timestamp)
                        return@TextButton
                    }
                    // 只写了一句话就点保存：先解析。解析出金额才存，
                    // 解析不出就把提示留在表单上，绝不猜一个金额入账。
                    if (!QuickEntryRule.canSubmit(quickEntry)) return@TextButton
                    val prefill = applyQuickEntry(quickEntry)
                    val parsed = prefill.amountText?.let { MoneyAmount.cents(it) }
                        ?: return@TextButton
                    onConfirm(
                        parsed,
                        prefill.type ?: selectedType,
                        prefill.category ?: selectedCategory,
                        prefill.merchant ?: merchant,
                        prefill.note ?: note,
                        prefill.timestampMs ?: timestamp,
                    )
                },
                // 只写了一句话时也要能点：金额由解析结果决定。
                enabled = amountValid || QuickEntryRule.canSubmit(quickEntry),
            ) { Text(tr("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToUtc(timestamp),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        timestamp = applyUtcDate(it, timestamp)
                    }
                    showDatePicker = false
                }) { Text(tr("确定")) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(tr("取消")) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun formatDate(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMs))

private fun String.hasMoreThanTwoFractionDigits(): Boolean =
    contains('.') && substringAfter('.').count { it.isDigit() } > 2

private fun localDateToUtc(timestampMs: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = timestampMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
        )
    }.timeInMillis
}

private fun applyUtcDate(utcDateMs: Long, keepTimeFromMs: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcDateMs
    }
    val local = Calendar.getInstance().apply { timeInMillis = keepTimeFromMs }
    local.set(
        utc.get(Calendar.YEAR),
        utc.get(Calendar.MONTH),
        utc.get(Calendar.DAY_OF_MONTH),
    )
    return local.timeInMillis
}
