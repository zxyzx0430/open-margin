package com.bookkeeping.app.ui.screen.settings

import com.bookkeeping.app.i18n.tr
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bookkeeping.app.billexport.BillExport
import com.bookkeeping.app.ui.screen.billimport.BillImportExportSheet
import com.bookkeeping.app.ui.screen.billimport.BillImportViewModel
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureService
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureHealth
import com.bookkeeping.app.notification.accessibility.AccessibilityConnectionState
import java.text.DateFormat
import java.util.Date
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.update.GitHubReleaseUpdateChecker
import com.bookkeeping.app.update.UpdateComponentScope
import com.bookkeeping.app.update.UpdateInstallFlow
import com.bookkeeping.app.update.UpdatePresentation
import com.bookkeeping.app.update.UpdateVersionPolicy
import com.bookkeeping.app.sync.SyncState
import com.bookkeeping.app.sync.SyncStateKind
import com.bookkeeping.app.sync.endpointHost
import com.bookkeeping.app.sync.readPendingCounts
import com.bookkeeping.app.sync.relativeSyncTime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.notification.SmsPermissionHelper
import com.bookkeeping.app.sync.SyncResultCode
import com.bookkeeping.app.sync.TransactionSyncWorker
import com.bookkeeping.app.ui.components.AutomationStatus
import com.bookkeeping.app.ui.components.SectionHeader
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.ui.theme.QingWarning
import com.bookkeeping.app.ui.theme.QingWarningContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onOpenMerchantRules: () -> Unit = {},
    onOpenSyncSettings: () -> Unit = {},
    openAutomationSetup: Boolean = false,
    onAutomationOpened: () -> Unit = {},
) {
    val keepAlive by viewModel.keepAliveEnabled.collectAsState()
    val successNotifications by viewModel.successNotificationsEnabled.collectAsState()
    val merchantRules by viewModel.merchantRules.collectAsState()
    val context = LocalContext.current
    val importViewModel: BillImportViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val syncPrefs = remember {
        context.getSharedPreferences(TransactionSyncWorker.PREFS, android.content.Context.MODE_PRIVATE)
    }

    var accessibilityHealth by remember {
        mutableStateOf(AccessibilityCaptureHealth.snapshot(context))
    }
    var notificationEnabled by remember {
        mutableStateOf(NotificationListenerHelper.isEnabled(context))
    }
    var smsGranted by remember { mutableStateOf(SmsPermissionHelper.isGranted(context)) }
    var postNotificationsGranted by remember {
        mutableStateOf(areNotificationsGranted(context))
    }
    var pageRecognitionEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, PAGE_RECOGNITION_COMPONENT))
    }
    var lastSyncMs by remember { mutableStateOf(syncPrefs.getLong("last_success_ms", 0L)) }
    var lastSyncCode by remember {
        mutableStateOf(syncPrefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null))
    }
    // 两个数字分开读：待上传是正常现象，冲突才需要警告色。
    var pendingUploads by remember {
        mutableStateOf(readPendingCounts(syncPrefs).uploads)
    }
    var pendingConflicts by remember {
        mutableStateOf(readPendingCounts(syncPrefs).conflicts)
    }
    var syncEnabled by remember { mutableStateOf(syncPrefs.getBoolean("enabled", false)) }
    var syncConfigured by remember {
        mutableStateOf(
            SyncState.isConfigured(
                syncPrefs.getString("url", ""),
                syncPrefs.getString("token", ""),
            ),
        )
    }
    // 列表里只放主机名；完整地址留在同步设置页，避免 URL 从中间断行。
    var syncHost by remember {
        mutableStateOf(endpointHost(syncPrefs.getString("url", "")))
    }
    var detailDialog by remember { mutableStateOf<String?>(null) }
    var showExportGuide by remember { mutableStateOf(false) }
    var showImportExportSheet by remember { mutableStateOf(false) }
    var exportFeedback by remember { mutableStateOf<String?>(null) }
    var exportInProgress by remember { mutableStateOf(false) }
    var permissionRefreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(openAutomationSetup) {
        if (openAutomationSetup) {
            detailDialog = "automation"
            onAutomationOpened()
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        postNotificationsGranted = areNotificationsGranted(context)
        smsGranted = SmsPermissionHelper.isGranted(context)
        permissionRefreshKey++
    }

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            exportInProgress = true
            exportFeedback = null
            viewModel.exportBills(uri) { message ->
                exportFeedback = message
                exportInProgress = false
            }
        }
    }

    fun dismissImportExportSheet() {
        if (!importViewModel.state.value.importing) {
            showImportExportSheet = false
            importViewModel.reset()
            exportFeedback = null
        }
    }

    LaunchedEffect(lifecycleOwner, permissionRefreshKey) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                withContext(Dispatchers.IO) {
                    accessibilityHealth = AccessibilityCaptureHealth.snapshot(context)
                    notificationEnabled = NotificationListenerHelper.isEnabled(context)
                    smsGranted = SmsPermissionHelper.isGranted(context)
                    postNotificationsGranted = areNotificationsGranted(context)
                    pageRecognitionEnabled =
                        isAccessibilityServiceEnabled(context, PAGE_RECOGNITION_COMPONENT)
                    lastSyncMs = syncPrefs.getLong("last_success_ms", 0L)
                    lastSyncCode = syncPrefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null)
                    pendingUploads = readPendingCounts(syncPrefs).uploads
                    pendingConflicts = readPendingCounts(syncPrefs).conflicts
                    syncEnabled = syncPrefs.getBoolean("enabled", false)
                    syncConfigured = SyncState.isConfigured(
                        syncPrefs.getString("url", ""),
                        syncPrefs.getString("token", ""),
                    )
                    syncHost = endpointHost(syncPrefs.getString("url", ""))
                }
                delay(5_000L)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missingCapabilityNames = AutomationStatus.missingNames(context)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        tr("设置"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AutomationSetupCard(
                    missingNames = missingCapabilityNames,
                    onStart = { detailDialog = "automation" },
                )
            }

            item { SectionHeader(tr("权限与运行状态")) }
            item {
                PermissionStatusGroup(
                    postNotificationsGranted = postNotificationsGranted,
                    notificationEnabled = notificationEnabled,
                    pageRecognitionEnabled = pageRecognitionEnabled,
                    smsGranted = smsGranted,
                    accessibilityHealth = accessibilityHealth,
                    onOpenSystemNotifications = { openAppNotificationSettings(context) },
                    onOpenNotificationSettings = {
                        NotificationListenerHelper.openSettings(context)
                    },
                    onOpenPageRecognitionSettings = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    onOpenAccessibilityDetails = { detailDialog = "accessibility" },
                    onOpenSmsSettings = { openAppDetailsSettings(context) },
                )
            }

            item { SectionHeader(tr("同步")) }
            item {
                SyncOverviewGroup(
                    enabled = syncEnabled,
                    configured = syncConfigured,
                    uploads = pendingUploads,
                    conflicts = pendingConflicts,
                    lastSuccessMs = lastSyncMs,
                    lastResultCode = lastSyncCode,
                    onOpenSyncStatus = onOpenSyncSettings,
                )
            }
            item {
                SettingsGroupCard {
                    SettingsActionRow(
                        icon = Icons.Filled.Cloud,
                        title = tr("同步设置"),
                        subtitle = syncHost.ifBlank { tr("未配置服务器") },
                        onClick = onOpenSyncSettings,
                    )
                }
            }

            item { SectionHeader(tr("账单导入")) }
            item {
                SettingsGroupCard {
                    SettingsActionRow(
                        icon = Icons.Filled.FileUpload,
                        title = tr("导入 / 导出账单"),
                        subtitle = tr("导入 CSV、导出全部账单"),
                        onClick = {
                            exportFeedback = null
                            showImportExportSheet = true
                        },
                    )
                    SettingsRowDivider()
                    SettingsActionRow(
                        icon = Icons.Filled.Info,
                        title = tr("微信 / 支付宝导出账单教程"),
                        subtitle = tr("两分钟导出 CSV"),
                        onClick = { showExportGuide = true },
                    )
                }
            }

            item { SectionHeader(tr("自动分类")) }
            item {
                SettingsGroupCard {
                    SettingsActionRow(
                        icon = Icons.Filled.Sync,
                        title = tr("商户分类记忆"),
                        subtitle = if (merchantRules.isEmpty()) {
                            tr("修改分类即记住")
                        } else {
                            merchantRules.size.toString() + " " + tr("个商户")
                        },
                        onClick = onOpenMerchantRules,
                    )
                }
            }

            item { SectionHeader(tr("提醒与语言")) }
            item {
                SettingsGroupCard {
                    SettingsSwitchRow(
                        icon = Icons.Filled.Notifications,
                        title = tr("常驻运行状态"),
                        subtitle = tr("通知栏显示采集状态"),
                        checked = keepAlive,
                        onCheckedChange = viewModel::setKeepAlive,
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Filled.Notifications,
                        title = tr("自动记账成功提醒"),
                        subtitle = tr("入账后弹出提醒"),
                        checked = successNotifications,
                        onCheckedChange = viewModel::setSuccessNotifications,
                    )
                    SettingsRowDivider()
                    SettingsActionRow(
                        icon = Icons.Filled.Sync,
                        title = tr("常驻状态详情"),
                        subtitle = tr("连接状态与通知排查"),
                        onClick = { detailDialog = "keepAlive" },
                    )
                }
            }
            item { LanguageCard() }

            item { SectionHeader(tr("关于与更新")) }
            item { AboutUpdateCard(
                onOpenAbout = { detailDialog = "about" },
                onOpenFeedback = { detailDialog = "feedback" },
            ) }

            item { SectionHeader(tr("国产 ROM 后台白名单")) }
            item {
                SettingsGroupCard {
                    SettingsActionRow(
                        icon = Icons.Filled.Warning,
                        title = tr("后台运行设置"),
                        subtitle = tr("自启动与省电策略"),
                        onClick = { detailDialog = "rom" },
                    )
                }
            }

        }

        if (showImportExportSheet) {
            val importSheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { !importViewModel.state.value.importing },
            )
            ModalBottomSheet(
                onDismissRequest = { dismissImportExportSheet() },
                sheetState = importSheetState,
            ) {
                BillImportExportSheet(
                    onClose = { dismissImportExportSheet() },
                    onExportBills = {
                        exportDocumentLauncher.launch(BillExport.defaultFileName())
                    },
                    exportFeedback = exportFeedback,
                    exportInProgress = exportInProgress,
                    viewModel = importViewModel,
                )
            }
        }

        detailDialog?.let { key ->
            when (key) {
                "automation" -> SettingsDetailDialog(
                    tr("自动记账设置"),
                    { detailDialog = null },
                ) {
                    AutomationSetupPanel(
                        notificationEnabled = notificationEnabled,
                        pageRecognitionEnabled = pageRecognitionEnabled,
                        fallbackAccessibilityEnabled =
                            accessibilityHealth.state == AccessibilityConnectionState.CONNECTED,
                        fallbackAccessibilityAuthorized = accessibilityHealth.authorized,
                        postNotificationsGranted = postNotificationsGranted,
                        smsGranted = smsGranted,
                        missingNames = missingCapabilityNames,
                        onRequestRuntimePermissions = {
                            runtimePermissionLauncher.launch(runtimePermissionsToRequest())
                        },
                        onOpenNotificationSettings = {
                            NotificationListenerHelper.openSettings(context)
                        },
                        onOpenAccessibilitySettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                        onOpenAppSettings = { openAppDetailsSettings(context) },
                        onRefresh = { permissionRefreshKey++ },
                        onOpenRomGuide = { detailDialog = "rom" },
                        onFinish = { detailDialog = null },
                    )
                }
                "rom" -> SettingsDetailDialog(tr("国产 ROM 后台白名单"), { detailDialog = null }) {
                    RomGuideCard()
                }
                "accessibility" -> SettingsDetailDialog(tr("无障碍诊断"), { detailDialog = null }) {
                    AccessibilityCard(
                        health = accessibilityHealth,
                        onRefresh = {
                            accessibilityHealth = AccessibilityCaptureHealth.snapshot(context)
                        },
                        onOpen = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                    )
                }
                "keepAlive" -> SettingsDetailDialog(tr("常驻运行状态"), { detailDialog = null }) {
                    KeepAlivePanel(
                        enabled = keepAlive,
                        onToggle = viewModel::setKeepAlive,
                        onOpenNotificationSettings = { openAppNotificationSettings(context) },
                    )
                }
                "feedback" -> FeedbackDialog(onDismiss = { detailDialog = null })
                "about" -> AboutDialog(onDismiss = { detailDialog = null })
            }
        }
    }

    if (showExportGuide) {
        AlertDialog(
            onDismissRequest = { showExportGuide = false },
            title = { Text(tr("导出微信 / 支付宝账单")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("微信"), style = MaterialTheme.typography.titleSmall)
                    Text(tr("我 → 服务 → 钱包 → 账单 → 右上角「…」→「下载账单」→「用于个人对账」→ 选时间范围 → 接收方式选「微信」，文件直接发到微信支付服务号，无需邮箱、无需解压；也可以选邮箱。"))
                    Text(tr("支付宝"), style = MaterialTheme.typography.titleSmall)
                    Text(tr("我的 → 账单 → 右上角「…」→「开具交易流水证明」→「用于个人对账」→ 选时间范围与交易类型 → 接收方式选「支付宝」，文件直接发到支付宝消息；也可以选邮箱。"))
                    Text(
                        tr("导入时选择下载到的账单文件（CSV / XLSX）即可。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportGuide = false }) { Text(tr("知道了")) }
            },
        )
    }
}

private const val PAGE_RECOGNITION_COMPONENT =
    "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

private fun isAccessibilityServiceEnabled(context: Context, className: String): Boolean {
    val expected = "${context.packageName}/$className"
    val list = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return list.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun openAppDetailsSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
}

@Composable
private fun SyncOverviewGroup(
    enabled: Boolean,
    configured: Boolean,
    uploads: Int,
    conflicts: Int,
    lastSuccessMs: Long,
    lastResultCode: String?,
    onOpenSyncStatus: () -> Unit,
) {
    val kind = SyncState.of(
        enabled = enabled,
        configured = configured,
        code = SyncResultCode.from(lastResultCode),
        lastSuccessMs = lastSuccessMs,
    )
    val syncTone = when (kind) {
        SyncStateKind.OK -> StatusTone.POSITIVE
        SyncStateKind.RETRY, SyncStateKind.BLOCKED, SyncStateKind.AUTH -> StatusTone.ERROR
        SyncStateKind.NOT_ENABLED, SyncStateKind.NOT_CONFIGURED, SyncStateKind.NEVER_RUN ->
            StatusTone.NEUTRAL
    }
    // 标题就是「最近同步」，副标题只给相对时间，不再复述状态词。
    val lastSyncSubtitle = if (lastSuccessMs > 0L) {
        relativeSyncTime(lastSuccessMs)
    } else {
        tr("尚未同步")
    }
    // 待上传是正常现象（中性），只有真冲突才是警告。
    val pendingBadge = when {
        conflicts > 0 -> String.format(Locale.getDefault(), tr("%d 笔冲突"), conflicts)
        uploads > 0 -> String.format(Locale.getDefault(), tr("%d 笔待上传"), uploads)
        else -> tr("无待上传")
    }
    val pendingSubtitle = when {
        conflicts > 0 && uploads > 0 ->
            String.format(Locale.getDefault(), tr("%d 笔修改待上传"), uploads)
        conflicts > 0 -> tr("逐笔核对后才能继续同步")
        uploads > 0 -> String.format(Locale.getDefault(), tr("%d 笔修改待上传"), uploads)
        else -> tr("没有待上传的修改")
    }
    SettingsGroupCard {
        SettingsStatusRow(
            icon = Icons.Filled.Cloud,
            title = tr("最近同步"),
            subtitle = lastSyncSubtitle,
            statusText = tr(kind.label),
            tone = syncTone,
            onClick = onOpenSyncStatus,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Warning,
            title = tr("待处理"),
            subtitle = pendingSubtitle,
            statusText = pendingBadge,
            tone = if (conflicts > 0) StatusTone.WARNING else StatusTone.NEUTRAL,
            onClick = onOpenSyncStatus,
        )
    }
}

@Composable
private fun PermissionStatusGroup(
    postNotificationsGranted: Boolean,
    notificationEnabled: Boolean,
    pageRecognitionEnabled: Boolean,
    smsGranted: Boolean,
    accessibilityHealth: AccessibilityCaptureHealth.Snapshot,
    onOpenSystemNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenPageRecognitionSettings: () -> Unit,
    onOpenAccessibilityDetails: () -> Unit,
    onOpenSmsSettings: () -> Unit,
) {
    val accessibilityStatus = when (accessibilityHealth.state) {
        AccessibilityConnectionState.CONNECTED -> tr("已连接")
        AccessibilityConnectionState.PERMISSION_OFF -> tr("未开启")
        AccessibilityConnectionState.AUTHORIZED_NOT_CONNECTED -> tr("已授权 · 未连接")
    }
    val accessibilityTone = when (accessibilityHealth.state) {
        AccessibilityConnectionState.CONNECTED -> StatusTone.POSITIVE
        AccessibilityConnectionState.PERMISSION_OFF -> StatusTone.ERROR
        AccessibilityConnectionState.AUTHORIZED_NOT_CONNECTED -> StatusTone.WARNING
    }
    // 副标题只写功能，不再追加「上次断开（原因）」：那是诊断信息，详情在无障碍诊断页。
    val accessibilitySubtitle = tr("转账与红包入账")
    SettingsGroupCard {
        SettingsStatusRow(
            icon = Icons.Filled.Notifications,
            title = tr("系统通知"),
            subtitle = tr("常驻状态与提醒"),
            statusText = if (postNotificationsGranted) tr("已允许") else tr("未允许"),
            tone = if (postNotificationsGranted) StatusTone.POSITIVE else StatusTone.ERROR,
            onClick = onOpenSystemNotifications,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Notifications,
            title = tr("通知使用权"),
            subtitle = tr("抓取支付通知"),
            statusText = if (notificationEnabled) tr("已授权") else tr("未授权"),
            tone = if (notificationEnabled) StatusTone.POSITIVE else StatusTone.ERROR,
            onClick = onOpenNotificationSettings,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Visibility,
            title = tr("页面识别"),
            subtitle = tr("识别支付页面"),
            statusText = if (pageRecognitionEnabled) tr("已开启") else tr("未开启"),
            tone = if (pageRecognitionEnabled) StatusTone.POSITIVE else StatusTone.ERROR,
            onClick = onOpenPageRecognitionSettings,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.AccessibilityNew,
            title = tr("无障碍兜底"),
            subtitle = accessibilitySubtitle,
            statusText = accessibilityStatus,
            tone = accessibilityTone,
            onClick = onOpenAccessibilityDetails,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Sms,
            title = tr("短信权限"),
            subtitle = tr("可选，银行短信"),
            statusText = if (smsGranted) tr("已授权") else tr("未开启"),
            tone = if (smsGranted) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            onClick = onOpenSmsSettings,
        )
    }
}

private fun areNotificationsGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private const val AUTOMATION_WIZARD_STEPS = 5

private fun runtimePermissionsToRequest(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    addAll(SmsPermissionHelper.REQUIRED_PERMISSIONS.toList())
}.toTypedArray()

private enum class AutomationWizardStep {
    RUNTIME_PERMISSIONS,
    NOTIFICATION_ACCESS,
    PAGE_RECOGNITION,
    ACCESSIBILITY_FALLBACK,
    ROM_GUIDANCE,
}

private fun initialWizardStep(
    notificationListenerEnabled: Boolean,
    pageRecognitionEnabled: Boolean,
    fallbackAccessibilityEnabled: Boolean,
    postNotificationsGranted: Boolean,
): AutomationWizardStep = when {
    !postNotificationsGranted -> AutomationWizardStep.RUNTIME_PERMISSIONS
    !notificationListenerEnabled -> AutomationWizardStep.NOTIFICATION_ACCESS
    !pageRecognitionEnabled -> AutomationWizardStep.PAGE_RECOGNITION
    !fallbackAccessibilityEnabled -> AutomationWizardStep.ACCESSIBILITY_FALLBACK
    else -> AutomationWizardStep.ROM_GUIDANCE
}

private fun requiredAutomationReady(
    notificationListenerEnabled: Boolean,
    pageRecognitionEnabled: Boolean,
    fallbackAccessibilityEnabled: Boolean,
    postNotificationsGranted: Boolean,
): Boolean = notificationListenerEnabled &&
    pageRecognitionEnabled &&
    fallbackAccessibilityEnabled &&
    postNotificationsGranted

private fun wizardProgress(step: AutomationWizardStep): String =
    String.format(Locale.getDefault(), "%d/%d", step.ordinal + 1, AUTOMATION_WIZARD_STEPS)

@Composable
private fun AutomationSetupCard(
    missingNames: List<String>,
    onStart: () -> Unit,
) {
    val ready = missingNames.isEmpty()
    val container = if (ready) QingPrimaryContainer else QingWarningContainer
    val content = if (ready) QingPrimaryDeep else QingWarning
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("自动记账设置"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                ) {
                    Text(
                        text = if (ready) tr("已开启") else String.format(
                            Locale.getDefault(),
                            tr("还缺 %d 项"),
                            missingNames.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (ready) QingPrimary else QingWarning,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                text = if (ready) {
                    tr("短信与 ROM 设置可选")
                } else {
                    tr("按提示逐项开启，系统开关只能由你手动确认")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!ready) {
                Text(
                    text = tr("缺少：") + missingNames.joinToString(" / "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(onClick = onStart) {
                    Text(tr("开始设置"))
                }
            }
        }
    }
}

@Composable
private fun AutomationSetupPanel(
    notificationEnabled: Boolean,
    pageRecognitionEnabled: Boolean,
    fallbackAccessibilityEnabled: Boolean,
    fallbackAccessibilityAuthorized: Boolean,
    postNotificationsGranted: Boolean,
    smsGranted: Boolean,
    missingNames: List<String>,
    onRequestRuntimePermissions: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRomGuide: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember {
        mutableStateOf(
            initialWizardStep(
                notificationListenerEnabled = notificationEnabled,
                pageRecognitionEnabled = pageRecognitionEnabled,
                fallbackAccessibilityEnabled = fallbackAccessibilityEnabled,
                postNotificationsGranted = postNotificationsGranted,
            ),
        )
    }
    var attempted by remember { mutableStateOf(false) }
    val requiredReady = requiredAutomationReady(
        notificationListenerEnabled = notificationEnabled,
        pageRecognitionEnabled = pageRecognitionEnabled,
        fallbackAccessibilityEnabled = fallbackAccessibilityEnabled,
        postNotificationsGranted = postNotificationsGranted,
    )

    when (step) {
        AutomationWizardStep.RUNTIME_PERMISSIONS -> AutomationStepCard(
            stepLabel = wizardProgress(step),
            stepIndex = step.ordinal + 1,
            title = tr("允许通知和短信"),
            body = tr("系统会同时请求通知权限和短信权限。通知权限为必需；短信权限用于补充银行短信识别，可选、可跳过。"),
            status = when {
                !postNotificationsGranted && attempted ->
                    tr("尚未检测到通知权限，请重试或打开应用设置。")
                !smsGranted -> tr("短信权限可选，可跳过。")
                else -> null
            },
            statusIsError = !postNotificationsGranted && attempted,
            primaryLabel = if (postNotificationsGranted) tr("下一步") else tr("请求权限"),
            onPrimary = {
                if (postNotificationsGranted) {
                    step = AutomationWizardStep.NOTIFICATION_ACCESS
                    attempted = false
                } else {
                    attempted = true
                    onRequestRuntimePermissions()
                }
            },
            secondaryLabel = when {
                !postNotificationsGranted -> tr("打开应用设置")
                !smsGranted -> tr("请求短信")
                else -> null
            },
            onSecondary = when {
                !postNotificationsGranted -> onOpenAppSettings
                !smsGranted -> onRequestRuntimePermissions
                else -> null
            },
        )
        AutomationWizardStep.NOTIFICATION_ACCESS -> AutomationStepCard(
            stepLabel = wizardProgress(step),
            stepIndex = step.ordinal + 1,
            title = tr("开启通知使用权"),
            body = tr("在系统页面找到「清衡」并打开通知使用权，然后返回。此开关只能由你在系统设置中手动确认。"),
            status = if (attempted && !notificationEnabled) {
                tr("尚未检测到，请确认已开启后重试。")
            } else null,
            statusIsError = attempted && !notificationEnabled,
            primaryLabel = if (notificationEnabled) tr("下一步") else tr("去通知使用权设置"),
            onPrimary = {
                if (notificationEnabled) {
                    step = AutomationWizardStep.PAGE_RECOGNITION
                    attempted = false
                } else {
                    attempted = true
                    onOpenNotificationSettings()
                }
            },
            secondaryLabel = if (attempted && !notificationEnabled) tr("重新检查") else null,
            onSecondary = onRefresh,
        )
        AutomationWizardStep.PAGE_RECOGNITION -> AutomationStepCard(
            stepLabel = wizardProgress(step),
            stepIndex = step.ordinal + 1,
            title = tr("开启页面识别"),
            body = tr("在无障碍页面找到「清衡 · 页面识别」并打开，用于识别微信/支付宝支付页面。此开关只能由你在系统设置中手动确认。"),
            status = if (attempted && !pageRecognitionEnabled) {
                tr("尚未检测到，请确认已开启后重试。")
            } else null,
            statusIsError = attempted && !pageRecognitionEnabled,
            primaryLabel = if (pageRecognitionEnabled) tr("下一步") else tr("去无障碍设置"),
            onPrimary = {
                if (pageRecognitionEnabled) {
                    step = AutomationWizardStep.ACCESSIBILITY_FALLBACK
                    attempted = false
                } else {
                    attempted = true
                    onOpenAccessibilitySettings()
                }
            },
            secondaryLabel = if (attempted && !pageRecognitionEnabled) tr("重新检查") else null,
            onSecondary = onRefresh,
        )
        AutomationWizardStep.ACCESSIBILITY_FALLBACK -> AutomationStepCard(
            stepLabel = wizardProgress(step),
            stepIndex = step.ordinal + 1,
            title = tr("开启无障碍兜底"),
            body = if (fallbackAccessibilityAuthorized && !fallbackAccessibilityEnabled) {
                tr("无障碍已授权，但服务当前未连接。请检查系统服务状态，返回应用后重试。")
            } else {
                tr("在无障碍页面找到「清衡」并打开，用于微信转账/红包、支付宝转账入账。此开关只能由你在系统设置中手动确认。")
            },
            status = when {
                !attempted || fallbackAccessibilityEnabled -> null
                fallbackAccessibilityAuthorized -> tr("已授权 · 未连接")
                else -> tr("尚未检测到，请确认已开启后重试。")
            },
            statusIsError = attempted && !fallbackAccessibilityEnabled,
            primaryLabel = if (fallbackAccessibilityEnabled) tr("下一步") else tr("去无障碍设置"),
            onPrimary = {
                if (fallbackAccessibilityEnabled) {
                    step = AutomationWizardStep.ROM_GUIDANCE
                    attempted = false
                } else {
                    attempted = true
                    onOpenAccessibilitySettings()
                }
            },
            secondaryLabel = if (attempted && !fallbackAccessibilityEnabled) tr("重新检查") else null,
            onSecondary = onRefresh,
        )
        AutomationWizardStep.ROM_GUIDANCE -> {
            if (requiredReady) {
                AutomationStepCard(
                    stepLabel = wizardProgress(step),
            stepIndex = step.ordinal + 1,
                    title = tr("自动记账已开启"),
                    body = tr("通知权限、通知使用权、页面识别和无障碍兜底均已就绪。"),
                    status = null,
                    statusIsError = false,
                    primaryLabel = tr("完成"),
                    onPrimary = onFinish,
                )
            } else {
                AutomationStepCard(
                    stepLabel = wizardProgress(step),
            stepIndex = step.ordinal + 1,
                    title = String.format(
                        Locale.getDefault(),
                        tr("还缺 %d 项"),
                        missingNames.size,
                    ),
                    body = tr("缺少：") + missingNames.joinToString(" / "),
                    status = null,
                    statusIsError = true,
                    primaryLabel = tr("重试"),
                    onPrimary = {
                        step = initialWizardStep(
                            notificationListenerEnabled = notificationEnabled,
                            pageRecognitionEnabled = pageRecognitionEnabled,
                            fallbackAccessibilityEnabled = fallbackAccessibilityEnabled,
                            postNotificationsGranted = postNotificationsGranted,
                        )
                        attempted = false
                    },
                )
            }
            Text(
                text = tr("可选建议：国产 ROM 还需允许自启动、把省电策略设为「无限制」，并允许常驻/持续通知；这些状态无法自动检测，也不影响自动记账是否已开启。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenRomGuide) {
                Text(tr("查看 ROM 后台设置（可选）"))
            }
        }
    }
}

@Composable
private fun AutomationStepCard(
    stepLabel: String,
    stepIndex: Int = 0,
    title: String,
    body: String,
    status: String?,
    statusIsError: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { (stepIndex / AUTOMATION_WIZARD_STEPS.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(primaryLabel)
            }
            val secondaryAction = onSecondary
            if (secondaryLabel != null && secondaryAction != null) {
                TextButton(onClick = secondaryAction) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun SettingsDetailDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = tr("关闭"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun SuccessNotificationCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("自动记账成功提醒"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (enabled) tr("已开启：保存后显示金额、商户和时间")
                               else tr("已关闭：不显示成功通知"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            HorizontalDivider()
            Text(
                text = tr("使用低优先级、静音通知，不打断正在进行的支付。只有实际保存成功后才提示；") +
                       tr("采集失败、权限异常等重要告警不受此开关影响。待确认重复提示也不受此开关影响。若系统通知权限未开启，此开关不会生效。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    desc: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            FilledTonalButton(onClick = onClick) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun AccessibilityCard(
    health: AccessibilityCaptureHealth.Snapshot,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
) {
    val enabled = health.authorized
    val statusTone = when (health.state) {
        AccessibilityConnectionState.CONNECTED -> StatusTone.POSITIVE
        AccessibilityConnectionState.PERMISSION_OFF -> StatusTone.ERROR
        else -> StatusTone.WARNING
    }
    val statusColor = when (statusTone) {
        StatusTone.POSITIVE -> QingIncome
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        StatusTone.WARNING -> QingWarning
        StatusTone.NEUTRAL -> QingTextSecondary
    }
    val statusText = when (health.state) {
        AccessibilityConnectionState.PERMISSION_OFF -> tr("未授权：无法采集")
        AccessibilityConnectionState.AUTHORIZED_NOT_CONNECTED -> tr("已授权 · 未连接")
        AccessibilityConnectionState.CONNECTED -> tr("已连接（不代表已抓取支付记录）")
    }
    val badgeText = when (health.state) {
        AccessibilityConnectionState.PERMISSION_OFF -> tr("未授权")
        AccessibilityConnectionState.AUTHORIZED_NOT_CONNECTED -> tr("已授权 · 未连接")
        AccessibilityConnectionState.CONNECTED -> tr("已连接")
    }
    val description = when (health.state) {
        AccessibilityConnectionState.PERMISSION_OFF ->
            tr("通知里不含金额的微信转账/红包、支付宝转账，需要无障碍服务兜底。")
        AccessibilityConnectionState.AUTHORIZED_NOT_CONNECTED ->
            tr("已授权，但服务当前未连接；连接恢复后才能处理没有通知的场景。")
        AccessibilityConnectionState.CONNECTED ->
            tr("微信转账/红包、支付宝转账入账等没有通知的场景会自动抓取。")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("无障碍兜底"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SoftBadge(
                    text = badgeText,
                    tone = statusTone,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = tr("实际运行状态"),
                style = MaterialTheme.typography.labelMedium,
                color = QingTextSecondary,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
            )
            Text(
                "${tr("上次连接")}：${formatHealthTime(health.connectedAtMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
            )
            Text(
                "${tr("上次页面事件")}：${formatHealthTime(health.eventAtMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
            )
            if (health.disconnectedAtMs > 0L) {
                val reason = health.disconnectedReason.takeIf { it.isNotBlank() }
                Text(
                    "${tr("上次断开")}：${formatHealthTime(health.disconnectedAtMs)}" +
                        (reason?.let { "（$it）" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
            }
            if (health.errorAtMs > 0L) {
                Text(
                    "${tr("上次异常")}：${formatHealthTime(health.errorAtMs)}，" +
                        "${tr("阶段")} ${health.errorStage}，${tr("类型")} ${health.errorKind}" +
                        "（${tr("累计")} ${health.errorCount} ${tr("次")}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
            }
            if (health.interruptedAtMs > 0L) {
                Text(
                    "${tr("上次系统中断通知")}：${formatHealthTime(health.interruptedAtMs)}" +
                        "（${tr("不代表断连")}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
            }
            OutlinedButton(onClick = onRefresh) {
                Text(tr("刷新实际运行状态"))
            }
            Text(
                tr("仅在本机保存连接时间、事件时间和异常类名；不会在诊断信息中保存聊天内容、商户或金额。"),
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = tr("隐私说明"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tr("无障碍权限会让本应用看到微信、支付宝两个 app 内的所有窗口内容。") +
                    tr("我们已通过 packageNames 限定仅监听这两个包名（不会读取你打开的其他 app），") +
                    tr("金额与商户信息默认只存在本机；若您主动开启 VPS 同步，则会上传结构化交易字段。"),
                style = MaterialTheme.typography.bodySmall,
                color = QingTextSecondary,
            )

            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (enabled) tr("去无障碍设置（管理/关闭）") else tr("去无障碍设置（开启）"))
            }
        }
    }
}

private fun formatHealthTime(timestampMs: Long): String =
    if (timestampMs <= 0L) tr("尚无记录")
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestampMs))

@Composable
private fun RomGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = tr("为什么需要？"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tr("小米、华为、OPPO、vivo 等国产 ROM 默认会激进省电，把不常用的 app 进程彻底杀掉，") +
                       tr("导致支付通知到达时本应用没法被系统拉起，漏抓账单。") +
                       tr("需要手动把本应用加进各家的「允许后台运行」/「自启动」白名单。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            RomEntry(
                brand = tr("小米 / Redmi"),
                path = tr("设置 → 应用设置 → 应用管理 → 找到本应用 → 「自启动」打开 +「省电策略」选「无限制」"),
            )
            RomEntry(
                brand = tr("华为 / 荣耀"),
                path = tr("设置 → 应用 → 应用启动管理 → 找到本应用 → 关闭「自动管理」→ 手动管理三项全开"),
            )
            RomEntry(
                brand = tr("OPPO / 一加 / realme"),
                path = tr("设置 → 电池 → 应用耗电管理 → 找到本应用 → 允许后台运行 + 允许关联启动"),
            )
            RomEntry(
                brand = "vivo / iQOO",
                path = tr("设置 → 电池 → 后台应用管理 → 找到本应用 → 允许后台运行"),
            )
            RomEntry(
                brand = tr("三星"),
                path = tr("设置 → 设备维护 → 电池 → 未受监视应用 → 添加本应用"),
            )
            RomEntry(
                brand = tr("原生 Android (Pixel)"),
                path = tr("一般不用动。如有问题：设置 → 电池 → 电池优化 → 本应用 → 不优化"),
            )
        }
    }
}

@Composable
private fun RomEntry(brand: String, path: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = brand,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Switch only presentation locale; no sync settings or transaction data change. */
@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppLanguage.read(context)) }
    var error by remember { mutableStateOf(false) }
    SettingsGroupCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsIconBubble(Icons.Filled.Language, StatusTone.NEUTRAL)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(tr("界面语言"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("只切换界面文案，不改动账本数据"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsRowDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AppLanguage.CHINESE to "简体中文",
                    AppLanguage.ENGLISH to "English"
                ).forEach { (code, label) ->
                    FilterChip(
                        selected = current == code,
                        onClick = {
                            if (code != current) {
                                if (AppLanguage.set(context, code)) {
                                    current = code
                                    (context as? Activity)?.recreate()
                                } else error = true
                            }
                        },
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (error) Text(tr("语言设置保存失败"),
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Stable-release check that downloads the APK and hands it to the system installer. */
@Composable
private fun AboutUpdateCard(onOpenAbout: () -> Unit, onOpenFeedback: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember {
        mutableStateOf<GitHubReleaseUpdateChecker.CheckResult?>(null)
    }
    var installDialog by remember { mutableStateOf<InstallDialogState?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    val closeDialog = {
        downloadJob?.cancel()
        installDialog = null
    }

    val startDownload: (InstallDialogState) -> Unit = { state ->
        val update = state.update
        if (!UpdateInstallFlow.hasInstallPermission(context)) {
            // Android 8+: the per-app switch must be granted by the user in
            // system settings. Never fail silently on a missing permission.
            val opened = UpdateInstallFlow.openInstallPermissionSettings(context)
            installDialog = state.copy(
                stage = InstallStage.PROMPT,
                progress = null,
                message = if (opened) {
                    tr("请允许「安装未知应用」，返回后再点「下载」")
                } else {
                    tr("无法打开安装权限设置，请在系统设置中允许本应用安装未知应用")
                },
            )
        } else {
            val started = UpdateInstallFlow.startDownload(context, update)
            val id = started.getOrNull()
            if (id == null) {
                installDialog = state.copy(
                    stage = InstallStage.FAILED,
                    progress = null,
                    message = tr("下载失败，请重试"),
                )
            } else {
                installDialog = state.copy(
                    stage = InstallStage.DOWNLOADING,
                    progress = 0,
                    message = null,
                )
                downloadJob?.cancel()
                downloadJob = scope.launch {
                    UpdateInstallFlow.observe(context, id, update).collect { event ->
                        val current = installDialog ?: return@collect
                        if (current.update.apkUrl != update.apkUrl) return@collect
                        installDialog = when (event) {
                            is UpdateInstallFlow.DownloadEvent.Running -> current.copy(
                                stage = InstallStage.DOWNLOADING,
                                progress = event.percent,
                                message = null,
                            )
                            UpdateInstallFlow.DownloadEvent.Verifying -> current.copy(
                                stage = InstallStage.VERIFYING,
                                progress = null,
                                message = null,
                            )
                            is UpdateInstallFlow.DownloadEvent.Completed -> {
                                val ready = current.copy(
                                    stage = InstallStage.READY,
                                    progress = 100,
                                    message = null,
                                )
                                when (UpdateInstallFlow.openInstaller(context, event.apk)) {
                                    UpdateInstallFlow.InstallOutcome.OPENED_INSTALLER -> null
                                    UpdateInstallFlow.InstallOutcome.NEEDS_PERMISSION -> {
                                        UpdateInstallFlow.openInstallPermissionSettings(context)
                                        ready.copy(
                                            message = tr("请允许「安装未知应用」，返回后点「安装」"),
                                        )
                                    }
                                    UpdateInstallFlow.InstallOutcome.NO_INSTALLER -> ready.copy(
                                        message = tr("没有找到系统安装程序，请用文件管理器打开下载的 APK"),
                                    )
                                }
                            }
                            is UpdateInstallFlow.DownloadEvent.Failed -> current.copy(
                                stage = InstallStage.FAILED,
                                progress = null,
                                message = installFailureText(event.reason),
                            )
                        }
                    }
                }
            }
        }
    }

    SettingsGroupCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsIconBubble(Icons.Filled.Info, StatusTone.NEUTRAL)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    tr("当前版本") + ": " + BuildConfig.VERSION_NAME +
                        " (Build " + BuildConfig.VERSION_CODE + ")",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    tr("发布标识") + ": " + UpdateVersionPolicy.CURRENT_TAG,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsRowDivider()
        SettingsActionRow(
            icon = Icons.Filled.Info,
            title = tr("关于清衡"),
            subtitle = "Open Margin · " + BuildConfig.VERSION_NAME,
            onClick = onOpenAbout,
        )
        SettingsRowDivider()
        SettingsActionRow(
            icon = Icons.Filled.Info,
            title = tr("反馈问题"),
            subtitle = tr("邮件或 GitHub Issue"),
            onClick = onOpenFeedback,
        )
        SettingsRowDivider()
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    checking = true
                    result = null
                    installDialog = null
                    scope.launch {
                        val checked = withContext(Dispatchers.IO) {
                            GitHubReleaseUpdateChecker.checkStable()
                        }
                        result = checked
                        checking = false
                        if (checked is GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable &&
                            checked.apkUrl != null
                        ) {
                            val verified = UpdateInstallFlow.verifiedDownload(context, checked)
                            installDialog = InstallDialogState(
                                update = checked,
                                stage = if (verified != null) {
                                    InstallStage.READY
                                } else {
                                    InstallStage.PROMPT
                                },
                            )
                        }
                    }
                },
            ) {
                Text(
                    text = if (checking) tr("正在检查更新…") else tr("检查更新"),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (val state = result) {
                null -> Unit
                GitHubReleaseUpdateChecker.CheckResult.UpToDate ->
                    Text(
                        tr("已是最新版本"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is GitHubReleaseUpdateChecker.CheckResult.Unavailable -> {
                    val kind = state.kind
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when (kind) {
                                GitHubReleaseUpdateChecker.FailureKind.NO_NETWORK ->
                                    tr("连不上 GitHub，请先开启代理")
                                GitHubReleaseUpdateChecker.FailureKind.TIMEOUT ->
                                    tr("连接 GitHub 超时，请检查代理")
                                GitHubReleaseUpdateChecker.FailureKind.HTTP ->
                                    if (state.httpCode == 403) {
                                        tr("GitHub 拒绝了请求（403），请稍后再试")
                                    } else {
                                        tr("GitHub 返回错误") + " " + state.httpCode
                                    }
                                GitHubReleaseUpdateChecker.FailureKind.PARSE ->
                                    tr("GitHub 返回内容无法解析")
                                GitHubReleaseUpdateChecker.FailureKind.UNKNOWN ->
                                    tr("检查更新失败，请稍后重试")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (kind == GitHubReleaseUpdateChecker.FailureKind.NO_NETWORK ||
                            kind == GitHubReleaseUpdateChecker.FailureKind.TIMEOUT
                        ) {
                            Text(
                                tr("更新检查直接访问 GitHub，中国大陆网络需要开启代理"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable -> {
                    val apkTag = state.apkTag
                    if (state.apkUrl == null || apkTag == null) {
                        if (state.componentScope == UpdateComponentScope.SERVER_ONLY) {
                            Text(
                                tr("仅 Windows / VPS 更新"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                tr("没有可下载的正式版 APK"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = tr("发现新版本") + " " + apkTag,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val verified =
                                        UpdateInstallFlow.verifiedDownload(context, state)
                                    installDialog = InstallDialogState(
                                        update = state,
                                        stage = if (verified != null) {
                                            InstallStage.READY
                                        } else {
                                            InstallStage.PROMPT
                                        },
                                    )
                                },
                            ) {
                                Text(
                                    text = tr("下载"),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    installDialog?.let { state ->
        val update = state.update
        val locked = state.stage == InstallStage.DOWNLOADING ||
            state.stage == InstallStage.VERIFYING
        AlertDialog(
            onDismissRequest = { if (!locked) closeDialog() },
            properties = DialogProperties(
                dismissOnBackPress = !locked,
                dismissOnClickOutside = !locked,
            ),
            title = {
                Text(tr("发现新版本") + " " + (update.apkTag ?: update.tag))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr("版本") + "：" + (update.apkTag ?: update.tag),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    UpdatePresentation.formatSize(update.apkSizeBytes)?.let { size ->
                        Text(
                            tr("APK 大小") + "：" + size,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    UpdatePresentation.formatDate(update.publishedAt)?.let { date ->
                        Text(
                            tr("发布日期") + "：" + date,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (update.notes.isNotEmpty()) {
                        update.notes.forEach { line ->
                            Text(
                                text = "· " + line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    when (state.stage) {
                        InstallStage.DOWNLOADING -> {
                            val percent = state.progress
                            if (percent == null) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .height(4.dp),
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .height(4.dp),
                                    drawStopIndicator = {},
                                )
                            }
                        }
                        InstallStage.VERIFYING -> LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .height(4.dp),
                        )
                        else -> Unit
                    }
                    state.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !locked,
                    onClick = {
                        when (state.stage) {
                            InstallStage.READY -> {
                                val apk = UpdateInstallFlow.apkFile(context, update)
                                if (apk == null) {
                                    installDialog = state.copy(
                                        message = tr("没有找到已下载的安装包，请重新下载"),
                                    )
                                } else {
                                    when (UpdateInstallFlow.openInstaller(context, apk)) {
                                        UpdateInstallFlow.InstallOutcome.OPENED_INSTALLER ->
                                            closeDialog()
                                        UpdateInstallFlow.InstallOutcome.NEEDS_PERMISSION -> {
                                            UpdateInstallFlow.openInstallPermissionSettings(context)
                                            installDialog = state.copy(
                                                message = tr(
                                                    "请允许「安装未知应用」，返回后点「安装」",
                                                ),
                                            )
                                        }
                                        UpdateInstallFlow.InstallOutcome.NO_INSTALLER ->
                                            installDialog = state.copy(
                                                message = tr(
                                                    "没有找到系统安装程序，请用文件管理器打开下载的 APK",
                                                ),
                                            )
                                    }
                                }
                            }
                            else -> startDownload(state)
                        }
                    },
                ) {
                    Text(
                        text = installConfirmLabel(state),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            dismissButton = {
                if (!locked) {
                    TextButton(onClick = closeDialog) {
                        Text(tr("取消"))
                    }
                }
            },
        )
    }
}

private enum class InstallStage { PROMPT, DOWNLOADING, VERIFYING, READY, FAILED }

private data class InstallDialogState(
    val update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable,
    val stage: InstallStage,
    val progress: Int? = null,
    val message: String? = null,
)

private fun installConfirmLabel(state: InstallDialogState): String = when (state.stage) {
    InstallStage.PROMPT -> tr("下载")
    InstallStage.DOWNLOADING -> {
        val percent = state.progress
        if (percent == null) tr("下载中…") else tr("下载中") + " " + percent + "%"
    }
    InstallStage.VERIFYING -> tr("正在校验…")
    InstallStage.READY -> tr("安装")
    InstallStage.FAILED -> tr("重试")
}

private fun installFailureText(reason: UpdateInstallFlow.Failure): String = when (reason) {
    UpdateInstallFlow.Failure.SIZE_MISMATCH,
    UpdateInstallFlow.Failure.DIGEST_MISMATCH,
    -> tr("下载文件校验失败，已删除，请重试")
    UpdateInstallFlow.Failure.REJECTED_URL -> tr("更新地址不受信任，已取消下载")
    else -> tr("下载失败，请重试")
}
