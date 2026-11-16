package com.bookkeeping.app.sync

import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.data.local.entity.TransactionCategory
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    var message by remember { mutableStateOf(prefs.getString("last_result", tr("同步关闭或尚未执行")) ?: "") }
    var lastResultCode by remember {
        mutableStateOf(prefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null))
    }

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
        message = prefs.getString("last_result", tr("尚未执行同步")) ?: ""
        lastResultCode = prefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null)
        lastSuccess = prefs.getLong("last_success_ms", 0L)
        conflictIds = prefs.getStringSet("pending_conflict_event_ids",emptySet())?.toSet() ?: emptySet()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            updateStatus()
        }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Text(
                tr("Windows 和 VPS 二选一：已成功同步的手机不能直接更换服务器地址；迁移需备份原服务数据。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = url, onValueChange = { url=it }, label = { Text(tr("HTTPS 服务器地址")) }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = token, onValueChange = { token=it }, label = { Text(tr("设备同步密钥")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors, modifier = Modifier.fillMaxWidth())
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
            if (enabled) {
                Text(
                    tr("新增或修改交易立即尝试同步，后台约每 15 分钟补偿同步。电脑关机或网络断开时先本地保存，重新连接后同步。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = {
                    TransactionSyncWorker.requestImmediate(context)
                    message = tr("已请求立即同步，完成后状态自动更新")
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("立即同步")) }
            }
            val conflicts=prefs.getInt("pending_sync_conflicts",0)
            if (conflicts>0) {
                Text(if (com.bookkeeping.app.i18n.AppLanguage.current() == com.bookkeeping.app.i18n.AppLanguage.ENGLISH) "$conflicts local changes are pending; see sync status." else "还有 $conflicts 笔本地修改未同步，详情请查看同步状态。")
            }
            if (conflictIds.isNotEmpty()) {
                Text(tr("以下交易存在版本冲突，请逐笔决定。每个按钮只影响对应的一笔交易。"))
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
                    val revision=preview?.optLong("revision",-1L) ?: -1L
                    val serverRecordId = serverRecord?.optString("id", "").orEmpty()
                        .ifBlank { preview?.optString("event_id", "").orEmpty() }
                        .ifBlank { eventId }
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(
                            conflictSideDetails(
                                source = "本机",
                                timestampMs = row.timestamp,
                                category = row.category,
                                merchant = row.merchant,
                                amountCents = row.amountCents,
                                currency = row.currency,
                                recordId = row.id.takeIf { it > 0L }?.toString(),
                            )
                        )
                        Text(
                            conflictSideDetails(
                                source = "服务器",
                                timestampMs = serverRecord?.longOrNull("timestamp_ms"),
                                category = serverRecord?.textOrNull("category"),
                                merchant = serverRecord?.textOrNull("merchant"),
                                amountCents = serverRecord?.longOrNull("amount_cents"),
                                currency = serverRecord?.textOrNull("currency"),
                                recordId = serverRecordId,
                            )
                        )
                        if (preview?.optString("action") == "delete") {
                            Text(tr("服务器版本已删除"))
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled=revision>=0,onClick={
                                prefs.edit()
                                    .putString("conflict_choice:$eventId","keep_phone")
                                    .putLong("conflict_choice_revision:$eventId",revision)
                                    .putLong("conflict_choice_local_modified_ms:$eventId",row.syncModifiedMs)
                                    .putString("conflict_choice_mutation_id:$eventId",row.syncMutationId)
                                    .apply()
                                TransactionSyncWorker.requestImmediate(context)
                                message=tr("只为这笔交易选择了本机版本，正在同步")
                            }) { Text(tr("保留本机")) }
                            OutlinedButton(enabled=revision>=0,onClick={
                                prefs.edit()
                                    .putString("conflict_choice:$eventId","keep_server")
                                    .putLong("conflict_choice_revision:$eventId",revision)
                                    .putLong("conflict_choice_local_modified_ms:$eventId",row.syncModifiedMs)
                                    .putString("conflict_choice_mutation_id:$eventId",row.syncMutationId)
                                    .apply()
                                TransactionSyncWorker.requestImmediate(context)
                                message=tr("只为这笔交易选择了服务器版本，正在同步")
                            }) { Text(tr("采用服务器")) }
                        }
                    }
                }
                if (matching.size>5) Text(tr("还有 ") + (matching.size-5) + tr(" 笔，请处理当前项目后继续查看。"))
            }
            val resultCode = SyncResultCode.from(lastResultCode)
            val failed = message.contains("失败") || message.contains("异常") ||
                message.contains("不可用") || message.contains("401") || message.contains("403")
            val statusColor = when (resultCode) {
                SyncResultCode.OK -> MaterialTheme.colorScheme.primary
                SyncResultCode.RETRY, SyncResultCode.BLOCKED, SyncResultCode.AUTH ->
                    MaterialTheme.colorScheme.error
                SyncResultCode.CONFIG -> MaterialTheme.colorScheme.onSurfaceVariant
                null -> if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = "${tr("最近成功")}：" + (
                    if (lastSuccess > 0L) {
                        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastSuccess))
                    } else {
                        tr("尚无")
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${tr("状态")}：${tr(resultCode?.label ?: message)}",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
            if (resultCode != null && message.isNotBlank()) {
                Text(
                    text = "${tr("详情")}：${tr(message)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun conflictSideDetails(
    source: String,
    timestampMs: Long?,
    category: String?,
    merchant: String?,
    amountCents: Long?,
    currency: String?,
    recordId: String?,
): String {
    val timestamp = timestampMs?.takeIf { it > 0L }?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))
    } ?: "—"
    val categoryValue = category?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { TransactionCategory.valueOf(raw).displayName }.getOrNull() ?: raw
    }?.let { tr(it) } ?: "—"
    val merchantValue = merchant?.takeIf { it.isNotBlank() } ?: "—"
    val amount = if (amountCents != null && !currency.isNullOrBlank()) {
        "$currency ${"%.2f".format(Locale.CHINA, amountCents / 100.0)}"
    } else {
        "—"
    }
    val idTail = recordId?.takeIf { it.isNotBlank() }?.takeLast(6) ?: "—"
    return listOf(
        "${tr("日期时间")}：$timestamp",
        "${tr("分类")}：$categoryValue",
        "${tr("商户")}：$merchantValue",
        "${tr("金额")}：$amount",
        "${tr("来源")}：${tr(source)}",
        "${tr("记录 ID 后 6 位")}：$idTail",
    ).joinToString("\n")
}

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.textOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
