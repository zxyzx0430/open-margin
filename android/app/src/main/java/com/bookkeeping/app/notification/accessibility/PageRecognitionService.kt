package com.bookkeeping.app.notification.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.rom.DeviceRomDetector
import com.bookkeeping.app.capture.success.PaymentPage
import com.bookkeeping.app.capture.success.PaymentPageDetector as SuccessPageDetector
import com.bookkeeping.app.capture.screenshot.ScreenshotReviewNotifier
import com.bookkeeping.app.capture.screenshot.CandidateStorageIntakeKind
import com.bookkeeping.app.data.diagnostics.ServiceHealthLog
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
import com.bookkeeping.app.notification.capture.CandidateSaveFailure
import com.bookkeeping.app.notification.capture.CandidateSaveResult
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
    private var lastPageEventId: String? = null
    private var lastCaptureAtMs: Long = 0L
    private var lastScreenshotWindowId: Int = 0
    private var lastScreenshotPageIdentity: String? = null
    private var lastScreenshotAttemptAtMs: Long = 0L
    @Volatile
    private var lastTakeScreenshotCallMs: Long = 0L
    private var lastWindowActivity: String? = null
    private var lastWindowPackage: String? = null
    private val recentCaptureKeys = mutableMapOf<String, Long>()
    private val recentNoOrderIdKeys = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                AccessibilityServiceEntryPoint::class.java,
            )
            store = CaptureCandidateStore(applicationContext)
            repository = entryPoint.transactionRepository()
            ServiceHealthLog.record(
                context = applicationContext,
                channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                event = ServiceHealthLog.EVENT_CONNECTED,
                summary = "页面识别服务已连接",
                detail = DeviceRomDetector.current().captureStrategy,
            )
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
            lastPageEventId = null
            lastCaptureAtMs = 0L
            lastScreenshotWindowId = 0
            lastScreenshotPageIdentity = null
            lastScreenshotAttemptAtMs = 0L
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
        if (PaymentCardDetector.isBillListPage(pageText)) {
            ServiceHealthLog.record(
                context = applicationContext,
                channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                event = ServiceHealthLog.EVENT_SKIPPED,
                summary = "已跳过账单列表页（避免批量误抓）",
                detail = packageName,
            )
            return
        }
        val allowStrictAuto = strictSuccessAllowance(packageName, activity, snapshot)

        // Payment/bill cards: merchant payment, transfer, red packet, withdrawal,
        // refund, and pay-after-use. Only explicitly completed cards may be booked.
        val cards = PaymentCardDetector.fromSnapshot(packageName, snapshot, now)
        if (cards.isNotEmpty()) {
            val firstIdentity = cards.first().pageIdentity
            val firstOrderId = cards.firstOrNull { !it.orderId.isNullOrBlank() }?.orderId
            if (PageIdentityThrottlePolicy.shouldSuppress(
                    previousIdentity = lastPageIdentity,
                    previousEventId = lastPageEventId,
                    previousCapturedAtMs = lastCaptureAtMs,
                    identity = firstIdentity,
                    eventId = firstOrderId,
                    nowMs = now,
                    windowMs = PAGE_CAPTURE_DEDUP_MS,
                )
            ) {
                ServiceHealthLog.record(
                    context = applicationContext,
                    channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                    event = ServiceHealthLog.EVENT_THROTTLED,
                    summary = "同页面重复触发已节流跳过",
                    detail = firstIdentity,
                )
                return
            }
            val needsReviewQueue = cards.size > 1 || cards.any { it.reviewRequired }
            if (needsReviewQueue) {
                scope.launch {
                    val recentLedger = runCatching {
                        repository.screenshotDedupRows(limit = 100)
                    }.getOrDefault(emptyList())
                    val pageRoute = PaymentCardReviewQueue.route(
                        detections = cards,
                        capturedAtMs = now,
                        store = store,
                        existingLedgerRows = recentLedger,
                    )
                    if (pageRoute is PaymentCardReviewQueue.Route.HeldForReview) {
                        lastPageIdentity = firstIdentity
                        lastPageEventId = firstOrderId
                        lastCaptureAtMs = now
                        ServiceHealthLog.record(
                            context = applicationContext,
                            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                            event = ServiceHealthLog.EVENT_HELD_FOR_REVIEW,
                            summary = "页面识别多笔/待确认候选，已转入待确认",
                            detail = "saved=${pageRoute.saved.saved.size}, failed=${pageRoute.saved.failed}",
                        )
                        if (pageRoute.saved.saved.isNotEmpty()) {
                            ScreenshotReviewNotifier.notifyPending(applicationContext, store.pending())
                        }
                        repository.recordDiagnostic(
                            attemptId = TransactionDiagnostics.newAttemptId(),
                            stage = TransactionDiagnostics.STAGE_DEDUP,
                            result = if (pageRoute.saved.failed == 0) TransactionDiagnostics.RESULT_WARNING
                            else TransactionDiagnostics.RESULT_FAILURE,
                            details = "Multi-card page held for individual confirmation",
                            outputSummary = "待确认 ${pageRoute.saved.saved.size} 笔" +
                                if (pageRoute.saved.failed > 0) " · 保存失败 ${pageRoute.saved.failed} 笔" else "",
                        )
                    }
                }
                return
            }
            when (val pageRoute = PaymentCardReviewQueue.route(cards, now, store)) {
                is PaymentCardReviewQueue.Route.HeldForReview -> return

                is PaymentCardReviewQueue.Route.SingleCandidate -> {
                    val candidates = listOf(pageRoute.candidate)
                    val pageIdentity = candidates.first().pageIdentity
                    if (PageIdentityThrottlePolicy.shouldSuppress(
                            previousIdentity = lastPageIdentity,
                            previousEventId = lastPageEventId,
                            previousCapturedAtMs = lastCaptureAtMs,
                            identity = pageIdentity,
                            eventId = candidates.first().orderId,
                            nowMs = now,
                            windowMs = PAGE_CAPTURE_DEDUP_MS,
                        )
                    ) {
                        ServiceHealthLog.record(
                            context = applicationContext,
                            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                            event = ServiceHealthLog.EVENT_THROTTLED,
                            summary = "同页面重复触发已节流跳过",
                            detail = pageIdentity,
                        )
                        return
                    }
                    if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
                    if (isScreenshotBurstCooldown(snapshot.root.windowId, pageIdentity, now)) {
                        ServiceHealthLog.record(
                            context = applicationContext,
                            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                            event = ServiceHealthLog.EVENT_THROTTLED,
                            summary = "同窗口短时截屏冷却中已跳过",
                            detail = pageIdentity,
                        )
                        return
                    }
                    capturing.set(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        recordScreenshotAttempt(snapshot.root.windowId, pageIdentity, now)
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

                PaymentCardReviewQueue.Route.NoBookableCandidate -> {
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
            }
        }

        // If accessibility nodes are incomplete, OCR the visible payment page
        // and parse the same card layout from OCR lines. This is the fallback
        // for notification-less red packets, refunds, transfers, and withdrawals.
        if (PaymentCardDetector.looksLikePaymentPage(packageName, pageText)) {
            val pageIdentity = PaymentCardDetector.pageIdentity(packageName, pageText)
            if (PageIdentityThrottlePolicy.shouldSuppress(
                    previousIdentity = lastPageIdentity,
                    previousEventId = lastPageEventId,
                    previousCapturedAtMs = lastCaptureAtMs,
                    identity = pageIdentity,
                    eventId = null,
                    nowMs = now,
                    windowMs = PAGE_CAPTURE_DEDUP_MS,
                )
            ) {
                ServiceHealthLog.record(
                    context = applicationContext,
                    channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                    event = ServiceHealthLog.EVENT_THROTTLED,
                    summary = "同页面重复触发已节流跳过",
                    detail = pageIdentity,
                )
                return
            }
            if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
            if (isScreenshotBurstCooldown(snapshot.root.windowId, pageIdentity, now)) {
                ServiceHealthLog.record(
                    context = applicationContext,
                    channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                    event = ServiceHealthLog.EVENT_THROTTLED,
                    summary = "同窗口短时截屏冷却中已跳过",
                    detail = pageIdentity,
                )
                return
            }
            lastPageIdentity = pageIdentity
            lastPageEventId = null
            lastCaptureAtMs = now
            capturing.set(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                recordScreenshotAttempt(snapshot.root.windowId, pageIdentity, now)
                ServiceHealthLog.record(
                    context = applicationContext,
                    channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                    event = ServiceHealthLog.EVENT_OCR_FALLBACK,
                    summary = "无障碍节点不全，启动页面截图 OCR 兜底",
                    detail = pageIdentity,
                )
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
        if (PageIdentityThrottlePolicy.shouldSuppress(
                previousIdentity = lastPageIdentity,
                previousEventId = lastPageEventId,
                previousCapturedAtMs = lastCaptureAtMs,
                identity = detection.pageIdentity,
                eventId = detection.orderId,
                nowMs = now,
                windowMs = PAGE_CAPTURE_DEDUP_MS,
            )
        ) {
            ServiceHealthLog.record(
                context = applicationContext,
                channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                event = ServiceHealthLog.EVENT_THROTTLED,
                summary = "同页面重复触发已节流跳过",
                detail = detection.pageIdentity,
            )
            return
        }
        if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
        if (isScreenshotBurstCooldown(snapshot.root.windowId, detection.pageIdentity, now)) {
            ServiceHealthLog.record(
                context = applicationContext,
                channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                event = ServiceHealthLog.EVENT_THROTTLED,
                summary = "同窗口短时截屏冷却中已跳过",
                detail = detection.pageIdentity,
            )
            return
        }
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
            recordScreenshotAttempt(snapshot.root.windowId, detection.pageIdentity, now)
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
                val sinceLastCall = SystemClock.elapsedRealtime() - lastTakeScreenshotCallMs
                if (sinceLastCall in 0 until MIN_TAKE_SCREENSHOT_INTERVAL_MS) {
                    kotlinx.coroutines.delay(MIN_TAKE_SCREENSHOT_INTERVAL_MS - sinceLastCall)
                }
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
                                        line.text.takeIf(String::isNotBlank)?.let { text ->
                                            line.bounds?.let {
                                                BillTextLine(
                                                    text = text,
                                                    top = it.top,
                                                    bottom = it.bottom,
                                                    left = it.left,
                                                    right = it.right,
                                                )
                                            } ?: BillTextLine(text = text)
                                        }
                                    }
                                    val cards = PaymentCardDetector.fromLines(
                                        packageName = packageName,
                                        lines = lines,
                                        capturedAtMs = now,
                                        screenHeightPx = height,
                                        pageIdentity = pageIdentity,
                                    )
                                    val recentLedger = runCatching {
                                        repository.screenshotDedupRows(limit = 100)
                                    }.getOrDefault(emptyList())
                                    when (val pageRoute = PaymentCardReviewQueue.route(
                                        detections = cards,
                                        capturedAtMs = now,
                                        store = store,
                                        screenshot = bytes,
                                        existingLedgerRows = recentLedger,
                                    )) {
                                        is PaymentCardReviewQueue.Route.HeldForReview -> {
                                            ServiceHealthLog.record(
                                                context = applicationContext,
                                                channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                                                event = ServiceHealthLog.EVENT_HELD_FOR_REVIEW,
                                                summary = "OCR 识别到待确认交易，已转入待确认",
                                                detail = "saved=${pageRoute.saved.saved.size}, failed=${pageRoute.saved.failed}",
                                            )
                                            if (bytes == null) {
                                                pageRoute.saved.saved.forEach { saved ->
                                                    store.mark(
                                                        saved.copy(
                                                            screenshotUnavailableReason = CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_INVALID,
                                                        ),
                                                        CaptureCandidate.STATUS_PENDING,
                                                    )
                                                }
                                            }
                                            if (pageRoute.saved.saved.isNotEmpty()) {
                                                ScreenshotReviewNotifier.notifyPending(
                                                    applicationContext,
                                                    store.pending(),
                                                )
                                            }
                                            if (pageRoute.saved.indexFull > 0) {
                                                ScreenshotReviewNotifier.notifyCandidateStorageFull(
                                                    applicationContext,
                                                    CandidateStorageIntakeKind.SCREENSHOT_PAGE,
                                                )
                                            }
                                            val missingImages = if (bytes == null) {
                                                pageRoute.saved.saved.size
                                            } else {
                                                pageRoute.saved.saved.count {
                                                    it.screenshotUnavailableReason != null ||
                                                        it.reason.startsWith(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_PREFIX)
                                                }
                                            }
                                            val intendedCandidateCount = cards.count {
                                                PaymentCardReviewQueue.candidate(it, now) != null
                                            }
                                            advancePageThrottleForBatch(
                                                intendedCandidateCount = intendedCandidateCount,
                                                saved = pageRoute.saved.saved,
                                                failed = pageRoute.saved.failed,
                                            )
                                            repository.recordDiagnostic(
                                                attemptId = attemptId,
                                                stage = TransactionDiagnostics.STAGE_SAVE,
                                                result = if (pageRoute.saved.failed > 0) TransactionDiagnostics.RESULT_FAILURE
                                                else if (missingImages > 0) TransactionDiagnostics.RESULT_WARNING
                                                else TransactionDiagnostics.RESULT_SUCCESS,
                                                details = when {
                                                    pageRoute.saved.indexFull > 0 ->
                                                        "Candidate index budget full; OCR candidate was not saved"
                                                    missingImages > 0 ->
                                                        "OCR candidates saved for review; screenshot evidence unavailable"
                                                    else -> "OCR payment candidates held for individual review"
                                                },
                                                outputSummary = "待确认 ${pageRoute.saved.saved.size} 笔" +
                                                    (if (pageRoute.saved.indexFull > 0) " · 存储已满，未保存 ${pageRoute.saved.indexFull} 笔"
                                                    else if (pageRoute.saved.failed > 0) " · 保存失败 ${pageRoute.saved.failed} 笔" else "") +
                                                    (if (missingImages > 0) " · 截图不可用 $missingImages 笔" else ""),
                                            )
                                            capturing.set(false)
                                        }

                                        is PaymentCardReviewQueue.Route.SingleCandidate -> {
                                            processCandidates(
                                                listOf(
                                                    if (bytes == null) {
                                                        pageRoute.candidate.copy(reason = "screenshot decode failed")
                                                    } else {
                                                        pageRoute.candidate
                                                    },
                                                ),
                                                bytes,
                                                allowAuto = allowAuto && bytes != null,
                                                contextText = recognizedText,
                                                diagnosticAttemptId = attemptId,
                                            )
                                        }

                                        PaymentCardReviewQueue.Route.NoBookableCandidate -> {
                                            // No completed card was parsed; a later event may retry.
                                            repository.recordDiagnostic(
                                                attemptId = attemptId,
                                                stage = TransactionDiagnostics.STAGE_PARSE,
                                                result = TransactionDiagnostics.RESULT_WARNING,
                                                details = "OCR did not identify one completed bill",
                                            )
                                            capturing.set(false)
                                        }
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
                            recordScreenshotFallback(errorCode)
                            val isThrottled =
                                errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                            scope.launch {
                                repository.recordDiagnostic(
                                    attemptId = attemptId,
                                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                                    result = if (isThrottled) TransactionDiagnostics.RESULT_WARNING
                                    else TransactionDiagnostics.RESULT_FAILURE,
                                    details = if (isThrottled) {
                                        "Screenshot throttled by system interval (error=3)"
                                    } else {
                                        "Screenshot capture failed"
                                    },
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
        if (!isFinal || direction == PaymentDirection.UNKNOWN || reviewRequired) return null
        return PaymentCardReviewQueue.candidate(this, now)
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
                val sinceLastCall = SystemClock.elapsedRealtime() - lastTakeScreenshotCallMs
                if (sinceLastCall in 0 until MIN_TAKE_SCREENSHOT_INTERVAL_MS) {
                    kotlinx.coroutines.delay(MIN_TAKE_SCREENSHOT_INTERVAL_MS - sinceLastCall)
                }
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
                                        candidates.map { it.copy(reason = "screenshot decode failed") },
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
                            recordScreenshotFallback(errorCode)
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

    private fun recordScreenshotFallback(errorCode: Int) {
        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            ServiceHealthLog.record(
                context = applicationContext,
                channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
                event = ServiceHealthLog.EVENT_THROTTLED,
                summary = "系统截屏间隔过短已节流 (error=3)",
                detail = "error=$errorCode, rom=" + DeviceRomDetector.current().family.name,
            )
            return
        }
        ServiceHealthLog.record(
            context = applicationContext,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_OCR_FALLBACK,
            summary = "页面识别截屏受系统限制；建议同时开启新截图自动识别",
            detail = "error=$errorCode, rom=" + DeviceRomDetector.current().family.name,
        )
    }

    @android.annotation.SuppressLint("NewApi")
    private fun takeScreenshotCompat(
        windowId: Int,
        callback: AccessibilityService.TakeScreenshotCallback,
    ) {
        lastTakeScreenshotCallMs = SystemClock.elapsedRealtime()
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
                var firstStored: CaptureCandidate? = null
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
                        if (screenshot == null &&
                            PaymentCardReviewQueue.isDuplicatePending(candidate, store.pending())
                        ) {
                            repository.recordDiagnostic(
                                attemptId = input.attemptId,
                                stage = TransactionDiagnostics.STAGE_DEDUP,
                                result = TransactionDiagnostics.RESULT_WARNING,
                                details = "Duplicate pending capture ignored",
                            )
                            return@forEachIndexed
                        }
                        val saveResult = store.saveWithResult(
                            candidate.copy(diagnosticAttemptId = input.attemptId),
                            screenshot,
                        )
                        val stored = when (saveResult) {
                            is CandidateSaveResult.Saved -> saveResult.candidate
                            is CandidateSaveResult.Failed -> {
                                val indexFull = saveResult.reason == CandidateSaveFailure.INDEX_FULL
                                repository.recordDiagnostic(
                                    attemptId = input.attemptId,
                                    stage = TransactionDiagnostics.STAGE_SAVE,
                                    result = TransactionDiagnostics.RESULT_FAILURE,
                                    details = if (indexFull) {
                                        "Candidate index budget full; OCR candidate was not saved"
                                    } else {
                                        "Capture candidate could not be stored"
                                    },
                                )
                                if (indexFull) {
                                    ScreenshotReviewNotifier.notifyCandidateStorageFull(
                                        applicationContext,
                                        CandidateStorageIntakeKind.SCREENSHOT_PAGE,
                                    )
                                }
                                return@forEachIndexed
                            }
                        }
                        if (firstStored == null) firstStored = stored
                        if (PageCaptureScreenshotPolicy.isUnavailable(stored, screenshot)) {
                            val alreadyPending = PaymentCardReviewQueue.isDuplicatePending(
                                stored,
                                store.pending().filter { it.candidateId != stored.candidateId },
                            )
                            if (alreadyPending) {
                                store.mark(stored, CaptureCandidate.STATUS_IGNORED)
                                repository.recordDiagnostic(
                                    attemptId = input.attemptId,
                                    stage = TransactionDiagnostics.STAGE_DEDUP,
                                    result = TransactionDiagnostics.RESULT_WARNING,
                                    details = "Duplicate pending capture ignored",
                                )
                            } else {
                                repository.recordDiagnostic(
                                    attemptId = input.attemptId,
                                    stage = TransactionDiagnostics.STAGE_SAVE,
                                    result = TransactionDiagnostics.RESULT_FAILURE,
                                    details = "Screenshot evidence unavailable; candidate kept for review",
                                )
                                store.mark(stored, CaptureCandidate.STATUS_PENDING)
                            }
                            return@forEachIndexed
                        }
                        val now = System.currentTimeMillis()
                        // Similar amount/merchant/time fields do not identify one
                        // event. Only a non-empty platform order ID may suppress
                        // another page callback before the repository check.
                        val dedupKey = stored.orderId?.trim()?.takeIf(String::isNotEmpty)
                            ?.let { stored.dedupKey() }
                        val recentlyCaptured = dedupKey?.let { key ->
                            synchronized(recentCaptureKeys) {
                                val firstSeen = recentCaptureKeys[key]
                                if (firstSeen != null && now - firstSeen < CAPTURE_KEY_TTL_MS) {
                                    true
                                } else {
                                    recentCaptureKeys[key] = now
                                    recentCaptureKeys.entries.removeAll {
                                        now - it.value > CAPTURE_KEY_TTL_MS
                                    }
                                    false
                                }
                            }
                        } ?: false
                        if (recentlyCaptured) {
                            store.mark(stored, CaptureCandidate.STATUS_IGNORED)
                            repository.recordDiagnostic(
                                attemptId = input.attemptId,
                                stage = TransactionDiagnostics.STAGE_DEDUP,
                                result = TransactionDiagnostics.RESULT_WARNING,
                                details = "Recent platform order ID suppressed",
                            )
                            return@forEachIndexed
                        }
                        if (dedupKey == null) {
                            val burstKey = noOrderIdBurstKey(stored)
                            val recentlyRepeated = synchronized(recentNoOrderIdKeys) {
                                val firstSeen = recentNoOrderIdKeys[burstKey]
                                if (firstSeen != null && now - firstSeen < NO_ORDER_ID_BURST_TTL_MS) {
                                    true
                                } else {
                                    recentNoOrderIdKeys[burstKey] = now
                                    recentNoOrderIdKeys.entries.removeAll {
                                        now - it.value > NO_ORDER_ID_BURST_TTL_MS
                                    }
                                    false
                                }
                            }
                            if (recentlyRepeated) {
                                store.mark(stored, CaptureCandidate.STATUS_IGNORED)
                                repository.recordDiagnostic(
                                    attemptId = input.attemptId,
                                    stage = TransactionDiagnostics.STAGE_DEDUP,
                                    result = TransactionDiagnostics.RESULT_WARNING,
                                    details = "Recent identical page capture suppressed",
                                )
                                return@forEachIndexed
                            }
                        }

                        if (!CaptureBookGate.mayWriteLedger(allowAuto)) {
                            // Hard gate: preview-only or auto-disabled passes keep the
                            // candidate in the local inbox for manual review and must
                            // not touch the ledger at all.
                            val alreadyPending = PaymentCardReviewQueue.isDuplicatePending(
                                stored,
                                store.pending().filter { it.candidateId != stored.candidateId },
                            )
                            store.mark(
                                stored,
                                if (alreadyPending) CaptureCandidate.STATUS_IGNORED else CaptureCandidate.STATUS_PENDING,
                            )
                            repository.recordDiagnostic(
                                attemptId = input.attemptId,
                                stage = TransactionDiagnostics.STAGE_DEDUP,
                                result = TransactionDiagnostics.RESULT_WARNING,
                                details = if (alreadyPending) {
                                    "Duplicate pending capture ignored"
                                } else {
                                    "Capture held for review; auto booking disabled"
                                },
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
                                PageCaptureReviewPolicy.holdPossibleDuplicate(result, stored, store)
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
                            if (!candidate.orderId.isNullOrBlank()) {
                                synchronized(recentCaptureKeys) {
                                    recentCaptureKeys.remove(candidate.dedupKey())
                                }
                            } else {
                                synchronized(recentNoOrderIdKeys) {
                                    recentNoOrderIdKeys.remove(noOrderIdBurstKey(candidate))
                                }
                            }
                        }
                        repository.recordDiagnostic(
                            attemptId = input.attemptId,
                            stage = TransactionDiagnostics.STAGE_CAPTURE,
                            result = TransactionDiagnostics.RESULT_FAILURE,
                            details = "Payment page processing failed",
                        )
                    }
                }
                advancePageThrottle(firstStored)
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
            lastTakeScreenshotCallMs = SystemClock.elapsedRealtime()
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
                        recordScreenshotFallback(errorCode)
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
                // Bitmap.wrapHardwareBuffer returns a Config.HARDWARE bitmap; drawing a
                // hardware bitmap onto a software Canvas throws IllegalArgumentException
                // ("Software rendering doesn't support hardware bitmaps") on Android 15/16.
                // Copy directly into a software ARGB_8888 bitmap instead.
                wrapped.copy(Bitmap.Config.ARGB_8888, false)
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
                val result = store.saveWithResult(candidate, screenshot)
                val stored = when (result) {
                    is CandidateSaveResult.Saved -> result.candidate
                    is CandidateSaveResult.Failed -> {
                        val indexFull = result.reason == CandidateSaveFailure.INDEX_FULL
                        recordCandidateSaveFailure(
                            if (indexFull) "Candidate index budget full; page candidate was not saved"
                            else "Page capture candidate save failed",
                        )
                        if (indexFull) {
                            ScreenshotReviewNotifier.notifyCandidateStorageFull(
                                applicationContext,
                                CandidateStorageIntakeKind.SCREENSHOT_PAGE,
                            )
                        }
                        Log.e(TAG, "candidate save failed: ${result.reason}")
                        return@launch
                    }
                }
                advancePageThrottle(stored)
                if (PageCaptureScreenshotPolicy.isUnavailable(stored, screenshot)) {
                    recordCandidateSaveFailure("Screenshot evidence unavailable; candidate saved without image")
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "candidate saved identity=${candidate.pageIdentity}")
                }
            } catch (failure: Exception) {
                Log.e(TAG, "candidate save failed: ${failure.javaClass.simpleName}")
                recordCandidateSaveFailure("Page capture candidate save failed")
            } finally {
                capturing.set(false)
            }
        }
    }

    private fun isScreenshotBurstCooldown(
        windowId: Int,
        identity: String,
        nowMs: Long,
    ): Boolean = ScreenshotBurstCooldownPolicy.shouldSkip(
        previousWindowId = lastScreenshotWindowId,
        previousIdentity = lastScreenshotPageIdentity,
        previousAttemptAtMs = lastScreenshotAttemptAtMs,
        windowId = windowId,
        identity = identity,
        nowMs = nowMs,
        cooldownMs = SCREENSHOT_BURST_COOLDOWN_MS,
    )

    private fun recordScreenshotAttempt(
        windowId: Int,
        identity: String,
        nowMs: Long,
    ) {
        lastScreenshotWindowId = windowId
        lastScreenshotPageIdentity = identity
        lastScreenshotAttemptAtMs = nowMs
    }

    private fun advancePageThrottle(stored: CaptureCandidate?) {
        if (stored == null) return
        val current = PageCaptureThrottleState(
            identity = lastPageIdentity,
            eventId = lastPageEventId,
            capturedAtMs = lastCaptureAtMs,
        )
        val next = PageCaptureThrottlePolicy.afterVerifiedSave(
            current = current,
            stored = stored,
            savedAtMs = System.currentTimeMillis(),
        )
        updatePageThrottle(next)
    }

    private fun advancePageThrottleForBatch(
        intendedCandidateCount: Int,
        saved: List<CaptureCandidate>,
        failed: Int,
    ) {
        val current = PageCaptureThrottleState(
            identity = lastPageIdentity,
            eventId = lastPageEventId,
            capturedAtMs = lastCaptureAtMs,
        )
        val next = PageCaptureThrottlePolicy.afterVerifiedBatch(
            current = current,
            intendedCandidateCount = intendedCandidateCount,
            saved = saved,
            failed = failed,
            savedAtMs = System.currentTimeMillis(),
        )
        updatePageThrottle(next)
    }

    private fun updatePageThrottle(next: PageCaptureThrottleState) {
        lastPageIdentity = next.identity
        lastPageEventId = next.eventId
        lastCaptureAtMs = next.capturedAtMs
    }

    private suspend fun recordCandidateSaveFailure(details: String) {
        try {
            repository.recordDiagnostic(
                attemptId = TransactionDiagnostics.newAttemptId(),
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = details,
            )
        } catch (diagnosticFailure: Exception) {
            Log.e(TAG, "candidate save diagnostic failed: ${diagnosticFailure.javaClass.simpleName}")
        }
    }

    override fun onInterrupt() {
        ServiceHealthLog.record(
            context = applicationContext,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_INTERRUPTED,
            summary = "页面识别服务被系统中断",
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        capturing.set(false)
        ServiceHealthLog.record(
            context = applicationContext,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_DISCONNECTED,
            summary = "页面识别服务已解绑",
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        capturing.set(false)
        ServiceHealthLog.record(
            context = applicationContext,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_DESTROYED,
            summary = "页面识别服务已停止",
        )
        ocrEngine?.close()
        ocrEngine = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BkPageRecognition"
        private const val PAGE_CAPTURE_DEDUP_MS = 5 * 60_000L
        private const val SCREENSHOT_BURST_COOLDOWN_MS = 3_000L
        private const val MIN_TAKE_SCREENSHOT_INTERVAL_MS = 1_200L
        private const val CAPTURE_KEY_TTL_MS = 10 * 60_000L
        private const val NO_ORDER_ID_BURST_TTL_MS = 15_000L

        private fun noOrderIdBurstKey(candidate: CaptureCandidate): String {
            val merchant = candidate.merchantCandidates.firstOrNull().orEmpty().trim()
            return "${candidate.packageName}|${candidate.kind}|${candidate.amountCents}|$merchant"
        }
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

data class PageCaptureThrottleState(
    val identity: String? = null,
    val eventId: String? = null,
    val capturedAtMs: Long = 0L,
)

/** Advances callback throttling only after the candidate store returned its verified row. */
object PageCaptureThrottlePolicy {
    fun afterVerifiedSave(
        current: PageCaptureThrottleState,
        stored: CaptureCandidate?,
        savedAtMs: Long,
    ): PageCaptureThrottleState = stored?.let {
        PageCaptureThrottleState(
            identity = it.pageIdentity,
            eventId = it.orderId?.trim()?.takeIf(String::isNotEmpty),
            capturedAtMs = savedAtMs,
        )
    } ?: current

    fun afterVerifiedBatch(
        current: PageCaptureThrottleState,
        intendedCandidateCount: Int,
        saved: List<CaptureCandidate>,
        failed: Int,
        savedAtMs: Long,
    ): PageCaptureThrottleState {
        if (failed != 0 || saved.size != intendedCandidateCount) return current
        return afterVerifiedSave(
            current = current,
            stored = saved.firstOrNull(),
            savedAtMs = savedAtMs,
        )
    }
}

/** Screenshot acquisition or storage failure always routes a page card to review. */
object PageCaptureScreenshotPolicy {
    fun isUnavailable(stored: CaptureCandidate, screenshot: ByteArray?): Boolean =
        (screenshot != null && stored.screenshotPath == null) ||
            stored.screenshotUnavailableReason != null ||
            stored.reason.startsWith(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_PREFIX) ||
            stored.reason == "screenshot decode failed" ||
            stored.reason == "screenshot start failed" ||
            stored.reason.startsWith("screenshot error ")
}

/** A page text hash is only safe to throttle when it carries the same strong event ID. */
object PageIdentityThrottlePolicy {
    fun shouldSuppress(
        previousIdentity: String?,
        previousEventId: String?,
        previousCapturedAtMs: Long,
        identity: String,
        eventId: String?,
        nowMs: Long,
        windowMs: Long,
    ): Boolean {
        val stableEventId = eventId?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return previousIdentity == identity &&
            previousEventId?.trim() == stableEventId &&
            nowMs - previousCapturedAtMs < windowMs
    }
}

/**
 * Hardware screenshot calls have a system-enforced interval (~1s). Suppressing
 * sub-3s accessibility content bursts on the same window + page identity stops
 * back-to-back `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` failures without
 * suppressing distinct payment screens.
 */
object ScreenshotBurstCooldownPolicy {
    fun shouldSkip(
        previousWindowId: Int,
        previousIdentity: String?,
        previousAttemptAtMs: Long,
        windowId: Int,
        identity: String,
        nowMs: Long,
        cooldownMs: Long,
    ): Boolean {
        if (windowId == 0 || previousWindowId != windowId) return false
        if (previousIdentity != identity) return false
        return nowMs >= previousAttemptAtMs && nowMs - previousAttemptAtMs < cooldownMs
    }
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
            DuplicateReason.CROSS_SOURCE,
            DuplicateReason.SAME_SOURCE_BURST -> PageCaptureOutcome.IGNORED
        }
    }
}
