package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action

/**
 * Values shared by the three screenshot intakes (MediaStore watcher, ACTION_SEND,
 * Photo Picker). Everything here is deliberately free of Android types so the
 * whole decision path is unit-testable on the JVM.
 */

/** How the image reached the app. Kept separate from the payment platform. */
enum class IntakeSource(val candidateValue: String) {
    MEDIASTORE("MEDIASTORE"),
    SHARE("SHARE"),
    PHOTO_PICKER("PHOTO_PICKER");

    companion object {
        fun fromCandidateValue(value: String?): IntakeSource? =
            entries.firstOrNull { it.candidateValue == value }
    }
}

/** One OCR line with its position in the (already rotated) image. */
data class TextBlock(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/** Structured evidence read off the page; no raw OCR text is persisted. */
data class ScreenshotPageEvidence(
    val platform: String,
    val pageType: String,
    val transactionLike: Boolean,
    val drafts: List<TransactionDraft>,
    val conflictingFields: Boolean = false,
    val reason: String = "",
)

/** A single transaction found in one image. */
data class TransactionDraft(
    val index: Int,
    val amountCents: Long?,
    val amountUnambiguous: Boolean,
    val direction: String,
    val directionExplicit: Boolean,
    val kind: String,
    val completionStatus: String,
    val completionExplicit: Boolean,
    val merchantCandidates: List<String> = emptyList(),
    val orderId: String? = null,
    val eventTimeMs: Long? = null,
    val pageIdentity: String = "",
    val supportedPaymentPage: Boolean = false,
    val reason: String = "",
)

/** Ledger row fingerprint used for cross-source de-duplication. */
data class LedgerFingerprint(
    val transactionId: Long,
    val platform: String,
    val amountCents: Long,
    val direction: String,
    val orderId: String? = null,
    val merchant: String? = null,
    val eventTimeMs: Long = 0L,
)

/** Result of validating the incoming URI before any parsing happens. */
sealed interface IntakeValidation {
    data object Ready : IntakeValidation
    data class Rejected(val reason: FailureReason) : IntakeValidation
}

enum class FailureReason {
    PERMISSION_DENIED,
    URI_INVALID,
    UNSUPPORTED_MIME,
    UNREADABLE,
    OVERSIZED,
    DECODE_FAILED,
    OCR_EMPTY,
    NOT_TRANSACTION,
    UNKNOWN_PAGE,
}

/** One intake request, normalised across the three entry points. */
data class ScreenshotIntakeRequest(
    val source: IntakeSource,
    val uri: String?,
    val mimeType: String?,
    val capturedAtMs: Long,
    val permissionGranted: Boolean = true,
    val readable: Boolean = true,
    val byteSize: Long? = null,
    val imageFingerprint: String? = null,
)

/** What the pipeline decided for one draft. */
data class CandidateDecision(
    val index: Int,
    val tier: String,
    val action: Action,
    val reason: String,
    val draft: TransactionDraft,
    /** True when an earlier medium-confidence draft in the same image is still unanswered. */
    val queued: Boolean = false,
    val matchedTransactionId: Long? = null,
)

data class ImportPlan(
    val decisions: List<CandidateDecision>,
    val failure: FailureReason? = null,
    val repeatImage: Boolean = false,
) {
    val bookings: List<CandidateDecision>
        get() = decisions.filter { it.action == Action.AUTO_BOOK }

    val needsConfirmation: List<CandidateDecision>
        get() = decisions.filter { it.action == Action.ASK_USER }.sortedBy { it.index }
}
