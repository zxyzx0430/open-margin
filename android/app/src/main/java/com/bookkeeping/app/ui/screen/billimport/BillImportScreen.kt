package com.bookkeeping.app.ui.screen.billimport

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.backup.BackupPreview
import com.bookkeeping.app.backup.BackupRestoreOptions
import com.bookkeeping.app.backup.BackupRestoreResult
import com.bookkeeping.app.backup.FullBackupManager
import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.billimport.BillPreviewRow
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.ui.theme.QingWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun bilingual(zh: String, en: String): String =
    if (AppLanguage.current() == AppLanguage.ENGLISH) en else zh

/**
 * 账单导入页（独立路由 bill-import）。
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
                text = bilingual("导入与搬家", "Import & migrate"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = bilingual(
                "自动识别微信 / 支付宝 / 清衡 / 钱迹 / 一木 / 鲨鱼账单（CSV / XLSX / 文本），确认前不会写入账本。",
                "Auto-detects WeChat / Alipay / Open Margin / Qianji / Yimu / Shark bills (CSV / XLSX / text); nothing is written until you confirm.",
            ),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
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
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun BillImportExportSheet(
    onClose: () -> Unit,
    onExportBills: () -> Unit,
    exportFeedback: String?,
    exportInProgress: Boolean,
    viewModel: BillImportViewModel,
    backupViewModel: FullBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val backupState by backupViewModel.state.collectAsState()

    val billLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::load)
    }
    val zipExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let(backupViewModel::exportZip)
    }
    val zipImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(backupViewModel::inspectBackup)
    }

    val sheetBusy = state.importing || backupState.isBusy
    BackHandler(enabled = sheetBusy) { }

    fun handleClose() {
        if (sheetBusy) return
        backupViewModel.resetFeedback()
        onClose()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 760.dp)
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = bilingual("数据迁移与备份", "Data migration & backup"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = ::handleClose, enabled = !sheetBusy) {
                Icon(Icons.Filled.Close, contentDescription = tr("关闭"))
            }
        }

        if (state.preview != null) {
            // 账单导入预览与确认（保留完整滚动列表体验）
            ImportSummary(preview = state.preview!!)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.preview!!.rows) { row -> ImportPreviewRow(row) }
            }
            HorizontalDivider()
            if (state.done) {
                Text(
                    text = tr("已导入") + " ${state.importedCount ?: 0} " + tr("笔") +
                        " · " + tr("已补齐") + " ${state.enrichedCount ?: 0} " + tr("笔"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = QingPrimary,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(onClick = ::handleClose, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("完成"))
                }
            } else {
                val count = state.preview!!.importableRows.size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::reset,
                        enabled = !state.importing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(tr("返回"))
                    }
                    Button(
                        onClick = viewModel::confirm,
                        enabled = count > 0 && !state.importing,
                        modifier = Modifier.weight(2f),
                    ) {
                        Text(
                            if (state.importing) tr("导入中…")
                            else tr("确认导入") + " $count " + tr("笔"),
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. 导入与搬家区
                MigrationSectionCard(
                    loading = state.loading,
                    disabled = sheetBusy || exportInProgress,
                    error = state.error,
                    onPickBillFile = {
                        billLauncher.launch(
                            arrayOf(
                                "text/*",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                            ),
                        )
                    },
                )

                // 2. 导出与换机备份区
                BackupSectionCard(
                    includeEvidence = backupState.includeEvidence,
                    onIncludeEvidenceChanged = backupViewModel::setIncludeEvidence,
                    exportCsvInProgress = exportInProgress,
                    exportCsvFeedback = exportFeedback,
                    exportZipInProgress = backupState.exportingZip,
                    exportZipFeedback = backupState.exportFeedback,
                    exportZipError = backupState.exportError,
                    inspectingZip = backupState.inspectingZip,
                    restoreResult = backupState.restoreResult,
                    restoreError = backupState.restoreError,
                    disabled = sheetBusy || state.loading || exportInProgress,
                    onExportCsv = onExportBills,
                    onExportZip = {
                        zipExportLauncher.launch(FullBackupManager.defaultZipFileName())
                    },
                    onImportZip = {
                        zipImportLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                )
            }
        }
    }

    backupState.restorePreview?.let { preview ->
        BackupRestorePreviewDialog(
            preview = preview,
            options = backupState.restoreOptions,
            restoring = backupState.restoringZip,
            error = backupState.restoreError,
            onOptionsChange = backupViewModel::updateRestoreOptions,
            onDismiss = backupViewModel::dismissRestorePreview,
            onConfirm = backupViewModel::confirmRestore,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MigrationSectionCard(
    loading: Boolean,
    disabled: Boolean,
    error: String?,
    onPickBillFile: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = bilingual("导入与搬家", "Import & migrate"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = QingPrimaryContainer.copy(alpha = 0.72f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = bilingual(
                            "直接选文件即可，自动识别平台格式",
                            "Pick any bill file directly — platform auto-detected",
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = QingPrimary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            bilingual("微信", "WeChat"),
                            bilingual("支付宝", "Alipay"),
                            bilingual("清衡", "Open Margin"),
                            bilingual("钱迹", "Qianji"),
                            bilingual("一木", "Yimu"),
                            bilingual("鲨鱼", "Shark"),
                        ).forEach { sourceName ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Text(
                                    text = sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = bilingual(
                    "支持 CSV、XLSX 或文本账单，预览并确认后才会写入账本。",
                    "Supports CSV, XLSX, or text exports; nothing is written until you confirm.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
            )
            Button(
                onClick = onPickBillFile,
                modifier = Modifier.fillMaxWidth(),
                enabled = !disabled,
            ) {
                Text(
                    tr("选择账单文件"),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BackupSectionCard(
    includeEvidence: Boolean,
    onIncludeEvidenceChanged: (Boolean) -> Unit,
    exportCsvInProgress: Boolean,
    exportCsvFeedback: String?,
    exportZipInProgress: Boolean,
    exportZipFeedback: String?,
    exportZipError: String?,
    inspectingZip: Boolean,
    restoreResult: BackupRestoreResult?,
    restoreError: String?,
    disabled: Boolean,
    onExportCsv: () -> Unit,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = bilingual("导出与换机备份", "Export & device backup"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = onExportCsv,
                modifier = Modifier.fillMaxWidth(),
                enabled = !disabled,
            ) {
                Text(
                    bilingual("导出账单 CSV", "Export bills CSV"),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (exportCsvInProgress) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            exportCsvFeedback?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !disabled) { onIncludeEvidenceChanged(!includeEvidence) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = bilingual("同时打包票据原图", "Include receipt images"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = bilingual(
                            "默认关闭（几百 KB 秒传），不含同步密钥等敏感凭证",
                            "Off by default for compact backups; sync tokens & secrets are never exported",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                    )
                }
                Switch(
                    checked = includeEvidence,
                    onCheckedChange = onIncludeEvidenceChanged,
                    enabled = !disabled,
                )
            }

            FilledTonalButton(
                onClick = onExportZip,
                modifier = Modifier.fillMaxWidth(),
                enabled = !disabled,
            ) {
                Text(
                    if (exportZipInProgress) {
                        bilingual("正在打包完整备份…", "Creating backup…")
                    } else {
                        bilingual("导出完整备份 (.zip)", "Export full backup (.zip)")
                    },
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (exportZipInProgress) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            exportZipFeedback?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = QingPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            exportZipError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            OutlinedButton(
                onClick = onImportZip,
                modifier = Modifier.fillMaxWidth(),
                enabled = !disabled,
            ) {
                Text(
                    if (inspectingZip) {
                        bilingual("正在解析备份文件…", "Inspecting backup…")
                    } else {
                        bilingual("从完整备份 (.zip) 恢复", "Restore from full backup (.zip)")
                    },
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (inspectingZip) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            restoreError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            restoreResult?.let { res ->
                RestoreResultBanner(res)
            }
        }
    }
}

@Composable
private fun RestoreResultBanner(result: BackupRestoreResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = QingPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = bilingual("安全合并恢复已完成", "Safe-merge restore completed"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = QingPrimary,
            )
            val details = buildList {
                if (result.insertedTransactions > 0 || result.skippedDuplicateTransactions > 0) {
                    add(
                        bilingual(
                            "账单：新增 ${result.insertedTransactions} 笔，跳过 ${result.skippedDuplicateTransactions} 笔重复",
                            "Transactions: +${result.insertedTransactions}, skipped ${result.skippedDuplicateTransactions} duplicates",
                        ),
                    )
                }
                if (result.insertedSubscriptions > 0 || result.updatedSubscriptions > 0) {
                    add(
                        bilingual(
                            "订阅：新增 ${result.insertedSubscriptions} 项，更新 ${result.updatedSubscriptions} 项",
                            "Subscriptions: +${result.insertedSubscriptions}, updated ${result.updatedSubscriptions}",
                        ),
                    )
                }
                if (result.restoredBudgetMonths > 0) {
                    add(
                        bilingual(
                            "预算：补齐 ${result.restoredBudgetMonths} 个月",
                            "Budgets: ${result.restoredBudgetMonths} month(s)",
                        ),
                    )
                }
                if (result.restoredMerchantRules > 0) {
                    add(
                        bilingual(
                            "分类规则：补齐 ${result.restoredMerchantRules} 条",
                            "Merchant rules: +${result.restoredMerchantRules}",
                        ),
                    )
                }
                if (result.restoredSettingsCount > 0) {
                    add(
                        bilingual(
                            "偏好设置：应用 ${result.restoredSettingsCount} 项",
                            "Preferences: ${result.restoredSettingsCount} item(s)",
                        ),
                    )
                }
                if (result.restoredEvidenceCount > 0) {
                    add(
                        bilingual(
                            "票据原图：恢复 ${result.restoredEvidenceCount} 张",
                            "Images: +${result.restoredEvidenceCount}",
                        ),
                    )
                }
            }
            Text(
                text = if (details.isEmpty()) {
                    bilingual(
                        "本机已包含备份中的所有数据，无需新增。",
                        "Your device already had all records from this backup.",
                    )
                } else {
                    details.joinToString("\n")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BackupRestorePreviewDialog(
    preview: BackupPreview,
    options: BackupRestoreOptions,
    restoring: Boolean,
    error: String?,
    onOptionsChange: ((BackupRestoreOptions) -> BackupRestoreOptions) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(preview.manifest.exportedAtMs))

    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = {
            Text(
                text = bilingual("确认从完整备份恢复", "Restore from full backup"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = QingPrimaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = QingPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = bilingual(
                                "安全合并模式：只补齐缺失记录，绝不删除本机现有任何数据",
                                "Safe-merge mode: only adds missing records and never deletes any existing local data.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = QingPrimary,
                        )
                    }
                }

                Text(
                    text = bilingual(
                        "备份时间：$dateText · 应用版本 v${preview.manifest.appVersion}",
                        "Backed up: $dateText · App v${preview.manifest.appVersion}",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = QingTextSecondary,
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = bilingual(
                            "预计新增 ${preview.newTransactionCount} 笔 · 跳过 ${preview.duplicateTransactionCount} 笔重复",
                            "Estimated new: ${preview.newTransactionCount} · Skip duplicates: ${preview.duplicateTransactionCount}",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                BackupModuleCheckboxRow(
                    title = bilingual("账单流水", "Transactions"),
                    summary = bilingual(
                        "预计新增 ${preview.newTransactionCount} 笔 · 重复 ${preview.duplicateTransactionCount} 笔",
                        "+${preview.newTransactionCount} new · ${preview.duplicateTransactionCount} duplicates",
                    ),
                    checked = options.restoreTransactions,
                    enabled = !restoring,
                    onCheckedChange = { checked ->
                        onOptionsChange { it.copy(restoreTransactions = checked) }
                    },
                )

                BackupModuleCheckboxRow(
                    title = bilingual("周期订阅", "Subscriptions"),
                    summary = bilingual(
                        "新增 ${preview.newSubscriptionCount} · 更新 ${preview.updatedSubscriptionCount} · 已有 ${preview.duplicateSubscriptionCount}",
                        "+${preview.newSubscriptionCount} new · ${preview.updatedSubscriptionCount} newer · ${preview.duplicateSubscriptionCount} existing",
                    ),
                    checked = options.restoreSubscriptions,
                    enabled = !restoring,
                    onCheckedChange = { checked ->
                        onOptionsChange { it.copy(restoreSubscriptions = checked) }
                    },
                )

                BackupModuleCheckboxRow(
                    title = bilingual("月度预算", "Budgets"),
                    summary = bilingual(
                        "${preview.budgetMonthCount} 个月配置",
                        "${preview.budgetMonthCount} month(s)",
                    ),
                    checked = options.restoreBudgets,
                    enabled = !restoring && preview.budgetMonthCount > 0,
                    onCheckedChange = { checked ->
                        onOptionsChange { it.copy(restoreBudgets = checked) }
                    },
                )

                BackupModuleCheckboxRow(
                    title = bilingual("商户分类规则", "Merchant rules"),
                    summary = bilingual(
                        "${preview.merchantRuleCount} 条规则",
                        "${preview.merchantRuleCount} rule(s)",
                    ),
                    checked = options.restoreMerchantRules,
                    enabled = !restoring && preview.merchantRuleCount > 0,
                    onCheckedChange = { checked ->
                        onOptionsChange { it.copy(restoreMerchantRules = checked) }
                    },
                )

                BackupModuleCheckboxRow(
                    title = bilingual("偏好设置（不含密钥）", "Preferences (no tokens)"),
                    summary = bilingual(
                        "${preview.settingsCount} 项配置",
                        "${preview.settingsCount} item(s)",
                    ),
                    checked = options.restoreSettings,
                    enabled = !restoring && preview.settingsCount > 0,
                    onCheckedChange = { checked ->
                        onOptionsChange { it.copy(restoreSettings = checked) }
                    },
                )

                BackupModuleCheckboxRow(
                    title = bilingual("票据原图", "Receipt images"),
                    summary = if (preview.evidenceFileCount == 0) {
                        bilingual("备份包未包含原图", "Not included in backup")
                    } else {
                        bilingual(
                            "新增 ${preview.newEvidenceFileCount} / 共 ${preview.evidenceFileCount} 张",
                            "+${preview.newEvidenceFileCount} new of ${preview.evidenceFileCount}",
                        )
                    },
                    checked = options.restoreEvidence && preview.evidenceFileCount > 0,
                    enabled = !restoring && preview.evidenceFileCount > 0,
                    onCheckedChange = { checked ->
                        onOptionsChange { it.copy(restoreEvidence = checked) }
                    },
                )

                HorizontalDivider()

                BackupModuleCheckboxRow(
                    title = bilingual("覆盖本机已有预算与规则", "Overwrite existing budgets & rules"),
                    summary = bilingual(
                        "默认关闭：仅补齐本机未设置的项",
                        "Off by default: only fills missing entries",
                    ),
                    checked = options.overwriteExistingConfigs,
                    enabled = !restoring,
                    onCheckedChange = { checked ->
                        onOptionsChange { it.copy(overwriteExistingConfigs = checked) }
                    },
                )

                if (preview.warnings.isNotEmpty()) {
                    Text(
                        text = preview.warnings.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = QingWarning,
                    )
                }

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !restoring) {
                Text(tr("取消"))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !restoring && options.hasAnySelected,
            ) {
                Text(
                    if (restoring) {
                        bilingual("恢复中…", "Restoring…")
                    } else {
                        bilingual("确认合并恢复", "Confirm merge restore")
                    },
                )
            }
        },
    )
}

@Composable
private fun BackupModuleCheckboxRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else QingTextSecondary,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportSummary(preview: BillImportPreview) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            "${tr("有效")} ${preview.validRows.size}",
            "${tr("可补齐")} ${preview.enrichableRows.size}",
            "${tr("重复")} ${preview.duplicateRows.size - preview.enrichableRows.size}",
            "${tr("已跳过")} ${preview.skippedRows.size}",
            "${tr("错误")} ${preview.errorRows.size}",
        ).forEach { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (preview.validRows.isEmpty() && preview.enrichableRows.isEmpty() &&
        preview.errorRows.isNotEmpty()
    ) {
        Text(
            text = preview.errorRows.firstOrNull()?.message ?: tr("没有可导入的有效记录"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = " · " + tr("第") + row.rowNumber + tr("行"),
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.parsed?.let {
                    " · " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(Date(it.occurredAtMs))
                }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = QingTextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.parsed?.let { parsed ->
                AutoSizeAmountText(
                    text = when (parsed.direction) {
                        BillDirection.INCOME -> "+"
                        BillDirection.EXPENSE -> "−"
                        BillDirection.NEUTRAL -> "±"
                    } + formatCny(parsed.amountCents),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(min = 82.dp),
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        row.kind == BillPreviewKind.ERROR -> MaterialTheme.colorScheme.error
                        row.kind == BillPreviewKind.SKIPPED -> QingTextSecondary
                        parsed.direction == BillDirection.INCOME -> QingIncome
                        else -> QingExpense
                    },
                    textAlign = TextAlign.End,
                )
            }
        }
        val parsed = row.parsed
        if (parsed != null) {
            val mappedCategory = BillImportMapping.toTransactionEntity(parsed)?.category
            val category = mappedCategory?.let { TransactionCategory.valueOf(it) }
                ?: BillImportMapping.classificationFor(parsed).category
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = parsed.merchant.ifBlank { tr("未知商户") },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tr(category.displayName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 120.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
