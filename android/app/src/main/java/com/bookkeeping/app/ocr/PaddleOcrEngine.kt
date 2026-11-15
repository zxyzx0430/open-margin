package com.bookkeeping.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.OCREngine
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * PaddleOCR V6 Tiny adapter. The native runtime is arm64-only in this build;
 * non-arm64 devices fall back to ML Kit so capture remains available.
 */
class PaddleOcrEngine(
    context: Context,
    private val fallback: OcrEngine? = null,
) : OcrEngine {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var engine: OCREngine? = null
    private var closed = false

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        if (bitmap.isRecycled) return OcrResult(error = "bitmap_recycled")
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            return fallback?.recognize(bitmap) ?: OcrResult(error = "paddle_abi_unsupported")
        }
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                if (closed) return@withContext OcrResult(error = "engine_closed")
                try {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    if (!OpenCVUtils.init(context)) {
                        return@withContext fallback?.recognize(bitmap)
                            ?: OcrResult(error = "opencv_init_failed")
                    }
                    val active = engine ?: OCREngine(
                        context,
                        PaddleOCRConfig(),
                        EngineConfig(numThreads = 4),
                        "models/v6/det/inference.onnx",
                        "models/v6/rec/inference.onnx",
                        "models/v6/rec/inference.yml",
                    ).also { engine = it }
                    val result = active.run(bitmap)
                    val lines = result.results.map { reading ->
                        val points = reading.box.points
                        OcrLine(
                            text = reading.text,
                            bounds = Rect(
                                points.minOf { it.x }.roundToInt().coerceIn(0, bitmap.width),
                                points.minOf { it.y }.roundToInt().coerceIn(0, bitmap.height),
                                points.maxOf { it.x }.roundToInt().coerceIn(0, bitmap.width),
                                points.maxOf { it.y }.roundToInt().coerceIn(0, bitmap.height),
                            ),
                            confidence = reading.confidence,
                        )
                    }
                    OcrResult(
                        lines = lines,
                        rawText = lines.joinToString("\n") { it.text },
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    runCatching { engine?.release() }
                    engine = null
                    fallback?.recognize(bitmap)
                        ?: OcrResult(error = failure.javaClass.simpleName)
                }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { engine?.release() }
        engine = null
        fallback?.close()
    }
}
