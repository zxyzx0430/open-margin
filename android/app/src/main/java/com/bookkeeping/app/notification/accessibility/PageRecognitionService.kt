package com.bookkeeping.app.notification.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.capture.success.PaymentPage
import com.bookkeeping.app.capture.success.PaymentPageDetector as SuccessPageDetector
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.CrossSourceDuplicatePolicy
import com.bookkeeping.app.data.repository.DuplicateReason
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.ocr.MlKitOcrEngine
import com.bookkeeping.app.ocr.OcrEngine
import com.bookkeeping.app.ocr.PaddleOcrEngine
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import kotlinx.coroutines.withContext
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Page recognition service.
 *
 * It only reads WeChat/Alipay pages, only captures after the page passes the
 * safety checks, and only writes local candidates. Completed cards go through
 * the normal deduplicating repository path; unfinished cards are never booked.
 * It never scrolls the source app.
 *
 * OCR is part of this single capture pipeline, not a separate opt-in feature.
 * The service runs whenever the user enables it in system accessibility.
 *
 * The user-visible entry is the compatibility subclass at
 * com.google.android.accessibility.selecttospeak.SelectToSpeakService.
 */
open class PageRecognitionService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val capturing = AtomicBoolean(false)

    private lateinit var store: CaptureCandidateStore
    private lateinit var repository: TransactionRepository
    private var ocrEngine: OcrEngine? = null

    private var lastPageIdentity: String? = null
    private var lastCaptureAtMs: Long = 0L
    private var lastWindowActivity: String? = null
    private var lastWindowPackage: String? = null
    private val recentCaptureKeys = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                AccessibilityServiceEntryPoint::class.java,
            )
            store = CaptureCandidateStore(applicationContext)
            repository = entryPoint.transactionRepository()
            if (BuildConfig.DEBUG) Log.d(TAG, "PageRecognitionService connected")
        } catch (failure: Exception) {
            Log.e(TAG, "connect failed: ${failure.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "EVT type=${event.eventType} pkg=${event.packageName} capturing=${capturing.get()}")
        }
        try {
            handleEvent(event)
        } catch (failure: Exception) {
            // A malformed event must never crash the accessibility service.
            Log.e(TAG, "event failed: ${failure.javaClass.simpleName}")
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        if (capturing.get()) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName != PaymentPageDetector.PKG_WECHAT &&
            packageName != PaymentPageDetector.PKG_ALIPAY
        ) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != packageName) return
        runCatching { root.refresh() }

        if (lastWindowPackage != packageName) {
            lastWindowPackage = packageName
            lastWindowActivity = null
            lastPageIdentity = null
            lastCaptureAtMs = 0L
        }

        val now = System.currentTimeMillis()
        val snapshot = root.toSnapshot(packageName, now)
        val eventClass = event.className?.toString().orEmpty()
        // Window-state events also carry dialogs, popups and input methods.
        // Letting those replace the remembered activity would either block a
        // real payment page or strip the activity evidence from a chat page.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            PageActivityGuard.isActivityClassName(packageName, eventClass)
        ) {
            lastWindowActivity = eventClass
        }
        val activity = lastWindowActivity
            ?: eventClass.takeIf { PageActivityGuard.isActivityClassName(packageName, it) }
            ?: ""
        val pageText = snapshot.flattenText()
        // WeChat's conversation list and Alipay home/chat pages contain chat
        // names, timestamps and unread badges. Never feed them to the payment
        // card parser: those pages can look like multiple small payments.
        if (PageActivityGuard.isChatOrHomePage(packageName, activity, pageText)) return
        val allowStrictAuto = strictSuccessAllowance(packageName, activity, snapshot)

        // Payment/bill cards: merchant payment, transfer, red packet, withdrawal,
        // refund, and pay-after-use. Only explicitly completed cards may be booked.
        val cards = PaymentCardDetector.fromSnapshot(packageName, snapshot, now)
        if (cards.isNotEmpty()) {
            val candidates = cards.mapNotNull { it.toCaptureCandidate(now) }
            if (candidates.isNotEmpty()) {
                // A page with several completed cards is a bill/history list,
                // not one payment result. Booking it in bulk duplicates history;
                // that belongs to the explicit bill-import flow.
                if (candidates.size > 1) {
                    if (BuildConfig.DEBUG) {
                        Log.v(TAG, "skip multi-card page cards=${candidates.size}")
                    }
                    return
                }
                val pageIdentity = candidates.first().pageIdentity
                if (pageIdentity == lastPageIdentity && now - lastCaptureAtMs < PAGE_CAPTURE_DEDUP_MS) return
                if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
                capturing.set(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    captureScreenshotForCandidates(
                        candidates,
                        allowStrictAuto,
                        snapshot.root.windowId,
                        contextText = snapshot.flattenText(),
                    )
                } else {
                    processCandidates(
                        candidates.map { it.copy(reason = "screenshot unsupported") },
                        null,
                        allowStrictAuto,
                        contextText = snapshot.flattenText(),
                    )
                }
                return
            }
            if (BuildConfig.DEBUG) {
                cards.forEach {
                    Log.v(
                        TAG,
                        "skip card kind=${it.kind} status=${it.status} " +
                            "reason=${it.nonFinalReason ?: it.reason}",
                    )
                }
            }
            // No completed card in the node tree; OCR may still see a completed
            // card that accessibility exposed only as a non-final placeholder.
        }

        // If accessibility nodes are incomplete, OCR the visible payment page
        // and parse the same card layout from OCR lines. This is the fallback
        // for notification-less red packets, refunds, transfers, and withdrawals.
        if (PaymentCardDetector.looksLikePaymentPage(packageName, pageText)) {
            val pageIdentity = PaymentCardDetector.pageIdentity(packageName, pageText)
            if (pageIdentity == lastPageIdentity &&
                now - lastCaptureAtMs < PAGE_CAPTURE_DEDUP_MS
            ) {
                return
            }
            if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
            capturing.set(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureScreenshotForOcr(
                    packageName,
                    pageIdentity,
                    now,
                    allowStrictAuto,
                    snapshot.root.windowId,
                )
            } else {
                capturing.set(false)
            }
            return
        }

        // Success-page fallback is part of the same pipeline. A page that already
        // produced a known non-final card must never be reinterpreted as success.
        if (cards.isNotEmpty()) return
        val detection = PaymentPageDetector.detect(packageName, snapshot, now)
        if (!detection.isSuccessPage) {
            if (BuildConfig.DEBUG) {
                Log.v(TAG, "skip reason=${detection.reason} identity=${detection.pageIdentity}")
            }
            return
        }
        if (detection.pageIdentity == lastPageIdentity &&
            now - lastCaptureAtMs < PAGE_CAPTURE_DEDUP_MS
        ) {
            return
        }
        if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
        val amount = detection.amountCents ?: return
        val candidate = CaptureCandidate(
            packageName = packageName,
            pageIdentity = detection.pageIdentity,
            amountCents = amount,
            merchantCandidates = detection.merchantCandidates,
            orderId = detection.orderId,
            capturedAtMs = now,
            screenshotPath = null,
            confidence = detection.confidence,
            reason = detection.reason,
            kind = CaptureCandidate.KIND_PAYMENT,
            eventTimeMs = now,
        )
        capturing.set(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenshotForCandidates(
                listOf(candidate),
                allowStrictAuto,
                snapshot.root.windowId,
                contextText = snapshot.flattenText(),
            )
        } else {
            processCandidates(
                listOf(candidate.copy(reason = "screenshot unsupported")),
                null,
                allowStrictAuto,
                contextText = snapshot.flattenText(),
            )
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun captureScreenshotForOcr(
        packageName: String,
        pageIdentity: String,
        now: Long,
        allowAuto: Boolean,
        windowId: Int,
    ) {
        val attemptId = TransactionDiagnostics.newAttemptId()
        scope.launch(Dispatchers.Main) {
            repository.recordDiagnostic(
                attemptId = attemptId,
                stage = TransactionDiagnostics.STAGE_CAPTURE,
                result = TransactionDiagnostics.RESULT_STARTED,
                details = "OCR payment-page capture started",
            )
            try {
                takeScreenshotCompat(
                    windowId,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bitmap = runCatching { screenshotToBitmap(screenshot) }.getOrNull()
                            if (bitmap == null) {
                                scope.launch {
                                    repository.recordDiagnostic(
                                        attemptId = attemptId,
                                        stage = TransactionDiagnostics.STAGE_CAPTURE,
                                        result = TransactionDiagnostics.RESULT_FAILURE,
                                        details = "Screenshot could not be decoded",
                                    )
                                }
                                capturing.set(false)
                                return
                            }
                            val height = bitmap.height
                            scope.launch {
                                var recognizedText: String? = null
                                try {
                                    val bytes = encodeScreenshot(bitmap)
                                    val engine = ocrEngine ?: PaddleOcrEngine(
                                        applicationContext,
                                        fallback = MlKitOcrEngine(),
                                    ).also { ocrEngine = it }
                                    val result = withContext(Dispatchers.Default) {
                                        engine.recognize(bitmap)
                                    }
                                    recognizedText = result.rawText.ifBlank {
                                        result.lines.joinToString("\n") { it.text }
                                    }
                                    val lines = result.lines.mapNotNull { line ->
                                        line.bounds?.let {
                                            BillTextLine(
                                                text = line.text,
                                                top = it.top,
                                                bottom = it.bottom,
                                                left = it.left,
                                                right = it.right,
                                            )
                                        }
                                    }
                                    val cards = PaymentCardDetector.fromLines(
                                        packageName = packageName,
                                        lines = lines,
                                        capturedAtMs = now,
                                        screenHeightPx = height,
                                        pageIdentity = pageIdentity,
                                    )
                                    val candidates = cards.mapNotNull { it.toCaptureCandidate(now) }
                                    if (candidates.size != 1) {
                                        // Zero: the screenshot worked; do not retry the
                                        // same page until its content changes.
                                        // Several: a history list, never bulk-book it.
                                        lastPageIdentity = pageIdentity
                                        lastCaptureAtMs = System.currentTimeMillis()
                                        repository.recordDiagnostic(
                                            attemptId = attemptId,
                                            stage = TransactionDiagnostics.STAGE_PARSE,
                                            result = TransactionDiagnostics.RESULT_WARNING,
                                            details = if (candidates.isEmpty()) {
                                                "OCR did not identify one completed bill"
                                            } else {
                                                "OCR page contained multiple bill candidates"
                                            },
                                        )
                                        capturing.set(false)
                                    } else {
                                        processCandidates(
                                            candidates,
                                            bytes,
                                            allowAuto,
                                            contextText = recognizedText,
                                            diagnosticAttemptId = attemptId,
                                        )
                                    }
                                } catch (failure: Exception) {
                                    repository.recordDiagnostic(
                                        attemptId = attemptId,
                                        stage = TransactionDiagnostics.STAGE_PARSE,
                                        result = TransactionDiagnostics.RESULT_FAILURE,
                                        details = "Payment page processing failed",
                                    )
                                    Log.e(TAG, "OCR capture failed: ${failure.javaClass.simpleName}")
                                    capturing.set(false)
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            scope.launch {
                                repository.recordDiagnostic(
                                    attemptId = attemptId,
                                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                                    result = TransactionDiagnostics.RESULT_FAILURE,
                                    details = "Screenshot capture failed",
                                )
                            }
                            Log.e(TAG, "OCR screenshot failed: $errorCode")
                            capturing.set(false)
                        }
                    },
                )
            } catch (failure: RuntimeException) {
                repository.recordDiagnostic(
                    attemptId = attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = "Payment page processing failed",
                )
                Log.e(TAG, "OCR screenshot start failed: ${failure.javaClass.simpleName}")
                capturing.set(false)
            }
        }
    }

    private fun PaymentCardDetection.toCaptureCandidate(now: Long): CaptureCandidate? {
        if (!isFinal) return null
        val candidateKind = when (kind) {
            PaymentCardKind.PAYMENT -> CaptureCandidate.KIND_PAYMENT
            PaymentCardKind.REFUND -> CaptureCandidate.KIND_REFUND
            PaymentCardKind.PAY_AFTER_USE -> CaptureCandidate.KIND_AUTO_PAY
            PaymentCardKind.TRANSFER -> CaptureCandidate.KIND_TRANSFER
            PaymentCardKind.RED_PACKET -> CaptureCandidate.KIND_RED_PACKET
            PaymentCardKind.WITHDRAWAL -> CaptureCandidate.KIND_WITHDRAWAL
            PaymentCardKind.BILL -> CaptureCandidate.KIND_BILL
        }
        val candidateDirection = when (direction) {
            PaymentDirection.EXPENSE -> CaptureCandidate.DIRECTION_EXPENSE
            PaymentDirection.INCOME -> CaptureCandidate.DIRECTION_INCOME
            PaymentDirection.UNKNOWN -> return null
        }
        return CaptureCandidate(
            packageName = source.packageName,
            pageIdentity = pageIdentity,
            amountCents = amountCents,
            merchantCandidates = merchantCandidates,
            orderId = orderId,
            capturedAtMs = now,
            screenshotPath = null,
            confidence = confidence,
            reason = reason.ifBlank { "ok" }.take(80),
            kind = candidateKind,
            eventTimeMs = eventTimeMs ?: now,
            direction = candidateDirection,
        )
    }

    private fun encodeScreenshot(bitmap: Bitmap): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
            out.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_SCREENSHOT_BYTES }
        }
    } catch (_: Exception) {
        null
    }

    private fun windowStillCurrent(
        root: android.view.accessibility.AccessibilityNodeInfo,
        packageName: String,
        windowId: Int,
    ): Boolean {
        val active = rootInActiveWindow ?: return false
        return active.packageName?.toString() == packageName && active.windowId == windowId
    }

    private fun strictSuccessAllowance(
        packageName: String,
        activity: String,
        snapshot: ScreenSnapshot,
    ): Boolean {
        if (activity.isBlank() || !SuccessPageDetector.eligibleActivity(packageName, activity)) {
            return true
        }
        val page = PaymentPage(
            packageName = packageName,
            windowId = snapshot.root.windowId,
            activity = activity,
            texts = snapshot.root.subtreeTexts(),
            editable = snapshot.root.walk().any { it.editable },
        )
        return SuccessPageDetector.detect(page) != null
    }

    @android.annotation.SuppressLint("NewApi")
    private fun captureScreenshotForCandidates(
        candidates: List<CaptureCandidate>,
        allowAuto: Boolean,
        windowId: Int,
        contextText: String? = null,
    ) {
        val attemptId = TransactionDiagnostics.newAttemptId()
        scope.launch(Dispatchers.Main) {
            repository.recordDiagnostic(
                attemptId = attemptId,
                stage = TransactionDiagnostics.STAGE_CAPTURE,
                result = TransactionDiagnostics.RESULT_STARTED,
                details = "Payment-page candidate capture started",
            )
            try {
                takeScreenshotCompat(
                    windowId,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bitmap = runCatching { screenshotToBitmap(screenshot) }.getOrNull()
                            val bytes = bitmap?.let(::compressScreenshot)
                            if (bitmap == null || bytes == null) {
                                scope.launch {
                                    repository.recordDiagnostic(
                                        attemptId = attemptId,
                                        stage = TransactionDiagnostics.STAGE_CAPTURE,
                                        result = TransactionDiagnostics.RESULT_WARNING,
                                        details = "Screenshot unavailable; parsed text will be used",
                                    )
                                    processCandidates(
                                        candidates,
                                        null,
                                        allowAuto,
                                        contextText,
                                        diagnosticAttemptId = attemptId,
                                    )
                                }
                            } else {
                                processCandidates(
                                    candidates,
                                    bytes,
                                    allowAuto,
                                    contextText,
                                    diagnosticAttemptId = attemptId,
                                )
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            scope.launch {
                                repository.recordDiagnostic(
                                    attemptId = attemptId,
                                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                                    result = TransactionDiagnostics.RESULT_WARNING,
                                    details = "Screenshot capture failed",
                                )
                                processCandidates(
                                    candidates.map { it.copy(reason = "screenshot error $errorCode") },
                                    null,
                                    allowAuto,
                                    contextText,
                                    diagnosticAttemptId = attemptId,
                                )
                            }
                        }
                    },
                )
            } catch (failure: RuntimeException) {
                repository.recordDiagnostic(
                    attemptId = attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_WARNING,
                    details = "Payment page processing failed",
                )
                processCandidates(
                    candidates.map { it.copy(reason = "screenshot start failed") },
                    null,
                    allowAuto,
                    contextText,
                    diagnosticAttemptId = attemptId,
                )
            }
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun takeScreenshotCompat(
        windowId: Int,
        callback: AccessibilityService.TakeScreenshotCallback,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windowId != 0) {
            takeScreenshotOfWindow(
                windowId,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        callback.onSuccess(screenshot)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                            // Some OEMs still expose a readable full-display
                            // composition for windows that reject a window-only
                            // capture. Try it once; OCR/auto-insert still requires
                            // a successfully decoded image.
                            Log.w(TAG, "window screenshot secure, retry display")
                            try {
                                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                            } catch (_: RuntimeException) {
                                callback.onFailure(errorCode)
                            }
                        } else {
                            callback.onFailure(errorCode)
                        }
                    }
                },
            )
        } else {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }
    }

    private fun processCandidates(
        candidates: List<CaptureCandidate>,
        screenshot: ByteArray?,
        allowAuto: Boolean = true,
        contextText: String? = null,
        diagnosticAttemptId: String? = null,
    ) {
        scope.launch {
            try {
                candidates.forEachIndexed { index, candidate ->
                    val input = TransactionDiagnosticInput(
                        attemptId = if (index == 0 && diagnosticAttemptId != null) diagnosticAttemptId
                        else TransactionDiagnostics.newAttemptId(),
                    )
                    var transaction: TransactionEntity? = null
                    try {
                        if (index > 0 || diagnosticAttemptId == null) {
                            repository.recordDiagnostic(
                                attemptId = input.attemptId,
                                stage = TransactionDiagnostics.STAGE_CAPTURE,
                                result = TransactionDiagnostics.RESULT_STARTED,
                                details = "Payment page capture started",
                            )
                        }
                        val merchant = candidate.merchantCandidates.firstOrNull().orEmpty()
                        val mappedTransaction = CaptureReviewMapping.toTransactionEntity(
                            candidate = candidate,
                            merchant = merchant,
                            category = CaptureReviewMapping.classificationFor(
                                candidate = candidate,
                                merchant = merchant,
                                contextText = contextText.orEmpty(),
                            ).category,
                        )
                        transaction = mappedTransaction
                        repository.recordDiagnostic(
                            attemptId = input.attemptId,
                            stage = TransactionDiagnostics.STAGE_PARSE,
                            result = TransactionDiagnostics.RESULT_SUCCESS,
                            details = "Payment page parsed",
                        )
                        val stored = store.save(
                            candidate.copy(diagnosticAttemptId = input.attemptId),
                            screenshot,
                        )
                        if (stored == null) {
                            repository.recordDiagnostic(
                                attemptId = input.attemptId,
                                stage = TransactionDiagnostics.STAGE_CAPTURE,
                                result = TransactionDiagnostics.RESULT_FAILURE,
                                details = "Capture candidate could not be stored",
                            )
                            return@forEachIndexed
                        }
                        val now = System.currentTimeMillis()
                        val dedupKey = stored.dedupKey()
                        val recentlyCaptured = synchronized(recentCaptureKeys) {
                            val firstSeen = recentCaptureKeys[dedupKey]
                            if (firstSeen != null && now - firstSeen < CAPTURE_KEY_TTL_MS) {
                                true
                            } else {
                                recentCaptureKeys[dedupKey] = now
                                recentCaptureKeys.entries.removeAll {
                                    now - it.value > CAPTURE_KEY_TTL_MS
                                }
                                false
                            }
                        }
                        if (recentlyCaptured) {
                            store.mark(stored, CaptureCandidate.STATUS_IGNORED)
                            repository.recordDiagnostic(
                                attemptId = input.attemptId,
                                stage = TransactionDiagnostics.STAGE_DEDUP,
                                result = TransactionDiagnostics.RESULT_WARNING,
                                details = "Recent page-capture fingerprint suppressed",
                            )
                            return@forEachIndexed
                        }

                        if (!CaptureBookGate.mayWriteLedger(allowAuto)) {
                            // Hard gate: preview-only or auto-disabled passes keep the
                            // candidate in the local inbox for manual review and must
                            // not touch the ledger at all.
                            store.mark(stored, CaptureCandidate.STATUS_PENDING)
                            repository.recordDiagnostic(
                                attemptId = input.attemptId,
                                stage = TransactionDiagnostics.STAGE_DEDUP,
                                result = TransactionDiagnostics.RESULT_WARNING,
                                details = "Capture held for review; auto booking disabled",
                            )
                            return@forEachIndexed
                        }
                        // 页面识别属于「另一条采集通道」：账本里可能已有一条通知/短信
                        // 记下的同额同方同平台记录。必须在这里显式传跨通道窗口——
                        // 默认 0 会让 CrossSourceDuplicatePolicy 整层跳过，这正是
                        // 「通知记一笔、点开页面又记一笔」的根因。
                        // 强证据（两侧同订单号）→ 不插新行，候选标记为已入账；
                        // 弱证据（通知缺商户 / 占位名，页面有真实商户）→ 同样不插新行，
                        // 但候选必须留在待确认列表并写明「疑似与已有记录重复」。
                        val result = repository.insertIfNotDuplicateResult(
                            transaction = mappedTransaction,
                            windowMs = PAGE_DEDUP_WINDOW_MS,
                            notifySuccess = false,
                            diagnosticInput = input,
                            crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                            holdWeakCrossSourceMatch = true,
                        )
                        when (PageCaptureResultPolicy.of(result)) {
                            PageCaptureOutcome.APPROVED ->
                                store.mark(stored, CaptureCandidate.STATUS_APPROVED)

                            PageCaptureOutcome.IGNORED ->
                                store.mark(stored, CaptureCandidate.STATUS_IGNORED)

                            PageCaptureOutcome.HOLD_FOR_REVIEW -> {
                                val existingId = (result as LedgerInsertResult.Duplicate)
                                    .existingTransactionId
                                store.mark(
                                    stored.copy(
                                        reason = CaptureCandidate.REASON_LIKELY_DUPLICATE,
                                        matchedTransactionId = existingId,
                                    ),
                                    CaptureCandidate.STATUS_PENDING,
                                )
                                repository.recordDiagnostic(
                                    attemptId = input.attemptId,
                                    stage = TransactionDiagnostics.STAGE_DEDUP,
                                    result = TransactionDiagnostics.RESULT_WARNING,
                                    details = "Page capture held as possible cross-channel duplicate",
                                    outputSummary = "疑似与已有 #$existingId 重复，保留待确认",
                                )
                            }

                            PageCaptureOutcome.RETRY -> {
                                val failure = (result as LedgerInsertResult.Failed).cause
                                throw (failure as? Exception)
                                    ?: IllegalStateException("page capture insert failed", failure)
                            }
                        }
                    } catch (failure: Exception) {
                        transaction?.let {
                            // Preserve retryability after a transient database error.
                            synchronized(recentCaptureKeys) { recentCaptureKeys.remove(candidate.dedupKey()) }
                        }
                        repository.recordDiagnostic(
                            attemptId = input.attemptId,
                            stage = TransactionDiagnostics.STAGE_CAPTURE,
                            result = TransactionDiagnostics.RESULT_FAILURE,
                            details = "Payment page processing failed",
                        )
                    }
                }
                lastPageIdentity = candidates.firstOrNull()?.pageIdentity
                lastCaptureAtMs = System.currentTimeMillis()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "processed ${candidates.size} card candidates")
                }
            } catch (failure: Exception) {
                Log.e(TAG, "candidate processing failed: ${failure.javaClass.simpleName}")
            } finally {
                capturing.set(false)
            }
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun captureScreenshot(candidate: CaptureCandidate) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = runCatching { screenshotToBitmap(screenshot) }.getOrNull()
                        val bytes = bitmap?.let(::compressScreenshot)
                        saveCandidate(
                            if (bytes == null) candidate.copy(reason = "screenshot decode failed")
                            else candidate,
                            bytes,
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        saveCandidate(
                            candidate.copy(reason = "screenshot error $errorCode"),
                            null,
                        )
                    }
                },
            )
        } catch (failure: RuntimeException) {
            saveCandidate(candidate.copy(reason = "screenshot start failed"), null)
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun screenshotToBitmap(
        result: AccessibilityService.ScreenshotResult,
    ): Bitmap? {
        val buffer = result.hardwareBuffer
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            try {
                // Copy through Canvas instead of Bitmap.copy: some vendor
                // screenshot buffers expose a row stride that copy() mishandles.
                Bitmap.createBitmap(wrapped.width, wrapped.height, Bitmap.Config.ARGB_8888).also {
                    android.graphics.Canvas(it).drawBitmap(wrapped, 0f, 0f, null)
                }
            } finally {
                wrapped.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private fun compressScreenshot(bitmap: Bitmap): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
            out.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_SCREENSHOT_BYTES }
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveCandidate(candidate: CaptureCandidate, screenshot: ByteArray?) {
        scope.launch {
            try {
                store.save(candidate, screenshot)
                lastPageIdentity = candidate.pageIdentity
                lastCaptureAtMs = System.currentTimeMillis()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "candidate saved identity=${candidate.pageIdentity}")
                }
            } catch (failure: Exception) {
                Log.e(TAG, "candidate save failed: ${failure.javaClass.simpleName}")
            } finally {
                capturing.set(false)
            }
        }
    }

    override fun onInterrupt() {
        // No polling or long-running work to stop here.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        capturing.set(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        capturing.set(false)
        ocrEngine?.close()
        ocrEngine = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BkPageRecognition"
        private const val PAGE_CAPTURE_DEDUP_MS = 5 * 60_000L
        private const val PAGE_DEDUP_WINDOW_MS = 30_000L
        private const val CAPTURE_KEY_TTL_MS = 10 * 60_000L
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
        private const val PAGE_RECOGNITION_CLASS =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

        /** True when the compatibility page-recognition service is enabled. */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/$PAGE_RECOGNITION_CLASS"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}

/** 页面识别拿到仓库结果之后要做什么；纯逻辑，方便 JVM 单测覆盖每条分支。 */
enum class PageCaptureOutcome {
    /** 已写入账本（新行或并入已有行）。 */
    APPROVED,

    /** 已确认是同一笔（同通道重发 / 跨通道同订单号），不再写第二行。 */
    IGNORED,

    /** 疑似与已有记录重复：不写新行，但候选必须留在待确认列表。 */
    HOLD_FOR_REVIEW,

    /** 写入失败：保留候选、记录异常，允许下次重试。 */
    RETRY,
}

/**
 * 把 [LedgerInsertResult] 翻译成页面识别的候选状态。
 *
 * 关键区分：[DuplicateReason.CROSS_SOURCE_WEAK] 是「弱证据」，只说明候选可能与
 * 账本里某条通知记录是同一笔，并不等于已经入账；把它当 [PageCaptureOutcome.IGNORED]
 * 会让候选从待确认列表消失，用户再也看不到这笔钱。所以它必须走
 * [PageCaptureOutcome.HOLD_FOR_REVIEW]。
 */
object PageCaptureResultPolicy {
    fun of(result: LedgerInsertResult): PageCaptureOutcome = when (result) {
        is LedgerInsertResult.Inserted, is LedgerInsertResult.Merged ->
            PageCaptureOutcome.APPROVED
        is LedgerInsertResult.Failed -> PageCaptureOutcome.RETRY
        is LedgerInsertResult.Duplicate -> when (result.reason) {
            DuplicateReason.CROSS_SOURCE_WEAK -> PageCaptureOutcome.HOLD_FOR_REVIEW
            DuplicateReason.SOURCE_ORDER_ID,
            DuplicateReason.SAME_SOURCE_WINDOW,
            DuplicateReason.CROSS_SOURCE -> PageCaptureOutcome.IGNORED
        }
    }
}
