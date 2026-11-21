package com.bookkeeping.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bookkeeping.app.R
import com.bookkeeping.app.i18n.AppLanguage
import java.io.File

/** "New version downloaded" notification; tapping it opens the system installer. */
object UpdateNotifier {
    private const val CHANNEL_ID = "app_update_channel"
    private const val NOTIFICATION_ID = 4100
    private const val REQUEST_CODE = 4100

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppLanguage.tr(context, "应用更新"),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = AppLanguage.tr(context, "新版本下载完成后的安装提醒")
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyReady(
        context: Context,
        manifest: SiteUpdatePolicy.SiteUpdateManifest,
        apk: File,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)

        val intent = Intent(context, UpdateInstallActivity::class.java).apply {
            putExtra(UpdateInstallActivity.EXTRA_APK_FILE_NAME, apk.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(AppLanguage.tr(context, "新版本已下载，点此安装"))
            .setContentText("v" + manifest.versionName)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
