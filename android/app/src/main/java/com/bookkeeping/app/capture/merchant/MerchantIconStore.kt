package com.bookkeeping.app.capture.merchant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

interface MerchantIconStore {
    /** Saves bytes in app-private storage and returns a file name, or null on failure. */
    suspend fun save(key: String, bytes: ByteArray): String?

    suspend fun load(path: String): ByteArray?

    suspend fun delete(path: String)
}

/**
 * File-backed store. Icons are local-only and keyed by a hash of the merchant
 * key; callers persist only the returned file name. The integration layer wires
 * this to the local-only merchant icon field in the transaction row.
 */
class FileMerchantIconStore(
    context: Context,
) : MerchantIconStore {
    private val directory = File(context.filesDir, "merchant_icons")

    override suspend fun save(key: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (key.isBlank() || bytes.isEmpty()) return@withContext null
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching null
            val name = sha256(key) + ".img"
            val target = File(directory, name)
            val temp = File(directory, "$name.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            name
        }.getOrNull()
    }

    override suspend fun load(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = File(path).name
            if (safeName.isBlank() || safeName != path) return@runCatching null
            val file = File(directory, safeName)
            if (!file.isFile) null else file.readBytes()
        }.getOrNull()
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val safeName = File(path).name
                if (safeName.isNotBlank() && safeName == path) {
                    File(directory, safeName).delete()
                }
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
