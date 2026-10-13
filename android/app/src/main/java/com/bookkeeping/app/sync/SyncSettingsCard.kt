package com.bookkeeping.app.sync

import android.content.SharedPreferences
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
import androidx.hilt.navigation.compose.hiltViewModel
import org.json.JSONObject

@Composable
fun SyncSettingsCard() {
    val context = LocalContext.current
    val pendingViewModel: PendingSyncViewModel = hiltViewModel()
    val pendingRows by pendingViewModel.pending.collectAsState()
    val prefs = remember { context.getSharedPreferences(TransactionSyncWorker.PREFS, 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var message by remember { mutableStateOf(prefs.getString("last_result", "同步关闭或尚未执行") ?: "") }

    // Windows first-connection wizard and this legacy VPS settings card share
    // the same preferences. An external successful wizard configuration must
    // refresh these editable fields; otherwise saving here would overwrite a
    // newly verified Windows endpoint with a stale (possibly empty) URL.
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
    var lastSuccess by remember { mutableStateOf(prefs.getLong("last_success_ms", 0L)) }
    var conflictIds by remember {
        mutableStateOf(prefs.getStringSet("pending_conflict_event_ids",emptySet())?.toSet() ?: emptySet())
    }
    fun updateStatus() {
        message = prefs.getString("last_result", "尚未执行同步") ?: ""
        lastSuccess = prefs.getLong("last_success_ms", 0L)
        conflictIds = prefs.getStringSet("pending_conflict_event_ids",emptySet())?.toSet() ?: emptySet()
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
                Text("还有 $conflicts 笔本地修改未同步，详情请查看同步状态。")
            }
            if (conflictIds.isNotEmpty()) {
                Text("以下交易存在版本冲突，请逐笔决定。每个按钮只影响对应的一笔交易。")
                val installation=prefs.getString("installation_id","").orEmpty()
                val matching=pendingRows.filter { row ->
                    val eventId=row.remoteEventId ?: ("android:$installation:" + row.id)
                    eventId in conflictIds
                }
                matching.take(5).forEach { row ->
                    val eventId=row.remoteEventId ?: ("android:$installation:" + row.id)
                    val preview=runCatching {
                        prefs.getString("conflict_server_preview:$eventId",null)
                            ?.let { JSONObject(it) }
                    }.getOrNull()
                    val serverRecord=preview?.optJSONObject("record")
                    val serverDescription=when {
                        preview==null -> "服务器版本正在加载"
                        preview.optString("action")=="delete" -> "服务器已删除这笔记录"
                        serverRecord!=null -> {
                            val amount=serverRecord.optLong("amount_cents",0L)/100.0
                            (serverRecord.optString("merchant","").ifBlank { "交易" }) +
                                " · " + serverRecord.optString("currency","CNY") +
                                " " + "%.2f".format(Locale.CHINA,amount)
                        }
                        else -> "服务器版本待核对"
                    }
                    val revision=preview?.optLong("revision",-1L) ?: -1L
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("本机：" + (row.merchant.ifBlank { row.source }) + " · " +
                            row.currency + " " + "%.2f".format(Locale.CHINA,row.amountCents/100.0))
                        Text("服务器：" + serverDescription)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled=revision>=0,onClick={
                                prefs.edit()
                                    .putString("conflict_choice:$eventId","keep_phone")
                                    .putLong("conflict_choice_revision:$eventId",revision)
                                    .putLong("conflict_choice_local_modified_ms:$eventId",row.syncModifiedMs)
                                    .putString("conflict_choice_mutation_id:$eventId",row.syncMutationId)
                                    .apply()
                                TransactionSyncWorker.requestImmediate(context)
                                message="只为这笔交易选择了本机版本，正在同步"
                            }) { Text("保留本机") }
                            OutlinedButton(enabled=revision>=0,onClick={
                                prefs.edit()
                                    .putString("conflict_choice:$eventId","keep_server")
                                    .putLong("conflict_choice_revision:$eventId",revision)
                                    .putLong("conflict_choice_local_modified_ms:$eventId",row.syncModifiedMs)
                                    .putString("conflict_choice_mutation_id:$eventId",row.syncMutationId)
                                    .apply()
                                TransactionSyncWorker.requestImmediate(context)
                                message="只为这笔交易选择了服务器版本，正在同步"
                            }) { Text("采用服务器") }
                        }
                    }
                }
                if (matching.size>5) Text("还有 " + (matching.size-5) + " 笔，请处理当前项目后继续查看。")
            }
            Text("最近成功：" + if (lastSuccess > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastSuccess)) else "尚无")
            Text("状态：$message")
        }
    }
}
