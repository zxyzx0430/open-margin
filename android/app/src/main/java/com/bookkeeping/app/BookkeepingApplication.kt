package com.bookkeeping.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.CaptureHealthWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class BookkeepingApplication : Application() {
    @Inject lateinit var settings: SettingsPreferences

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            MerchantRules.replace(settings.merchantRulesBlocking())
        }
        scheduleCaptureHealthRecovery()
    }

    private fun scheduleCaptureHealthRecovery() {
        val request = PeriodicWorkRequestBuilder<CaptureHealthWorker>(
            15,
            TimeUnit.MINUTES,
        ).build()
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "capture-health-recovery",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
