package com.bookkeeping.app.notification.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.ocr.MlKitOcrEngine
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
 * pure PaymentPageDetector safety checks, and only writes a local candidate.
 * It never inserts a ledger row and never scrolls the source app.
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
    private var ocrEngine: MlKitOcrEngine? = null

    private var lastPageIdentity: String? = null
    private var lastCaptureAtMs: Long = 0L

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

        val now = System.currentTimeMillis()
        val snapshot = root.toSnapshot(packageName, now)

        // WeChat Pay bill cards: refund arrival, pay-after-use, merchant payment.
        // They are not "success pages"; parse them as separate card candidates.
        val billCards = WechatBillCardDetector.fromSnapshot(packageName, snapshot, now)
        if (billCards.isNotEmpty()) {
            val pageIdentity = billCards.first().pageIdentity
            if (pageIdentity == lastPageIdentity && now - lastCaptureAtMs < PAGE_CAPTURE_DEDUP_MS) return
            if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
            val candidates = billCards.map { card ->
                CaptureCandidate(
                    packageName = packageName,
                    pageIdentity = card.pageIdentity,
                    amountCents = card.amountCents,
                    merchantCandidates = card.merchantCandidates,
                    orderId = card.orderId,
                    capturedAtMs = now,
                    screenshotPath = null,
                    confidence = card.confidence,
                    reason = card.reason,
                    kind = when (card.kind) {
                        BillCardKind.REFUND -> CaptureCandidate.KIND_REFUND
                        BillCardKind.AUTO_PAY -> CaptureCandidate.KIND_AUTO_PAY
                        BillCardKind.PAYMENT -> CaptureCandidate.KIND_PAYMENT
                    },
                    eventTimeMs = card.eventTimeMs ?: now,
                )
            }
            capturing.set(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureScreenshotForCandidates(candidates)
            } else {
                processCandidates(candidates.map { it.copy(reason = "screenshot unsupported") }, null)
            }
            return
        }

        // If accessibility nodes are incomplete, OCR the visible WeChat Pay bill
        // page and parse the same card layout from OCR lines.
        val pageText = snapshot.flattenText()
        if (packageName == PaymentPageDetector.PKG_WECHAT && looksLikeWechatBill(pageText)) {
            val pageIdentity = "wechat-bill|${Integer.toHexString(pageText.hashCode())}"
            if (pageIdentity == lastPageIdentity &&
                now - lastCaptureAtMs < PAGE_CAPTURE_DEDUP_MS
            ) {
                return
            }
            if (!windowStillCurrent(root, packageName, snapshot.root.windowId)) return
            capturing.set(true)
            lastPageIdentity = pageIdentity
            lastCaptureAtMs = now
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureScreenshotForOcr(pageIdentity, now)
            } else {
                capturing.set(false)
            }
            return
        }

        // Success-page fallback is part of the same pipeline.
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
            captureScreenshotForCandidates(listOf(candidate))
        } else {
            processCandidates(listOf(candidate.copy(reason = "screenshot unsupported")), null)
        }
    }

    private fun looksLikeWechatBill(text: String): Boolean =
        text.contains("微信支付") &&
            (text.contains("账单") || text.contains("退款") ||
                text.contains("先用后付") || text.contains("已支付") ||
                text.contains("交易详情"))

    @android.annotation.SuppressLint("NewApi")
    private fun captureScreenshotForOcr(pageIdentity: String, now: Long) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = runCatching { screenshotToBitmap(screenshot) }.getOrNull()
                        if (bitmap == null) {
                            capturing.set(false)
                            return
                        }
                        val height = bitmap.height
                        scope.launch {
                            try {
                                val bytes = encodeScreenshot(bitmap)
                                val engine = ocrEngine ?: MlKitOcrEngine().also { ocrEngine = it }
                                val result = withContext(Dispatchers.Default) {
                                    engine.recognize(bitmap)
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
                                val cards = WechatBillCardDetector.fromLines(
                                    packageName = PaymentPageDetector.PKG_WECHAT,
                                    lines = lines,
                                    capturedAtMs = now,
                                    screenHeightPx = height,
                                    pageIdentity = pageIdentity,
                                )
                                val candidates = cards.map { card ->
                                    billCardCandidate(PaymentPageDetector.PKG_WECHAT, pageIdentity, card, now)
                                }
                                if (candidates.isEmpty()) {
                                    capturing.set(false)
                                } else {
                                    processCandidates(candidates, bytes)
                                }
                            } catch (failure: Exception) {
                                Log.e(TAG, "OCR capture failed: ${failure.javaClass.simpleName}")
                                capturing.set(false)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "OCR screenshot failed: $errorCode")
                        capturing.set(false)
                    }
                },
            )
        } catch (failure: RuntimeException) {
            Log.e(TAG, "OCR screenshot start failed: ${failure.javaClass.simpleName}")
            capturing.set(false)
        }
    }

    private fun billCardCandidate(
        packageName: String,
        pageIdentity: String,
        card: BillCardDetection,
        now: Long,
    ): CaptureCandidate = CaptureCandidate(
        packageName = packageName,
        pageIdentity = pageIdentity,
        amountCents = card.amountCents,
        merchantCandidates = card.merchantCandidates,
        orderId = card.orderId,
        capturedAtMs = now,
        screenshotPath = null,
        confidence = card.confidence,
        reason = card.reason,
        kind = when (card.kind) {
            BillCardKind.REFUND -> CaptureCandidate.KIND_REFUND
            BillCardKind.AUTO_PAY -> CaptureCandidate.KIND_AUTO_PAY
            BillCardKind.PAYMENT -> CaptureCandidate.KIND_PAYMENT
        },
        eventTimeMs = card.eventTimeMs ?: now,
    )

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

    @android.annotation.SuppressLint("NewApi")
    private fun captureScreenshotForCandidates(candidates: List<CaptureCandidate>) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = runCatching { screenshotToBitmap(screenshot) }.getOrNull()
                        val bytes = bitmap?.let(::compressScreenshot)
                        processCandidates(candidates, bytes)
                    }

                    override fun onFailure(errorCode: Int) {
                        processCandidates(
                            candidates.map { it.copy(reason = "screenshot error $errorCode") },
                            null,
                        )
                    }
                },
            )
        } catch (failure: RuntimeException) {
            processCandidates(
                candidates.map { it.copy(reason = "screenshot start failed") },
                null,
            )
        }
    }

    private fun processCandidates(candidates: List<CaptureCandidate>, screenshot: ByteArray?) {
        scope.launch {
            try {
                candidates.forEach { candidate ->
                    val stored = store.save(candidate, screenshot) ?: return@forEach
                    if (stored.confidence >= AUTO_INSERT_CONFIDENCE &&
                        stored.merchantCandidates.isNotEmpty()
                    ) {
                        val inserted = runCatching {
                            repository.insertIfNotDuplicate(
                                CaptureReviewMapping.toTransactionEntity(
                                    candidate = stored,
                                    merchant = stored.merchantCandidates.first(),
                                    category = TransactionCategory.OTHER,
                                ),
                                notifySuccess = false,
                            )
                        }.getOrNull()
                        if (inserted != null) {
                            store.mark(
                                stored,
                                if (inserted > 0L) CaptureCandidate.STATUS_APPROVED
                                else CaptureCandidate.STATUS_IGNORED,
                            )
                        }
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
        private const val AUTO_INSERT_CONFIDENCE = 0.85f
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
    }
}
