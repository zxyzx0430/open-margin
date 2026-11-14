package com.bookkeeping.app.ui.screen.settings

import com.bookkeeping.app.i18n.tr
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.bookkeeping.app.notification.CaptureConnectionState
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.CaptureHealthSnapshot
import com.bookkeeping.app.notification.CaptureUptime
import com.bookkeeping.app.notification.KeepAliveService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureService
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureHealth
import com.bookkeeping.app.notification.accessibility.AccessibilityConnectionState
import java.text.DateFormat
import java.util.Date
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.update.GitHubReleaseUpdateChecker
import com.bookkeeping.app.update.UpdateComponentScope
import com.bookkeeping.app.update.UpdateVersionPolicy
import com.bookkeeping.app.sync.SyncSettingsCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.notification.SmsPermissionHelper
import com.bookkeeping.app.sync.TransactionSyncWorker
import com.bookkeeping.app.ui.components.SectionHeader
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.sync.WindowsFirstConnectionWizard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onImportBills: () -> Unit = {},
) {
    val keepAlive by viewModel.keepAliveEnabled.collectAsState()
    val successNotifications by viewModel.successNotificationsEnabled.collectAsState()
    val context = LocalContext.current
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
    var lastSyncMessage by remember {
        mutableStateOf(syncPrefs.getString("last_result", "") ?: "")
    }
    var conflicts by remember { mutableStateOf(syncPrefs.getInt("pending_sync_conflicts", 0)) }
    var syncEnabled by remember { mutableStateOf(syncPrefs.getBoolean("enabled", false)) }
    var endpoint by remember { mutableStateOf(syncPrefs.getString("url", "") ?: "") }
    var detailDialog by remember { mutableStateOf<String?>(null) }
    var permissionRefreshKey by remember { mutableStateOf(0) }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        postNotificationsGranted = areNotificationsGranted(context)
        smsGranted = SmsPermissionHelper.isGranted(context)
        permissionRefreshKey++
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
                    lastSyncMessage = syncPrefs.getString("last_result", "") ?: ""
                    conflicts = syncPrefs.getInt("pending_sync_conflicts", 0)
                    syncEnabled = syncPrefs.getBoolean("enabled", false)
                    endpoint = syncPrefs.getString("url", "") ?: ""
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

    val missingCapabilityNames = buildList {
        if (!postNotificationsGranted) add(tr("通知权限"))
        if (!notificationEnabled) add(tr("通知使用权"))
        if (!pageRecognitionEnabled) add(tr("页面识别服务"))
        if (!accessibilityHealth.authorized) add(tr("无障碍兜底"))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(tr("设置")) },
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

            item { SectionHeader(tr("采集与运行状态")) }
            item {
                CaptureStatusGroup(
                    notificationEnabled = notificationEnabled,
                    accessibilityHealth = accessibilityHealth,
                    smsGranted = smsGranted,
                    lastSyncMs = lastSyncMs,
                    lastSyncMessage = lastSyncMessage,
                    onOpenNotificationSettings = {
                        NotificationListenerHelper.openSettings(context)
                    },
                    onOpenAccessibilitySettings = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    onOpenAppSettings = { openAppDetailsSettings(context) },
                )
            }

            item { SectionHeader(tr("同步")) }
            item {
                SyncOverviewGroup(
                    enabled = syncEnabled,
                    endpoint = endpoint,
                    conflicts = conflicts,
                )
            }
            item {
                SettingsGroupCard {
                    SettingsActionRow(
                        icon = Icons.Filled.Cloud,
                        title = tr("同步设置"),
                        subtitle = tr("服务器地址、密钥、立即同步"),
                        onClick = { detailDialog = "sync" },
                    )
                    SettingsRowDivider()
                    SettingsActionRow(
                        icon = Icons.Filled.Sync,
                        title = tr("首次连接向导"),
                        subtitle = tr("3 步连接 Windows / VPS"),
                        onClick = { detailDialog = "wizard" },
                    )
                }
            }

            item { SectionHeader(tr("权限授权")) }
            item {
                PermissionStatusGroup(
                    notificationEnabled = notificationEnabled,
                    smsGranted = smsGranted,
                    accessibilityHealth = accessibilityHealth,
                    onOpenNotificationSettings = {
                        NotificationListenerHelper.openSettings(context)
                    },
                    onOpenSmsSettings = { openAppDetailsSettings(context) },
                    onOpenAccessibilityDetails = { detailDialog = "accessibility" },
                )
            }

            item { SectionHeader(tr("账单导入")) }
            item {
                SettingsGroupCard {
                    SettingsActionRow(
                        icon = Icons.Filled.FileUpload,
                        title = tr("导入账单"),
                        subtitle = tr("支持微信/支付宝官方 CSV / XLSX / 文本"),
                        onClick = onImportBills,
                    )
                }
                SettingsHint(
                    tr("历史账单请使用官方导出文件。自动采集的高置信度记录会自动入账，低置信度记录进入待确认；密码/验证页面会跳过。"),
                )
            }

            item { SectionHeader(tr("提醒与语言")) }
            item {
                SettingsGroupCard {
                    SettingsSwitchRow(
                        icon = Icons.Filled.Notifications,
                        title = tr("常驻运行状态"),
                        subtitle = tr("通知栏显示连接状态与运行时长"),
                        checked = keepAlive,
                        onCheckedChange = viewModel::setKeepAlive,
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Filled.Notifications,
                        title = tr("自动记账成功提醒"),
                        subtitle = tr("保存后显示金额、商户和时间"),
                        checked = successNotifications,
                        onCheckedChange = viewModel::setSuccessNotifications,
                    )
                    SettingsRowDivider()
                    SettingsActionRow(
                        icon = Icons.Filled.Sync,
                        title = tr("常驻状态详情"),
                        subtitle = tr("查看运行时长、断开时间和最近入账"),
                        onClick = { detailDialog = "keepAlive" },
                    )
                }
            }
            item { LanguageCard() }

            item { SectionHeader(tr("关于与更新")) }
            item { AboutUpdateCard() }

            item { SectionHeader(tr("国产 ROM 后台白名单")) }
            item {
                SettingsGroupCard {
                    SettingsActionRow(
                        icon = Icons.Filled.Warning,
                        title = tr("后台运行设置"),
                        subtitle = tr("自启动 / 省电策略 / 后台运行"),
                        onClick = { detailDialog = "rom" },
                    )
                }
            }
        }

        detailDialog?.let { key ->
            when (key) {
                "automation" -> SettingsDetailDialog(
                    tr("一键开启自动记账"),
                    { detailDialog = null },
                ) {
                    AutomationSetupPanel(
                        notificationEnabled = notificationEnabled,
                        pageRecognitionEnabled = pageRecognitionEnabled,
                        fallbackAccessibilityEnabled = accessibilityHealth.authorized,
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
                "sync" -> SettingsDetailDialog(tr("同步设置"), { detailDialog = null }) {
                    SyncSettingsCard()
                }
                "wizard" -> SettingsDetailDialog(tr("首次连接向导"), { detailDialog = null }) {
                    WindowsFirstConnectionWizard()
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
                    KeepAliveCard(enabled = keepAlive, onToggle = viewModel::setKeepAlive)
                }
            }
        }
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

private fun relativeSyncTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return tr("尚未同步")
    val diff = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> tr("刚刚")
        diff < 3_600_000L -> String.format(
            Locale.getDefault(),
            tr("%d 分钟前"),
            diff / 60_000L,
        )
        diff < 86_400_000L -> String.format(
            Locale.getDefault(),
            tr("%d 小时前"),
            diff / 3_600_000L,
        )
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(timestampMs))
    }
}

