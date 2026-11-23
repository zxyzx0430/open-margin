package com.bookkeeping.app.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookkeeping.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Background download for opted-in production builds.
 *
 * DownloadManager owns the transfer (survives process death, shows progress,
 * retries transient failures). This worker only enqueues, polls, and runs the
 * three gates: size, SHA-256, then the signing certificate. If the transfer
 * outlives one WorkManager attempt the worker returns retry and the next
 * attempt resumes observing the same DownloadManager id.
 *
 * Deliberately allows metered networks: the user asked for automatic downloads
 * without a Wi-Fi-only restriction.
 */
class SiteUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        // Package gate, not BuildConfig.DEBUG: only the production application
        // id may download or hand an APK to the installer.
        if (!SiteUpdatePolicy.canAutoDownload(context.packageName)) return@withContext Result.success()

        val prefs = SiteUpdatePrefs(context)
        if (!prefs.autoDownloadEnabled()) return@withContext Result.success()

        val raw = inputData.getString(KEY_MANIFEST) ?: return@withContext Result.success()
        val manifest = SiteUpdatePolicy.parse(raw, BuildConfig.VERSION_CODE)
            ?: return@withContext Result.success()
        val update = manifest.asCheckResult()

        val target = UpdateInstallFlow.apkFile(context, update)
        if (target != null && target.isFile) {
            when (ApkIntegrity.verify(target, manifest.size, manifest.sha256)) {
                ApkCheck.OK -> {
                    if (ApkSignatureVerifier.matchesInstalled(context, target)) {
                        notifyOnce(context, prefs, manifest, target)
                        return@withContext Result.success()
                    }
                    // Signature mismatch: never retry the same tampered file.
                    runCatching { target.delete() }
                }
                ApkCheck.MISSING -> Unit
                ApkCheck.SIZE_MISMATCH, ApkCheck.DIGEST_MISMATCH ->
                    runCatching { target.delete() }
            }
        }

        val terminal = withTimeoutOrNull(POLL_BUDGET_MS) {
            UpdateInstallFlow.streamDownload(context, update).first { event ->
                event is UpdateInstallFlow.DownloadEvent.Completed ||
                    event is UpdateInstallFlow.DownloadEvent.Failed
            }
        }
        when (terminal) {
            is UpdateInstallFlow.DownloadEvent.Completed -> {
                prefs.clearPendingDownload()
                notifyOnce(context, prefs, manifest, terminal.apk)
                Result.success()
            }
            is UpdateInstallFlow.DownloadEvent.Failed -> {
                // Silent by design: a failed background update must not nag.
                prefs.clearPendingDownload()
                Result.success()
            }
            else -> Result.retry()
        }
    }

    private fun notifyOnce(
        context: Context,
        prefs: SiteUpdatePrefs,
        manifest: SiteUpdatePolicy.SiteUpdateManifest,
        apk: java.io.File,
    ) {
        if (prefs.notifiedVersionCode() >= manifest.versionCode) return
        UpdateNotifier.notifyReady(context, manifest, apk)
        prefs.setNotifiedVersionCode(manifest.versionCode)
    }

    companion object {
        const val KEY_MANIFEST = "manifest_json"
        private const val WORK_NAME = "site_update_download"
        private const val POLL_BUDGET_MS = 8L * 60L * 1000L

        /** Unique work: repeated checks must not start parallel downloads. */
        fun enqueue(context: Context, rawManifestJson: String) {
            val request = OneTimeWorkRequestBuilder<SiteUpdateWorker>()
                .setInputData(workDataOf(KEY_MANIFEST to rawManifestJson))
                .setConstraints(
                    Constraints.Builder()
                        // CONNECTED, not UNMETERED: mobile data is allowed.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}
