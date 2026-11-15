package com.bookkeeping.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.ui.navigation.RootNavigation
import com.bookkeeping.app.ui.theme.BookkeepingAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// @AndroidEntryPoint = 让 Hilt 能给这个 Activity 及其下属 Composable 注入依赖
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsPreferences

    override fun attachBaseContext(newBase: Context) {
        // Read the language before views/resources are initialized.
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    // Android 13+ 的通知权限在自动记账向导里按上下文申请；首启不弹无解释的系统框。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 如果用户开了「常驻通知保活」就启动前台服务。
        // Android 12+ 限制后台启动前台服务，这里在 Activity onCreate 是合法时机。
        if (settings.keepAliveEnabledBlocking()) {
            KeepAliveService.start(this)
        }

        setContent {
            BookkeepingAppTheme {
                RootNavigation()
            }
        }
    }
}
