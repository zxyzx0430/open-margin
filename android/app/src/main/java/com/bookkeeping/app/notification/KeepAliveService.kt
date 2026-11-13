package com.bookkeeping.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.bookkeeping.app.i18n.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the ongoing capture-status notification.
 * The service itself does no capture work; keeping the process alive also reduces
 * the chance that aggressive Android ROMs unbind the notification listener.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startupRefresh: Job? = null

    override fun onCreate() {
        super.onCreate()
        startedAtMs = System.currentTimeMillis()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = CaptureStatusNotifier.build(this, startedAtMs)

        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    CaptureStatusNotifier.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(CaptureStatusNotifier.NOTIFICATION_ID, notification)
            }
            true
        } catch (failure: RuntimeException) {
            // FGS start can be refused by the system (background restrictions or
            // a missing type/permission). Stop cleanly instead of crashing.
            Log.e(TAG, "startForeground failed: ${failure.javaClass.simpleName}")
            false
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true

        // An authorized listener that never connects must turn red after the grace
        // period instead of staying "checking" forever.
        if (startupRefresh?.isActive != true) {
            startupRefresh = scope.launch {
                delay(CaptureHealthPolicy.STARTUP_GRACE_MS)
                refreshStatus(this@KeepAliveService)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAliveService"

        @Volatile var isRunning: Boolean = false
            private set

        @Volatile var startedAtMs: Long = 0L
            private set

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        fun refreshStatus(context: Context) {
            if (!isRunning) return
            CaptureStatusNotifier.post(context, startedAtMs)
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            // Recreate on every start so the channel name/description follow app
            // language changes; Android preserves the user's channel settings.
            val channel = NotificationChannel(
                CaptureStatusNotifier.CHANNEL_ID,
                AppLanguage.tr(context, "采集运行状态"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = AppLanguage.tr(
                    context,
                    "显示自动记账是否连接、运行时长和最近一次成功入账",
                )
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
