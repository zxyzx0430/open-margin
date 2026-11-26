package com.bookkeeping.app.ui.screen.budget

import com.bookkeeping.app.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bookkeeping.app.data.local.entity.TransactionCategory

private const val MAX_CATEGORY_BUDGET_YUAN = 1_000_000.0

// 编辑预算对话框
// pickCategory = true：选分类 + 输金额；false：仅输金额（用于总预算）
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

    com.bookkeeping.app.ui.components.QingGestureSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (pickCategory) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = tr("分类"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { menuExpanded = true },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = selectedCategory?.let { tr(it.displayName) } ?: tr("选择分类"),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (selectedCategory != null) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    isError = amountError != null,
                    supportingText = amountError?.let { err ->
                        { Text(err, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                    ) {
                        Text(tr("取消"), maxLines = 1)
                    }
                    Button(
                        onClick = {
                            if (amountValid && categoryValid) {
                                amount?.let { onConfirm(selectedCategory, it) }
                            }
                        },
                        enabled = amountValid && categoryValid,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                    ) {
                        Text(tr("保存"), maxLines = 1)
                    }
                }
        }
    }
}

private fun String.hasMoreThanTwoFractionDigits(): Boolean =
    contains('.') && substringAfter('.').count { it.isDigit() } > 2
