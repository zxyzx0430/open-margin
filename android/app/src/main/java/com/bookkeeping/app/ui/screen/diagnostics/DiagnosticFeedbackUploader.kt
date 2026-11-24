package com.bookkeeping.app.ui.screen.diagnostics

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class FeedbackUploadResult(
    val ok: Boolean,
    val feedbackId: String? = null,
    val errorMessage: String? = null,
)

object DiagnosticFeedbackUploader {
    const val DEFAULT_ENDPOINT = "https://zxyzx.me/open-margin/feedback/upload"
    private const val MAX_LOG_UTF8_BYTES = 1_800_000
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    internal fun buildPayloadJson(
        logText: String,
        description: String,
        contact: String,
        appVersion: String,
        versionCode: Int,
        deviceModel: String,
        androidVersion: String,
        fileName: String,
    ): String {
        val safeLog = truncateUtf8(logText, MAX_LOG_UTF8_BYTES)
        return JSONObject().apply {
            put("log_text", safeLog)
            put("description", description.trim().take(2000))
            put("contact", contact.trim().take(200))
            put("app_version", appVersion.trim())
            put("version_code", versionCode)
            put("device_model", deviceModel.trim())
            put("android_version", androidVersion.trim())
            put("file_name", fileName.trim())
        }.toString()
    }

    internal fun parseResponse(statusCode: Int, body: String): FeedbackUploadResult {
        if (statusCode !in 200..299) {
            return FeedbackUploadResult(
                ok = false,
                errorMessage = "HTTP $statusCode",
            )
        }
        return try {
            val json = JSONObject(body)
            val ok = json.optBoolean("ok", false)
            val feedbackId = json.optString("feedback_id").takeIf { it.isNotBlank() }
            if (ok) {
                FeedbackUploadResult(ok = true, feedbackId = feedbackId)
            } else {
                FeedbackUploadResult(ok = false, errorMessage = json.optString("error").ifBlank { "upload rejected" })
            }
        } catch (failure: Exception) {
            FeedbackUploadResult(ok = false, errorMessage = failure.message ?: "invalid response")
        }
    }

    fun upload(
        logText: String,
        description: String,
        contact: String,
        appVersion: String,
        versionCode: Int,
        deviceModel: String,
        androidVersion: String,
        fileName: String,
        endpoint: String = DEFAULT_ENDPOINT,
    ): FeedbackUploadResult {
        val payload = buildPayloadJson(
            logText = logText,
            description = description,
            contact = contact,
            appVersion = appVersion,
            versionCode = versionCode,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
            fileName = fileName,
        )
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            parseResponse(code, body)
        } catch (failure: IOException) {
            FeedbackUploadResult(ok = false, errorMessage = failure.message ?: "network error")
        } catch (failure: Exception) {
            FeedbackUploadResult(ok = false, errorMessage = failure.message ?: "upload error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return text
        val suffix = "\n\n...[truncated to fit 1.8MB limit]..."
        val limit = (maxBytes - suffix.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        var end = limit
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(raw, 0, end, Charsets.UTF_8) + suffix
    }
}
