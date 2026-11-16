package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.formatCny
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    detailViewModel: TransactionDetailViewModel = hiltViewModel(),
    transactionViewModel: TransactionViewModel = hiltViewModel(),
) {
    val transaction by detailViewModel.transaction.collectAsState()
    val entry = transaction
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (editing && entry != null) {
        // 全屏编辑页替换详情页；保存/取消都回到这里，删除复用同一个二次确认。
        TransactionEditScreen(
            initial = entry,
            onCancel = { editing = false },
            onDelete = { confirmingDelete = true },
            onSave = { fields ->
                transactionViewModel.saveTransaction(entry, fields)
                editing = false
            },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(tr("账单详情"), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = tr("返回"),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                if (entry != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { editing = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(tr("编辑"))
                        }
                        OutlinedButton(
                            onClick = { confirmingDelete = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(tr("删除"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
        ) { innerPadding ->
            if (entry == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = tr("这笔记录已不存在"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = transactionAmount(entry),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.type == TransactionType.INCOME.name) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    DetailField(tr("收支类型"), transactionTypeLabel(entry.type))
                    DetailField(tr("分类"), transactionCategoryLabel(entry.category))
                    DetailField(tr("商户"), entry.merchant.ifBlank { tr("未知商户") })
                    DetailField(tr("备注"), entry.note.ifBlank { tr("无备注") })
                    DetailField(tr("账户"), entry.account?.takeIf { it.isNotBlank() } ?: tr("未设置"))
                    DetailField(tr("来源"), transactionSourceLabel(entry.source))
                    DetailField(tr("发生时间"), transactionTime(entry.timestamp))
                    DetailField(tr("审核状态"), transactionReviewStatus(entry))
                    DetailField(
                        tr("服务器同步"),
                        if (entry.remoteEventId != null) tr("已同步到服务器") else tr("尚未同步到服务器"),
                    )
                    DetailField(
                        tr("本地改动"),
                        if (entry.syncDirty || entry.remoteEventId == null) {
                            tr("有本地改动未上传")
                        } else {
                            tr("没有待上传的本地改动")
                        },
                    )
                    entry.duplicateOfId?.let { originalId ->
                        DetailField(
                            tr("疑似重复原记录"),
                            "#" + originalId.toString().takeLast(6),
                        )
                    }
                }
            }
        }
    }

    entry?.takeIf { confirmingDelete }?.let { entry ->
        DeleteTransactionDialog(
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                transactionViewModel.delete(entry)
                confirmingDelete = false
                editing = false
                onDeleted()
            },
        )
    }
}

@Composable
private fun DeleteTransactionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("删除这笔交易？")) },
        text = {
            Text(
                tr("删除将在电脑或 VPS 在线后双向同步；其他设备也会删除该笔交易。若只是误识别或退款，建议在网站标记为不计入或退款。")
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(tr("确认删除"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        },
    )
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun transactionAmount(transaction: TransactionEntity): String {
    val amount = if (transaction.currency == "CNY") {
        formatCny(transaction.amountCents)
    } else {
        transaction.currency + " " + "%.2f".format(Locale.CHINA, transaction.amountCents / 100.0)
    }
    return (if (transaction.type == TransactionType.INCOME.name) "+" else "−") + amount
}

private fun transactionTypeLabel(type: String): String = when (type) {
    TransactionType.INCOME.name -> tr("收入")
    else -> tr("支出")
}

private fun transactionCategoryLabel(category: String): String =
    tr(runCatching { TransactionCategory.valueOf(category).displayName }
        .getOrDefault(TransactionCategory.OTHER.displayName))

private fun transactionSourceLabel(source: String): String = when (source) {
    "ALIPAY" -> tr("支付宝")
    "WECHAT" -> tr("微信")
    "BANK_SMS" -> tr("银行短信")
    "MANUAL" -> tr("手动")
    else -> tr("其他来源")
}

private fun transactionReviewStatus(transaction: TransactionEntity): String = when {
    transaction.duplicateOfId != null || transaction.remoteReviewStatus == "possible_duplicate" ->
        tr("待确认重复")
    transaction.remoteReviewStatus == "confirmed" -> tr("已确认")
    transaction.remoteReviewStatus == "duplicate" -> tr("已确认")
    transaction.remoteReviewStatus == "refund" -> tr("退款")
    transaction.remoteReviewStatus == "transfer" -> tr("转账")
    transaction.remoteReviewStatus == "excluded" -> tr("不计入")
    else -> tr("未核实")
}

private fun transactionTime(timestamp: Long): String {
    val locale = if (AppLanguage.current() == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.CHINA
    val pattern = if (AppLanguage.current() == AppLanguage.ENGLISH) {
        "MMM d, yyyy HH:mm"
    } else {
        "yyyy-MM-dd HH:mm"
    }
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
