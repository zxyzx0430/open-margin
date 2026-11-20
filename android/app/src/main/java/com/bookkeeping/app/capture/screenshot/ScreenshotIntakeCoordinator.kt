package com.bookkeeping.app.capture.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
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
 * Android glue for every screenshot intake. It owns URI access, decoding and
 * OCR; all admission decisions come from [ScreenshotImportPipeline] so the three
 * entry points cannot drift apart.
 */
class ScreenshotIntakeCoordinator private constructor(
    private val context: Context?,
    private val store: CaptureCandidateStore,
    private val repositoryProvider: () -> TransactionRepository?,
    private val diagnosticSink: suspend (ScreenshotImportDiagnostics.FailureNote) -> Unit,
) {
    /**
     * Android entry point: URI access, decoding and OCR all come from [context].
     * Everything after OCR is free of Android types, so tests build the
     * coordinator from a store and a fake diagnostics sink instead.
     */
    constructor(context: Context) : this(
        context = context,
        store = CaptureCandidateStore(context),
        repositoryProvider = {
            runCatching {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ScreenshotImportEntryPoint::class.java,
                ).transactionRepository()
            }.getOrNull()
        },
        diagnosticSink = { note ->
            runCatching {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ScreenshotImportEntryPoint::class.java,
                ).transactionDiagnostics()
            }.getOrNull()?.record(
                attemptId = note.attemptId,
                stage = note.stage,
                result = note.result,
                details = note.details,
            )
        },
    )

    internal constructor(
        store: CaptureCandidateStore,
        repositoryProvider: () -> TransactionRepository? = { null },
        diagnosticSink: suspend (ScreenshotImportDiagnostics.FailureNote) -> Unit = {},
    ) : this(null, store, repositoryProvider, diagnosticSink)

    data class Outcome(
        val booked: Int = 0,
        val pending: Int = 0,
        val waiting: Int = 0,
        val linked: Int = 0,
        val ignored: Int = 0,
        /** Waiting candidates this import settled with later evidence. */
        val superseded: Int = 0,
        val failure: FailureReason? = null,
        val repeatImage: Boolean = false,
        val autoBookAllowed: Boolean = true,
        val confirmationCandidates: List<CaptureCandidate> = emptyList(),
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
        val validation = ScreenshotImportPipeline.validate(request)
        if (validation is IntakeValidation.Rejected) {
            recordFailure(attemptId, validation.reason)
            return Outcome(failure = validation.reason, autoBookAllowed = autoBookAllowed)
        }

        val boundedRead = runCatching {
            ScreenshotBoundedReader.read(openStream(), ScreenshotImportPipeline.MAX_IMAGE_BYTES)
        }.getOrNull()
        if (boundedRead == null) {
            recordFailure(attemptId, FailureReason.UNREADABLE)
            return Outcome(failure = FailureReason.UNREADABLE, autoBookAllowed = autoBookAllowed)
        }
        val bytes = when (boundedRead) {
            is ScreenshotBoundedReader.Result.Complete -> boundedRead.bytes
            ScreenshotBoundedReader.Result.Oversized -> {
                recordFailure(attemptId, FailureReason.OVERSIZED)
                return Outcome(failure = FailureReason.OVERSIZED, autoBookAllowed = autoBookAllowed)
            }
        }
        if (bytes.isEmpty()) {
            recordFailure(attemptId, FailureReason.UNREADABLE)
            return Outcome(failure = FailureReason.UNREADABLE, autoBookAllowed = autoBookAllowed)
        }
        val decoded = runCatching { decode(bytes) }.getOrNull()
        if (decoded == null) {
            recordFailure(attemptId, FailureReason.DECODE_FAILED)
            return Outcome(failure = FailureReason.DECODE_FAILED, autoBookAllowed = autoBookAllowed)
        }
        val ocr = runCatching { recognize(decoded) }.getOrNull()
        decoded.recycle()
        if (ocr == null || ocr.isEmpty) {
            recordFailure(attemptId, FailureReason.OCR_EMPTY)
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
        return ingestBlocks(
            request = request,
            blocks = blocks,
            bytes = bytes,
            fingerprint = sha256(bytes),
            attemptId = attemptId,
            allowAuto = allowAuto,
            previewOnly = previewOnly,
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
    ): Outcome {
        val source = request.source
        val capturedAtMs = request.capturedAtMs
        val repository = repositoryProvider()
        val existing = runCatching { repository?.screenshotDedupRows() }.getOrNull().orEmpty()
        val previousFingerprints = runCatching {
            store.latest(MAX_REMEMBERED).mapNotNull { it.imageFingerprint }.toSet()
        }.getOrDefault(emptySet())

        val plan = ScreenshotImportPipeline.plan(
            request = request.copy(imageFingerprint = fingerprint),
            blocks = blocks,
            existing = existing.map { it.toFingerprint() },
            previousImageFingerprints = previousFingerprints,
            allowAuto = allowAuto,
            previewOnly = previewOnly,
        )
        plan.failure?.let { failure ->
            // A recognised but empty page still records a local diagnostic row;
            // no ledger write and no user-visible noise.
            recordFailure(attemptId, failure)
            return Outcome(
                failure = failure,
                repeatImage = plan.repeatImage,
                autoBookAllowed = allowAuto && !previewOnly,
            )
        }
        if (plan.repeatImage) {
            return Outcome(repeatImage = true, autoBookAllowed = allowAuto && !previewOnly)
        }

        var booked = 0
        var pending = 0
        val confirmationCandidates = mutableListOf<CaptureCandidate>()
        var waiting = 0
        var linked = 0
        var ignored = 0
        var superseded = 0
        plan.decisions.forEach { decision ->
            val candidate = ScreenshotCandidateFactory.create(
                decision = decision,
                source = source,
                capturedAtMs = capturedAtMs,
                imageFingerprint = fingerprint,
                diagnosticAttemptId = attemptId,
            )
            val stored = store.save(candidate, bytes)
            if (stored == null) {
                ignored++
                return@forEach
            }
            if (ScreenshotWaitingCorrelation.isSettlingEvidence(stored)) {
                // A later completed page settles the earlier waiting placeholder,
                // so one transaction leaves one actionable item and at most one
                // ledger row. Ambiguous matches return null and change nothing.
                val settled = runCatching {
                    store.relatedWaiting(stored, capturedAtMs)
                }.getOrNull()
                if (settled != null && store.mark(settled, CaptureCandidate.STATUS_LINKED)) {
                    superseded++
                }
            }
            when (decision.action) {
                Action.AUTO_BOOK -> {
                    if (!CaptureBookGate.mayWriteLedger(allowAuto, previewOnly)) {
                        store.mark(stored, CaptureCandidate.STATUS_PENDING)
                        pending++
                        return@forEach
                    }
                    repository?.let { repo ->
                        val merchant = candidate.merchantCandidates.firstOrNull().orEmpty()
                        val entity = CaptureReviewMapping.toTransactionEntity(
                            candidate = candidate,
                            merchant = merchant,
                            category = CaptureReviewMapping.classificationFor(
                                candidate = candidate,
                                merchant = merchant,
                                contextText = merchant,
                            ).category,
                        )
                        val inserted = runCatching {
                            repo.insertIfNotDuplicate(entity, notifySuccess = false)
                        }.getOrNull()
                        if (inserted != null && inserted > 0L) {
                            store.mark(stored, CaptureCandidate.STATUS_APPROVED)
                            booked++
                        } else {
                            store.mark(stored, CaptureCandidate.STATUS_IGNORED)
                            ignored++
                        }
                    } ?: run {
                        store.mark(stored, CaptureCandidate.STATUS_PENDING)
                        pending++
                    }
                }
                Action.ASK_USER -> {
                    store.mark(stored, CaptureCandidate.STATUS_PENDING)
                    pending++
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
        }
        if (pending > 0 && source == IntakeSource.MEDIASTORE) {
            context?.let { runCatching { ScreenshotReviewNotifier.notifyPending(it, store.pending()) } }
        }
        return Outcome(
            booked = booked,
            pending = pending,
            waiting = waiting,
            linked = linked,
            ignored = ignored,
            superseded = superseded,
            autoBookAllowed = allowAuto && !previewOnly,
            confirmationCandidates = confirmationCandidates,
        )
    }

    suspend fun confirmCandidate(candidate: CaptureCandidate): ConfirmationResult {
        if (!CaptureBookGate.mayConfirm(candidate)) return ConfirmationResult.NOT_WRITABLE
        val current = store.pending().firstOrNull { it.candidateId == candidate.candidateId }
            ?: return ConfirmationResult.ALREADY_HANDLED
        if (!CaptureBookGate.mayConfirm(current)) return ConfirmationResult.NOT_WRITABLE
        val repository = repositoryProvider() ?: return ConfirmationResult.FAILED
        val merchant = current.merchantCandidates.firstOrNull().orEmpty()
        val category = CaptureReviewMapping.classificationFor(
            candidate = current,
            merchant = merchant,
            contextText = merchant,
        ).category
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate = current,
            merchant = merchant,
            category = category,
        )
        val inserted = try {
            repository.insertIfNotDuplicate(
                entity,
                notifySuccess = false,
                diagnosticInput = TransactionDiagnosticInput(
                    attemptId = current.diagnosticAttemptId ?: TransactionDiagnostics.newAttemptId(),
                ),
            )
        } catch (_: Exception) {
            return ConfirmationResult.FAILED
        }
        return if (inserted != null && inserted > 0L) {
            store.mark(current, CaptureCandidate.STATUS_APPROVED)
            ConfirmationResult.SAVED
        } else {
            store.mark(current, CaptureCandidate.STATUS_IGNORED)
            ConfirmationResult.ALREADY_EXISTS
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
     * Best effort: a diagnostic write must never block, change or fail capture,
     * and it records only the attempt id, stage and failure code.
     */
    private suspend fun recordFailure(attemptId: String, failure: FailureReason) {
        val note = ScreenshotImportDiagnostics.failureNote(attemptId, failure)
        try {
            diagnosticSink(note)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics must never fail an import.
        }
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
        private const val MAX_EDGE = 1600
        private const val MAX_REMEMBERED = 80
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScreenshotImportEntryPoint {
    fun transactionRepository(): TransactionRepository
    fun transactionDiagnostics(): TransactionDiagnostics
}
