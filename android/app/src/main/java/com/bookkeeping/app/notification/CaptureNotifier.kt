package com.bookkeeping.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.i18n.AppLanguage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Optional, silent success notification. Never used for failures or permissions. */
object CaptureNotifier {
    private const val CHANNEL_ID = "capture_success_channel"
    private const val BASE_NOTIFICATION_ID = 2000
    private const val OPEN_APP_REQUEST_CODE = 2000

    fun notifySaved(
        context: Context,
        transaction: TransactionEntity,
        possibleDuplicate: Boolean,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)

        val merchant = transaction.merchant.ifBlank {
            sourceLabel(context, transaction.source)
        }
        val amount = String.format(Locale.CHINA, "%.2f", transaction.amountCents / 100.0)
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(transaction.timestamp))
        var text = "${transaction.currency} $amount · $merchant · $time"
        if (possibleDuplicate) {
            text += " · " + AppLanguage.tr(context, "可能与已有记录重复")
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            OPEN_APP_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                AppLanguage.tr(
                    context,
                    if (possibleDuplicate) "已保存，待确认重复" else "自动记账成功",
                )
            )
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

        val notificationId = BASE_NOTIFICATION_ID + (transaction.id % 10_000).toInt()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppLanguage.tr(context, "自动记账提醒"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = AppLanguage.tr(context, "保存成功后显示金额、商户和时间")
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun sourceLabel(context: Context, source: String): String = when (source) {
        "WECHAT" -> AppLanguage.tr(context, "微信")
        "ALIPAY" -> AppLanguage.tr(context, "支付宝")
        "BANK_SMS" -> AppLanguage.tr(context, "银行短信")
        "MANUAL" -> AppLanguage.tr(context, "手动")
        else -> AppLanguage.tr(context, "其他来源")
    }
}