@Composable
private fun CaptureStatusGroup(
    notificationEnabled: Boolean,
    accessibilityHealth: AccessibilityCaptureHealth.Snapshot,
    smsGranted: Boolean,
    lastSyncMs: Long,
    lastSyncMessage: String,
    onOpenNotificationSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val accessibilityStatus = when (accessibilityHealth.state) {
        AccessibilityConnectionState.CONNECTED -> tr("已连接")
        AccessibilityConnectionState.PERMISSION_OFF -> tr("未开启")
        else -> tr("已授权未连接")
    }
    val accessibilityTone = when (accessibilityHealth.state) {
        AccessibilityConnectionState.CONNECTED -> StatusTone.POSITIVE
        AccessibilityConnectionState.PERMISSION_OFF -> StatusTone.ERROR
        else -> StatusTone.WARNING
    }
    val syncTone = when {
        lastSyncMs <= 0L -> StatusTone.NEUTRAL
        else -> StatusTone.POSITIVE
    }
    SettingsGroupCard {
        SettingsStatusRow(
            icon = Icons.Filled.Notifications,
            title = tr("自动记账"),
            subtitle = tr("自动识别消费并生成账单"),
            statusText = if (notificationEnabled) tr("已开启") else tr("未开启"),
            tone = if (notificationEnabled) StatusTone.POSITIVE else StatusTone.ERROR,
            onClick = onOpenNotificationSettings,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.AccessibilityNew,
            title = tr("无障碍兜底"),
            subtitle = tr("增强采集稳定性"),
            statusText = accessibilityStatus,
            tone = accessibilityTone,
            onClick = onOpenAccessibilitySettings,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Sms,
            title = tr("短信权限"),
            subtitle = tr("可选，用于补充识别部分账单短信"),
            statusText = if (smsGranted) tr("已开启") else tr("可选，未开启"),
            tone = if (smsGranted) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            onClick = onOpenAppSettings,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Sync,
            title = tr("最近同步"),
            subtitle = lastSyncMessage.ifBlank { tr("尚未执行同步") }.let { tr(it) },
            statusText = relativeSyncTime(lastSyncMs),
            tone = syncTone,
        )
    }
}

