package com.bookkeeping.app.ui.screen.billimport

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.backup.webdav.WebDavPreset
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBackupSheet(
    onDismiss: () -> Unit,
    viewModel: WebDavBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        viewModel.refreshFromPrefs()
    }

    ModalBottomSheet(
        onDismissRequest = { if (!state.isBusy) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("WebDAV 网盘自动备份"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tr("单机防丢 + 换机搬家 · 只传轻量账本压缩包（不含付款原图），自动留最近 10 份"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { if (!state.isBusy) onDismiss() }) {
                    Icon(Icons.Filled.Close, contentDescription = tr("关闭"))
                }
            }

            if (state.lastBackupMs > 0L) {
                val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.lastBackupMs))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = QingPrimaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.CloudDone, contentDescription = null, tint = QingPrimary, modifier = Modifier.size(18.dp))
                        Text(
                            text = tr("上次备份：%s · %s").format(timeStr, state.lastBackupStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = QingPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Text(
                text = tr("选择网盘"),
                style = MaterialTheme.typography.labelMedium,
                color = QingTextSecondary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WebDavPreset.entries.forEach { preset ->
                    val selected = state.preset == preset
                    Surface(
                        onClick = { viewModel.selectPreset(preset) },
                        shape = RoundedCornerShape(50),
                        color = if (selected) QingPrimaryContainer else QingSurface,
                        border = BorderStroke(1.dp, if (selected) QingPrimary else QingOutline),
                    ) {
                        Text(
                            text = tr(preset.label),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) QingPrimary else QingText,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::updateUrl,
                label = { Text(tr("WebDAV 备份目录地址")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::updateUsername,
                label = { Text(tr("账号（如坚果云注册邮箱）")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text(tr("应用密码")) },
                supportingText = { Text(tr(state.preset.passwordHint)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = QingSurface,
                border = BorderStroke(1.dp, QingOutline.copy(alpha = 0.65f)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("每日自动备份"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                tr("今天有新账变动时自动备份 1 次"),
                                style = MaterialTheme.typography.bodySmall,
                                color = QingTextSecondary,
                            )
                        }
                        Switch(checked = state.autoEnabled, onCheckedChange = viewModel::setAutoEnabled)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("仅 Wi-Fi 下自动备份"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                tr("省流量不走蜂窝网络"),
                                style = MaterialTheme.typography.bodySmall,
                                color = QingTextSecondary,
                            )
                        }
                        Switch(checked = state.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (state.testing) tr("测试中...") else tr("测试连接"))
                }
                Button(
                    onClick = viewModel::backupNow,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.backingUp) tr("备份中...") else tr("立即备份"))
                }
            }

            OutlinedButton(
                onClick = viewModel::loadRemoteBackups,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.loadingRemote) tr("读取网盘中...") else tr("查看并从网盘恢复"))
            }

            state.feedback?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = QingPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
            state.error?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.remoteFiles.isNotEmpty()) {
                Text(
                    text = tr("网盘历史备份（点击可预览并恢复）"),
                    style = MaterialTheme.typography.labelMedium,
                    color = QingTextSecondary,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.remoteFiles, key = { it.url }) { file ->
                        val sizeKb = (file.sizeBytes / 1024L).coerceAtLeast(1L)
                        val dateText = if (file.modifiedMs > 0L) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.modifiedMs))
                        } else {
                            file.name
                        }
                        Surface(
                            onClick = { viewModel.inspectRemoteFile(file) },
                            shape = RoundedCornerShape(12.dp),
                            color = QingSurface,
                            border = BorderStroke(1.dp, QingOutline.copy(alpha = 0.65f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "$dateText · ${sizeKb} KB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = QingTextSecondary,
                                    )
                                }
                                Text(
                                    text = tr("恢复"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = QingPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.restoring) viewModel.dismissPreview() },
            title = { Text(tr("确认从网盘恢复")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr("这份备份里共有 %d 笔账单、%d 项订阅。").format(
                            preview.manifest.transactionCount,
                            preview.manifest.subscriptionCount,
                        ),
                    )
                    Text(
                        tr("其中本机还没有的新账单：%d 笔；已有重复账单：%d 笔。").format(
                            preview.newTransactionCount,
                            preview.duplicateTransactionCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                    )
                    Text(
                        tr("「合并恢复」只补回缺的账单，不会冲掉新记的账；选「覆盖设置」前会自动在本机悄悄留一份兜底备份。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.restoring,
                    onClick = { viewModel.confirmRestore(overwriteConfigs = false) },
                ) {
                    Text(if (state.restoring) tr("恢复中...") else tr("合并恢复（推荐）"))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !state.restoring,
                        onClick = { viewModel.confirmRestore(overwriteConfigs = true) },
                    ) {
                        Text(tr("合并账单并覆盖设置"))
                    }
                    TextButton(
                        enabled = !state.restoring,
                        onClick = viewModel::dismissPreview,
                    ) {
                        Text(tr("取消"))
                    }
                }
            },
        )
    }
}
