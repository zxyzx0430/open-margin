package com.bookkeeping.app.notification

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.bookkeeping.app.data.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking

/**
 * Periodic best-effort recovery for ROMs that kill the foreground service or
 * unbind the notification listener. WorkManager's minimum interval is 15 min;
 * this never blocks the UI and does nothing when keep-alive is disabled.
 */
class CaptureHealthWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsPreferences(context)
        if (!runBlocking { settings.keepAliveEnabledBlocking() }) return Result.success()
        if (!KeepAliveService.isRunning) {
            runCatching { KeepAliveService.start(context) }
        }
        NotificationListenerHelper.requestRebind(context)
        return Result.success()
    }
}
