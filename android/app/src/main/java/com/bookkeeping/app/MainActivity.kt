package com.bookkeeping.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.ui.navigation.RootNavigation
import com.bookkeeping.app.ui.theme.BookkeepingAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// @AndroidEntryPoint = 让 Hilt 能给这个 Activity 及其下属 Composable 注入依赖
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settings: SettingsPreferences

    // Android 13+ 必须运行时申请 POST_NOTIFICATIONS，否则前台服务的常驻通知不可见
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不需要处理：拒绝就只是看不到常驻通知，不影响其他功能 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val transparent = Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
        )

        val showNotificationPermissionIntro = shouldShowNotificationPermissionIntro()

        // 如果用户开了「常驻通知保活」就启动前台服务。
        // Android 12+ 限制后台启动前台服务，这里在 Activity onCreate 是合法时机。
        if (settings.keepAliveEnabledBlocking()) {
            KeepAliveService.start(this)
        }

        setContent {
            BookkeepingAppTheme {
                RootNavigation()
                var showPermissionIntro by remember {
                    mutableStateOf(showNotificationPermissionIntro)
                }
                if (showPermissionIntro) {
                    AlertDialog(
                        onDismissRequest = { showPermissionIntro = false },
                        title = { Text(tr("通知权限说明")) },
                        text = {
                            Text(
                                tr(
                                    "通知权限用于显示自动记账服务状态；清衡通过通知使用权读取付款通知并自动记账。"
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermissionIntro = false
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }) {
                                Text(tr("继续"))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionIntro = false }) {
                                Text(tr("暂不"))
                            }
                        },
                    )
                }
            }
        }
    }

    private fun shouldShowNotificationPermissionIntro(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        val preferences = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val alreadyExplained = preferences.getBoolean(KEY_NOTIFICATION_INTRO_SEEN, false)
        if (granted || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            preferences.edit().putBoolean(KEY_NOTIFICATION_INTRO_SEEN, true).apply()
            return false
        }
        if (alreadyExplained) return false

        // Set the flag before displaying the explanation, so dismissing it or
        // denying the system prompt never causes the explanation to repeat.
        preferences.edit().putBoolean(KEY_NOTIFICATION_INTRO_SEEN, true).apply()
        return true
    }

    private companion object {
        const val PERMISSION_PREFS = "open_margin_permission_prompts"
        const val KEY_NOTIFICATION_INTRO_SEEN = "notification_permission_intro_seen"
    }
}
