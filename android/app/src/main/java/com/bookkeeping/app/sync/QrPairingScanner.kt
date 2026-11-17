package com.bookkeeping.app.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.i18n.tr
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

internal enum class QrScanFailure {
    /** No camera permission; barcode scanning was never started. */
    CAMERA_DENIED,

    /** A picked image decoded but held no QR code. */
    IMAGE_UNREADABLE,

    /** The content resolver refused to open the picked image. */
    IMAGE_ACCESS_DENIED,
}

/** Result of the gallery path; keeps "no QR" separate from "cannot read file". */
internal sealed interface QrImageDecodeResult {
    data class Decoded(val text: String) : QrImageDecodeResult
    data object Unreadable : QrImageDecodeResult
    data object AccessDenied : QrImageDecodeResult
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
            when (val decoded = withContext(Dispatchers.IO) { decodeQrFromUri(context, uri) }) {
                is QrImageDecodeResult.Decoded -> accept(decoded.text, true)
                QrImageDecodeResult.AccessDenied -> onFailure(QrScanFailure.IMAGE_ACCESS_DENIED)
                QrImageDecodeResult.Unreadable -> onFailure(QrScanFailure.IMAGE_UNREADABLE)
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
        ) { Text(tr("扫码连接")) }
        OutlinedButton(
            enabled = enabled,
            onClick = { galleryLauncher.launch("image/*") },
        ) { Text(tr("从相册选择二维码")) }
    }
}

/** Compact camera action for the pairing information page; it opens the camera directly. */
@Composable
internal fun PairingQrScanAction(
    enabled: Boolean = true,
    buttonLabel: String = "扫一扫",
    onPayload: (PairingPayload) -> Unit,
    onRejected: (PairingRejectReason) -> Unit,
    onFailure: (QrScanFailure) -> Unit,
) {
    val context = LocalContext.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        when (val parsed = PairingPayload.parse(result.contents)) {
            is PairingParseResult.Success -> onPayload(parsed.payload)
            is PairingParseResult.Rejected -> onRejected(parsed.reason)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanLauncher.launch(scanOptions())
        else onFailure(QrScanFailure.CAMERA_DENIED)
    }

    TextButton(
        enabled = enabled,
        onClick = {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) scanLauncher.launch(scanOptions())
            else permissionLauncher.launch(Manifest.permission.CAMERA)
        },
    ) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
        Text(tr(buttonLabel))
    }
}

private fun scanOptions(): ScanOptions = ScanOptions().apply {
    setCaptureActivity(PairingQrCaptureActivity::class.java)
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt(tr("对准电脑上的配对二维码"))
    setBeepEnabled(false)
    setOrientationLocked(false)
    setBarcodeImageEnabled(false)
}

/** Cap large photos; 2560 px still leaves ample pixels per QR module. */
private const val MAX_DECODE_DIMENSION = 2560

/**
 * Decodes the first QR found in a picked image. Runs off the main thread.
 *
 * The gallery path is deliberately independent from the camera path: it reads
 * the provider stream once, applies EXIF orientation, forces ARGB pixels and
 * tries normal/inverted binarization plus a pure-barcode fallback. A token is
 * never logged; only safe diagnostics (URI authority, sizes, exception type).
 */
internal fun decodeQrFromUri(context: Context, uri: Uri): QrImageDecodeResult {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (error: SecurityException) {
        logDebug("access denied: ${uri.scheme}://${uri.authority}")
        return QrImageDecodeResult.AccessDenied
    } catch (error: Exception) {
        logDebug("open failed: ${error.javaClass.simpleName}")
        return QrImageDecodeResult.Unreadable
    } ?: return QrImageDecodeResult.Unreadable
    if (bytes.isEmpty()) return QrImageDecodeResult.Unreadable

    val bitmap = try {
        decodeBitmap(bytes)
    } catch (error: Exception) {
        logDebug("bitmap failed: ${error.javaClass.simpleName}")
        null
    } ?: return QrImageDecodeResult.Unreadable

    return try {
        logDebug(
            "uri=${uri.scheme}://${uri.authority} bytes=${bytes.size} " +
                "bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config}"
        )
        val text = decodeQrBitmap(bitmap)
        logDebug("decoded length=${text?.length ?: -1}")
        if (text.isNullOrBlank()) {
            QrImageDecodeResult.Unreadable
        } else {
            QrImageDecodeResult.Decoded(text)
        }
    } catch (error: Exception) {
        logDebug("decode failed: ${error.javaClass.simpleName}")
        QrImageDecodeResult.Unreadable
    } finally {
        bitmap.recycle()
    }
}

private fun logDebug(message: String) {
    if (BuildConfig.DEBUG) Log.d("QrPairing", message)
}

private fun decodeBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DECODE_DIMENSION) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        // ARGB_8888 + mutable keeps the decoder on a software bitmap.
        // Hardware bitmaps throw from getPixels().
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    return applyOrientation(decoded, orientation)
}

private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    val oriented = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
    )
    if (oriented !== bitmap) bitmap.recycle()
    return oriented
}

/** Tries the four right-angle orientations, normal and inverted. */
internal fun decodeQrBitmap(bitmap: Bitmap): String? {
    val readable = if (bitmap.config == Bitmap.Config.ARGB_8888) {
        bitmap
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    }
    try {
        decodeAtOrientation(readable, allowPureMode = true)?.let { return it }
        for (degrees in intArrayOf(90, 180, 270)) {
            val rotated = Bitmap.createBitmap(
                readable, 0, 0, readable.width, readable.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true,
            )
            try {
                decodeAtOrientation(rotated, allowPureMode = false)?.let { return it }
            } finally {
                if (rotated !== readable) rotated.recycle()
            }
        }
    } finally {
        if (readable !== bitmap) readable.recycle()
    }
    return null
}

private fun decodeAtOrientation(bitmap: Bitmap, allowPureMode: Boolean): String? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    decodeSource(source, pure = false)?.let { return it }
    val inverted = InvertedLuminanceSource(source)
    decodeSource(inverted, pure = false)?.let { return it }
    if (allowPureMode) {
        // Setup-page QRs are rendered edge-to-edge; a tight crop has no quiet
        // zone, which the normal detector needs.
        decodeSource(source, pure = true)?.let { return it }
        decodeSource(inverted, pure = true)?.let { return it }
    }
    return null
}

private fun decodeSource(source: LuminanceSource, pure: Boolean): String? {
    val hints = buildMap<DecodeHintType, Any> {
        put(DecodeHintType.TRY_HARDER, true)
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        if (pure) put(DecodeHintType.PURE_BARCODE, true)
    }
    return try {
        MultiFormatReader().apply { setHints(hints) }
            .decode(BinaryBitmap(HybridBinarizer(source)))
            .text
    } catch (_: NotFoundException) {
        null
    } catch (_: ChecksumException) {
        null
    } catch (_: FormatException) {
        null
    }
}
