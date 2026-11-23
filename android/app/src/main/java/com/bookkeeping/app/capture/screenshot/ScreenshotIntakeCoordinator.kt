package com.bookkeeping.app.capture.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.bookkeeping.app.data.diagnostics.DiagnosticRawText
import com.bookkeeping.app.data.diagnostics.DiagnosticStep
import com.bookkeeping.app.data.diagnostics.DiagnosticSummary
import com.bookkeeping.app.data.diagnostics.RawLine
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.CrossSourceDuplicatePolicy
import com.bookkeeping.app.data.repository.DuplicateReason
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CandidateSaveFailure
import com.bookkeeping.app.notification.capture.CandidateSaveResult
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.capture.ImageFingerprintLookup
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import com.bookkeeping.app.notification.capture.ScreenshotWaitingCorrelation
import com.bookkeeping.app.ocr.MlKitOcrEngine
import com.bookkeeping.app.ocr.OcrEngine
import com.bookkeeping.app.ocr.PaddleOcrEngine
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one ledger write this coordinator performs.
 *
 * Production wraps [TransactionRepository.insertIfNotDuplicateResult], whose
 * result distinguishes inserted / duplicate (with the matching rule) / failed.
 * The seam exists so a plain JVM test can drive all three without Room.
 */
internal interface ScreenshotLedgerWriter {
    suspend fun insert(entity: TransactionEntity, attemptId: String): LedgerInsertResult

    /**
     * 用户在审阅页显式确认后的写入。与 [insert] 同一套查重，但跨通道命中同一笔时
     * 允许把本次截图里更完整的商户/订单号/capture 溯源并进已有行（不新增行）。
     */
    suspend fun confirm(entity: TransactionEntity, attemptId: String): LedgerInsertResult
}

/**
 * Android glue for every screenshot intake. It owns URI access, decoding and
 * OCR; all admission decisions come from [ScreenshotImportPipeline] so the three
 * entry points cannot drift apart.
 */
