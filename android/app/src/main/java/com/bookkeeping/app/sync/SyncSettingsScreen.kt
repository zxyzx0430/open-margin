package com.bookkeeping.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bookkeeping.app.i18n.tr
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
    }
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val workInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(TransactionSyncWorker.IMMEDIATE_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val periodicWorkInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(TransactionSyncWorker.PERIODIC_WORK_NAME)
    }.collectAsState(initial = emptyList())
    var snapshot by remember(prefs) { mutableStateOf(readSettingsSnapshot(prefs)) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var copyFeedback by remember { mutableStateOf("") }
    var serverScanMessage by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var showConflictDetails by remember { mutableStateOf(false) }
    var wizardOpen by remember { mutableStateOf(false) }
    var wizardPayload by remember { mutableStateOf<PairingPayload?>(null) }
    var manualEntry by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = readSettingsSnapshot(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = readSettingsSnapshot(prefs)
            delay(2_000L)
        }
    }
    LaunchedEffect(feedback) {
        if (feedback.isNotBlank()) {
            delay(2_500L)
            feedback = ""
        }
    }
    LaunchedEffect(copyFeedback) {
        if (copyFeedback.isNotBlank()) {
            delay(2_000L)
            copyFeedback = ""
        }
    }

    fun closeWizard() {
        wizardOpen = false
        wizardPayload = null
        manualEntry = false
    }
    fun openWizard(payload: PairingPayload?, manual: Boolean) {
        serverScanMessage = ""
        wizardPayload = payload
        manualEntry = manual
        wizardOpen = true
    }
    fun invalidQrMessage() = tr("二维码无效，请扫描清衡服务器上的配对二维码。")
    fun cameraPermissionMessage() = tr("未获得相机权限，无法扫码，请在系统设置中允许相机权限。")
    fun setAutoSync(enabled: Boolean) {
        if (enabled && !snapshot.configured) {
            feedback = tr("请先配对服务器再开启自动同步")
            return
        }
        try {
            TransactionSyncWorker.configure(context, snapshot.url, snapshot.token, enabled)
            snapshot = readSettingsSnapshot(prefs)
            feedback = tr(if (enabled) "自动同步已开启" else "自动同步已关闭")
        } catch (_: IllegalArgumentException) {
            feedback = tr("同步配置未通过校验")
        }
    }

    BackHandler(enabled = wizardOpen) { closeWizard() }
    val syncing = workInfos.any {
        it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
    } || periodicWorkInfos.any { it.state == WorkInfo.State.RUNNING }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        tr(if (wizardOpen) "配对服务器" else "同步"),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (wizardOpen) closeWizard() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        if (wizardOpen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WindowsFirstConnectionWizard(
                    initialPayload = wizardPayload,
                    openManualEntry = manualEntry,
                    onConnected = {
                        snapshot = readSettingsSnapshot(prefs)
                        closeWizard()
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SyncStatusCard(
                        snapshot = snapshot,
                        syncing = syncing,
                        onSync = { TransactionSyncWorker.requestImmediate(context) },
                        onOpenConflictDetails = { showConflictDetails = true },
                    )
                }
                item { SyncSectionTitle(tr("服务器")) }
                item {
                    SyncServerCard(
                        snapshot = snapshot,
                        tokenVisible = tokenVisible,
                        onToggleTokenVisibility = { tokenVisible = !tokenVisible },
                        onCopyAddress = {
                            if (snapshot.url.isNotBlank()) {
                                clipboard.setText(AnnotatedString(snapshot.url))
                                copyFeedback = tr("已复制")
                            }
                        },
                        onCopyToken = {
                            if (snapshot.token.isNotBlank()) {
                                clipboard.setText(AnnotatedString(snapshot.token))
                                copyFeedback = tr("已复制")
                            }
                        },
                        feedback = copyFeedback,
                        onManualEntry = { openWizard(payload = null, manual = true) },
                        onScan = { payload -> openWizard(payload, manual = false) },
                        onRejected = { serverScanMessage = invalidQrMessage() },
                        onFailure = { serverScanMessage = cameraPermissionMessage() },
                        scanMessage = serverScanMessage,
                    )
                }
                item {
                    SyncAdvancedCard(
                        expanded = advancedExpanded,
                        enabled = snapshot.enabled,
                        canReset = snapshot.url.isNotBlank() || snapshot.token.isNotBlank(),
                        feedback = feedback,
                        onToggleExpanded = { advancedExpanded = !advancedExpanded },
                        onAutoSyncChange = ::setAutoSync,
                        onReset = { confirmReset = true },
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("重置连接")) },
            text = { Text(tr("重置连接说明")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    TransactionSyncWorker.resetConnection(context)
                    snapshot = readSettingsSnapshot(prefs)
                    feedback = tr("连接已重置")
                }) { Text(tr("重置连接")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(tr("取消")) }
            },
        )
    }
    if (showConflictDetails) {
        SyncStatusSheet(
            onDismiss = { showConflictDetails = false },
            onOpenSyncSettings = { showConflictDetails = false },
        )
    }
}

