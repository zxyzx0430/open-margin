package com.bookkeeping.app.capture.merchant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayOutputStream

data class MerchantIconResult(
    val bytes: ByteArray?,
    val reason: String,
)

/** Pure geometry checks so the risky parts can be JVM-tested. */
object IconCropPolicy {
    fun validate(
        imageWidth: Int,
        imageHeight: Int,
        region: Rect,
        minSize: Int = 16,
    ): Boolean = validate(
        imageWidth, imageHeight,
        region.left, region.top, region.right, region.bottom, minSize,
    )

    fun validate(
        imageWidth: Int,
        imageHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        minSize: Int = 16,
    ): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0) return false
        if (left < 0 || top < 0 || right > imageWidth || bottom > imageHeight) return false
        val width = right - left
        val height = bottom - top
        if (width < minSize || height < minSize) return false
        val aspect = width.toDouble() / height
        if (aspect !in 0.7..1.43) return false
        if (maxOf(width, height) > minOf(imageWidth, imageHeight) * 0.28) return false
        if (width.toDouble() * height > imageWidth.toDouble() * imageHeight * 0.04) return false
        return true
    }
}

/**
 * Crops a bounded merchant-logo region from the same screenshot used for OCR.
 * It never scans the whole image or guesses from text; callers must supply the
 * region from a page/OCR hint. A failed crop is non-fatal.
 */
object MerchantIconCropper {
    fun crop(
        bitmap: Bitmap,
        region: Rect,
        size: Int = 128,
    ): MerchantIconResult {
        if (bitmap.isRecycled) return MerchantIconResult(null, "bitmap_recycled")
        if (size < 32 || size > 256) return MerchantIconResult(null, "bad_output_size")
        if (!IconCropPolicy.validate(bitmap.width, bitmap.height, region)) {
            return MerchantIconResult(null, "bad_region")
        }
        val source = try {
            Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
        } catch (_: Exception) {
            return MerchantIconResult(null, "crop_failed")
        }
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(icon)
            canvas.drawColor(Color.WHITE)
            val inset = size * 0.08f
            val maxContent = size - inset * 2
            val scale = minOf(maxContent / source.width, maxContent / source.height)
            val drawWidth = (source.width * scale).toInt().coerceAtLeast(1)
            val drawHeight = (source.height * scale).toInt().coerceAtLeast(1)
            val left = (size - drawWidth) / 2
            val top = (size - drawHeight) / 2
            canvas.drawBitmap(
                source,
                null,
                Rect(left, top, left + drawWidth, top + drawHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            val png = encode(icon, Bitmap.CompressFormat.PNG, 100)
            val webp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                encode(icon, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
            } else {
                @Suppress("DEPRECATION")
                encode(icon, Bitmap.CompressFormat.WEBP, 100)
            }
            val best = when {
                png == null -> webp
                webp == null -> png
                png.size <= webp.size -> png
                else -> webp
            }
            return if (best == null) {
                MerchantIconResult(null, "encode_failed")
            } else {
                MerchantIconResult(best, "saved")
            }
        } catch (_: Exception) {
            return MerchantIconResult(null, "processing_failed")
        } finally {
            // createBitmap can return the original bitmap for a full-image region.
            if (source !== bitmap) source.recycle()
            icon.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        return if (bitmap.compress(format, quality, output)) output.toByteArray() else null
    }
}
