package com.bookkeeping.app.notification.capture

import android.content.Context
import com.bookkeeping.app.capture.screenshot.ScreenshotRetention
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import java.io.File
import java.util.Locale

/** Local candidate inbox; it never writes the ledger. */
class CaptureCandidateStore internal constructor(private val filesRoot: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val dir = File(filesRoot, "page_capture")
    private val indexFile = File(dir, "candidates.jsonl")
    private val lock = Any()

    fun save(candidate: CaptureCandidate, screenshot: ByteArray?): CaptureCandidate? {
        if (!candidate.isValid()) return null
        synchronized(lock) {
            if (!dir.exists() && !dir.mkdirs()) return null
            runCatching { pruneExpiredLocked() }
            val screenshotPath = screenshot
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_SCREENSHOT_BYTES }
                ?.let { bytes ->
                    val file = File(dir, "shot_${candidate.candidateId}.jpg")
                    if (runCatching { file.writeBytes(bytes) }.isSuccess) file.absolutePath else null
                } ?: candidate.screenshotPath?.takeIf(::isAppPrivateFile)
            val stored = candidate.copy(
                screenshotPath = screenshotPath,
                diagnosticAttemptId = candidate.diagnosticAttemptId
                    ?: TransactionDiagnostics.newAttemptId(),
            )
            val saved = runCatching {
                indexFile.appendText(stored.toJson().toString() + "\n")
                trimIndex()
                runCatching { pruneExpiredLocked() }
                true
            }.getOrDefault(false)
            if (!saved && screenshotPath != null) deleteOwnedFile(screenshotPath)
            return stored.takeIf { saved }
        }
    }

    fun latest(limit: Int = 50): List<CaptureCandidate> = synchronized(lock) {
        runCatching { pruneExpiredLocked() }
        val collapsed = linkedMapOf<String, CaptureCandidate>()
        readAll().forEach { candidate -> collapsed[candidate.candidateId] = candidate }
        collapsed.values
            .sortedBy { it.capturedAtMs }
            .takeLast(limit.coerceIn(1, MAX_ENTRIES))
    }

    fun findByImageFingerprint(fingerprint: String): CaptureCandidate? = synchronized(lock) {
        runCatching { pruneExpiredLocked() }
        readAll().lastOrNull { it.imageFingerprint == fingerprint }
    }

    fun pending(limit: Int = 50): List<CaptureCandidate> =
        latest(MAX_ENTRIES)
            .filter { it.status == CaptureCandidate.STATUS_PENDING }
            .groupBy { it.imageFingerprint ?: it.candidateId }
            .values
            .mapNotNull { group -> group.minByOrNull { it.capturedAtMs } }
            .sortedBy { it.capturedAtMs }
            .take(limit.coerceIn(1, MAX_ENTRIES))

    /** Return one unambiguous waiting item for later state evidence. */
    fun relatedWaiting(incoming: CaptureCandidate, nowMs: Long): CaptureCandidate? = synchronized(lock) {
        runCatching { pruneExpiredLocked() }
        val incomingOrderId = incoming.orderId?.trim()?.takeIf(String::isNotEmpty)
        val waiting = latest(MAX_ENTRIES).filter { it.status == CaptureCandidate.STATUS_WAITING }
        val exactOrder = incomingOrderId?.let { order ->
            waiting.filter {
                it.paymentPlatform == incoming.paymentPlatform && it.orderId?.trim() == order
            }
        }.orEmpty()
        if (exactOrder.size == 1) return@synchronized exactOrder.single()
        if (exactOrder.size > 1) return@synchronized null

        val merchant = normalizeMerchant(incoming.merchantCandidates.firstOrNull())
        if (merchant.isBlank() || incomingOrderId != null) return@synchronized null
        val matches = waiting.filter { previous ->
            previous.orderId == null &&
                previous.paymentPlatform == incoming.paymentPlatform &&
                previous.kind == incoming.kind &&
                previous.amountCents == incoming.amountCents &&
                previous.direction == incoming.direction &&
                normalizeMerchant(previous.merchantCandidates.firstOrNull()) == merchant &&
                kotlin.math.abs(nowMs - previous.capturedAtMs) <= CORRELATION_WINDOW_MS
        }
        matches.singleOrNull()
    }

    /** Appends a status record; readers keep the last record for each candidateId. */
    fun mark(candidate: CaptureCandidate, status: String): Boolean {
        if (status !in CaptureCandidate.STATUSES) return false
        synchronized(lock) {
            if (!dir.exists() && !dir.mkdirs()) return false
            return runCatching {
                indexFile.appendText(candidate.copy(status = status).toJson().toString() + "\n")
                trimIndex()
                runCatching { pruneExpiredLocked() }
                true
            }.getOrDefault(false)
        }
    }

    fun clear() {
        synchronized(lock) { dir.deleteRecursively() }
    }

    /** Explicit so app startup and scheduled cleanup can retry without a write. */
    fun pruneExpired(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        runCatching { pruneExpiredLocked(nowMs) }.isSuccess
    }

    private fun readAll(): List<CaptureCandidate> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            indexFile.readLines().mapNotNull { line ->
                runCatching { CaptureCandidate.fromJson(org.json.JSONObject(line)) }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    private fun trimIndex() {
        if (!indexFile.isFile) return
        if (indexFile.length() <= MAX_INDEX_BYTES && indexFile.readLines().size <= MAX_ENTRIES) return
        val kept = indexFile.readLines().takeLast(MAX_ENTRIES)
        indexFile.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    private fun pruneExpiredLocked(nowMs: Long = System.currentTimeMillis()) {
        if (!dir.isDirectory) return
        val referenced = mutableSetOf<String>()
        val expiredPaths = mutableSetOf<String>()
        if (indexFile.isFile) {
            val keptLines = mutableListOf<String>()
            indexFile.readLines().forEach { line ->
                val candidate = runCatching {
                    CaptureCandidate.fromJson(org.json.JSONObject(line))
                }.getOrNull() ?: return@forEach
                if (ScreenshotRetention.isExpired(candidate.capturedAtMs, nowMs)) {
                    candidate.screenshotPath?.let(expiredPaths::add)
                } else {
                    keptLines += line
                    candidate.screenshotPath?.let(referenced::add)
                }
            }
            val replacement = File(dir, INDEX_TMP_NAME)
            replacement.writeText(keptLines.joinToString("\n", postfix = if (keptLines.isEmpty()) "" else "\n"))
            if (!replacement.renameTo(indexFile)) {
                replacement.copyTo(indexFile, overwrite = true)
                replacement.delete()
            }
        }
        expiredPaths.filterNot(referenced::contains).forEach(::deleteOwnedFile)
        val files = dir.listFiles().orEmpty()
        val referencedNames = files.filter { it.absolutePath in referenced }.map { it.name }.toSet()
        ScreenshotRetention.orphans(
            files = files.map { it.name },
            referencedNames = referencedNames,
            keepNames = setOf(indexFile.name, INDEX_TMP_NAME),
        ).forEach { name -> File(dir, name).deleteRecursively() }
    }

    private fun isAppPrivateFile(path: String): Boolean = runCatching {
        val target = File(path).canonicalFile
        val root = filesRoot.canonicalFile
        target.path.startsWith(root.path + File.separator) && target.isFile
    }.getOrDefault(false)

    private fun deleteOwnedFile(path: String) {
        runCatching {
            val target = File(path).canonicalFile
            if (target.parentFile == dir.canonicalFile) target.delete()
        }
    }

    private fun normalizeMerchant(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    companion object {
        private const val MAX_ENTRIES = 200
        private const val MAX_INDEX_BYTES = 512 * 1024L
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
        private const val INDEX_TMP_NAME = "candidates.jsonl.tmp"
        private const val CORRELATION_WINDOW_MS = 10L * 60 * 1000
    }
}
