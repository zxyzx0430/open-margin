package com.bookkeeping.app.ui.components

import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.isCleanupSingletonMerchant
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingWarning

/**
 * Saves a merchant → category rule. The merchant is editable so rows captured
 * without a merchant name can still get a useful rule.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MerchantRuleDialog(
    merchant: String,
    onDismiss: () -> Unit,
    onSave: (merchant: String, category: TransactionCategory) -> Unit,
    initialCategory: TransactionCategory? = null,
    historyCount: ((String) -> Int)? = null,
    historyLoading: Boolean = false,
    onSaveWithHistory: ((String, TransactionCategory, Boolean) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var merchantName by remember(merchant) { mutableStateOf(merchant) }
    var selectedCategory by remember(merchant, initialCategory) {
        mutableStateOf(initialCategory)
    }
    var updateHistory by remember(merchant) { mutableStateOf(true) }

    val trimmedMerchant = merchantName.trim()
    val placeholderMerchant = trimmedMerchant.isNotEmpty() && isCleanupSingletonMerchant(trimmedMerchant)
    val validMerchant = trimmedMerchant.isNotEmpty() && !placeholderMerchant
    val historyResult by produceState<Pair<String, Int>?>(null, trimmedMerchant, validMerchant, historyCount) {
        value = null
        val count = if (!validMerchant || historyCount == null) 0
            else withContext(Dispatchers.Default) { historyCount(trimmedMerchant).coerceAtLeast(0) }
        value = trimmedMerchant to count
    }
    val matchingHistoryCount = historyResult?.takeIf { it.first == trimmedMerchant }?.second ?: 0
    val historyPending = historyLoading || (historyCount != null && historyResult?.first != trimmedMerchant)
    val canUpdateHistory =
        onSaveWithHistory != null &&
            matchingHistoryCount > 0 &&
            selectedCategory != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tr("添加分类规则"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = tr("保存后，该商户的新记录将自动归类"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = merchantName,
                onValueChange = { merchantName = it },
                label = { Text(tr("商户名")) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            if (placeholderMerchant) {
                Text(
                    text = tr("请输入具体商户名，占位名称不能保存为规则"),
                    style = MaterialTheme.typography.bodySmall,
                    color = QingWarning,
                )
            }
            Text(
                text = tr("选择分类"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
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
            if (canUpdateHistory) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .toggleable(
                            value = updateHistory,
                            role = Role.Checkbox,
                            onValueChange = { updateHistory = it },
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = updateHistory, onCheckedChange = null)
                    Text(
                        text = tr("同时更新该商户历史记录（共 %d 笔）").format(matchingHistoryCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(tr("取消"))
                }
                Button(
                    onClick = {
                        val category = selectedCategory ?: return@Button
                        if (onSaveWithHistory != null) {
                            onSaveWithHistory(trimmedMerchant, category, canUpdateHistory && updateHistory)
                        } else {
                            onSave(trimmedMerchant, category)
                        }
                    },
                    enabled = validMerchant && selectedCategory != null && !historyPending,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(tr("保存"))
                }
            }
        }
    }
}
