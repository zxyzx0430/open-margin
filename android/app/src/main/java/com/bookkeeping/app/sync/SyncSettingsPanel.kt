package com.bookkeeping.app.sync

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr

/**
 * 同步设置页：只负责改配置（地址 / 密钥 / 开关 / 保存），不展示同步状态。
 *
 * 拆页的原因：状态和可编辑配置放在一起时，用户「看一眼为什么失败」时手边就是
 * 密钥输入框，很容易误改。状态、待上传、冲突全部挪到 SyncStatusPanel，
 * 这里连「立即同步」都不放 —— 那是状态页的动作。
 */
@Composable
fun SyncSettingsPanel(onOpenWizard: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(
            TransactionSyncWorker.PREFS,
            android.content.Context.MODE_PRIVATE,
        )
    }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var message by remember { mutableStateOf("") }
    var confirmChangeServer by remember { mutableStateOf(false) }

    // 首次连接向导和这张卡片共用同一份 SharedPreferences。向导里验证成功后
    // 会写入新的地址和密钥；这里不跟着刷新的话，再点「保存设置」就会把
    // 刚验证过的地址覆盖回旧值。
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == "url" || changed == "token" || changed == "enabled") {
                url = prefs.getString("url", "") ?: ""
                token = prefs.getString("token", "") ?: ""
                enabled = prefs.getBoolean("enabled", false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        cursorColor = MaterialTheme.colorScheme.primary,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("清衡 · 双向同步（Windows / VPS）"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                tr("手机和服务器会双向同步交易新增、修改和删除。服务器在线时下载更新，离线先保存在本机；不上传短信全文、银行卡号或个人备注。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 「二选一」和「换地址 = 换账本」是操作后果说明，不是免责套话，必须留在页面上。
            Text(
                tr("Windows 和 VPS 二选一：已成功同步的手机不能直接更换服务器地址；迁移需备份原服务数据。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(tr("HTTPS 服务器地址")) },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(tr("设备同步密钥")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("启用双向同步"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("新增或修改交易后立即尝试同步"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Button(
                onClick = {
                    try {
                        TransactionSyncWorker.configure(context, url, token, enabled)
                        message = if (enabled) tr("已保存：后台自动同步已开启") else tr("已关闭同步")
                    } catch (error: IllegalArgumentException) {
                        message = error.message ?: tr("同步配置未通过校验")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("保存设置")) }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 开启后会发生什么：这是操作后果说明，保留。
            if (enabled) {
                Text(
                    tr("新增或修改交易立即尝试同步，后台约每 15 分钟补偿同步。电脑关机或网络断开时先本地保存，重新连接后同步。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(
                onClick = onOpenWizard,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("首次连接向导")) }
            Text(
                tr("3 步连接 Windows / VPS"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = tr("更换服务器"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                tr("换地址等于换账本，本地记录不会自动迁移。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { confirmChangeServer = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("更换服务器")) }
        }
    }

    if (confirmChangeServer) {
        AlertDialog(
            onDismissRequest = { confirmChangeServer = false },
            title = { Text(tr("更换服务器")) },
            text = {
                Text(
                    tr(
                        "换地址等于换账本，本地记录不会自动迁移。" +
                            "请先在原服务器备份并迁移数据；已成功同步的手机不能直接换服务器。",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmChangeServer = false
                    url = ""
                    token = ""
                    message = tr("已清空地址和密钥：填写新服务器地址和密钥后再保存")
                }) { Text(tr("我已备份，继续更换")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmChangeServer = false }) { Text(tr("取消")) }
            },
        )
    }
}
