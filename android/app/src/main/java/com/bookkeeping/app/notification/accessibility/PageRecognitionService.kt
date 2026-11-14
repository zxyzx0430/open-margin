package com.bookkeeping.app.notification.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Optional success-page recognition service.
 *
 * It only reads WeChat/Alipay pages, only captures after the page passes the
 * pure PaymentPageDetector safety checks, and only writes a local candidate.
 * It never inserts a ledger row and never scrolls the source app.
 *
 * The user-visible entry is the compatibility subclass at
 * com.google.android.accessibility.selecttospeak.SelectToSpeakService.
 */
open class PageRecognitionService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val capturing = AtomicBoolean(false)

    private lateinit var settings: SettingsPreferences
    private lateinit var store: CaptureCandidateStore

    @Volatile private var captureEnabled = false
    @Volatile private var compatibilityEnabled = false

    private var lastPageIdentity: String? = null
    private var lastCaptureAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                AccessibilityServiceEntryPoint::class.java,
            )
            settings = entryPoint.settingsPreferences()
            store = CaptureCandidateStore(applicationContext)
            captureEnabled = settings.successPageCaptureEnabledBlocking()
            compatibilityEnabled = settings.compatibilityAccessibilityEnabledBlocking()
            scope.launch {
                settings.observeSuccessPageCaptureEnabled().collect { captureEnabled = it }
            }
            scope.launch {
                settings.observeCompatibilityAccessibilityEnabled().collect { compatibilityEnabled = it }
            }
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
        if (!captureEnabled || !compatibilityEnabled || capturing.get()) return
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
        // The page may have changed while the snapshot was being built.
        val active = rootInActiveWindow ?: return
        if (active.packageName?.toString() != packageName ||
            active.windowId != snapshot.root.windowId
        ) {
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
        )
        capturing.set(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenshot(candidate)
        } else {
            saveCandidate(candidate.copy(reason = "screenshot unsupported"), null)
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BkPageRecognition"
        private const val PAGE_CAPTURE_DEDUP_MS = 30_000L
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
    }
}
