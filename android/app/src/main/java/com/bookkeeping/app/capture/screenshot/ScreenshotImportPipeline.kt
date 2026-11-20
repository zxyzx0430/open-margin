package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import java.util.Locale

/**
 * The single pipeline every screenshot entry point (MediaStore watcher,
 * ACTION_SEND, Photo Picker) goes through.
 *
 * Stage order is fixed: validate the input, interpret the page, drop exact
 * duplicates, then apply the high / medium / low admission gate per draft.
 * Nothing here writes the ledger; the caller executes [ImportPlan.bookings]
 * through the normal repository, and only when auto booking is allowed.
 */
object ScreenshotImportPipeline {

    const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

    private val SUPPORTED_MIME_PREFIXES = listOf("image/jpeg", "image/jpg", "image/png", "image/webp",
        "image/heic", "image/heif")

    fun validate(request: ScreenshotIntakeRequest): IntakeValidation {
        if (!request.permissionGranted) return IntakeValidation.Rejected(FailureReason.PERMISSION_DENIED)
        val uri = request.uri?.trim().orEmpty()
        if (uri.isEmpty() || !uri.startsWith("content://")) {
            return IntakeValidation.Rejected(FailureReason.URI_INVALID)
        }
        val mime = request.mimeType?.lowercase(Locale.ROOT)?.trim()
        if (mime.isNullOrEmpty() || SUPPORTED_MIME_PREFIXES.none { mime.startsWith(it) }) {
            return IntakeValidation.Rejected(FailureReason.UNSUPPORTED_MIME)
        }
        if (!request.readable) return IntakeValidation.Rejected(FailureReason.UNREADABLE)
        if ((request.byteSize ?: 0L) > MAX_IMAGE_BYTES) {
            return IntakeValidation.Rejected(FailureReason.OVERSIZED)
        }
        return IntakeValidation.Ready
    }

    /**
     * @param allowAuto false for preview-only paths; every candidate is then held
     *   for review instead of being booked automatically.
     * @param previousImageFingerprints fingerprints already present in the local
     *   candidate inbox, so a re-shared image is not booked twice.
     */
    fun plan(
        request: ScreenshotIntakeRequest,
        blocks: List<TextBlock>?,
        existing: List<LedgerFingerprint>,
        previousImageFingerprints: Set<String> = emptySet(),
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): ImportPlan {
        when (val validation = validate(request)) {
            is IntakeValidation.Rejected -> return ImportPlan(emptyList(), validation.reason)
            IntakeValidation.Ready -> Unit
        }
        if (blocks == null) return ImportPlan(emptyList(), FailureReason.DECODE_FAILED)
        if (blocks.none { it.text.isNotBlank() }) {
            return ImportPlan(emptyList(), FailureReason.OCR_EMPTY)
        }
        val fingerprint = request.imageFingerprint
        if (fingerprint != null && fingerprint in previousImageFingerprints) {
            return ImportPlan(emptyList(), repeatImage = true)
        }
        val evidence = ScreenshotPageInterpreter.interpret(blocks, request.capturedAtMs, timeZone)
        if (evidence.drafts.isEmpty()) {
            val failure = when (evidence.pageType) {
                ScreenshotPageInterpreter.PAGE_UNKNOWN -> FailureReason.UNKNOWN_PAGE
                else -> FailureReason.NOT_TRANSACTION
            }
            return ImportPlan(emptyList(), failure)
        }

        val decisions = mutableListOf<CandidateDecision>()
        var mediumAsked = false
        evidence.drafts.forEach { draft ->
            val dedup = deduplicate(draft, existing)
            val assessment = ScreenshotCapturePolicy.assess(
                evidence = ScreenshotCapturePolicy.Evidence(
                    supportedPaymentPage = draft.supportedPaymentPage,
                    paymentPlatformKnown = evidence.platform != CaptureCandidate.PLATFORM_UNKNOWN,
                    transactionLike = evidence.transactionLike,
                    amountCents = draft.amountCents,
                    amountUnambiguous = draft.amountUnambiguous,
                    direction = draft.direction,
                    directionExplicit = draft.directionExplicit,
                    completionStatus = draft.completionStatus,
                    completionExplicit = draft.completionExplicit,
                    kind = draft.kind,
                    conflictingFields = evidence.conflictingFields,
                    duplicateResult = dedup.first,
                ),
                allowAuto = allowAuto,
                previewOnly = previewOnly,
            )
            var action = assessment.action
            // Belt and braces: the policy already asks for these kinds, but a
            // screenshot decision must never carry AUTO_BOOK for money that
            // only the user can classify, even if a caller forgets to pass the
            // kind into the evidence.
            if (action == Action.AUTO_BOOK && ScreenshotCapturePolicy.requiresConfirmation(draft.kind)) {
                action = Action.ASK_USER
            }
            var reason = draft.reason
            var matched: Long? = null
            when (action) {
                Action.LINK_EXISTING -> {
                    matched = dedup.second
                    reason = "existing ledger transaction"
                }
                Action.AUTO_BOOK -> reason = "high confidence; auto booked"
                Action.ASK_USER -> {
                    if (mediumAsked) {
                        decisions += CandidateDecision(
                            index = draft.index,
                            tier = assessment.tier,
                            action = action,
                            reason = "queued behind the previous confirmation",
                            draft = draft,
                            queued = true,
                            ledgerWriteAllowed = allowAuto && !previewOnly,
                        )
                        return@forEach
                    }
                    mediumAsked = true
                    reason = when {
                        ScreenshotCapturePolicy.requiresConfirmation(draft.kind) ->
                            "${draft.kind.lowercase(Locale.ROOT)} needs confirmation before booking"
                        assessment.tier == CaptureCandidate.TIER_HIGH ->
                            "high confidence but auto booking disabled"
                        else -> "needs user confirmation"
                    }
                }
                Action.WAIT_FOR_EVIDENCE -> {
                    action = Action.WAIT_FOR_EVIDENCE
                    reason = "transaction not completed yet"
                }
                Action.IGNORE -> {
                    action = Action.IGNORE
                    reason = draft.reason.ifBlank { "not a reliable transaction" }
                }
            }
            decisions += CandidateDecision(
                index = draft.index,
                tier = assessment.tier,
                action = action,
                reason = reason,
                draft = draft,
                matchedTransactionId = matched,
                ledgerWriteAllowed = allowAuto && !previewOnly,
            )
        }
        return ImportPlan(decisions)
    }