class ScreenshotIntakeCoordinator private constructor(
    private val context: Context?,
    private val store: CaptureCandidateStore,
    private val repositoryProvider: () -> TransactionRepository?,
    private val diagnosticSink: suspend (DiagnosticStep) -> Unit,
    /** Null means "build one from [repositoryProvider]"; tests inject a fake. */
    private val ledgerWriter: ScreenshotLedgerWriter?,
    private val decodeOverride: ((ByteArray) -> Bitmap?)?,
    private val failureNoticeSink: (IntakeSource, FailureReason) -> Unit,
) {
    /**
     * Android entry point: URI access, decoding and OCR all come from [context].
     * Everything after OCR is free of Android types, so tests build the
     * coordinator from a store and a fake diagnostics sink instead.
     */
    constructor(ctx: Context) : this(
        context = ctx,
        store = CaptureCandidateStore(ctx),
        repositoryProvider = { androidRepositoryOrNull(ctx) },
        diagnosticSink = { step -> androidDiagnostics(ctx)?.record(step.copy(callSite = step.callSite ?: CALL_SITE_INGEST)) },
        ledgerWriter = null,
        decodeOverride = null,
        failureNoticeSink = { source, reason ->
            if (source == IntakeSource.MEDIASTORE) {
                ScreenshotIntakeFailureNoticeQueue.markPending(ctx, source, reason)
            }
        },
    )

    internal constructor(
        store: CaptureCandidateStore,
        repositoryProvider: () -> TransactionRepository? = { null },
        diagnosticSink: suspend (DiagnosticStep) -> Unit = {},
        ledgerWriter: ScreenshotLedgerWriter? = null,
        decodeOverride: ((ByteArray) -> Bitmap?)? = null,
        failureNoticeSink: (IntakeSource, FailureReason) -> Unit = { _, _ -> },
    ) : this(null, store, repositoryProvider, diagnosticSink, ledgerWriter, decodeOverride, failureNoticeSink)

    data class Outcome(
        val booked: Int = 0,
        val pending: Int = 0,
        val waiting: Int = 0,
        val linked: Int = 0,
        val ignored: Int = 0,
        /** Subset of [ignored]: the ledger already had this payment (a real duplicate). */
        val duplicates: Int = 0,
        /**
         * Candidates whose ledger or candidate write failed. They stay PENDING and
         * retryable, so [pending] includes them; a failure is never counted as
         * [ignored].
         */
        val failed: Int = 0,
        /** Waiting candidates this import settled with later evidence. */
        val superseded: Int = 0,
        val failure: FailureReason? = null,
        val repeatImage: Boolean = false,
        val autoBookAllowed: Boolean = true,
        val confirmationCandidates: List<CaptureCandidate> = emptyList(),
        val bookedTransactionIds: List<Long> = emptyList(),
    ) {
        val handled: Int get() = booked + pending + waiting + linked
    }

    enum class ConfirmationResult { SAVED, ALREADY_EXISTS, ALREADY_HANDLED, NOT_WRITABLE, FAILED }

    private val ocrMutex = Mutex()
    @Volatile private var ocrEngine: OcrEngine? = null

    suspend fun ingest(
        uri: Uri,
        source: IntakeSource,
        capturedAtMs: Long = System.currentTimeMillis(),
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
    ): Outcome {
        val resolver = requireContext().contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
        val request = ScreenshotIntakeRequest(
            source = source,
            uri = uri.toString(),
            mimeType = mime,
            capturedAtMs = capturedAtMs,
            readable = true,
            byteSize = size,
        )
        return ingestRequest(request, allowAuto, previewOnly) {
            runCatching { resolver.openInputStream(uri) }.getOrNull()
        }
    }

    /**
     * Validate, read, decode and recognise one intake, then hand the page to
     * [ingestBlocks]. Android-free except for decoding and OCR.
     *
     * The attempt id is created before validation: every rejection below writes
     * one failure diagnostic, so a screenshot that never reaches the parser can
     * still be traced. The write is best effort and never changes the outcome.
     */
    internal suspend fun ingestRequest(
        request: ScreenshotIntakeRequest,
        allowAuto: Boolean,
        previewOnly: Boolean,
        openStream: () -> InputStream?,
    ): Outcome {
        val attemptId = TransactionDiagnostics.newAttemptId()
        val autoBookAllowed = allowAuto && !previewOnly
        val inputSummary = intakeSummary(request)
        val validation = ScreenshotImportPipeline.validate(request)
        if (validation is IntakeValidation.Rejected) {
            recordIntakeFailure(attemptId, request.source, validation.reason, inputSummary)
            return Outcome(failure = validation.reason, autoBookAllowed = autoBookAllowed)
        }

        val boundedRead = runCatching {
            ScreenshotBoundedReader.read(openStream(), ScreenshotImportPipeline.MAX_IMAGE_BYTES)
        }.getOrNull()
        if (boundedRead == null) {
            recordIntakeFailure(attemptId, request.source, FailureReason.UNREADABLE, inputSummary)
            return Outcome(failure = FailureReason.UNREADABLE, autoBookAllowed = autoBookAllowed)
        }
        val bytes = when (boundedRead) {
            is ScreenshotBoundedReader.Result.Complete -> boundedRead.bytes
            ScreenshotBoundedReader.Result.Oversized -> {
                recordIntakeFailure(attemptId, request.source, FailureReason.OVERSIZED, inputSummary)
                return Outcome(failure = FailureReason.OVERSIZED, autoBookAllowed = autoBookAllowed)
            }
        }
        if (bytes.isEmpty()) {
            recordIntakeFailure(attemptId, request.source, FailureReason.UNREADABLE, inputSummary)
            return Outcome(failure = FailureReason.UNREADABLE, autoBookAllowed = autoBookAllowed)
        }
        val decoded = runCatching {
            if (decodeOverride == null) decode(bytes) else decodeOverride.invoke(bytes)
        }.getOrNull()
        if (decoded == null) {
            recordIntakeFailure(attemptId, request.source, FailureReason.DECODE_FAILED, inputSummary)
            return Outcome(failure = FailureReason.DECODE_FAILED, autoBookAllowed = autoBookAllowed)
        }
        val ocr = runCatching { recognize(decoded) }.getOrNull()
        decoded.recycle()
        if (ocr == null || ocr.isEmpty) {
            recordIntakeFailure(attemptId, request.source, FailureReason.OCR_EMPTY, inputSummary)
            return Outcome(failure = FailureReason.OCR_EMPTY, autoBookAllowed = autoBookAllowed)
        }
        val blocks = ocr.lines.mapNotNull { line ->
            val bounds = line.bounds
            line.text.takeIf { it.isNotBlank() }?.let {
                TextBlock(
                    text = it,
                    left = bounds?.left ?: 0,
                    top = bounds?.top ?: 0,
                    right = bounds?.right ?: 0,
                    bottom = bounds?.bottom ?: 0,
                    hasBounds = bounds != null,
                )
            }
        }
        // Verbatim OCR text in reading order, one line per block. Stored because
        // "截图没记上" is only diagnosable with what the page actually said; the
        // image itself stays in its own store. Bounded by DiagnosticRawText, not
        // by the 160-char summary clip.
        val pageRawText = DiagnosticRawText.ocrLines(blocks.toRawLines())
        // Counts plus the payload: how many text blocks came back and what they said.
        recordStep(
            attemptId = attemptId,
            stage = TransactionDiagnostics.STAGE_CAPTURE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            details = "OCR payment-page capture started",
            callSite = CALL_SITE_INGEST,
            inputSummary = inputSummary,
            outputSummary = "OCR ${blocks.size} 个文本块",
            rawText = pageRawText,
        )
        return ingestBlocks(
            request = request,
            blocks = blocks,
            bytes = bytes,
            fingerprint = sha256(bytes),
            attemptId = attemptId,
            allowAuto = allowAuto,
            previewOnly = previewOnly,
            pageRawText = pageRawText,
        )
    }

    /** Admit the recognised page and apply each decision to the candidate store. */
    internal suspend fun ingestBlocks(
        request: ScreenshotIntakeRequest,
        blocks: List<TextBlock>,
        bytes: ByteArray?,
        fingerprint: String,
        attemptId: String,
        allowAuto: Boolean,
        previewOnly: Boolean,
        /**
         * Verbatim OCR text of this page, one line per block in reading order.
         * Null for callers that only hold the blocks; the text is then derived
         * from [blocks] so the recorded chain still carries the original input.
         */
        pageRawText: String? = null,
    ): Outcome {
        val source = request.source
        val capturedAtMs = request.capturedAtMs
        val rawText = pageRawText ?: DiagnosticRawText.ocrLines(blocks.toRawLines())
        val repository = repositoryProvider()
        // One write path for AUTO_BOOK. The repository result says whether the
        // money landed, was already there (and why), or the write failed; null
        // (no repository, no fake) keeps the old "hold for review" behaviour.
        val insertLedger: ScreenshotLedgerWriter? = ledgerWriter
            ?: repository?.let { repo ->
                object : ScreenshotLedgerWriter {
                    override suspend fun insert(
                        entity: TransactionEntity,
                        attemptId: String,
                    ): LedgerInsertResult = repo.insertIfNotDuplicateResult(
                        transaction = entity,
                        notifySuccess = false,
                        crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                        diagnosticInput = TransactionDiagnosticInput(
                            attemptId = attemptId,
                            callSite = CALL_SITE_INGEST,
                        ),
                    )

                    override suspend fun confirm(
                        entity: TransactionEntity,
                        attemptId: String,
                    ): LedgerInsertResult = repo.insertIfNotDuplicateResult(
                        transaction = entity,
                        notifySuccess = false,
                        crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                        // 用户已经确认这笔要记：命中的跨通道同一笔直接补全字段，
                        // 不再只回一句「已在账本中」。
                        mergeConfirmedCrossSource = true,
                        diagnosticInput = TransactionDiagnosticInput(
                            attemptId = attemptId,
                            callSite = "ScreenshotIntakeCoordinator.confirmCandidate",
                        ),
                    )
                }
            }
        val ledgerRows = runCatching { repository?.screenshotDedupRows() }.getOrNull().orEmpty()
        val existing = ledgerRows
        // The review inbox is intentionally capped at 80 visible candidates,
        // but image idempotency follows the store's retention window. Looking
        // up this exact fingerprint also keeps ignored/restored cards reserved
        // until their evidence expires.
        val fingerprintLookup = runCatching {
            store.lookupImageFingerprint(fingerprint)
        }.getOrElse {
            ImageFingerprintLookup.Unavailable("fingerprint index lookup failed")
        }
        val previousFingerprints = if (fingerprintLookup == ImageFingerprintLookup.Match) {
            setOf(fingerprint)
        } else {
            emptySet()
        }

        val initialPlan = ScreenshotImportPipeline.plan(
            request = request.copy(imageFingerprint = fingerprint),
            blocks = blocks,
            existing = existing.map { it.toFingerprint() },
            previousImageFingerprints = previousFingerprints,
            allowAuto = allowAuto,
            previewOnly = previewOnly,
            // 层 1：只有没写截图 provenance 的行才算「另一条通道」的候选
            // （通知 / 短信 / 无障碍 / 手工记账都不写 captureObservedAtMs）。
            crossChannelRows = ledgerRows
                .filter { it.captureObservedAtMs == null }
                .map { it.toFingerprint() },
        )
        // Fingerprint availability describes only the local screenshot replay
        // index, not transaction certainty. Keep the page decision unchanged:
        // the ledger writer below still performs its repository duplicate gate,
        // and a writer-reported collision keeps the candidate reviewable.
        val plan = initialPlan
        val ocrSummary = "OCR ${blocks.size} 个文本块"
        plan.failure?.let { failure ->
            // A recognised but empty page still records a local diagnostic row;
            // no ledger write and no user-visible noise. The OCR text rides along
            // because a page that parsed to nothing is exactly the case a user
            // reports as "it did not record".
            recordIntakeFailure(attemptId, source, failure, ocrSummary, rawText)
            return Outcome(
                failure = failure,
                repeatImage = plan.repeatImage,
                autoBookAllowed = allowAuto && !previewOnly,
            )
        }
        if (plan.repeatImage) {
            recordStep(
                attemptId = attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_WARNING,
                details = "Recent page-capture fingerprint suppressed",
                callSite = CALL_SITE_INGEST,
                inputSummary = ocrSummary,
                outputSummary = "同一张截图已处理过，本次未写入",
            )
            return Outcome(repeatImage = true, autoBookAllowed = allowAuto && !previewOnly)
        }

        val drafts = plan.decisions.map { it.draft }
        val firstDraft = drafts.firstOrNull()
        val candidateSummary = firstDraft?.let { draft ->
            DiagnosticSummary.amountWithMerchant(
                amount = DiagnosticSummary.amount(
                    type = draft.direction,
                    amountCents = draft.amountCents ?: 0L,
                    currency = draft.currency,
                ),
                merchant = draft.merchantCandidates.firstOrNull(),
            )
        }
        recordStep(
            attemptId = attemptId,
            stage = TransactionDiagnostics.STAGE_PARSE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            details = "Payment page parsed",
            callSite = CALL_SITE_INGEST,
            inputSummary = ocrSummary,
            outputSummary = listOfNotNull(
                "${drafts.size} 笔候选",
                candidateSummary,
            ).joinToString(" · "),
            rawText = rawText,
        )

        val matched = plan.decisions.mapNotNull { it.matchedTransactionId }
        recordStep(
            attemptId = attemptId,
            stage = TransactionDiagnostics.STAGE_DEDUP,
            result = when {
                matched.isNotEmpty() -> TransactionDiagnostics.RESULT_WARNING
                fingerprintLookup is ImageFingerprintLookup.Unavailable ->
                    TransactionDiagnostics.RESULT_WARNING
                else -> TransactionDiagnostics.RESULT_SUCCESS
            },
            details = when {
                matched.isNotEmpty() -> "Exact duplicate candidate skipped"
                fingerprintLookup is ImageFingerprintLookup.Unavailable ->
                    "Image fingerprint lookup unavailable; ledger duplicate checks remain active"
                else -> "No duplicate found"
            },
            callSite = CALL_SITE_INGEST,
            inputSummary = candidateSummary ?: ocrSummary,
            outputSummary = when {
                matched.isNotEmpty() -> "命中已有 ${matched.joinToString("、") { "#$it" }}，跳过 ${matched.size} 笔"
                fingerprintLookup is ImageFingerprintLookup.Unavailable ->
                    "截图指纹索引不可用；仍由账本写入层执行查重"
                else -> "未命中已有账单"
            },
            rawText = rawText,
        )

        var booked = 0
        var pending = 0
        val bookedIds = mutableListOf<Long>()
        val confirmationCandidates = mutableListOf<CaptureCandidate>()
        var waiting = 0
        var linked = 0
        var ignored = 0
        var duplicates = 0
        var failed = 0
        var superseded = 0
        // Preview / allowAuto=false must not touch anything: a waiting card is
        // only closed once this import produced a real outcome for its own card.
        val settleAllowed = CaptureBookGate.mayWriteLedger(allowAuto, previewOnly)
        // Cards created by this import are never "later evidence": a sibling on
        // the same page must not settle a placeholder this run just stored.
        val createdHere = mutableSetOf<String>()
        val waitingToSettle = linkedMapOf<String, CaptureCandidate>()
        plan.decisions.forEach { decision ->
            val candidateDraft = ScreenshotCandidateFactory.create(
                decision = decision,
                source = source,
                capturedAtMs = capturedAtMs,
                imageFingerprint = fingerprint,
                diagnosticAttemptId = attemptId,
            )
            val candidate = if (bytes == null) {
                candidateDraft.copy(
                    screenshotUnavailableReason = CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_MISSING,
                )
            } else {
                candidateDraft
            }
            val stored = when (val save = store.saveWithResult(candidate, bytes)) {
                is CandidateSaveResult.Saved -> save.candidate
                is CandidateSaveResult.Failed -> {
                    // A full review index is a storage failure, never a duplicate
                    // or ignored transaction. Preserve the explicit reason for
                    // diagnostics and tell the user how to retry after expiry.
                    failed++
                    val indexFull = save.reason == CandidateSaveFailure.INDEX_FULL
                    recordWriteFailure(
                        attemptId = attemptId,
                        code = if (indexFull) "CANDIDATE_INDEX_FULL" else "CANDIDATE_STORE_WRITE_FAILED",
                        cause = null,
                        inputSummary = candidateSummary ?: ocrSummary,
                        rawText = rawText,
                        outputSummary = if (indexFull) "候选存储已满，本笔候选未保存" else "候选卡片未保存",
                    )
                    if (indexFull) context?.let {
                        ScreenshotReviewNotifier.notifyCandidateStorageFull(it, CandidateStorageIntakeKind.SCREENSHOT_PAGE)
                    }
                    return@forEach
                }
            }
            createdHere += stored.candidateId
            val screenshotReadable = store.hasReadableScreenshot(stored, bytes)
            val reviewCandidate = if (!screenshotReadable) {
                stored.copy(
                    screenshotUnavailableReason = stored.screenshotUnavailableReason ?:
                        if (bytes == null) CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_MISSING
                        else CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_IO,
                )
            } else {
                stored
            }
            if (!screenshotReadable) {
                val persisted = store.mark(reviewCandidate, CaptureCandidate.STATUS_PENDING)
                pending++
                if (!persisted) {
                    failed++
                    recordWriteFailure(
                        attemptId = attemptId,
                        code = "SCREENSHOT_EVIDENCE_WARNING_WRITE_FAILED",
                        cause = null,
                        inputSummary = candidateSummary ?: ocrSummary,
                        rawText = rawText,
                        outputSummary = "截图不可用，本笔保持待确认；账本未写入",
                    )
                }
                recordStep(
                    attemptId = attemptId,
                    stage = TransactionDiagnostics.STAGE_SAVE,
                    result = TransactionDiagnostics.RESULT_WARNING,
                    details = "Screenshot evidence unavailable; candidate kept pending",
                    callSite = CALL_SITE_INGEST,
                    outputSummary = "截图未能验证，本笔已保留待确认；请重新导入截图并核对后再记录",
                )
                val pendingCandidate = store.pending().lastOrNull { it.candidateId == reviewCandidate.candidateId }
                    ?: reviewCandidate.copy(status = CaptureCandidate.STATUS_PENDING)
                if (source != IntakeSource.MEDIASTORE) confirmationCandidates += pendingCandidate
                return@forEach
            }
            var applied = false
            when (decision.action) {
                Action.AUTO_BOOK -> {
                    if (!CaptureBookGate.mayWriteLedger(allowAuto, previewOnly)) {
                        store.mark(reviewCandidate, CaptureCandidate.STATUS_PENDING)
                        pending++
                        return@forEach
                    }
                    insertLedger?.let { writer ->
                        val merchant = stored.merchantCandidates.firstOrNull().orEmpty()
                        val evidenceImageName = context?.let {
                            ReceiptEvidenceStore(it).archiveFromPath(stored.screenshotPath)
                        }
                        val entity = CaptureReviewMapping.toTransactionEntity(
                            candidate = stored,
                            merchant = merchant,
                            category = CaptureReviewMapping.classificationFor(
                                candidate = stored,
                                merchant = merchant,
                                contextText = merchant,
                            ).category,
                            evidenceImageName = evidenceImageName,
                        )
                        // Keep the ledger write inside this chain: same attempt id
                        // so the log reads capture → parse → dedup → save as one run.
                        // The result is explicit; a thrown write is folded into
                        // Failed instead of being flattened into "duplicate".
                        val result = try {
                            writer.insert(entity, attemptId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            LedgerInsertResult.Failed(failure)
                        }
                        when (result) {
                            is LedgerInsertResult.Inserted -> {
                                store.mark(stored, CaptureCandidate.STATUS_APPROVED)
                                booked++
                                bookedIds += result.transactionId
                                applied = true
                            }
                            is LedgerInsertResult.Merged -> {
                                // Folded into an existing row: no new row, but the
                                // payment is recorded. Unchanged from before.
                                store.mark(stored, CaptureCandidate.STATUS_APPROVED)
                                booked++
                                bookedIds += result.transactionId
                                applied = true
                            }
                            is LedgerInsertResult.Duplicate -> {
                                when (result.reason) {
                                    // Every cross-channel hit is a heuristic, weak
                                    // evidence included: the other channel's row can
                                    // be a different payment. Keep the card for the
                                    // user; never let a new reason fall through to
                                    // "ignored" just because nobody updated this
                                    // branch.
                                    DuplicateReason.CROSS_SOURCE,
                                    DuplicateReason.CROSS_SOURCE_WEAK -> {
                                        store.mark(stored, CaptureCandidate.STATUS_PENDING)
                                        pending++
                                        recordStep(
                                            attemptId = attemptId,
                                            stage = TransactionDiagnostics.STAGE_DEDUP,
                                            result = TransactionDiagnostics.RESULT_WARNING,
                                            details = "Cross-channel match kept for confirmation (${result.reason.name})",
                                            callSite = CALL_SITE_INGEST,
                                            inputSummary = candidateSummary ?: ocrSummary,
                                            outputSummary = "疑似 #${result.existingTransactionId}（另一通道），保留待确认",
                                        )
                                    }
                                    // A non-empty platform order ID is the only
                                    // ledger evidence strong enough to close this card.
                                    DuplicateReason.SOURCE_ORDER_ID -> {
                                        store.mark(stored, CaptureCandidate.STATUS_IGNORED)
                                        ignored++
                                        duplicates++
                                        recordStep(
                                            attemptId = attemptId,
                                            stage = TransactionDiagnostics.STAGE_DEDUP,
                                            result = TransactionDiagnostics.RESULT_WARNING,
                                            details = "Duplicate confirmed by ledger (${result.reason.name})",
                                            callSite = CALL_SITE_INGEST,
                                            inputSummary = candidateSummary ?: ocrSummary,
                                            outputSummary = "已存在 #${result.existingTransactionId}，跳过",
                                        )
                                    }
                                }
                            }
                            is LedgerInsertResult.Failed -> {
                                // Never IGNORED: the card stays retryable and the
                                // real exception goes to diagnostics.
                                store.mark(stored, CaptureCandidate.STATUS_PENDING)
                                pending++
                                failed++
                                recordWriteFailure(
                                    attemptId = attemptId,
                                    code = "LEDGER_WRITE_FAILED",
                                    cause = result.cause,
                                    inputSummary = candidateSummary ?: ocrSummary,
                                    rawText = rawText,
                                )
                            }
                        }
                    } ?: run {
                        // No repository (Hilt unavailable): the card is still
                        // offered for review, so it counts as a real outcome.
                        store.mark(stored, CaptureCandidate.STATUS_PENDING)
                        pending++
                        applied = true
                    }
                }
                Action.ASK_USER -> {
                    store.mark(reviewCandidate, CaptureCandidate.STATUS_PENDING)
                    pending++
                    applied = true
                    if (source != IntakeSource.MEDIASTORE) confirmationCandidates += stored
                }
                Action.WAIT_FOR_EVIDENCE -> {
                    store.mark(stored, CaptureCandidate.STATUS_WAITING)
                    waiting++
                }
                Action.LINK_EXISTING -> {
                    store.mark(stored, CaptureCandidate.STATUS_LINKED)
                    linked++
                }
                Action.IGNORE -> {
                    store.mark(stored, CaptureCandidate.STATUS_IGNORED)
                    ignored++
                }
            }
            // Only evidence that became its own written or reviewable item may
            // close an earlier placeholder. Ignored or waiting cards, failed
            // inserts and preview runs change nothing.
            if (applied && settleAllowed && ScreenshotWaitingCorrelation.isSettlingEvidence(stored)) {
                runCatching { store.relatedWaiting(stored, capturedAtMs) }
                    .getOrNull()
                    ?.takeIf { it.candidateId !in createdHere }
                    ?.let { waitingToSettle[it.candidateId] = it }
            }
        }
        waitingToSettle.values.forEach { settled ->
            if (store.mark(settled, CaptureCandidate.STATUS_LINKED)) superseded++
        }
        if (pending > 0 && source == IntakeSource.MEDIASTORE) {
            context?.let { runCatching { ScreenshotReviewNotifier.notifyPending(it, store.pending()) } }
        }
        recordStep(
            attemptId = attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = when {
                failed > 0 -> TransactionDiagnostics.RESULT_WARNING
                booked > 0 || pending > 0 || waiting > 0 || linked > 0 -> TransactionDiagnostics.RESULT_SUCCESS
                else -> TransactionDiagnostics.RESULT_WARNING
            },
            details = "Screenshot intake applied",
            transactionId = bookedIds.firstOrNull(),
            callSite = CALL_SITE_INGEST,
            inputSummary = "${plan.decisions.size} 笔候选",
            outputSummary = buildString {
                val counts = mutableListOf(
                    "入账" to booked,
                    "待确认" to pending,
                    "等待" to waiting,
                    "跳过" to ignored + linked,
                )
                if (failed > 0) counts += "失败" to failed
                append(DiagnosticSummary.counts(*counts.toTypedArray()))
                if (!CaptureBookGate.mayWriteLedger(allowAuto, previewOnly)) append(" · 预览模式，未写入账本")
            },
            rawText = rawText,
        )
        return Outcome(
            booked = booked,
            pending = pending,
            waiting = waiting,
            linked = linked,
            ignored = ignored,
            duplicates = duplicates,
            failed = failed,
            superseded = superseded,
            autoBookAllowed = allowAuto && !previewOnly,
            confirmationCandidates = confirmationCandidates,
            bookedTransactionIds = bookedIds.toList(),
        )
    }

    suspend fun confirmCandidate(candidate: CaptureCandidate): ConfirmationResult {
        if (!CaptureBookGate.mayConfirm(candidate)) return ConfirmationResult.NOT_WRITABLE
        val current = store.pending().firstOrNull { it.candidateId == candidate.candidateId }
            ?: return ConfirmationResult.ALREADY_HANDLED
        if (!CaptureBookGate.mayConfirm(current)) return ConfirmationResult.NOT_WRITABLE
        // 生产走 repository；JVM 测试通过 ledgerWriter 走同一套确认语义。
        val writer = ledgerWriter
        val repository = if (writer == null) repositoryProvider() else null
        val merchant = current.merchantCandidates.firstOrNull().orEmpty()
        val category = CaptureReviewMapping.classificationFor(
            candidate = current,
            merchant = merchant,
            contextText = merchant,
        ).category
        val evidenceImageName = context?.let {
            ReceiptEvidenceStore(it).archiveFromPath(current.screenshotPath)
        }
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate = current,
            merchant = merchant,
            category = category,
            evidenceImageName = evidenceImageName,
        )
        val attemptId = current.diagnosticAttemptId ?: TransactionDiagnostics.newAttemptId()
        val result = try {
            when {
                writer != null -> writer.confirm(entity, attemptId)
                repository != null -> repository.insertIfNotDuplicateResult(
                    transaction = entity,
                    notifySuccess = false,
                    diagnosticInput = TransactionDiagnosticInput(
                        attemptId = attemptId,
                        callSite = "ScreenshotIntakeCoordinator.confirmCandidate",
                    ),
                    crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                    // 用户已经确认「这笔要记」：跨通道命中的同一笔直接补全字段，
                    // 不新增行，也不再只回一句「已在账本中」。弱相似度不足以证明重复，
                    // 用户确认后按独立新行写入。
                    holdWeakCrossSourceMatch = true,
                    mergeConfirmedCrossSource = true,
                )
                else -> return ConfirmationResult.FAILED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LedgerInsertResult.Failed(failure)
        }
        return when (result) {
            is LedgerInsertResult.Inserted, is LedgerInsertResult.Merged -> {
                store.mark(current, CaptureCandidate.STATUS_APPROVED)
                ConfirmationResult.SAVED
            }
            is LedgerInsertResult.Duplicate -> {
                if (result.reason == DuplicateReason.CROSS_SOURCE ||
                    result.reason == DuplicateReason.CROSS_SOURCE_WEAK
                ) {
                    // 仓库已经先试过合并；走到这里说明两条记录不可能合成一条
                    // （商户矛盾、订单号矛盾、超出 120 秒窗口、对侧不可合并）。
                    // 跨通道只是启发式；如果仓库仍返回该结果，保留候选 PENDING
                    // 并写明疑似匹配的那条。
                    recordStep(
                        attemptId = attemptId,
                        stage = TransactionDiagnostics.STAGE_DEDUP,
                        result = TransactionDiagnostics.RESULT_WARNING,
                        details = "Cross-channel match could not be merged; kept for confirmation",
                        callSite = "ScreenshotIntakeCoordinator.confirmCandidate",
                        inputSummary = DiagnosticSummary.amountWithMerchant(
                            amount = DiagnosticSummary.amount(
                                type = current.direction,
                                amountCents = current.amountCents ?: 0L,
                            ),
                            merchant = merchant,
                        ),
                        outputSummary = "疑似 #${result.existingTransactionId}（另一通道，无法合并），保留待确认",
                    )
                    ConfirmationResult.ALREADY_EXISTS
                } else {
                    store.mark(current, CaptureCandidate.STATUS_IGNORED)
                    ConfirmationResult.ALREADY_EXISTS
                }
            }
            is LedgerInsertResult.Failed -> {
                // Card stays PENDING; the real exception is now traceable.
                recordWriteFailure(
                    attemptId = attemptId,
                    code = "LEDGER_WRITE_FAILED",
                    cause = result.cause,
                    callSite = "ScreenshotIntakeCoordinator.confirmCandidate",
                )
                ConfirmationResult.FAILED
            }
        }
    }

    fun ignoreCandidate(candidate: CaptureCandidate): Boolean {
        val current = store.pending().firstOrNull { it.candidateId == candidate.candidateId } ?: return false
        return store.mark(current, CaptureCandidate.STATUS_IGNORED)
    }

    fun close() {
        ocrEngine?.close()
        ocrEngine = null
    }

    /**
     * Best effort: a diagnostic write must never block, change or fail capture.
     * Failures keep the stable reason code in [DiagnosticStep.details] and add
     * only a structured input summary (image size, block count) — no page text.
     */
    private suspend fun recordFailure(
        attemptId: String,
        failure: FailureReason,
        inputSummary: String? = null,
        rawText: String? = null,
    ) {
        val note = ScreenshotImportDiagnostics.failureNote(attemptId, failure)
        recordStep(
            attemptId = note.attemptId,
            stage = note.stage,
            result = note.result,
            details = note.details,
            callSite = CALL_SITE_INGEST,
            inputSummary = inputSummary,
            rawText = rawText,
        )
    }

    private suspend fun recordIntakeFailure(
        attemptId: String,
        source: IntakeSource,
        failure: FailureReason,
        inputSummary: String? = null,
        rawText: String? = null,
    ) {
        recordFailure(attemptId, failure, inputSummary, rawText)
        runCatching { failureNoticeSink(source, failure) }
    }

    /**
     * A recognised candidate could not be written. This is never a "skip": the
     * candidate is kept (or reported as not stored), so diagnostics must carry
     * what actually broke — a stable code plus the real exception class and
     * message, clipped, and never page content.
     */
    private suspend fun recordWriteFailure(
        attemptId: String,
        code: String,
        cause: Throwable?,
        callSite: String = CALL_SITE_INGEST,
        inputSummary: String? = null,
        rawText: String? = null,
        outputSummary: String = "写入失败，候选保留待确认",
    ) {
        val details = if (cause == null) {
            code
        } else {
            "$code · ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
        }
        recordStep(
            attemptId = attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_FAILURE,
            details = DiagnosticSummary.clip(details).orEmpty(),
            callSite = callSite,
            inputSummary = inputSummary,
            outputSummary = outputSummary,
            rawText = rawText,
        )
    }

    private suspend fun recordStep(
        attemptId: String,
        stage: String,
        result: String,
        details: String = "",
        transactionId: Long? = null,
        callSite: String? = CALL_SITE_INGEST,
        inputSummary: String? = null,
        outputSummary: String? = null,
        rawText: String? = null,
    ) {
        val step = DiagnosticStep(
            attemptId = attemptId,
            stage = stage,
            result = result,
            details = details,
            transactionId = transactionId,
            callSite = callSite,
            inputSummary = inputSummary,
            outputSummary = outputSummary,
            rawText = rawText,
        )
        try {
            diagnosticSink(step)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics must never fail an import.
        }
    }

    /** "截图 1 张 · 400 KB · IMAGE_JPEG" — size and type, never the pixels. */
    private fun intakeSummary(request: ScreenshotIntakeRequest): String = listOfNotNull(
        "截图 1 张",
        request.byteSize?.takeIf { it > 0L }?.let { bytes -> "${bytes / 1024} KB" },
        request.mimeType?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

    /**
     * The data layer keeps no dependency on the capture package, so the OCR
     * blocks are handed over as [RawLine] value objects. Coordinates are only
     * used for ordering; `hasBounds` keeps a missing box from sorting to the top.
     */
    private fun List<TextBlock>.toRawLines(): List<RawLine> = map { block ->
        RawLine(
            text = block.text,
            top = block.top,
            left = block.left,
            hasBounds = block.hasBounds,
        )
    }

    private fun requireContext(): Context =
        checkNotNull(context) { "URI intake and OCR need an Android context" }

    private suspend fun recognize(bitmap: Bitmap) = ocrMutex.withLock {
        val engine = ocrEngine ?: PaddleOcrEngine(
            requireContext(),
            fallback = MlKitOcrEngine(),
        ).also { ocrEngine = it }
        engine.recognize(bitmap)
    }

    @Suppress("DEPRECATION")
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val upright = if (rotation == 0f) original else Bitmap.createBitmap(
            original, 0, 0, original.width, original.height,
            Matrix().apply { postRotate(rotation) }, true,
        ).also { if (it != original) original.recycle() }
        return upright
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun TransactionEntity.toFingerprint() = LedgerFingerprint(
        transactionId = id,
        platform = platformFor(source),
        amountCents = amountCents,
        direction = if (type == "INCOME") CaptureCandidate.DIRECTION_INCOME else CaptureCandidate.DIRECTION_EXPENSE,
        orderId = sourceTransactionId,
        merchant = merchant,
        eventTimeMs = timestamp,
        currency = currency,
    )

    private fun platformFor(source: String): String = when (source) {
        "WECHAT" -> CaptureCandidate.PLATFORM_WECHAT
        "ALIPAY" -> CaptureCandidate.PLATFORM_ALIPAY
        else -> CaptureCandidate.PLATFORM_UNKNOWN
    }

    companion object {
        /** Dotted entry point shown under each step; also the chain's entry kind. */
        const val CALL_SITE_INGEST = "ScreenshotIntakeCoordinator.ingest"
        private const val MAX_EDGE = 1600
    }
}

/**
 * Resolved outside the delegating constructor call on purpose: Kotlin refuses
 * `runCatching { ... }` lambdas inside a `this(...)` delegation because the
 * implicit receiver is not initialized yet ("Cannot access '<this>' before the
 * instance has been initialized").
 */
private fun importEntryPoint(context: Context): ScreenshotImportEntryPoint =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ScreenshotImportEntryPoint::class.java,
    )

private fun androidRepositoryOrNull(context: Context): TransactionRepository? =
    runCatching { importEntryPoint(context).transactionRepository() }.getOrNull()

private fun androidDiagnostics(context: Context): TransactionDiagnostics? =
    runCatching { importEntryPoint(context).transactionDiagnostics() }.getOrNull()

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScreenshotImportEntryPoint {
    fun transactionRepository(): TransactionRepository
    fun transactionDiagnostics(): TransactionDiagnostics
}
