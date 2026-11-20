package com.bookkeeping.app.data.diagnostics

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.bookkeeping.app.data.local.BookkeepingDatabase
import kotlinx.coroutines.runBlocking

/** Periodic app-private pruning; the writer and diagnostics page also prune on access. */
class TransactionDiagnosticRetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val database = BookkeepingDatabase.build(applicationContext)
        return try {
            runBlocking {
                database.transactionDiagnosticDao().pruneBefore(
                    TransactionDiagnosticRetention.cutoffMs(System.currentTimeMillis()),
                )
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            database.close()
        }
    }
}
