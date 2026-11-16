package com.bookkeeping.app.ui.screen.budget

import com.bookkeeping.app.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory

private const val MAX_CATEGORY_BUDGET_YUAN = 1_000_000.0

// 编辑预算对话框
// pickCategory = true：选分类 + 输金额；false：仅输金额（用于总预算）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBudgetDialog(
    title: String,
    initialYuan: Double = 0.0,
    pickCategory: Boolean = false,
    excludeCategories: Set<TransactionCategory> = emptySet(),
    initialCategory: TransactionCategory? = null,
    onDismiss: () -> Unit,
    onConfirm: (category: TransactionCategory?, yuan: Double) -> Unit,
) {
    var amountText by remember {
        mutableStateOf(if (initialYuan > 0) "%.2f".format(initialYuan) else "")
    }
    var amountInputError by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var menuExpanded by remember { mutableStateOf(false) }

    val amount = amountText.toDoubleOrNull()
    val isCategoryBudget = pickCategory || initialCategory != null
    val amountIsNegative = amountText.contains('-') || amountText.contains('−') ||
        (amount?.let { it < 0.0 } == true)
    val amountError = when {
        amountIsNegative -> tr("预算不能为负数")
        amountInputError != null -> amountInputError
        amount != null && isCategoryBudget && amount > MAX_CATEGORY_BUDGET_YUAN -> tr("预算金额过大")
        amount == 0.0 -> tr("预算必须大于 0")
        amountText.isNotEmpty() && (amount == null || !amount.isFinite()) -> tr("请输入有效金额")
        else -> null
    }
    val amountValid = amountError == null && amount != null && amount.isFinite() && amount > 0.0 &&
        (!isCategoryBudget || amount <= MAX_CATEGORY_BUDGET_YUAN)
    val categoryValid = !pickCategory || selectedCategory != null

    val availableCategories = remember(excludeCategories) {
        TransactionCategory.entries.filter {
            it !in excludeCategories &&
                it != TransactionCategory.SALARY &&
                it != TransactionCategory.BONUS &&
                it != TransactionCategory.GIFT &&
                it != TransactionCategory.TRANSFER
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pickCategory) {
                    ExposedDropdownMenuBox(
                        expanded = menuExpanded,
                        onExpandedChange = { menuExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedCategory?.let { tr(it.displayName) } ?: tr("选择分类"),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(tr("分类")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            availableCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(tr(cat.displayName)) },
                                    onClick = {
                                        selectedCategory = cat
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        val candidate = raw.filter { c -> c.isDigit() || c == '.' || c == '-' || c == '−' }
                        if (candidate.contains('-') || candidate.contains('−')) {
                            amountText = candidate
                            amountInputError = null
                        } else if (candidate.hasMoreThanTwoFractionDigits()) {
                            // Reject the edit and explain why instead of silently truncating it.
                            amountInputError = tr("金额最多保留两位小数")
                        } else {
                            amountText = candidate
                            amountInputError = null
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (amountValid && categoryValid) {
                        amount?.let { onConfirm(selectedCategory, it) }
                    }
                },
                enabled = amountValid && categoryValid,
            ) { Text(tr("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        },
    )
}

private fun String.hasMoreThanTwoFractionDigits(): Boolean =
    contains('.') && substringAfter('.').count { it.isDigit() } > 2
