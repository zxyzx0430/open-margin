package com.bookkeeping.app.sync

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SyncSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(TransactionSyncWorker.PREFS, 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var message by remember { mutableStateOf(prefs.getString("last_result", "同步关闭或尚未执行") ?: "") }
    var lastSuccess by remember { mutableStateOf(prefs.getLong("last_success_ms", 0L)) }
    fun updateStatus() {
        message = prefs.getString("last_result", "尚未执行同步") ?: ""
        lastSuccess = prefs.getLong("last_success_ms", 0L)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            updateStatus()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("清衡 · 双向同步（Windows / VPS）")
            Text("手机和服务器会双向同步交易新增、修改和删除。服务器在线时下载更新，离线先保存在本机；不上传短信全文、银行卡号或个人备注。")
            Text("Windows 和 VPS 二选一：已成功同步的手机不能直接更换服务器地址；迁移需备份原服务数据。")
            OutlinedTextField(value = url, onValueChange = { url=it }, label = { Text("HTTPS 服务器地址") }, singleLine = true)
            OutlinedTextField(value = token, onValueChange = { token=it }, label = { Text("设备同步密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Switch(checked=enabled, onCheckedChange={ enabled=it })
            FilledTonalButton(onClick={
                try {
                    TransactionSyncWorker.configure(context, url, token, enabled)
                    message = if (enabled) "已保存：后台自动同步已开启" else "已关闭同步"
                } catch (error: IllegalArgumentException) {
                    message = error.message ?: "同步配置未通过校验"
                }
            }) { Text("保存设置") }
            if (enabled) {
                Text("新增或修改交易立即尝试同步，后台约每 15 分钟补偿同步。电脑关机或网络断开时先本地保存，重新连接后同步。")
                FilledTonalButton(onClick = {
                    TransactionSyncWorker.requestImmediate(context)
                    message = "已请求立即同步，完成后状态自动更新"
                }) { Text("立即同步") }
            }
            val conflicts=prefs.getInt("pending_sync_conflicts",0)
            if (conflicts>0) {
                Text("有 $conflicts 笔本地修改尚未确认。遇到双方几乎同时修改的交易时，请选择优先版本。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        prefs.edit().putString("conflict_resolution","keep_phone").apply()
                        TransactionSyncWorker.requestImmediate(context)
                        message="已选择本机修改优先，正在尝试同步"
                    }) { Text("使用本机") }
                    OutlinedButton(onClick = {
                        prefs.edit().putString("conflict_resolution","keep_server").apply()
                        TransactionSyncWorker.requestImmediate(context)
                        message="已选择服务器修改优先，正在尝试同步"
                    }) { Text("使用服务器") }
                }
            }
            Text("最近成功：" + if (lastSuccess > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastSuccess)) else "尚无")
            Text("状态：$message")
        }
    }
}
