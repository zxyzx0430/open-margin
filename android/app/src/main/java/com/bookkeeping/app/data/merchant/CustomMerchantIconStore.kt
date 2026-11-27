package com.bookkeeping.app.data.merchant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.bookkeeping.app.capture.merchant.FileMerchantIconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.min

data class CustomMerchantIconEntry(
    val merchant: String,
    val brandKey: String? = null,
    val imageFileName: String? = null,
)

/**
 * Local per-merchant custom icon overrides (either a built-in brand drawable key
 * or a cropped local image file stored in filesDir/merchant_icons).
 */
class CustomMerchantIconStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _overrides = MutableStateFlow(loadAll())
    val overrides: StateFlow<Map<String, CustomMerchantIconEntry>> = _overrides.asStateFlow()

    fun resolve(merchant: String): CustomMerchantIconEntry? =
        resolveFrom(_overrides.value, merchant)

    fun setBrandIcon(merchant: String, brandKey: String) {
        val trimmed = merchant.trim()
        val key = normalizeKey(trimmed)
        if (key.isEmpty() || brandKey.isBlank()) return
        val next = _overrides.value.toMutableMap()
        next[key] = CustomMerchantIconEntry(
            merchant = trimmed,
            brandKey = brandKey,
            imageFileName = null,
        )
        saveAndEmit(next)
    }

    fun setImageIcon(merchant: String, imageFileName: String) {
        val trimmed = merchant.trim()
        val key = normalizeKey(trimmed)
        if (key.isEmpty() || imageFileName.isBlank()) return
        val next = _overrides.value.toMutableMap()
        next[key] = CustomMerchantIconEntry(
            merchant = trimmed,
            brandKey = null,
            imageFileName = imageFileName,
        )
        saveAndEmit(next)
    }

    fun remove(merchant: String) {
        val key = normalizeKey(merchant)
        if (key.isEmpty()) return
        val current = _overrides.value
        if (!current.containsKey(key)) return
        val next = current.toMutableMap()
        next.remove(key)
        saveAndEmit(next)
    }

    private fun saveAndEmit(map: Map<String, CustomMerchantIconEntry>) {
        val arr = JSONArray()
        map.values.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("merchant", entry.merchant)
                    if (entry.brandKey != null) put("brandKey", entry.brandKey)
                    if (entry.imageFileName != null) put("imageFileName", entry.imageFileName)
                },
            )
        }
        prefs.edit().putString(KEY_OVERRIDES, arr.toString()).apply()
        _overrides.value = map
    }

    private fun loadAll(): Map<String, CustomMerchantIconEntry> {
        val raw = prefs.getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val result = linkedMapOf<String, CustomMerchantIconEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val merchant = obj.optString("merchant", "").trim()
                val key = normalizeKey(merchant)
                if (key.isEmpty()) continue
                val brandKey = obj.optString("brandKey", "").takeIf { it.isNotBlank() }
                val imageFileName = obj.optString("imageFileName", "").takeIf { it.isNotBlank() }
                if (brandKey != null || imageFileName != null) {
                    result[key] = CustomMerchantIconEntry(merchant, brandKey, imageFileName)
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val PREFS_NAME = "open_margin_custom_merchant_icons"
        private const val KEY_OVERRIDES = "overrides_json"

        @Volatile
        private var instance: CustomMerchantIconStore? = null

        fun get(context: Context): CustomMerchantIconStore =
            instance ?: synchronized(this) {
                instance ?: CustomMerchantIconStore(context).also { instance = it }
            }

        fun normalizeKey(merchant: String): String = merchant.trim().lowercase()

        fun resolveFrom(
            overrides: Map<String, CustomMerchantIconEntry>,
            merchant: String,
        ): CustomMerchantIconEntry? {
            val key = normalizeKey(merchant)
            if (key.isEmpty()) return null
            return overrides[key]
        }

        /**
         * Decodes [uri], center-crops to a 1:1 square, scales to 192x192 PNG,
         * and stores it inside filesDir/merchant_icons/.
         */
        suspend fun savePickedImage(
            context: Context,
            uri: Uri,
            merchant: String,
        ): String? = withContext(Dispatchers.IO) {
            val normalized = normalizeKey(merchant).ifEmpty { "merchant" }
            val bytes = runCatching {
                val appContext = context.applicationContext
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                val maxSrc = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (maxSrc / sample > 768) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                } ?: return@runCatching null

                val side = min(decoded.width, decoded.height).coerceAtLeast(1)
                val x = (decoded.width - side) / 2
                val y = (decoded.height - side) / 2
                val cropped = if (decoded.width != decoded.height) {
                    Bitmap.createBitmap(decoded, x, y, side, side).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } else {
                    decoded
                }
                val scaled = if (cropped.width != 192 || cropped.height != 192) {
                    Bitmap.createScaledBitmap(cropped, 192, 192, true).also {
                        if (it !== cropped) cropped.recycle()
                    }
                } else {
                    cropped
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 95, out)
                scaled.recycle()
                out.toByteArray()
            }.getOrNull() ?: return@withContext null

            FileMerchantIconStore(context.applicationContext)
                .save("custom_${normalized}_${System.currentTimeMillis()}", bytes)
        }
    }
}
