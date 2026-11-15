package com.bookkeeping.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureHealth

/** Single source of truth for the four capabilities required by auto-tracking. */
object AutomationStatus {
    const val PAGE_RECOGNITION_COMPONENT =
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    fun areNotificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isPageRecognitionEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/$PAGE_RECOGNITION_COMPONENT"
        val list = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return list.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun missingNames(context: Context): List<String> = buildList {
        if (!areNotificationsGranted(context)) add(tr("通知权限"))
        if (!NotificationListenerHelper.isEnabled(context)) add(tr("通知使用权"))
        if (!isPageRecognitionEnabled(context)) add(tr("页面识别服务"))
        if (!AccessibilityCaptureHealth.snapshot(context).authorized) add(tr("无障碍兜底"))
    }

    fun requiredReady(context: Context): Boolean = missingNames(context).isEmpty()
}
