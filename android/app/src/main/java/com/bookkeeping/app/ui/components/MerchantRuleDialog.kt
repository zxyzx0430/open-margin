package com.bookkeeping.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr

/**
 * Saves a merchant → category rule. The merchant is editable so rows captured
 * without a merchant name can still get a useful rule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MerchantRuleDialog(
    merchant: String,
    onDismiss: () -> Unit,
    onSave: (merchant: String, category: TransactionCategory) -> Unit,
    initialCategory: TransactionCategory? = null,
    historyCount: ((String) -> Int)? = null,
    onSaveWithHistory: ((String, TransactionCategory, Boolean) -> Unit)? = null,
) {
    var merchantName by remember(merchant) { mutableStateOf(merchant) }
    var selectedCategory by remember(merchant, initialCategory) {
        mutableStateOf(initialCategory)
    }

    var updateHistory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("添加分类规则")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = merchantName,
                    onValueChange = { merchantName = it },
                    label = { Text(tr("商户名")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (historyCount != null && onSaveWithHistory != null) {
                    Row {
                        Checkbox(checked = updateHistory, onCheckedChange = { updateHistory = it })
                        Text(tr("同时更新该商户历史记录（共 %d 笔）").format(historyCount(merchantName)))
                    }
                }
                Text(
                    text = tr("选择分类"),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransactionCategory.entries.forEach { category ->
                        LedgerFilterChip(
                            label = tr(category.displayName),
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val category = selectedCategory ?: return@TextButton
                    if (onSaveWithHistory != null) onSaveWithHistory(merchantName.trim(), category, updateHistory)
                    else onSave(merchantName.trim(), category)
                },
                enabled = merchantName.isNotBlank() && selectedCategory != null,
            ) {
                Text(tr("保存"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        },
    )
}
