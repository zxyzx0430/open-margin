package com.bookkeeping.app.capture.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import com.bookkeeping.app.ocr.MlKitOcrEngine
import com.bookkeeping.app.ocr.OcrEngine
import com.bookkeeping.app.ocr.PaddleOcrEngine
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android glue for every screenshot intake. It owns URI access, decoding and
 * OCR; all admission decisions come from [ScreenshotImportPipeline] so the three
 * entry points cannot drift apart.
 */
class ScreenshotIntakeCoordinator(
    private val context: Context,
    private val store: CaptureCandidateStore = CaptureCandidateStore(context),
    private val repositoryProvider: () -> TransactionRepository? = {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ScreenshotImportEntryPoint::class.java,
            ).transactionRepository()
        }.getOrNull()
    },
) {
    data class Outcome(
        val booked: Int = 0,
        val pending: Int = 0,
        val waiting: Int = 0,
        val linked: Int = 0,
        val ignored: Int = 0,
        val failure: FailureReason? = null,
        val repeatImage: Boolean = false,
        val autoBookAllowed: Boolean = true,
    ) {
        val handled: Int get() = booked + pending + waiting + linked
    }

    private val ocrMutex = Mutex()
    @Volatile private var ocrEngine: OcrEngine? = null

    suspend fun ingest(
        uri: Uri,
        source: IntakeSource,
        capturedAtMs: Long = System.currentTimeMillis(),
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
    ): Outcome {
        val resolver = context.contentResolver
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
        val validation = ScreenshotImportPipeline.validate(request)
        if (validation is IntakeValidation.Rejected) {
            return Outcome(failure = validation.reason, autoBookAllowed = allowAuto && !previewOnly)
        }

        val bytes = runCatching { readBounded(resolver.openInputStream(uri)) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return Outcome(failure = FailureReason.UNREADABLE, autoBookAllowed = allowAuto && !previewOnly)
        }
        val fingerprint = sha256(bytes)
        val decoded = runCatching { decode(bytes) }.getOrNull()
        if (decoded == null) {
            return Outcome(failure = FailureReason.DECODE_FAILED, autoBookAllowed = allowAuto && !previewOnly)
        }
        val ocr = runCatching { recognize(decoded) }.getOrNull()
        decoded.recycle()
        if (ocr == null || ocr.isEmpty) {
            return Outcome(failure = FailureReason.OCR_EMPTY, autoBookAllowed = allowAuto && !previewOnly)
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
                )
            }
        }
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
            return Outcome(
                failure = failure,
                repeatImage = plan.repeatImage,
                autoBookAllowed = allowAuto && !previewOnly,
            )
        }
        if (plan.repeatImage) {
            return Outcome(repeatImage = true, autoBookAllowed = allowAuto && !previewOnly)
        }

        val attemptId = TransactionDiagnostics.newAttemptId()
        var booked = 0
        var pending = 0
        var waiting = 0
        var linked = 0
        var ignored = 0
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
        if (pending > 0) {
            runCatching { ScreenshotReviewNotifier.notifyPending(context, pending) }
        }
        return Outcome(
            booked = booked,
            pending = pending,
            waiting = waiting,
            linked = linked,
            ignored = ignored,
            autoBookAllowed = allowAuto && !previewOnly,
        )
    }

    fun close() {
        ocrEngine?.close()
        ocrEngine = null
    }

    private suspend fun recognize(bitmap: Bitmap) = ocrMutex.withLock {
        val engine = ocrEngine ?: PaddleOcrEngine(
            context,
            fallback = MlKitOcrEngine(),
        ).also { ocrEngine = it }
        engine.recognize(bitmap)
    }

    private fun readBounded(stream: java.io.InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > ScreenshotImportPipeline.MAX_IMAGE_BYTES) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
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
}
