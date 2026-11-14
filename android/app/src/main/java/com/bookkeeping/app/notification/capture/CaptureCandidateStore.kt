package com.bookkeeping.app.notification.capture

import android.content.Context
import java.io.File
import kotlin.math.abs

/**
 * Small local inbox for page-capture candidates. It is not the ledger: a later
 * coordinator reviews these candidates, applies normal deduplication, and only
 * then inserts a transaction. Screenshots stay in app-private storage.
 */
class CaptureCandidateStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "page_capture")
    private val indexFile = File(dir, "candidates.jsonl")
    private val lock = Any()

    fun save(candidate: CaptureCandidate, screenshot: ByteArray?): CaptureCandidate? {
        if (!candidate.isValid()) return null
        synchronized(lock) {
            if (!dir.exists() && !dir.mkdirs()) return null
            val screenshotPath = screenshot
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_SCREENSHOT_BYTES }
                ?.let { bytes ->
                    val file = File(
                        dir,
                        "shot_${candidate.capturedAtMs}_${abs(candidate.pageIdentity.hashCode())}.jpg",
                    )
                    if (runCatching { file.writeBytes(bytes) }.isSuccess) file.absolutePath else null
                }
            val stored = candidate.copy(screenshotPath = screenshotPath)
            runCatching {
                indexFile.appendText(stored.toJson().toString() + "\n")
                trimIndex()
            }.onFailure { return null }
            return stored
        }
    }

    fun latest(limit: Int = 50): List<CaptureCandidate> = synchronized(lock) {
        if (!indexFile.isFile) return emptyList()
        val candidates = runCatching {
            indexFile.readLines()
                .asSequence()
                .mapNotNull { line ->
                    runCatching {
                        CaptureCandidate.fromJson(org.json.JSONObject(line))
                    }.getOrNull()
                }
                .toList()
        }.getOrElse { emptyList() }
        candidates.sortedBy { it.capturedAtMs }.takeLast(limit.coerceIn(1, MAX_ENTRIES))
    }

    fun clear() {
        synchronized(lock) {
            dir.deleteRecursively()
        }
    }

    private fun trimIndex() {
        if (!indexFile.isFile) return
        if (indexFile.length() <= MAX_INDEX_BYTES && indexFile.readLines().size <= MAX_ENTRIES) return
        val kept = indexFile.readLines().takeLast(MAX_ENTRIES)
        indexFile.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    companion object {
        private const val MAX_ENTRIES = 200
        private const val MAX_INDEX_BYTES = 512 * 1024L
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
    }
}
