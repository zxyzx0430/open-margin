package com.bookkeeping.app.rom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object RomSettingsLauncher {
    fun open(context: Context, kind: RomSettingsKind): Boolean =
        RomSettingsRouter.route(DeviceRomDetector.current(), kind) { target ->
            val intent = when {
                target.appDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.packageName))
                target.packageName != null && target.activityName != null -> Intent().apply {
                    setClassName(target.packageName, if (target.activityName.startsWith("."))
                        target.packageName + target.activityName else target.activityName)
                }
                target.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
                    Intent(target.action, Uri.parse("package:" + context.packageName))
                target.action != null -> Intent(target.action)
                else -> return@route false
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Query visibility may give a false negative for OEM components; still try startActivity.
            runCatching { intent.resolveActivity(context.packageManager) }
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        } != null
    fun openAppDetails(context: Context): Boolean = open(context, RomSettingsKind.APP_DETAILS)
}
