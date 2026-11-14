package com.bookkeeping.app.ui.screen.billimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.billimport.BillPreviewRow
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BillImportDialog(
    onDismiss: () -> Unit,
    viewModel: BillImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::load)
    }

    Dialog(
        onDismissRequest = {
            viewModel.reset()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("导入账单"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (!state.importing) {
                            viewModel.reset()
                            onDismiss()
                        }
                    }) { Text(tr("关闭")) }
                }
                Text(
                    text = tr("支持微信/支付宝官方 CSV、XLSX 或文本账单；确认前不会写入账本。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
                OutlinedButton(
                    onClick = {
                        launcher.launch(
                            arrayOf(
                                "text/*",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "*/*",
                            ),
                        )
                    },
                    enabled = !state.loading && !state.importing,
                ) {
                    Text(tr("选择账单文件"))
                }

                if (state.loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.preview?.let { preview ->
                    ImportSummary(preview = preview)
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(preview.rows) { row -> ImportPreviewRow(row) }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.done) {
                            Text(
                                text = tr("已导入") + " ${state.importedCount ?: 0} " + tr("笔"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = QingPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            val count = preview.validRows.size
                            Button(
                                onClick = viewModel::confirm,
                                enabled = count > 0 && !state.importing,
                            ) {
                                Text(
                                    if (state.importing) tr("导入中…")
                                    else tr("确认导入") + " $count " + tr("笔"),
                                )
                            }
                        }
                        TextButton(onClick = {
                            if (!state.importing) {
                                viewModel.reset()
                                onDismiss()
                            }
                        }) { Text(tr("关闭")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportSummary(preview: BillImportPreview) {
    Text(
        text = tr("有效") + " ${preview.validRows.size} · " +
            tr("重复") + " ${preview.duplicateRows.size} · " +
            tr("错误") + " ${preview.errorRows.size}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (preview.validRows.isEmpty() && preview.errorRows.isNotEmpty()) {
        Text(
            text = preview.errorRows.firstOrNull()?.message ?: tr("没有可导入的有效记录"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ImportPreviewRow(row: BillPreviewRow) {
    val label = when (row.kind) {
        BillPreviewKind.VALID -> tr("有效")
        BillPreviewKind.DUPLICATE -> tr("重复")
        BillPreviewKind.ERROR -> tr("错误")
    }
    val labelColor = when (row.kind) {
        BillPreviewKind.VALID -> QingIncome
        BillPreviewKind.DUPLICATE -> MaterialTheme.colorScheme.tertiary
        BillPreviewKind.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = " · " + tr("第") + row.rowNumber + tr("行"),
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
            )
            Text(
                text = row.parsed?.let {
                    " · " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(Date(it.occurredAtMs))
                }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
                modifier = Modifier.weight(1f),
            )
            row.parsed?.let { parsed ->
                Text(
                    text = (if (parsed.direction == BillDirection.INCOME) "+" else "−") +
                        "¥" + "%.2f".format(Locale.CHINA, parsed.amountCents / 100.0),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (parsed.direction == BillDirection.INCOME) QingIncome else QingExpense,
                )
            }
        }
        val parsed = row.parsed
        if (parsed != null) {
            Text(
                text = parsed.merchant.ifBlank { tr("未知商户") },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            val message = row.message ?: row.duplicateOfRow?.let { tr("与第") + it + tr("行重复") }
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                )
            }
        } else {
            Text(
                text = row.message ?: tr("无法解析"),
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
            )
        }
    }
}
