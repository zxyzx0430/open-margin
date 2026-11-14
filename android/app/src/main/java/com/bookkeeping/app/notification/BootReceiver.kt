package com.bookkeeping.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bookkeeping.app.data.settings.SettingsPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val settings = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        BootEntryPoint::class.java,
                    ).settingsPreferences()
                    if (!settings.keepAliveEnabledBlocking()) return@runCatching
                    try {
                        KeepAliveService.start(context)
                    } catch (_: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException or
                        // MissingForegroundServiceTypeException.
                    } catch (_: SecurityException) {
                        // The system refused the foreground-service start.
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootEntryPoint {
    fun settingsPreferences(): SettingsPreferences
}
