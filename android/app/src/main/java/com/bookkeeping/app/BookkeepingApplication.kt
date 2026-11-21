package com.bookkeeping.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticRetentionWorker
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchController
import com.bookkeeping.app.notification.CaptureHealthWorker
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.capture.ScreenshotEvidenceRetentionWorker
import com.bookkeeping.app.notification.CategoryLearning
import com.bookkeeping.app.voice.asr.AsrMemoryAction
import com.bookkeeping.app.voice.asr.AsrTrimMemoryPolicy
import com.bookkeeping.app.voice.asr.VoiceAsrHolder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application 同时是 `ComponentCallbacks2`（[android.app.Application] 本来就实现了它），
 * 系统内存紧张时会回调 [onTrimMemory]。这里只把「真的紧张」的级别转成释放 ASR 模型：
 * 常驻策略见 [AsrTrimMemoryPolicy] 与 docs/VOICE_POLISH.md。
 */
@HiltAndroidApp
class BookkeepingApplication : Application() {
    @Inject lateinit var settings: SettingsPreferences
    @Inject lateinit var transactionRepository: TransactionRepository
    @Inject lateinit var transactionDiagnostics: TransactionDiagnostics

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Restore the saved per-app locale before any Activity creates resources.
        AppLanguage.restoreApplicationLocale(this)
        // 最先安装：后面任何初始化出错都能留下堆栈
        CrashCatcher.install(this)
        installVoiceAsr()
        applicationScope.launch {
            MerchantRules.replace(settings.merchantRulesBlocking())
            val (counts, disabled) = settings.categoryLearningBlocking()
            CategoryLearning.replace(counts, disabled)
            // 一次性纠正升级前已经各插一条的重复采集（prefs 版本标记保证只跑一次）。
            // 纯本地写，失败不影响启动。
            runCatching { transactionRepository.repairCapturedDuplicatesOnce() }
            runCatching { transactionDiagnostics.pruneExpired() }
            // 截图证据 7 天到期：启动先清一次，再交给定期任务。
            runCatching { CaptureCandidateStore(applicationContext).pruneExpired() }
        }
        scheduleCaptureHealthRecovery()
        scheduleDiagnosticRetention()
        scheduleScreenshotRetention()
        // 新截图监听默认关闭；只有用户在设置里开启并授权后才会真正注册。
        runCatching { ScreenshotWatchController.start(this) }
        // 观察者可能在上次进程里被 MIUI 静默丢掉回调：启动时按 _ID 水位补扫一次。
        runCatching { ScreenshotWatchController.catchUp(this) }
    }

    /** 截图副本 / OCR 候选 / 诊断的 7 天清理，进程重启后仍然生效。 */
    private fun scheduleScreenshotRetention() {
        val request = PeriodicWorkRequestBuilder<ScreenshotEvidenceRetentionWorker>(12, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "screenshot_evidence_retention",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 语音引擎常驻：不在这里加载模型（不能拖慢启动），只安装 Context 并排一个
     * 延迟 2.5s 的后台预热；真正加载失败也不影响其他功能，用户长按时会再试。
     */
    private fun installVoiceAsr() {
        // 纯 JVM 侧的常量是平台常量的镜像：启动时核对一次，防止以后有人改错数字。
        check(AsrTrimMemoryPolicy.RUNNING_LOW == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            "AsrTrimMemoryPolicy.RUNNING_LOW 与平台常量不一致"
        }
        check(AsrTrimMemoryPolicy.RUNNING_CRITICAL == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            "AsrTrimMemoryPolicy.RUNNING_CRITICAL 与平台常量不一致"
        }
        check(AsrTrimMemoryPolicy.COMPLETE == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            "AsrTrimMemoryPolicy.COMPLETE 与平台常量不一致"
        }
        VoiceAsrHolder.install(this)
        VoiceAsrHolder.schedulePrewarm()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (AsrTrimMemoryPolicy.actionFor(level)) {
            AsrMemoryAction.RELEASE -> {
                Log.d(TAG, "内存紧张（level=$level），释放 ASR 模型")
                VoiceAsrHolder.releaseNow("trim-memory level=$level")
            }

            // RUNNING_LOW 不释放：这时候释放，下一次记账就得重新等模型加载。
            AsrMemoryAction.LOG_ONLY -> Log.d(TAG, "内存偏低（level=$level），ASR 模型保持常驻")

            AsrMemoryAction.NONE -> Unit
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.d(TAG, "onLowMemory：释放 ASR 模型")
        VoiceAsrHolder.releaseNow("onLowMemory")
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

    private fun scheduleDiagnosticRetention() {
        val request = PeriodicWorkRequestBuilder<TransactionDiagnosticRetentionWorker>(
            15,
            TimeUnit.MINUTES,
        ).build()
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "transaction-diagnostic-retention",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    private companion object {
        const val TAG = "BookkeepingApplication"
    }
}
