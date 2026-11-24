package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy
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
    /**
     * False when the OCR engine returned no bounding box. The zeroed
     * coordinates above are then a placeholder and must never be read as
     * evidence that two lines overlap or sit on the same row.
     */
    val hasBounds: Boolean = left != 0 || top != 0 || right != 0 || bottom != 0,
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
    val account: String? = null,
    val orderId: String? = null,
    val eventTimeMs: Long? = null,
    /** Only currency-shaped amounts (¥ / 元) are parsed, so drafts are CNY. */
    val currency: String = "CNY",
    val pageIdentity: String = "",
    val supportedPaymentPage: Boolean = false,
    /**
     * True when this draft is one row of a settled bill list (several amount
     * cards, each carrying its own `账单详情 >` footer or payment-method row,
     * and no unfinished word on the card itself). A bill list only prints money
     * that already moved, so an ordinary payment row is booked without a second
     * tap. Kept on the draft so the review card and the tests can tell a list
     * row apart from a detail page that merely happened to say nothing.
     */
    val settledBillRow: Boolean = false,
    /**
     * True when [completionStatus] is not printed anywhere on the page: the row
     * is a settled bill list row and the list itself is the only reason to
     * believe the money moved.
     *
     * That inference is enough for an ordinary payment (a bill list only lists
     * settled money), but it is not a status word. A transfer's completion is
     * proved by 已被接收 / 已收款 in the chat bubble; a list row never prints
     * those words, so [ScreenshotCapturePolicy.transferAutoBookAllowed] keeps
     * such a row for the user. Always false when the page stated the status.
     */
    val completionInferredFromList: Boolean = false,
    val reason: String = "",
)

/**
 * Ledger row fingerprint used for cross-source de-duplication.
 *
 * [currency] mirrors the entity column: two rows with the same numeric amount in
 * different currencies are different money, never a duplicate.
 */
data class LedgerFingerprint(
    val transactionId: Long,
    val platform: String,
    val amountCents: Long,
    val direction: String,
    val orderId: String? = null,
    val merchant: String? = null,
    val eventTimeMs: Long = 0L,
    val currency: String = "CNY",
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

/**
 * Why a draft that looked bookable was still held for the user.
 *
 * Structured evidence, not a reason string: a caller (and the review card) can
 * branch on this without comparing prose, and a wording change can no longer
 * silence the hint. [ScreenshotCandidateFactory] turns it into
 * [com.bookkeeping.app.notification.capture.CaptureCandidate.reason], which is
 * what the stored card and the review UI read.
 */
enum class DuplicateHint {
    /** Not a duplicate: any other reason held the draft (or it was auto booked). */
    NONE,

    /**
     * 层 1: the ledger already holds the same money, written by another intake
     * channel (notification / SMS / accessibility / manual). A resemblance, not
     * an identity, so the draft asks; [CandidateDecision.matchedTransactionId]
     * points at the suspected row.
     */
    CROSS_CHANNEL,

    /**
     * 层 3: the same amount, direction and merchant already appeared earlier in
     * this image. There is no ledger row to point at — the duplicate is the
     * sibling draft, which may itself have been booked.
     */
    SAME_IMAGE,

    /** Existing ledger resemblance without enough evidence to link automatically. */
    LEDGER_AMBIGUOUS,
}

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
    /** False for preview-only/allowAuto=false imports; persisted across review paths. */
    val ledgerWriteAllowed: Boolean = true,
    /** Why this draft was demoted to review; [DuplicateHint.NONE] for every other reason. */
    val duplicateHint: DuplicateHint = DuplicateHint.NONE,
)

data class ImportPlan(
    val decisions: List<CandidateDecision>,
    val failure: FailureReason? = null,
    val repeatImage: Boolean = false,
    val detailReason: String? = null,
) {
    /**
     * Rows the caller may write. Refund / red packet / withdrawal kinds are
     * filtered here as a second lock: even if a future decision path marks one
     * as auto, no caller can reach the ledger with it before the user confirms.
     * Transfers are no longer unconditional: [ScreenshotCapturePolicy] only
     * lets a completed, direction-explicit transfer from a known platform reach
     * [Action.AUTO_BOOK], so a transfer that got here passed those gates.
     */
    val bookings: List<CandidateDecision>
        get() = decisions.filter {
            it.action == Action.AUTO_BOOK &&
                it.ledgerWriteAllowed &&
                !ScreenshotCapturePolicy.requiresConfirmation(it.draft.kind)
        }

    val needsConfirmation: List<CandidateDecision>
        get() = decisions.filter { it.action == Action.ASK_USER }.sortedBy { it.index }
}
