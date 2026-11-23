package com.bookkeeping.app.ui.screen.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.widget.Toast
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceSettings
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceStore
import com.bookkeeping.app.capture.screenshot.ScreenshotImportActivity
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchController
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchSettings
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.StatusTone

/**
 * 截图入口设置，按监听的真实状态分五种，各给一个明确动作：
 * - UNSUPPORTED：本安装包没有相册监听（Play 版），不给开关，只留分享 / 选图入口；
 * - OFF：开关关闭，打开开关才是唯一动作；
 * - PERMISSION_MISSING：权限被关掉，开关点了也开不起来，给系统设置入口；
 * - PERMISSION_PARTIAL：Android 14 只授权部分照片，监听读不到新截图，给「授权全部照片」；
 * - ACTIVE：开关打开且在监听，关掉开关是唯一动作。
 * 文案只写事实：不说「正常」「应该可以」，也不加「不代表…」这类括号。
 */
@Composable
internal fun ScreenshotCaptureCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(ScreenshotWatchController.state(context)) }

    // 用户可能在系统设置里改了相册权限再回来；回到前台重新读一次，
    // 否则卡片会继续显示离开前的旧状态。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val next = ScreenshotWatchController.state(context)
                // 进程启动时没权限，start() 已经直接退出；用户去系统设置补授权回来后
                // 要补一次注册，否则卡片写着「正在监听」而实际没有监听。
                if (next != state && next == ScreenshotWatchController.State.ACTIVE) {
                    ScreenshotWatchController.refresh(context)
                }
                state = next
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 只有拿到完整相册权限才真的打开监听；否则把意图写回关闭，
        // 不让「开关显示关、偏好里还留着待开启」这种半开状态留下来。
        if (granted) {
            ScreenshotWatchSettings.setEnabled(context, true)
            ScreenshotWatchController.refresh(context)
        } else {
            ScreenshotWatchSettings.setEnabled(context, false)
            ScreenshotWatchController.stop(context)
        }
        state = ScreenshotWatchController.state(context)
    }

    // when 是表达式，新增状态时这里编译不过，文案不会漏。
    val subtitle = when (state) {
        ScreenshotWatchController.State.UNSUPPORTED -> tr("此安装包不含相册监听，用分享或选图")
        ScreenshotWatchController.State.OFF -> tr("默认关闭，开启后只处理新截图")
        ScreenshotWatchController.State.PERMISSION_MISSING -> tr("相册权限已关闭，监听没有运行")
        ScreenshotWatchController.State.PERMISSION_PARTIAL -> tr("只授权了部分照片，监听无法运行")
        ScreenshotWatchController.State.ACTIVE -> tr("正在监听新增截图，会自动识别")
    }

    SettingsGroupCard {
        when (state) {
            ScreenshotWatchController.State.UNSUPPORTED -> SettingsStatusRow(
                icon = Icons.Filled.PhotoCamera,
                title = tr("自动识别新截图"),
                subtitle = subtitle,
                statusText = tr("不可用"),
                tone = StatusTone.NEUTRAL,
            )

            ScreenshotWatchController.State.PERMISSION_MISSING -> SettingsActionRow(
                icon = Icons.Filled.PhotoCamera,
                title = tr("自动识别新截图"),
                subtitle = subtitle,
                trailing = tr("打开系统设置"),
                onClick = { openPhotoAccessSettings(context) },
            )

            ScreenshotWatchController.State.PERMISSION_PARTIAL -> SettingsActionRow(
                icon = Icons.Filled.PhotoCamera,
                title = tr("自动识别新截图"),
                subtitle = subtitle,
                trailing = tr("授权全部照片"),
                onClick = { permissionLauncher.launch(photoPermission()) },
            )

            ScreenshotWatchController.State.OFF,
            ScreenshotWatchController.State.ACTIVE -> SettingsSwitchRow(
                icon = Icons.Filled.PhotoCamera,
                title = tr("自动识别新截图"),
                subtitle = subtitle,
                checked = state == ScreenshotWatchController.State.ACTIVE,
                onCheckedChange = { wanted ->
                    if (!wanted) {
                        ScreenshotWatchSettings.setEnabled(context, false)
                        ScreenshotWatchController.stop(context)
                        state = ScreenshotWatchController.state(context)
                        return@SettingsSwitchRow
                    }
                    permissionLauncher.launch(photoPermission())
                },
            )
        }
        SettingsRowDivider()
        SettingsActionRow(
            icon = Icons.Filled.PhotoLibrary,
            title = tr("从相册选截图"),
            subtitle = tr("支持批量导入微信 / 支付宝交易截图，单次最多 10 张"),
            onClick = {
                runCatching { context.startActivity(ScreenshotImportActivity.intent(context)) }
            },
        )
        var retentionMode by remember {
            mutableStateOf(ReceiptEvidenceSettings.retentionMode(context))
        }
        SettingsRowDivider()
        SettingsActionRow(
            icon = Icons.Filled.Folder,
            title = tr("扫描原图保留"),
            subtitle = if (retentionMode == ReceiptEvidenceSettings.RetentionMode.FOREVER) {
                tr("永久保留")
            } else {
                tr("90 天（上限 100MB）")
            },
            trailing = tr("切换"),
            onClick = {
                val next = if (retentionMode == ReceiptEvidenceSettings.RetentionMode.FOREVER) {
                    ReceiptEvidenceSettings.RetentionMode.NINETY_DAYS
                } else {
                    ReceiptEvidenceSettings.RetentionMode.FOREVER
                }
                ReceiptEvidenceSettings.setRetentionMode(context, next)
                retentionMode = next
            },
        )
        var evidenceBytes by remember {
            mutableLongStateOf(ReceiptEvidenceStore(context).totalSizeBytes())
        }
        var confirmingClearEvidence by remember { mutableStateOf(false) }
        SettingsRowDivider()
        SettingsActionRow(
            icon = Icons.Filled.Delete,
            title = tr("清理扫描原图缓存"),
            subtitle = if (evidenceBytes > 0L) {
                tr("当前约 %s · 仅清理本地归档原图，不影响账单记录")
                    .format(formatEvidenceBytes(evidenceBytes))
            } else {
                tr("当前暂无本地原图缓存")
            },
            onClick = {
                if (evidenceBytes > 0L) {
                    confirmingClearEvidence = true
                } else {
                    Toast.makeText(
                        context,
                        tr("当前暂无本地原图缓存"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
        if (confirmingClearEvidence) {
            AlertDialog(
                onDismissRequest = { confirmingClearEvidence = false },
                title = { Text(tr("清理扫描原图缓存？")) },
                text = { Text(tr("将删除本地归档的扫描原图（约 %s），已记入的账单记录不会受影响。").format(formatEvidenceBytes(evidenceBytes))) },
                confirmButton = {
                    TextButton(onClick = {
                        val removed = ReceiptEvidenceStore(context).clearAll()
                        evidenceBytes = ReceiptEvidenceStore(context).totalSizeBytes()
                        confirmingClearEvidence = false
                        Toast.makeText(
                            context,
                            tr("已清理 %d 张本地原图").format(removed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Text(tr("确认清理"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingClearEvidence = false }) {
                        Text(tr("取消"))
                    }
                },
            )
        }
    }
}

private fun formatEvidenceBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.CHINA, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%d KB".format(Locale.CHINA, (bytes + 1023L) / 1024L)
    else -> "$bytes B"
}

/** 相册读取权限：Android 13 起是 READ_MEDIA_IMAGES，更早是存储读取。 */
private fun photoPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** 权限被系统拒绝后只能去应用详情页重新打开，弹窗不一定会再出现。 */
private fun openPhotoAccessSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
}
