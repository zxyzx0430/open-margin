package com.bookkeeping.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/**
 * 同步状态页：只看状态、只做救火，**不出现服务器地址和密钥**。
 *
 * 原来的 SyncSettingsCard 把状态和可编辑配置塞在同一张卡里，用户想确认
 * 「刚才为什么失败」时，手边就是地址和密钥输入框，容易误改。这里只读状态，
 * 改配置的入口是显式的「重新连接」→ 同步设置页。
 */
@Composable
fun SyncStatusPanel(onOpenSyncSettings: () -> Unit) {
    val context = LocalContext.current
    val pendingViewModel: PendingSyncViewModel = hiltViewModel()
    val pendingRows by pendingViewModel.pending.collectAsState()
    val prefs = remember {
        context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
    }
    var snapshot by remember { mutableStateOf(readSyncSnapshot(prefs)) }
    var deferred by remember { mutableStateOf(setOf<String>()) }
    var syncRequested by remember { mutableStateOf(false) }

    // 同步在后台 WorkManager 里跑，没有可观察的回调；沿用原来的 5 秒轮询。
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = readSyncSnapshot(prefs)
            delay(5_000L)
        }
    }

    val kind = snapshot.kind
    val installation = prefs.getString("installation_id", "").orEmpty()
    val conflicts = remember(snapshot.conflictIds, snapshot.missingIds, pendingRows, installation) {
        snapshot.conflictIds
            .map { eventId -> buildConflictEntry(eventId, prefs, pendingRows, installation, snapshot) }
            .sortedByDescending { it.phone?.timestampMs ?: it.server?.timestampMs ?: 0L }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SyncStateHeader(kind = kind, lastSuccessMs = snapshot.lastSuccessMs)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(tr("待处理"))
            Text(
                text = if (snapshot.uploads > 0) {
                    String.format(Locale.getDefault(), tr("%d 笔修改待上传"), snapshot.uploads)
                } else {
                    tr("没有待上传的修改")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (conflicts.isEmpty()) {
                Text(
                    text = tr("没有需要逐笔核对的冲突"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        tr("%d 笔冲突待处理，每个按钮只影响对应的一笔"),
                        conflicts.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                conflicts.take(MAX_VISIBLE_CONFLICTS).forEachIndexed { index, entry ->
                    ConflictCard(
                        entry = entry,
                        index = index + 1,
                        total = conflicts.size,
                        deferred = entry.eventId in deferred,
                        onDefer = { deferred = deferred + entry.eventId },
                        onRestore = { deferred = deferred - entry.eventId },
                        onKeepPhone = {
                            applyConflictChoice(prefs, entry, keepPhone = true)
                            syncRequested = false
                            TransactionSyncWorker.requestImmediate(context)
                            snapshot = readSyncSnapshot(prefs)
                        },
                        onKeepServer = {
                            applyConflictChoice(prefs, entry, keepPhone = false)
                            syncRequested = false
                            TransactionSyncWorker.requestImmediate(context)
                            snapshot = readSyncSnapshot(prefs)
                        },
                    )
                }
                if (conflicts.size > MAX_VISIBLE_CONFLICTS) {
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            tr("还有 %d 笔，处理完当前项目后继续查看"),
                            conflicts.size - MAX_VISIBLE_CONFLICTS,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SyncLastResult(snapshot = snapshot)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(tr("操作"))
            Button(
                onClick = {
                    TransactionSyncWorker.requestImmediate(context)
                    syncRequested = true
                },
                enabled = snapshot.kind.syncAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("立即同步")) }
            if (syncRequested) {
                Text(
                    text = tr("已请求立即同步，完成后状态自动更新"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (kind.needsReconnect) {
                OutlinedButton(
                    onClick = onOpenSyncSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("重新连接")) }
            }
        }
    }
}

private const val MAX_VISIBLE_CONFLICTS = 20

/** 状态页顶部：一条大状态 + 最近成功时间，不写任何解释。 */
@Composable
private fun SyncStateHeader(kind: SyncStateKind, lastSuccessMs: Long) {
    val (container, content) = when (kind) {
        SyncStateKind.OK -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        SyncStateKind.RETRY, SyncStateKind.BLOCKED, SyncStateKind.AUTH ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        SyncStateKind.NOT_ENABLED, SyncStateKind.NOT_CONFIGURED, SyncStateKind.NEVER_RUN ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = tr(kind.label),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = content,
            )
            Text(
                text = if (lastSuccessMs > 0L) {
                    tr("最近成功：") + relativeSyncTime(lastSuccessMs)
                } else {
                    tr("尚未成功同步")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.85f),
            )
        }
    }
}

/** 最近一次结果：结果词 + 时间 + 可展开原文 + 复制。 */
@Composable
private fun SyncLastResult(snapshot: SyncSnapshot) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500L)
            copied = false
        }
    }
    val code = snapshot.code
    val raw = snapshot.message
    val resultLabel = when {
        code != null -> code.label
        raw.isBlank() -> "尚未执行同步"
        SyncState.legacyFailed(raw) -> "同步异常"
        else -> "同步正常"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(tr("最近一次结果"))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr(resultLabel),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (snapshot.resultAtMs > 0L) relativeSyncTime(snapshot.resultAtMs)
                else tr("尚无"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (raw.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) tr("收起") else tr("展开"))
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(raw))
                    copied = true
                }) { Text(if (copied) tr("已复制") else tr("复制")) }
            }
            if (expanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = raw,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * 一笔冲突。必须能回答「冲突的是哪一笔、哪一项」：
 * 两侧各一行（来源 · 日期时间 · 商户 · 金额），下面逐条列出不一致的字段。
 */
@Composable
private fun ConflictCard(
    entry: ConflictEntry,
    index: Int,
    total: Int,
    deferred: Boolean,
    onDefer: () -> Unit,
    onRestore: () -> Unit,
    onKeepPhone: () -> Unit,
    onKeepServer: () -> Unit,
) {
    val phone = entry.phone
    val server = entry.server
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        tr("冲突 %d/%d"),
                        index,
                        total,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                SoftBadge(text = tr("待处理"), tone = StatusTone.WARNING)
            }
            if (phone != null) {
                Text(
                    text = conflictSideLine(tr("本机"), phone),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = tr("本机这笔记录已不在待上传列表"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.missingOnServer) {
                Text(
                    text = tr("服务器上找不到这条记录"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (server != null) {
                Text(
                    text = conflictSideLine(tr("服务器"), server),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entry.serverDeleted) {
                    Text(
                        text = tr("服务器版本已删除"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!deferred && !entry.missingOnServer && !entry.serverDeleted &&
                phone != null && server != null
            ) {
                val fields = SyncConflictDiff.differingFields(phone, server)
                if (fields.isEmpty()) {
                    Text(
                        text = tr("内容相同，仅版本号不同"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = tr("不一致"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    fields.forEach { field ->
                        Text(
                            text = conflictFieldLine(field, phone, server),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (deferred) {
                Text(
                    text = tr("稍后处理，仍在待处理列表"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRestore) { Text(tr("展开")) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onKeepPhone,
                        enabled = entry.canResolve,
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("保留本机"), maxLines = 1) }
                    OutlinedButton(
                        onClick = onKeepServer,
                        enabled = entry.canResolve,
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("采用服务器"), maxLines = 1) }
                }
                TextButton(onClick = onDefer) { Text(tr("稍后处理")) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun conflictFieldLine(
    field: SyncConflictField,
    phone: SyncConflictSide,
    server: SyncConflictSide,
): String {
    val phoneValue = when (field) {
        SyncConflictField.TIME -> formatConflictTime(phone.timestampMs)
        SyncConflictField.MERCHANT -> formatConflictMerchant(phone.merchant)
        SyncConflictField.AMOUNT -> formatConflictAmount(phone.amountCents, phone.currency)
        SyncConflictField.CATEGORY -> formatConflictCategory(phone.category)
    }
    val serverValue = when (field) {
        SyncConflictField.TIME -> formatConflictTime(server.timestampMs)
        SyncConflictField.MERCHANT -> formatConflictMerchant(server.merchant)
        SyncConflictField.AMOUNT -> formatConflictAmount(server.amountCents, server.currency)
        SyncConflictField.CATEGORY -> formatConflictCategory(server.category)
    }
    return tr(field.label) + tr("本机") + " " + phoneValue +
        " / " + tr("服务器") + " " + serverValue
}

/** 状态页需要的一次性快照；prefs 是唯一数据源，不新增数据库表。 */
private data class SyncSnapshot(
    val enabled: Boolean,
    val url: String,
    val token: String,
    val code: SyncResultCode?,
    val message: String,
    val resultAtMs: Long,
    val lastSuccessMs: Long,
    val uploads: Int,
    val conflictIds: Set<String>,
    val missingIds: Set<String>,
) {
    val configured: Boolean get() = SyncState.isConfigured(url, token)
    val kind: SyncStateKind get() = SyncState.of(enabled, configured, code, lastSuccessMs)
}

private fun readSyncSnapshot(prefs: SharedPreferences): SyncSnapshot {
    val counts = readPendingCounts(prefs)
    return SyncSnapshot(
        enabled = prefs.getBoolean("enabled", false),
        url = prefs.getString("url", "").orEmpty(),
        token = prefs.getString("token", "").orEmpty(),
        code = SyncResultCode.from(prefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null)),
        message = prefs.getString("last_result", "").orEmpty(),
        resultAtMs = prefs.getLong(TransactionSyncWorker.LAST_RESULT_MS, 0L),
        lastSuccessMs = prefs.getLong("last_success_ms", 0L),
        uploads = counts.uploads,
        conflictIds = prefs.getStringSet(TransactionSyncWorker.PENDING_CONFLICT_IDS, emptySet())
            .orEmpty().toSet(),
        missingIds = prefs.getStringSet(
            TransactionSyncWorker.PENDING_CONFLICT_MISSING_IDS,
            emptySet(),
        ).orEmpty().toSet(),
    )
}

private data class ConflictEntry(
    val eventId: String,
    val row: TransactionEntity?,
    val phone: SyncConflictSide?,
    val server: SyncConflictSide?,
    val serverDeleted: Boolean,
    val missingOnServer: Boolean,
    val revision: Long,
) {
    val canResolve: Boolean get() = row != null && revision >= 0L
}

private fun buildConflictEntry(
    eventId: String,
    prefs: SharedPreferences,
    pendingRows: List<TransactionEntity>,
    installation: String,
    snapshot: SyncSnapshot,
): ConflictEntry {
    val row = pendingRows.firstOrNull { pending ->
        (pending.remoteEventId ?: "android:$installation:${pending.id}") == eventId
    }
    val preview = runCatching {
        prefs.getString("conflict_server_preview:$eventId", null)?.let { JSONObject(it) }
    }.getOrNull()
    val serverRecord = preview?.optJSONObject("record")
    val revision = preview?.optLong("revision", -1L) ?: -1L
    val recordId = serverRecord?.optString("id", "").orEmpty()
        .ifBlank { preview?.optString("event_id", "").orEmpty() }
        .ifBlank { eventId }
    // 没有预览只有两种来源：服务器上根本没有这条（current == null），
    // 或旧版本没写预览。两种都当「服务器上找不到这条」讲，不能假装有对比。
    val missingOnServer = eventId in snapshot.missingIds || preview == null
    return ConflictEntry(
        eventId = eventId,
        row = row,
        phone = row?.let {
            SyncConflictSide(
                recordId = it.id.takeIf { id -> id > 0L }?.toString().orEmpty(),
                timestampMs = it.timestamp,
                merchant = it.merchant,
                amountCents = it.amountCents,
                currency = it.currency,
                category = it.category,
            )
        },
        server = if (serverRecord == null) null else SyncConflictSide(
            recordId = recordId,
            timestampMs = serverRecord.longOrNull("timestamp_ms"),
            merchant = serverRecord.textOrNull("merchant"),
            amountCents = serverRecord.longOrNull("amount_cents"),
            currency = serverRecord.textOrNull("currency"),
            category = serverRecord.textOrNull("category"),
        ),
        serverDeleted = preview?.optString("action") == "delete",
        missingOnServer = missingOnServer,
        revision = revision,
    )
}

/**
 * 与 SyncConflictApproval 的校验字段一一对应：revision、本机修改时间、mutation id。
 * 服务器内容在用户点按钮前又变过的话，同步端会拒绝这次选择，不会把旧决定套到新内容上。
 */
private fun applyConflictChoice(
    prefs: SharedPreferences,
    entry: ConflictEntry,
    keepPhone: Boolean,
) {
    val row = entry.row ?: return
    prefs.edit()
        .putString("conflict_choice:${entry.eventId}", if (keepPhone) "keep_phone" else "keep_server")
        .putLong("conflict_choice_revision:${entry.eventId}", entry.revision)
        .putLong("conflict_choice_local_modified_ms:${entry.eventId}", row.syncModifiedMs)
        .putString("conflict_choice_mutation_id:${entry.eventId}", row.syncMutationId)
        .apply()
}

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.textOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
