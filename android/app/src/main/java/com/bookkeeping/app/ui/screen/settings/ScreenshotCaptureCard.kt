package com.bookkeeping.app.ui.screen.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bookkeeping.app.capture.screenshot.ScreenshotImportActivity
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchController
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchSettings
import com.bookkeeping.app.i18n.tr

/**
 * Screenshot intake settings: an explicit on/off switch for the new-screenshot
 * watcher (off by default, GitHub builds only) plus the manual picker entry.
 */
@Composable
internal fun ScreenshotCaptureCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ScreenshotWatchSettings.isEnabled(context)) }
    var state by remember { mutableStateOf(ScreenshotWatchController.state(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // On Android 14+ "select photos" counts as partial access and is still
        // usable for the screenshots the user picked.
        enabled = granted || ScreenshotWatchController.state(context) ==
            ScreenshotWatchController.State.PERMISSION_PARTIAL
        if (enabled) {
            ScreenshotWatchSettings.setEnabled(context, true)
            ScreenshotWatchController.refresh(context)
        } else {
            ScreenshotWatchSettings.setEnabled(context, false)
            ScreenshotWatchController.stop(context)
        }
        state = ScreenshotWatchController.state(context)
    }

    val subtitle = when {
        !ScreenshotWatchSettings.isAvailable() -> tr("此版本不启用自动监听")
        state == ScreenshotWatchController.State.PERMISSION_MISSING -> tr("需要相册读取权限")
        state == ScreenshotWatchController.State.PERMISSION_PARTIAL -> tr("仅处理你选中的图片")
        enabled -> tr("发现新截图后自动识别")
        else -> tr("默认关闭，开启后只处理新截图")
    }

    SettingsGroupCard {
        SettingsSwitchRow(
            icon = Icons.Filled.Share,
            title = tr("自动识别新截图"),
            subtitle = subtitle,
            checked = enabled,
            onCheckedChange = { wanted ->
                if (!wanted) {
                    enabled = false
                    ScreenshotWatchSettings.setEnabled(context, false)
                    ScreenshotWatchController.stop(context)
                    state = ScreenshotWatchController.state(context)
                    return@SettingsSwitchRow
                }
                if (!ScreenshotWatchSettings.isAvailable()) return@SettingsSwitchRow
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                permissionLauncher.launch(permission)
            },
        )
        SettingsRowDivider()
        SettingsActionRow(
            icon = Icons.Filled.Share,
            title = tr("从相册选截图"),
            subtitle = tr("识别微信 / 支付宝交易截图"),
            onClick = {
                runCatching { context.startActivity(ScreenshotImportActivity.intent(context)) }
            },
        )
    }
}