@Composable
private fun SyncOverviewGroup(
    enabled: Boolean,
    endpoint: String,
    conflicts: Int,
) {
    SettingsGroupCard {
        SettingsStatusRow(
            icon = Icons.Filled.Cloud,
            title = tr("Windows / VPS 双向同步"),
            subtitle = endpoint.ifBlank { tr("未配置") },
            statusText = if (enabled) tr("已开启") else tr("未开启"),
            tone = if (enabled) StatusTone.POSITIVE else StatusTone.NEUTRAL,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Warning,
            title = tr("冲突处理"),
            subtitle = if (conflicts > 0) tr("有本地修改或冲突需要核对") else tr("无待处理冲突"),
            statusText = if (conflicts > 0) String.format(
                Locale.getDefault(),
                tr("%d 笔待处理"),
                conflicts,
            ) else tr("正常"),
            tone = if (conflicts > 0) StatusTone.WARNING else StatusTone.POSITIVE,
        )
    }
}

@Composable
private fun PermissionStatusGroup(
    notificationEnabled: Boolean,
    smsGranted: Boolean,
    accessibilityHealth: AccessibilityCaptureHealth.Snapshot,
    onOpenNotificationSettings: () -> Unit,
    onOpenSmsSettings: () -> Unit,
    onOpenAccessibilityDetails: () -> Unit,
) {
    SettingsGroupCard {
        SettingsStatusRow(
            icon = Icons.Filled.Notifications,
            title = tr("通知使用权"),
            subtitle = tr("用于抓取支付宝/微信支付通知"),
            statusText = if (notificationEnabled) tr("已授权") else tr("未授权"),
            tone = if (notificationEnabled) StatusTone.POSITIVE else StatusTone.ERROR,
            onClick = onOpenNotificationSettings,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.Sms,
            title = tr("短信权限"),
            subtitle = tr("可选，用于读取银行交易短信"),
            statusText = if (smsGranted) tr("已授权") else tr("可选，未开启"),
            tone = if (smsGranted) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            onClick = onOpenSmsSettings,
        )
        SettingsRowDivider()
        SettingsStatusRow(
            icon = Icons.Filled.AccessibilityNew,
            title = tr("无障碍兜底"),
            subtitle = tr("微信转账/红包、支付宝转账入账"),
            statusText = if (accessibilityHealth.authorized) tr("已授权") else tr("未授权"),
            tone = if (accessibilityHealth.authorized) StatusTone.POSITIVE else StatusTone.WARNING,
            onClick = onOpenAccessibilityDetails,
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
    smsGranted: Boolean,
): AutomationWizardStep = when {
    !postNotificationsGranted || !smsGranted -> AutomationWizardStep.RUNTIME_PERMISSIONS
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
    val container = if (ready) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
    val content = if (ready) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onErrorContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = tr("一键开启自动记账"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                    )
                    Text(
                        text = if (ready) {
                            tr("4 项必需能力已完成；短信与 ROM 设置可选")
                        } else {
                            tr("缺少：") + missingNames.joinToString(" / ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = content,
                    )
                }
                Text(
                    text = if (ready) {
                        tr("已开启")
                    } else {
                        String.format(Locale.getDefault(), tr("还缺 %d 项"), missingNames.size)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
            }
            if (!ready) {
                FilledTonalButton(onClick = onStart) {
                    Text(tr("一键开启"))
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
                smsGranted = smsGranted,
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
            title = tr("开启无障碍兜底"),
            body = tr("在无障碍页面找到「清衡」并打开，用于微信转账/红包、支付宝转账入账。此开关只能由你在系统设置中手动确认。"),
            status = if (attempted && !fallbackAccessibilityEnabled) {
                tr("尚未检测到，请确认已开启后重试。")
            } else null,
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
                            smsGranted = smsGranted,
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
    title: String,
    body: String,
    status: String?,
    statusIsError: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
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
            FilledTonalButton(onClick = onPrimary) {
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(tr("关闭")) }
                }
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

@Composable
private fun KeepAliveCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var nowWall by remember { mutableStateOf(System.currentTimeMillis()) }
    var health by remember { mutableStateOf<CaptureHealthSnapshot?>(null) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowElapsed = SystemClock.elapsedRealtime()
                nowWall = System.currentTimeMillis()
                health = withContext(Dispatchers.IO) {
                    CaptureHealth.snapshot(context, KeepAliveService.startedAtMs)
                }
                delay(1_000L)
            }
        }
    }
    val snapshot = health
    val uptime = CaptureUptime.format(
        CaptureUptime.elapsed(
            connectedAtMs = snapshot?.connectedAtMs ?: 0L,
            connectedElapsedMs = snapshot?.connectedElapsedMs ?: 0L,
            nowMs = nowWall,
            nowElapsedMs = nowElapsed,
        ),
        tr("%02d 小时 %02d 分钟 %02d 秒"),
    )
    val downtime = CaptureUptime.formatDowntime(
        CaptureUptime.elapsed(
            connectedAtMs = snapshot?.disconnectedAtMs ?: 0L,
            connectedElapsedMs = snapshot?.disconnectedElapsedMs ?: 0L,
            nowMs = nowWall,
            nowElapsedMs = nowElapsed,
        ),
        tr("%02d 秒"),
        tr("%02d 分钟"),
        tr("%d 小时 %02d 分钟"),
    )
    val statusText = when (snapshot?.state) {
        null -> "⚪ " + tr("正在确认")
        CaptureConnectionState.CONNECTED ->
            "🟢 " + tr("已连接 · 已稳定运行") + " " + uptime
        CaptureConnectionState.DISCONNECTED -> {
            val disconnectedAt = snapshot.disconnectedAtMs
            if (disconnectedAt > 0L) {
                val wall = android.text.format.DateFormat.getTimeFormat(context)
                    .format(Date(disconnectedAt))
                "🔴 " + String.format(
                    Locale.getDefault(),
                    tr("%s 断开 · 已断开 %s"),
                    wall,
                    downtime,
                )
            } else {
                "🔴 " + tr("尚未连接")
            }
        }
        CaptureConnectionState.PERMISSION_OFF -> "🔴 " + tr("通知使用权已关闭")
        CaptureConnectionState.UNKNOWN -> "⚪ " + tr("正在确认")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("常驻运行状态"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = if (enabled) tr("已开启：通知栏显示连接状态与运行时长")
                               else tr("未开启：不会显示常驻状态条，也可能被国产 ROM 杀掉"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )
            Text(
                text = tr("当前状态：") + statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = tr("开启后通知栏会显示绿色/红色状态圆点：绿色表示通知监听已连接，红色表示已断开或权限关闭；") +
                       tr("同时显示本次连接运行时长、断开时间和最近一次成功入账。") +
                       tr("刚启动的几秒会先显示灰色确认中，避免误报。") +
                       tr("Android 13 及以上需要通知权限，否则状态条不会显示。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = tr("如果通知栏没有状态条：请在系统设置中允许本应用的常驻/持续通知。") +
                       tr("小米/Redmi/POCO 的路径是：应用信息 → 通知 → Permanent notifications。") +
                       tr("同时开启自启动，并把电池策略设为「无限制」；否则小米可能不会发送开机广播。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
            FilledTonalButton(
                onClick = { openAppNotificationSettings(context) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(tr("打开系统通知设置"))
            }
        }
    }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (enabled) tr("无障碍兜底：已开启") else tr("无障碍兜底：未开启"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (enabled)
                    tr("微信转账/红包、支付宝转账入账等没有通知的场景会自动抓取。")
                else
                    tr("微信转账、微信红包、支付宝转账入账等场景，通知里不含金额，必须靠无障碍服务兜底。") +
                    tr("未开启会漏抓这些交易。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))
            val statusText = when (health.state) {
                AccessibilityConnectionState.PERMISSION_OFF -> "未授权：无法采集"
                AccessibilityConnectionState.AUTHORIZED_NOT_CONNECTED ->
                    "已授权，但服务未连接（可能被系统停止或尚未启动）"
                AccessibilityConnectionState.CONNECTED -> "服务已连接（不代表已抓取支付记录）"
            }
            Text("实际运行状态：$statusText", style = MaterialTheme.typography.bodyMedium)
            Text(
                "上次连接：${formatHealthTime(health.connectedAtMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "上次页面事件：${formatHealthTime(health.eventAtMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (health.disconnectedAtMs > 0L) {
                Text(
                    "上次断开：${formatHealthTime(health.disconnectedAtMs)}（${health.disconnectedReason}）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (health.errorAtMs > 0L) {
                Text(
                    "上次异常：${formatHealthTime(health.errorAtMs)}，阶段 ${health.errorStage}，类型 ${health.errorKind}（累计 ${health.errorCount} 次）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (health.interruptedAtMs > 0L) {
                Text(
                    "上次系统中断通知：${formatHealthTime(health.interruptedAtMs)}（不代表断连）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onRefresh) {
                Text("刷新实际运行状态")
            }
            Text(
                "仅在本机保存连接时间、事件时间和异常类名；不会在诊断信息中保存聊天内容、商户或金额。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            // 强提示：隐私边界
            Text(
                text = tr("隐私说明"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tr("无障碍权限会让本应用看到微信、支付宝两个 app 内的所有窗口内容。") +
                       tr("我们已通过 packageNames 限定仅监听这两个包名（不会读取你打开的其他 app），") +
                       tr("金额与商户信息默认只存在本机；若您主动开启 VPS 同步，则会上传结构化交易字段。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            FilledTonalButton(onClick = onOpen) {
                Text(if (enabled) tr("去无障碍设置（管理/关闭）") else tr("去无障碍设置（开启）"))
            }
        }
    }
}

private fun formatHealthTime(timestampMs: Long): String =
    if (timestampMs <= 0L) "尚无记录"
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestampMs))

@Composable
private fun RomGuideCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("界面语言"), style = MaterialTheme.typography.titleSmall)
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
                        label = { Text(label) },
                    )
                }
            }
            if (error) Text(tr("语言设置保存失败"),
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Read-only public version check. APK upgrades always remain user-initiated. */
@Composable
private fun AboutUpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includePrereleases by remember {
        mutableStateOf(UpdateVersionPolicy.CURRENT_TAG.contains("-rc."))
    }
    var checking by remember { mutableStateOf(false) }
    var result by remember {
        mutableStateOf<GitHubReleaseUpdateChecker.CheckResult?>(null)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("当前版本") + ": " + BuildConfig.VERSION_NAME +
                    " (Build " + BuildConfig.VERSION_CODE + ")",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                tr("发布标识") + ": " + UpdateVersionPolicy.CURRENT_TAG,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("包含测试版本（RC）"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = includePrereleases,
                    onCheckedChange = {
                        includePrereleases = it
                        result = null
                    },
                    enabled = !checking,
                )
            }
            FilledTonalButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    result = null
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            GitHubReleaseUpdateChecker.check(includePrereleases)
                        }
                        checking = false
                    }
                },
            ) {
                Text(if (checking) tr("正在检查更新…") else tr("检查更新"))
            }
            when (val state = result) {
                null -> Unit
                GitHubReleaseUpdateChecker.CheckResult.UpToDate ->
                    Text(tr("没有找到更新的 GitHub 发布版本；未检测电脑/VPS 的实际部署版本。"))
                GitHubReleaseUpdateChecker.CheckResult.Unavailable ->
                    Text(
                        tr("检查更新失败，请检查网络或稍后重试"),
                        color = MaterialTheme.colorScheme.error,
                    )
                is GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable -> {
                    Text(tr("发现新版本：") + state.tag)
                    val (scopeTitle, scopeAdvice) = when (state.componentScope) {
                        UpdateComponentScope.ANDROID_ONLY ->
                            tr("仅 Android APP 更新") to tr("此次只需更新手机 APP；电脑/VPS 不需要因这个版本重新部署。")
                        UpdateComponentScope.SERVER_ONLY ->
                            tr("仅 Windows / VPS 更新") to tr("此次只更新电脑或 VPS 服务端；手机 APP 无需因这个版本重装。")
                        UpdateComponentScope.BOTH ->
                            tr("Android APP 与 Windows / VPS 都有更新") to tr("此次两个组件都要更新，请按发布说明的步骤执行。")
                        UpdateComponentScope.UNSPECIFIED ->
                            tr("更新范围未注明") to tr("不能确定该版本需要更新哪些组件，请先阅读发布说明。")
                    }
                    Text(
                        tr("待更新组件：") + scopeTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(scopeAdvice, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("此处显示发行版本的更新范围，并未检测你电脑/VPS 的实际安装版本。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        // The checker constructs only github.com/zxyzx0430/open-margin
                        // release URLs from strictly parsed version tags.
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl))
                            )
                        }
                    }) {
                        Text(tr("查看 GitHub 发布页"))
                    }
                }
            }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/zxyzx0430/open-margin/releases")
                        )
                    )
                }
            }) {
                Text(tr("浏览全部发布版本"))
            }
            Text(
                tr("检查更新仅访问项目的公开 GitHub Releases，不上传财务数据；更新需要手动下载安装。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
