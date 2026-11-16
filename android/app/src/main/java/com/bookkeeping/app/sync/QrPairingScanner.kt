package com.bookkeeping.app.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bookkeeping.app.i18n.AppLanguage
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI label for the two new pairing controls. The project-wide English map lives
 * outside this sync module's scope, so these labels stay local; prefer adding
 * them to EnglishUiText when that file is next touched.
 */
internal fun pairingText(chinese: String, english: String): String =
    if (AppLanguage.current() == AppLanguage.ENGLISH) english else chinese

internal enum class QrScanFailure {
    /** No camera permission; Barcode scanning was never started. */
    CAMERA_DENIED,

    /** A picked image decoded but held no QR code. */
    IMAGE_UNREADABLE,
}

/**
 * Camera scan + gallery decode for the Windows pairing payload.
 *
 * zxing-android-embedded is fully offline (no Google Play Services), which
 * matters on China ROMs. Camera permission is requested by this composable so
 * the gallery path stays usable when the user denies it.
 */
@Composable
internal fun QrPairingControls(
    enabled: Boolean,
    onPayload: (PairingPayload, Boolean) -> Unit,
    onRejected: (PairingRejectReason) -> Unit,
    onFailure: (QrScanFailure) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun accept(raw: String?, fromGallery: Boolean) {
        when (val parsed = PairingPayload.parse(raw)) {
            is PairingParseResult.Success -> onPayload(parsed.payload, fromGallery)
            is PairingParseResult.Rejected -> onRejected(parsed.reason)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) accept(result.contents, false)
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { decodeQrFromUri(context, uri) }
            if (text.isNullOrBlank()) {
                onFailure(QrScanFailure.IMAGE_UNREADABLE)
            } else {
                accept(text, true)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(scanOptions())
        } else {
            onFailure(QrScanFailure.CAMERA_DENIED)
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            enabled = enabled,
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    scanLauncher.launch(scanOptions())
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        ) { Text(pairingText("扫码连接", "Scan QR code")) }
        OutlinedButton(
            enabled = enabled,
            onClick = { galleryLauncher.launch("image/*") },
        ) { Text(pairingText("从相册选择二维码", "Choose QR from gallery")) }
    }
}

private fun scanOptions(): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt(pairingText("对准电脑上的配对二维码", "Point at the pairing QR on your PC"))
    setBeepEnabled(false)
    setOrientationLocked(false)
    setBarcodeImageEnabled(false)
}

private const val MAX_DECODE_DIMENSION = 1600

/**
 * Decodes the first QR found in a picked image. Kept off the main thread by
 * the caller; photos are sampled down before luminance conversion so a large
 * gallery image cannot exhaust the heap.
 */
internal fun decodeQrFromUri(context: Context, uri: Uri): String? = runCatching {
    // Read once: photo-picker grants can be one-shot providers, and decoding
    // bounds + pixels from the same bytes avoids a second provider round-trip.
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DECODE_DIMENSION) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        // ARGB_8888 + mutable keeps the decoder on a software bitmap.
        // Hardware bitmaps throw from getPixels(), which would look like
        // "no QR code found" for a perfectly readable image.
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: return@runCatching null
    try {
        decodeQrBitmap(bitmap)
    } finally {
        bitmap.recycle()
    }
}.getOrNull()

/** Tries the four right-angle orientations, then gives up. */
internal fun decodeQrBitmap(bitmap: Bitmap): String? {
    // Defensive: callers may hand over a hardware bitmap despite the
    // BitmapFactory options above; getPixels() is not supported there.
    val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        bitmap
    }
    try {
        return decodeQrBitmapReadable(readable)
    } finally {
        if (readable !== bitmap) readable.recycle()
    }
}

private fun decodeQrBitmapReadable(bitmap: Bitmap): String? {
    // Normal detector first (photos, screenshots with surrounding page).
    decodeWithHints(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))?.let { return it }
    // Some screenshot crops have no quiet zone; the setup page renders QR
    // modules edge-to-edge. Pure mode can still read those.
    return decodeWithHints(
        bitmap,
        mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.PURE_BARCODE to true),
    )
}

private fun decodeWithHints(
    bitmap: Bitmap,
    hints: Map<DecodeHintType, Any>,
): String? {
    val reader = MultiFormatReader().apply { setHints(hints) }
    for (degrees in intArrayOf(0, 90, 180, 270)) {
        val candidate = if (degrees == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true,
            )
        }
        try {
            val pixels = IntArray(candidate.width * candidate.height)
            candidate.getPixels(pixels, 0, candidate.width, 0, 0, candidate.width, candidate.height)
            val source = RGBLuminanceSource(candidate.width, candidate.height, pixels)
            return reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            // Try the next orientation.
        } catch (_: ChecksumException) {
            // Try the next orientation.
        } catch (_: FormatException) {
            // Try the next orientation.
        } catch (_: Exception) {
            // A bitmap-level failure is not retryable in another orientation.
            return null
        } finally {
            if (candidate !== bitmap) candidate.recycle()
        }
    }
    return null
}
