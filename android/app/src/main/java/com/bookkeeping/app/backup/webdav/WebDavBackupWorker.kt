package com.bookkeeping.app.backup.webdav

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bookkeeping.app.backup.AndroidBackupStorage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class WebDavBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WebDavBackupEntryPoint {
        fun backupStorage(): AndroidBackupStorage
    }

    override suspend fun doWork(): Result {
        val prefs = WebDavPrefs(applicationContext)
        val snap = prefs.read()
        if (!snap.autoEnabled || !snap.isConfigured) return Result.success()

        val storage = EntryPointAccessors.fromApplication(
            applicationContext,
            WebDavBackupEntryPoint::class.java,
        ).backupStorage()

        val coordinator = WebDavBackupCoordinator(
            context = applicationContext,
            storage = storage,
            prefs = prefs,
        )
        return runCatching {
            coordinator.runAutoBackupIfNeeded()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "open_margin_webdav_daily_backup"

        fun syncSchedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