@Composable
private fun SyncStatusCard(
    snapshot: SyncSettingsSnapshot,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenConflictDetails: () -> Unit,
) {
    val headline = syncSettingsHeadline(snapshot.kind, syncing, snapshot.configured)
    val statusColor = when {
        syncing || snapshot.kind == SyncStateKind.OK -> MaterialTheme.colorScheme.primary
        snapshot.kind == SyncStateKind.RETRY ||
            snapshot.kind == SyncStateKind.BLOCKED ||
            snapshot.kind == SyncStateKind.AUTH -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = tr(headline),
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = snapshot.url.takeIf { it.isNotBlank() }
                    ?.let { middleEllipsize(it) } ?: tr("尚未配置服务器"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${tr("上次成功")} · ${relativeSyncTime(snapshot.lastSuccessMs)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SyncCountCell(
                    count = snapshot.uploads,
                    label = tr("待上传"),
                    modifier = Modifier.weight(1f),
                )
                SyncCountCell(
                    count = snapshot.conflicts,
                    label = tr("冲突"),
                    modifier = Modifier.weight(1f),
                    countColor = if (snapshot.conflicts > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (snapshot.conflicts > 0) {
                TextButton(
                    onClick = onOpenConflictDetails,
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) { Text(tr("查看冲突")) }
            }
            if (syncing) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Button(
                onClick = onSync,
                enabled = snapshot.enabled && snapshot.configured && !syncing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(tr("同步中"), modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text(tr("立即同步"))
                }
            }
        }
    }
}

@Composable
private fun SyncCountCell(
    count: Int,
    label: String,
    modifier: Modifier = Modifier,
    countColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier) {
        Text(
            text = count.toString(),
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = countColor,
            maxLines = 1,
        )
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SyncSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 2.dp, start = 2.dp),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SyncSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SyncServerCard(
    snapshot: SyncSettingsSnapshot,
    tokenVisible: Boolean,
    onToggleTokenVisibility: () -> Unit,
    onCopyAddress: () -> Unit,
    onCopyToken: () -> Unit,
    onManualEntry: () -> Unit,
    feedback: String,
    onScan: (PairingPayload) -> Unit,
    onRejected: () -> Unit,
    onFailure: () -> Unit,
    scanMessage: String,
) {
    SyncSettingsCard {
        if (snapshot.configured) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("服务器地址"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = snapshot.url.takeIf { it.isNotBlank() }?.let(::middleEllipsize)
                            ?: tr("尚未配置服务器"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onCopyAddress, enabled = snapshot.url.isNotBlank()) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = tr("复制地址"))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("密钥"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = when {
                            snapshot.token.isBlank() -> tr("未配置")
                            tokenVisible -> snapshot.token
                            else -> "••••••"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggleTokenVisibility, enabled = snapshot.token.isNotBlank()) {
                    Icon(
                        imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = tr(if (tokenVisible) "隐藏密钥" else "显示密钥"),
                    )
                }
                IconButton(onClick = onCopyToken, enabled = snapshot.token.isNotBlank()) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = tr("复制密钥"))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (snapshot.configured) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("重新配对"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        tr("扫描服务器上的配对二维码"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PairingQrScanAction(
                    buttonLabel = "重新配对",
                    onPayload = onScan,
                    onRejected = { onRejected() },
                    onFailure = { onFailure() },
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    tr("扫码或手动填写服务器地址与设备密钥。"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("配对服务器"),
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    PairingQrScanAction(
                        buttonLabel = "扫一扫",
                        onPayload = onScan,
                        onRejected = { onRejected() },
                        onFailure = { onFailure() },
                    )
                }
            }
        }
        TextButton(
            onClick = onManualEntry,
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text(tr("手动填写地址与密钥"))
        }
        if (feedback.isNotBlank()) {
            Text(
                text = feedback,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (scanMessage.isNotBlank()) {
            Text(
                text = scanMessage,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SyncAdvancedCard(
    expanded: Boolean,
    enabled: Boolean,
    canReset: Boolean,
    feedback: String,
    onToggleExpanded: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    SyncSettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("高级"),
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = tr(if (expanded) "收起" else "展开"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("自动同步"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        tr("新增或修改交易后立即尝试同步"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onAutoSyncChange)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    tr("重置连接只清除地址和密钥；本机交易与同步账本身份会保留。"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(
                    onClick = onReset,
                    enabled = canReset,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("重置连接")) }
                if (feedback.isNotBlank()) {
                    Text(
                        feedback,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

private data class SyncSettingsSnapshot(
    val enabled: Boolean,
    val url: String,
    val token: String,
    val configured: Boolean,
    val kind: SyncStateKind,
    val lastSuccessMs: Long,
    val uploads: Int,
    val conflicts: Int,
)

private fun readSettingsSnapshot(prefs: SharedPreferences): SyncSettingsSnapshot {
    val enabled = prefs.getBoolean("enabled", false)
    val url = prefs.getString("url", "").orEmpty()
    val token = prefs.getString("token", "").orEmpty()
    val configured = SyncState.isConfigured(url, token)
    val lastSuccessMs = prefs.getLong("last_success_ms", 0L)
    val kind = SyncState.of(
        enabled = enabled,
        configured = configured,
        code = SyncResultCode.from(prefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null)),
        lastSuccessMs = lastSuccessMs,
    )
    val counts = readPendingCounts(prefs)
    return SyncSettingsSnapshot(
        enabled = enabled,
        url = url,
        token = token,
        configured = configured,
        kind = kind,
        lastSuccessMs = lastSuccessMs,
        uploads = counts.uploads,
        conflicts = counts.conflicts,
    )
}

internal fun syncSettingsHeadline(
    kind: SyncStateKind,
    syncing: Boolean,
    configured: Boolean,
): String = when {
    syncing -> "同步中"
    !configured -> "未配置"
    kind == SyncStateKind.OK -> "已连接"
    kind == SyncStateKind.NOT_ENABLED -> "同步已关闭"
    kind == SyncStateKind.NEVER_RUN -> "尚未同步"
    else -> "同步异常"
}

internal fun middleEllipsize(value: String, maxChars: Int = 44): String {
    if (value.length <= maxChars || maxChars < 3) return value
    val visibleChars = maxChars - 1
    val startChars = (visibleChars + 1) / 2
    val endChars = visibleChars / 2
    return value.take(startChars) + "…" + value.takeLast(endChars)
}
