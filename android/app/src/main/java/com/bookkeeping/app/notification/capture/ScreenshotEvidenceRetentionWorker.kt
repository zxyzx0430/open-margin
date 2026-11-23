package com.bookkeeping.app.notification.capture

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceSettings
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceStore
import com.bookkeeping.app.data.local.BookkeepingDatabase
import kotlinx.coroutines.runBlocking

/** Periodic best-effort cleanup for screenshot evidence, candidate JSON, and archived receipts. */
class ScreenshotEvidenceRetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val candidatePruned = CaptureCandidateStore(applicationContext).pruneExpired()
        runCatching {
            val db = BookkeepingDatabase.build(applicationContext)
            try {
                val activeNames = runBlocking { db.transactionDao().activeEvidenceImageNames() }.toSet()
                val mode = ReceiptEvidenceSettings.retentionMode(applicationContext)
                ReceiptEvidenceStore(applicationContext).prune(
                    activeImageNames = activeNames,
                    retentionMode = mode,
                )
            } finally {
                db.close()
            }
        }
        return if (candidatePruned) Result.success() else Result.retry()
    }
}