    /**
     * Exact order id wins. Otherwise amount + direction + platform plus a
     * merchant on one side and a <=2 minute gap. Equal amounts a few minutes
     * apart are *not* merged on their own, so two identical purchases stay two.
     */
    fun deduplicate(
        draft: TransactionDraft,
        existing: List<LedgerFingerprint>,
    ): Pair<ScreenshotCapturePolicy.DuplicateResult, Long?> {
        val orderId = draft.orderId?.trim()?.takeIf(String::isNotEmpty)
        val platform = platformOf(draft.pageIdentity)
        if (orderId != null) {
            if (platform == CaptureCandidate.PLATFORM_UNKNOWN) {
                return ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
            }
            val exact = existing.filter {
                it.platform == platform && it.platform != CaptureCandidate.PLATFORM_UNKNOWN &&
                    it.orderId?.trim() == orderId
            }
            when {
                exact.size == 1 -> return ScreenshotCapturePolicy.DuplicateResult.EXACT_MATCH to exact.single().transactionId
                exact.size > 1 -> return ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
            }
        }
        val amount = draft.amountCents ?: return ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
        if (platform == CaptureCandidate.PLATFORM_UNKNOWN) {
            return ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
        }
        val merchant = normalize(draft.merchantCandidates.firstOrNull())
        val possible = existing.filter { row ->
            row.platform == platform && row.platform != CaptureCandidate.PLATFORM_UNKNOWN &&
                row.amountCents == amount && row.direction == draft.direction &&
                (orderId == null || row.orderId?.trim().let { it == null || it == orderId }) &&
                normalize(row.merchant).let { rowMerchant ->
                    merchant.isEmpty() || rowMerchant.isEmpty() || merchant == rowMerchant
                }
        }
        if (draft.eventTimeMs == null) {
            return if (possible.isEmpty()) ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
            else ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
        }
        val candidates = possible.filter { row ->
            row.eventTimeMs <= 0L ||
                kotlin.math.abs(row.eventTimeMs - draft.eventTimeMs) <= DEDUP_WINDOW_MS
        }
        return when (candidates.size) {
            0 -> ScreenshotCapturePolicy.DuplicateResult.CLEAR to null
            1 -> if (candidates.single().eventTimeMs > 0L) {
                ScreenshotCapturePolicy.DuplicateResult.EXACT_MATCH to candidates.single().transactionId
            } else {
                ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
            }
            else -> ScreenshotCapturePolicy.DuplicateResult.AMBIGUOUS to null
        }
    }

    /** Next medium-confidence candidate to ask about; the rest stay queued. */
    fun nextConfirmation(decisions: List<CandidateDecision>): CandidateDecision? =
        decisions.filter { it.action == Action.ASK_USER }.minByOrNull { it.index }

    private fun normalize(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun platformOf(pageIdentity: String): String = when {
        pageIdentity.startsWith("wechat") -> CaptureCandidate.PLATFORM_WECHAT
        pageIdentity.startsWith("alipay") -> CaptureCandidate.PLATFORM_ALIPAY
        else -> CaptureCandidate.PLATFORM_UNKNOWN
    }

    private const val DEDUP_WINDOW_MS = 2L * 60 * 1000
}
