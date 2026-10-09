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
            Text("私人 VPS 同步")
            Text("同步仅上传交易时间、金额、来源、分类及商户。不上传短信全文、银行卡号、个人备注。")
            OutlinedTextField(value = url, onValueChange = { url=it }, label = { Text("HTTPS 服务器地址") }, singleLine = true)
            OutlinedTextField(value = token, onValueChange = { token=it }, label = { Text("设备同步密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Switch(checked=enabled, onCheckedChange={ enabled=it })
            FilledTonalButton(onClick={
                try {
                    TransactionSyncWorker.configure(context, url, token, enabled)
                    message = if (enabled) "已保存：后台自动同步已开启" else "已关闭同步"
                } catch (_: IllegalArgumentException) {
                    message = "需要 HTTPS 地址及至少16位的密钥"
                }
            }) { Text("保存设置") }
            if (enabled) {
                Text("新消费会立即安排上传；后台每 15 分钟补偿同步（系统省电时可能延迟）。")
                FilledTonalButton(onClick = {
                    TransactionSyncWorker.requestImmediate(context)
                    message = "已请求立即同步，完成后状态自动更新"
                }) { Text("立即同步") }
            }
            Text("最近成功：" + if (lastSuccess > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastSuccess)) else "尚无")
            Text("状态：$message")
        }
    }
}
