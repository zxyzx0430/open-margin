package com.bookkeeping.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Bundled ML Kit Chinese recognizer. The engine runs locally; callers must still
 * gate screenshots by payment-page checks and must never upload the image.
 */
class MlKitOcrEngine : OcrEngine {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        if (bitmap.isRecycled) return OcrResult(error = "bitmap_recycled")
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    if (continuation.isActive) {
                        val lines = text.textBlocks
                            .flatMap { block -> block.lines }
                            .map { line -> OcrLine(line.text, line.boundingBox) }
                        continuation.resume(
                            OcrResult(lines = lines, rawText = text.text)
                        )
                    }
                }
                .addOnFailureListener { failure ->
                    if (continuation.isActive) {
                        continuation.resume(
                            OcrResult(error = failure.javaClass.simpleName)
                        )
                    }
                }
        }
    }

    override fun close() {
        recognizer.close()
    }
}
