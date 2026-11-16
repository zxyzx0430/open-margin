package com.bookkeeping.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.CaptureHealthWorker
import com.bookkeeping.app.notification.CategoryLearning
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
        // 最先安装：后面任何初始化出错都能留下堆栈
        CrashCatcher.install(this)
        applicationScope.launch {
            MerchantRules.replace(settings.merchantRulesBlocking())
            val (counts, disabled) = settings.categoryLearningBlocking()
            CategoryLearning.replace(counts, disabled)
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
