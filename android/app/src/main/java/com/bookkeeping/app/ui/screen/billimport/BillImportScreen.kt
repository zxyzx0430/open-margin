package com.bookkeeping.app.ui.screen.billimport

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.billimport.BillPreviewRow
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单导入页（独立路由 bill-import）。
 *
 * 以前导入是「账单 tab 里的弹窗 + 设置页先跳到账单 tab」，关掉后用户被留在账单页。
 * 现在它是一个正常页面：从哪个 tab 进来，返回/完成就 popBackStack 回那个 tab；
 * 导入结果只在本页提示，不替用户切页面。
 */
@Composable
fun BillImportScreen(
    onClose: () -> Unit,
    viewModel: BillImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::load)
    }

    // 写入过程中不让离开：ViewModel 随返回销毁，协程被取消可能留下半批数据。
    BackHandler(enabled = !state.importing) { onClose() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                enabled = !state.importing,
                modifier = Modifier.offset(x = (-10).dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("返回"),
                )
            }
            Text(
                text = tr("导入账单"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = tr("支持微信/支付宝官方 CSV、XLSX 或文本账单；确认前不会写入账本。"),
            style = MaterialTheme.typography.bodySmall,
            color = QingTextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                launcher.launch(
                    arrayOf(
                        "text/*",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                    ),
                )
            },
            enabled = !state.loading && !state.importing,
        ) {
            Text(tr("选择账单文件"))
        }

        if (state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
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
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        state.preview?.let { preview ->
            Spacer(Modifier.height(14.dp))
            ImportSummary(preview = preview)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(preview.rows) { row -> ImportPreviewRow(row) }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.done) {
                    Text(
                        text = tr("已导入") + " ${state.importedCount ?: 0} " + tr("笔") +
                            " · " + tr("已补齐") + " ${state.enrichedCount ?: 0} " + tr("笔") +
                            "\n" + tr("可在「账单」页查看，列表会自动刷新。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = QingPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onClose) { Text(tr("完成")) }
                } else {
                    val count = preview.importableRows.size
                    Button(
                        onClick = viewModel::confirm,
                        enabled = count > 0 && !state.importing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (state.importing) tr("导入中…")
                            else tr("确认导入") + " $count " + tr("笔"),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ImportSummary(preview: BillImportPreview) {
    Text(
        text = tr("有效") + " ${preview.validRows.size} · " +
            tr("可补齐") + " ${preview.enrichableRows.size} · " +
            tr("重复") + " ${preview.duplicateRows.size - preview.enrichableRows.size} · " +
            tr("已跳过") + " ${preview.skippedRows.size} · " +
            tr("错误") + " ${preview.errorRows.size}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (preview.validRows.isEmpty() && preview.enrichableRows.isEmpty() &&
        preview.errorRows.isNotEmpty()
    ) {
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
        BillPreviewKind.DUPLICATE -> if (row.enrichable) tr("可补齐") else tr("重复")
        BillPreviewKind.SKIPPED -> tr("已跳过")
        BillPreviewKind.ERROR -> tr("错误")
    }
    val labelColor = when (row.kind) {
        BillPreviewKind.VALID -> QingIncome
        BillPreviewKind.DUPLICATE -> MaterialTheme.colorScheme.tertiary
        BillPreviewKind.SKIPPED -> QingTextSecondary
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
                    text = when (parsed.direction) {
                        BillDirection.INCOME -> "+"
                        BillDirection.EXPENSE -> "−"
                        BillDirection.NEUTRAL -> "±"
                    } + formatCny(parsed.amountCents),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        row.kind == BillPreviewKind.ERROR -> MaterialTheme.colorScheme.error
                        row.kind == BillPreviewKind.SKIPPED -> QingTextSecondary
                        parsed.direction == BillDirection.INCOME -> QingIncome
                        else -> QingExpense
                    },
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
