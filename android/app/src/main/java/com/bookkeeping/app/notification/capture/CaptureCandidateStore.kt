package com.bookkeeping.app.notification.capture

import android.content.Context
import com.bookkeeping.app.capture.screenshot.ScreenshotRetention
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import java.io.File

/** Local candidate inbox; it never writes the ledger. */
class CaptureCandidateStore internal constructor(private val filesRoot: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val dir = File(filesRoot, "page_capture")
    private val indexFile = File(dir, "candidates.jsonl")

    fun save(candidate: CaptureCandidate, screenshot: ByteArray?): CaptureCandidate? {
        if (!candidate.isValid()) return null
        synchronized(lock) {
            if (!dir.exists() && !dir.mkdirs()) return null
            runCatching { pruneExpiredLocked() }
            // APPROVED 是终态：这笔钱已经写进账本。重复 ingest 同一张截图
            // （MediaStore 多次回调 / 兜底扫描）会算出同一个 candidateId，
            // 再 append 一条 PENDING 就是把它从「已入账」拖回待确认/已忽略，
            // 用户看到「账本有、候选说漏了」。拒绝写入，调用方保留原记录。
            if (lastRecordLocked(candidate.candidateId)?.status == CaptureCandidate.STATUS_APPROVED) {
                return null
            }
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

    /**
     * The card recorded under [candidateId], or null.
     *
     * candidateId is derived from the image fingerprint, the draft index and the
     * money, so the same screenshot read twice resolves to the same card. That is
     * what lets the intake coordinator recognise a re-ingest before it writes a
     * second ledger row or revives a card that is already settled.
     */
    fun findByCandidateId(candidateId: String): CaptureCandidate? = synchronized(lock) {
        if (candidateId.isBlank()) return@synchronized null
        runCatching { pruneExpiredLocked() }
        lastRecordLocked(candidateId)
    }

    /**
     * Every card recorded for one image, newest record per candidateId.
     *
     * One screenshot can hold two payments; they stay two cards. Used by the
     * intake coordinator to stop before OCR when every card of this exact image
     * is already settled (APPROVED / IGNORED / LINKED).
     */
    fun byImageFingerprint(fingerprint: String): List<CaptureCandidate> = synchronized(lock) {
        runCatching { pruneExpiredLocked() }
        val collapsed = linkedMapOf<String, CaptureCandidate>()
        readAll()
            .filter { it.imageFingerprint == fingerprint }
            .forEach { collapsed[it.candidateId] = it }
        collapsed.values.toList()
    }

    /**
     * Every waiting card, oldest first.
     *
     * One screenshot can hold two payments and they stay two cards: collapsing
     * them by [CaptureCandidate.imageFingerprint] hid the second one, which then
     * "reappeared" after the first was ignored and looked like a closed card
     * coming back. Repeated imports of the same image are stopped upstream by
     * the importer's repeatImage rule, so candidateId is the only key needed.
     */
    fun pending(limit: Int = 50): List<CaptureCandidate> =
        latest(MAX_ENTRIES)
            .filter { it.status == CaptureCandidate.STATUS_PENDING }
            .distinctBy { it.candidateId }
            .sortedBy { it.capturedAtMs }
            .take(limit.coerceIn(1, MAX_ENTRIES))

    /**
     * Cards that were ignored, newest first, for the recovery list.
     *
     * Same identity rule as [pending]: one screenshot can hold two payments and
     * they stay two rows, so restoring one never hides or resurrects the other.
     * The store has a single IGNORED status for both user taps and automatic
     * skips, so both kinds show up here; the user decides which ones to bring
     * back.
     */
    fun ignored(limit: Int = 50): List<CaptureCandidate> =
        latest(MAX_ENTRIES)
            .filter { it.status == CaptureCandidate.STATUS_IGNORED }
            .distinctBy { it.candidateId }
            .sortedByDescending { it.capturedAtMs }
            .take(limit.coerceIn(1, MAX_ENTRIES))

    /**
     * Put one ignored card back into review.
     *
     * Only a PENDING status record is appended: nothing here touches the ledger,
     * so the user still has to confirm the card. [nowMs] is a parameter so the
     * retention boundary is testable without waiting seven days, and the lookup
     * deliberately runs before [pruneExpiredLocked] so a record that is expired
     * but still on disk answers [RestoreOutcome.EXPIRED] instead of vanishing
     * into "not found".
     */
    fun restore(candidateId: String, nowMs: Long = System.currentTimeMillis()): RestoreOutcome =
        synchronized(lock) {
            if (candidateId.isBlank()) return@synchronized RestoreOutcome.NOT_FOUND
            val record = readAll().lastOrNull { it.candidateId == candidateId }
            when {
                record == null -> RestoreOutcome.NOT_FOUND
                ScreenshotRetention.isExpired(record.capturedAtMs, nowMs) ->
                    RestoreOutcome.EXPIRED
                record.status != CaptureCandidate.STATUS_IGNORED -> RestoreOutcome.NOT_IGNORED
                !dir.exists() && !dir.mkdirs() -> RestoreOutcome.WRITE_FAILED
                else -> {
                    val written = runCatching {
                        indexFile.appendText(
                            record.copy(status = CaptureCandidate.STATUS_PENDING)
                                .toJson().toString() + "\n",
                        )
                        trimIndex()
                        true
                    }.getOrDefault(false)
                    if (written) {
                        runCatching { pruneExpiredLocked(nowMs) }
                        RestoreOutcome.RESTORED
                    } else {
                        RestoreOutcome.WRITE_FAILED
                    }
                }
            }
        }

    /**
     * Return one unambiguous waiting item for later state evidence.
     *
     * Delegates to [ScreenshotWaitingCorrelation] so the screenshot coordinator,
     * the store and the unit tests all share one rule.
     */
    fun relatedWaiting(incoming: CaptureCandidate, nowMs: Long): CaptureCandidate? = synchronized(lock) {
        runCatching { pruneExpiredLocked() }
        ScreenshotWaitingCorrelation.settleTarget(incoming, latest(MAX_ENTRIES), nowMs)
    }

    /** Appends a status record; readers keep the last record for each candidateId. */
    fun mark(candidate: CaptureCandidate, status: String): Boolean {
        if (status !in CaptureCandidate.STATUSES) return false
        synchronized(lock) {
            // APPROVED 是终态，只能来自一次成功的账本写入。之后的任何 mark
            // （IGNORED / PENDING / WAITING / LINKED）都不得把它降级：重复 ingest
            // 命中自己刚写入的那一行时，旧代码正是用一条 IGNORED 把已入账的卡片
            // 拖进「已忽略的截图」，于是同一笔钱「账本有、候选说已忽略」。
            // 重复写 APPROVED 本身是幂等成功，不再追加一条记录。
            if (lastRecordLocked(candidate.candidateId)?.status == CaptureCandidate.STATUS_APPROVED) {
                return status == CaptureCandidate.STATUS_APPROVED
            }
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

    /** Newest record for one candidateId; readers keep the last line per id. */
    private fun lastRecordLocked(candidateId: String): CaptureCandidate? =
        readAll().lastOrNull { it.candidateId == candidateId }

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

    companion object {
        // Store instances share the same on-disk index, including background cleanup.
        private val lock = Any()
        private const val MAX_ENTRIES = 200
        private const val MAX_INDEX_BYTES = 512 * 1024L
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
        private const val INDEX_TMP_NAME = "candidates.jsonl.tmp"
    }
}

/**
 * Why [CaptureCandidateStore.restore] did or did not bring a card back.
 *
 * Every failure keeps a distinct value: "已过期" and "找不到" are different
 * stories for the user, and a silent success on a cleaned-up screenshot is the
 * bug this type exists to prevent.
 */
enum class RestoreOutcome {
    RESTORED,
    NOT_FOUND,
    EXPIRED,
    NOT_IGNORED,
    WRITE_FAILED,
}

/**
 * 已经有定论的候选状态：入账、已忽略、已关联到账本里的一行。
 *
 * 同一张截图被重复 ingest（MediaStore 多次回调 / 兜底扫描）时，这些状态都不该
 * 再被识别一遍、更不该再写一行账；只有 APPROVED 是**不可逆**的，其余两个
 * 仍可由用户在「已忽略的截图」里 restore 回待确认。
 */
internal val TERMINAL_CANDIDATE_STATUSES = setOf(
    CaptureCandidate.STATUS_APPROVED,
    CaptureCandidate.STATUS_IGNORED,
    CaptureCandidate.STATUS_LINKED,
)

