package com.bookkeeping.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusDot
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/**
 * 同步状态面板：底部弹出，一屏说清状态，动作固定在底部。
 *
 * 旧版把「同步正常」写成超大标题、下面再写一遍「最近成功：刚刚」，同一个事实占三层；
 * 现在状态只出现一次，其余每行都是「标签 + 值」，待上传和冲突合成一行计数。
 * 只读状态，改地址/密钥仍然走显式的「重新连接」→ 同步设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusSheet(onDismiss: () -> Unit, onOpenSyncSettings: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 36.dp, height = 4.dp) },
    ) {
        SyncStatusPanel(onOpenSyncSettings = onOpenSyncSettings)
    }
}

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
    // 请求提示只留几秒，避免一行灰字长期挂在按钮上方。
    LaunchedEffect(syncRequested) {
        if (syncRequested) {
            delay(8_000L)
            syncRequested = false
        }
    }

    val kind = snapshot.kind
    val installation = prefs.getString("installation_id", "").orEmpty()
    val conflicts = remember(snapshot.conflictIds, snapshot.missingIds, pendingRows, installation) {
        snapshot.conflictIds
            .map { eventId -> buildConflictEntry(eventId, prefs, pendingRows, installation, snapshot) }
            .sortedByDescending { it.phone?.timestampMs ?: it.server?.timestampMs ?: 0L }
    }
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxPanelHeight),
    ) {
        SyncStatusHeadline(kind = kind, lastSuccessMs = snapshot.lastSuccessMs)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SyncPendingRow(
                uploads = snapshot.uploads,
                conflicts = conflicts,
                deferred = deferred,
                onDefer = { entry -> deferred = deferred + entry.eventId },
                onRestore = { entry -> deferred = deferred - entry.eventId },
                onKeepPhone = { entry ->
                    applyConflictChoice(prefs, entry, keepPhone = true)
                    TransactionSyncWorker.requestImmediate(context)
                    snapshot = readSyncSnapshot(prefs)
                },
                onKeepServer = { entry ->
                    applyConflictChoice(prefs, entry, keepPhone = false)
                    TransactionSyncWorker.requestImmediate(context)
                    snapshot = readSyncSnapshot(prefs)
                },
            )
            SyncLastResultRow(snapshot = snapshot)
        }
        SyncSheetActions(
            showReconnect = kind.needsReconnect,
            syncAvailable = kind.syncAvailable,
            syncRequested = syncRequested,
            onSync = {
                TransactionSyncWorker.requestImmediate(context)
                syncRequested = true
            },
            onReconnect = onOpenSyncSettings,
        )
    }
}

private const val MAX_VISIBLE_CONFLICTS = 20

/** 顶部状态区：圆点 + 状态词 + 最近成功时间，一行说完，没有解释句。 */
@Composable
private fun SyncStatusHeadline(kind: SyncStateKind, lastSuccessMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(tone = kind.tone)
        Text(
            text = tr(kind.headline),
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (lastSuccessMs > 0L) {
            Text(
                text = relativeSyncTime(lastSuccessMs),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 待上传 + 冲突合成一行计数：两个数字分开数得清，也不用读两段说明。
 * 有冲突时下面直接铺开逐笔对比，处理能力不缩水。
 */
@Composable
private fun SyncPendingRow(
    uploads: Int,
    conflicts: List<ConflictEntry>,
    deferred: Set<String>,
    onDefer: (ConflictEntry) -> Unit,
    onRestore: (ConflictEntry) -> Unit,
    onKeepPhone: (ConflictEntry) -> Unit,
    onKeepServer: (ConflictEntry) -> Unit,
) {
    val summaryColor = when {
        conflicts.isNotEmpty() -> MaterialTheme.colorScheme.error
        uploads > 0 -> LocalQingSemanticColors.current.warningText
        else -> MaterialTheme.colorScheme.onSurface
    }
    StatusLabelRow(label = tr("待处理")) {
        Text(
            text = String.format(Locale.getDefault(), tr("%d 笔待上传"), uploads) +
                " · " +
                String.format(Locale.getDefault(), tr("%d 笔冲突"), conflicts.size),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = summaryColor,
        )
        conflicts.take(MAX_VISIBLE_CONFLICTS).forEachIndexed { index, entry ->
            ConflictCard(
                entry = entry,
                index = index + 1,
                total = conflicts.size,
                deferred = entry.eventId in deferred,
                onDefer = { onDefer(entry) },
                onRestore = { onRestore(entry) },
                onKeepPhone = { onKeepPhone(entry) },
                onKeepServer = { onKeepServer(entry) },
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

/** 最近一次结果：结果词 · 时间，右侧两个小 tonal 按钮，展开后原文只占六行。 */
@Composable
private fun SyncLastResultRow(snapshot: SyncSnapshot) {
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
    val timeLabel = if (snapshot.resultAtMs > 0L) relativeSyncTime(snapshot.resultAtMs) else ""
    StatusLabelRow(label = tr("最近一次结果")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (timeLabel.isBlank()) tr(resultLabel) else tr(resultLabel) + " · " + timeLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (raw.isNotBlank()) {
                MiniTonalButton(text = tr(if (expanded) "收起" else "展开")) {
                    expanded = !expanded
                }
                MiniTonalButton(text = tr(if (copied) "已复制" else "复制")) {
                    clipboard.setText(AnnotatedString(raw))
                    copied = true
                }
            }
        }
        if (expanded && raw.isNotBlank()) {
            RawServerText(raw = raw)
        }
    }
}

/** 服务器原文：等宽小字，最多六行，超出在框内滚动。 */
@Composable
private fun RawServerText(raw: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = raw,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .heightIn(max = 102.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

/** 标签行：左列是标签，右列是值；行与行之间留 20dp，靠标签建立层次。 */
@Composable
private fun StatusLabelRow(
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(84.dp)
                .padding(top = 3.dp),
            maxLines = 2,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 32dp 的 tonal 小按钮：看得出是按钮，又不会跟主按钮抢注意力。 */
@Composable
private fun MiniTonalButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** 底部固定动作区：不随内容滚动，主按钮 48dp。 */
@Composable
private fun SyncSheetActions(
    showReconnect: Boolean,
    syncAvailable: Boolean,
    syncRequested: Boolean,
    onSync: () -> Unit,
    onReconnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (syncRequested) {
                Text(
                    text = tr("已请求立即同步，完成后状态自动更新"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showReconnect) {
                OutlinedButton(
                    onClick = onReconnect,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) { Text(tr("重新连接")) }
            }
            Button(
                onClick = onSync,
                enabled = syncAvailable,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    text = tr("立即同步"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 六个状态词之外不再造词；从没同步过也按「同步正常」说，时间列留空，详情在下一行。 */
private val SyncStateKind.headline: String
    get() = when (this) {
        SyncStateKind.NEVER_RUN -> "同步正常"
        else -> label
    }

private val SyncStateKind.tone: StatusTone
    get() = when (this) {
        SyncStateKind.OK, SyncStateKind.NEVER_RUN -> StatusTone.POSITIVE
        SyncStateKind.RETRY -> StatusTone.WARNING
        SyncStateKind.BLOCKED, SyncStateKind.AUTH -> StatusTone.ERROR
        SyncStateKind.NOT_ENABLED, SyncStateKind.NOT_CONFIGURED -> StatusTone.NEUTRAL
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
