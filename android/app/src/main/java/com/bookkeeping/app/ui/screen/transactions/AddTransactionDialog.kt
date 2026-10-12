package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.MoneyAmount

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

    val amountValid = MoneyAmount.cents(amountText) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if(initial==null)"新建账单" else "编辑账单（将同步）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 收入 / 支出 切换
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == TransactionType.EXPENSE,
                        onClick = { selectedType = TransactionType.EXPENSE },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = selectedType == TransactionType.INCOME,
                        onClick = { selectedType = TransactionType.INCOME },
                        label = { Text("收入") }
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountText.isNotEmpty() && !amountValid,
                    modifier = Modifier.fillMaxWidth()
                )

                // 分类下拉
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedCategory.displayName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("分类") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false }
                    ) {
                        TransactionCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.displayName) },
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
                    label = { Text("商户/对方（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = MoneyAmount.cents(amountText) ?: return@TextButton
                    onConfirm(amount, selectedType, selectedCategory, merchant, note)
                },
                enabled = amountValid
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
