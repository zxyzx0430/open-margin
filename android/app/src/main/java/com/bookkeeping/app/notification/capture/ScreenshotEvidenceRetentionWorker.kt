package com.bookkeeping.app.notification.capture

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Periodic best-effort cleanup for screenshot evidence and candidate JSON. */
class ScreenshotEvidenceRetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result =
        if (CaptureCandidateStore(applicationContext).pruneExpired()) Result.success()
        else Result.retry()
}
