package com.bookkeeping.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/** One OCR line with its screen/image bounds. Confidence is optional; ML Kit may omit it. */
data class OcrLine(
    val text: String,
    val bounds: Rect? = null,
    val confidence: Float? = null,
)

data class OcrResult(
    val lines: List<OcrLine> = emptyList(),
    val rawText: String = "",
    val error: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty() && rawText.isBlank()
}

/** Local OCR abstraction so the capture pipeline can swap engines without touching parsing. */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult

    /** Engines that own native resources override this. */
    fun close() = Unit
}
