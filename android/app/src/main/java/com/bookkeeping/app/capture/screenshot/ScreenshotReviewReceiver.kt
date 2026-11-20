package com.bookkeeping.app.capture.screenshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Background "record / ignore" actions for the medium-confidence notification. */
class ScreenshotReviewReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_BOOK && action != ACTION_IGNORE) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val candidateId = intent.getStringExtra(EXTRA_CANDIDATE_ID)
                val store = CaptureCandidateStore(appContext)
                val candidate = candidateId
                    ?.let { id -> store.latest().lastOrNull { it.candidateId == id } }
                    ?: store.pending().firstOrNull()
                if (candidate != null) {
                    if (action == ACTION_BOOK) book(appContext, store, candidate)
                    else store.mark(candidate, CaptureCandidate.STATUS_IGNORED)
                }
                // Reveal the next queued medium candidate instead of stacking.
                val remaining = store.pending()
                if (remaining.isNotEmpty()) {
                    ScreenshotReviewNotifier.notifyPending(appContext, remaining.size)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun book(
        context: Context,
        store: CaptureCandidateStore,
        candidate: CaptureCandidate,
    ) {
        if (!CaptureBookGate.mayConfirm(candidate)) return
        val repository = runCatching {
            EntryPointAccessors.fromApplication(
                context,
                ScreenshotReviewEntryPoint::class.java,
            ).transactionRepository()
        }.getOrNull() ?: return
        val merchant = candidate.merchantCandidates.firstOrNull().orEmpty()
        val entity: TransactionEntity = CaptureReviewMapping.toTransactionEntity(
            candidate = candidate.copy(status = CaptureCandidate.STATUS_APPROVED),
            merchant = merchant,
            category = CaptureReviewMapping.classificationFor(
                candidate = candidate,
                merchant = merchant,
                contextText = merchant,
            ).category,
        )
        val inserted = runCatching {
            repository.insertIfNotDuplicate(entity, notifySuccess = false)
        }.getOrNull()
        store.mark(
            candidate,
            if (inserted != null && inserted > 0L) CaptureCandidate.STATUS_APPROVED
            else CaptureCandidate.STATUS_IGNORED,
        )
    }

    companion object {
        const val ACTION_BOOK = "com.bookkeeping.app.action.SCREENSHOT_BOOK"
        const val ACTION_IGNORE = "com.bookkeeping.app.action.SCREENSHOT_IGNORE"
        const val EXTRA_CANDIDATE_ID = "candidate_id"
        const val EXTRA_NEXT = "next"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScreenshotReviewEntryPoint {
    fun transactionRepository(): TransactionRepository
}
