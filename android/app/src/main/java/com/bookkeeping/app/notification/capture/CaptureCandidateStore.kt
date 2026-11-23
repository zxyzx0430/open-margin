package com.bookkeeping.app.notification.capture

import android.content.Context
import com.bookkeeping.app.capture.screenshot.ScreenshotRetention
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal enum class CandidateHistoryWriteFaultPoint {
    AFTER_JOURNAL_REPLACEMENT,
    AFTER_INDEX_REPLACEMENT,
    BEFORE_CHECKPOINT_REPLACEMENT,
    AFTER_CANDIDATE_METADATA_JOURNAL_REPLACEMENT,
    AFTER_CANDIDATE_METADATA_INDEX_REPLACEMENT,
    BEFORE_CANDIDATE_METADATA_REPLACEMENT,
}

/** Local candidate inbox; it never writes the ledger. */
class CaptureCandidateStore internal constructor(
    private val filesRoot: File,
    private val screenshotStorageQuotaBytes: Long = MAX_SCREENSHOT_STORAGE_QUOTA_BYTES,
    private val candidateIndexBudgetBytes: Long = MAX_CANDIDATE_INDEX_BUDGET_BYTES,
    private val candidateCountBudget: Int = MAX_CANDIDATE_COUNT,
    private val writeFaultInjector: ((CandidateHistoryWriteFaultPoint) -> Unit)? = null,
) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val dir = File(filesRoot, "page_capture")
    private val indexFile = File(dir, "candidates.jsonl")
    private val candidateHistoryMetadataFile = File(dir, CANDIDATE_HISTORY_METADATA_NAME)
    private val candidateHistoryMetadataJournalFile = File(dir, CANDIDATE_HISTORY_METADATA_JOURNAL_NAME)
    private val legacyCandidateHistoryFile = File(dir, LEGACY_CANDIDATE_HISTORY_NAME)
    private val legacyCandidateHistoryJournalFile = File(dir, LEGACY_CANDIDATE_HISTORY_JOURNAL_NAME)
    private val imageFingerprintFile = File(dir, "image-fingerprints.jsonl")
    private val imageFingerprintMetadataFile = File(dir, "image-fingerprints.meta")

    fun save(candidate: CaptureCandidate, screenshot: ByteArray?): CaptureCandidate? =
        (saveWithResult(candidate, screenshot) as? CandidateSaveResult.Saved)?.candidate

    /**
     * Store an item and preserve the reason when the bounded review index is full.
     * The screenshot quota remains independent from the candidate index budget.
     */
    fun saveWithResult(
        candidate: CaptureCandidate,
        screenshot: ByteArray?,
        nowMs: Long = System.currentTimeMillis(),
    ): CandidateSaveResult {
        if (!candidate.isValid()) return CandidateSaveResult.Failed(CandidateSaveFailure.INVALID_CANDIDATE)
        return synchronized(lock) {
            val createdDirectory = !dir.exists()
            if (!dir.exists() && !dir.mkdirs()) {
                return@synchronized CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE)
            }
            runCatching { pruneExpiredLocked(nowMs) }
            // Recovery can consume the first-save metadata journal. Re-evaluate
            // initial-history eligibility afterward, or the cached false from
            // before recovery rejects the first retry on an empty history.
            val allowInitialHistory = createdDirectory || isNewCandidateHistoryLocked()
            val existing = readLatestCandidatesStrict()
                ?: return@synchronized CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE)
            if (!ensureLegacyCandidateHistoryMigrationLocked(nowMs, existing)) {
                return@synchronized CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE)
            }
            var newlyWrittenScreenshot: String? = null
            var screenshotUnavailableReason: String? = null
            val screenshotPath = when {
                screenshot != null -> {
                    val bytes = screenshot
                    if (bytes.isEmpty() || bytes.size > MAX_SCREENSHOT_BYTES) {
                        screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_INVALID
                        null
                    } else {
                        val file = File(dir, "shot_${sha256(bytes)}.jpg")
                        when {
                            file.isFile && file.length() == bytes.size.toLong() &&
                                runCatching { file.readBytes().contentEquals(bytes) }.getOrDefault(false) ->
                                canonicalStoredScreenshotPath(file.absolutePath)
                                    ?: run {
                                        screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_IO
                                        null
                                    }

                            file.exists() -> {
                                screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_IO
                                null
                            }

                            screenshotBytesInUse(existing, nowMs) + bytes.size >
                                screenshotStorageQuotaBytes.coerceAtLeast(0L) -> {
                                screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_QUOTA
                                null
                            }

                            else -> {
                                newlyWrittenScreenshot = file.absolutePath
                                val written = runCatching { file.writeBytes(bytes) }.isSuccess &&
                                    file.isFile && file.length() == bytes.size.toLong() &&
                                    runCatching { file.readBytes().contentEquals(bytes) }.getOrDefault(false)
                                if (written) canonicalStoredScreenshotPath(file.absolutePath) ?: run {
                                    screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_IO
                                    null
                                } else {
                                    screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_IO
                                    null
                                }
                            }
                        }
                    }
                }

                candidate.screenshotPath != null -> {
                    val path = candidate.screenshotPath
                    when {
                        !isStoredScreenshotFile(path) -> {
                            screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_IO
                            null
                        }

                        !isWithinScreenshotQuota(path, existing, nowMs) -> {
                            screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_QUOTA
                            null
                        }

                        else -> canonicalStoredScreenshotPath(path).also { canonical ->
                            if (canonical == null) screenshotUnavailableReason = SCREENSHOT_UNAVAILABLE_IO
                        }
                    }
                }

                else -> null
            }
            val stored = candidate.copy(
                screenshotPath = screenshotPath,
                reason = candidate.reason,
                screenshotUnavailableReason = screenshotUnavailableReason ?: candidate.screenshotUnavailableReason,
                imageFingerprint = candidate.imageFingerprint ?: screenshot
                    ?.takeIf { it.isNotEmpty() && it.size <= MAX_SCREENSHOT_BYTES }
                    ?.let(::sha256),
                diagnosticAttemptId = candidate.diagnosticAttemptId
                    ?: TransactionDiagnostics.newAttemptId(),
            )
            val retained = existing.filterNot {
                ScreenshotRetention.isExpired(it.capturedAtMs, nowMs) &&
                    it.candidateId != stored.candidateId
            }.toMutableList()
            retained.removeAll { it.candidateId == stored.candidateId }
            retained += stored
            val isNewCandidate = existing.none { it.candidateId == stored.candidateId }
            if (isNewCandidate && !candidateIndexWithinBudget(retained)) {
                newlyWrittenScreenshot?.let { deleteScreenshotIfUnreferenced(it, existing, nowMs) }
                return@synchronized CandidateSaveResult.Failed(CandidateSaveFailure.INDEX_FULL)
            }
            if (!writeCandidateIndexLocked(retained, allowInitialHistory = allowInitialHistory)) {
                val current = readLatestCandidatesStrict() ?: existing
                newlyWrittenScreenshot?.let { deleteScreenshotIfUnreferenced(it, current, nowMs) }
                return@synchronized CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE)
            }
            stored.imageFingerprint?.takeIf(String::isNotBlank)?.let {
                recordImageFingerprintLocked(it, stored.capturedAtMs, nowMs)
            }
            runCatching { pruneExpiredLocked(nowMs) }
            CandidateSaveResult.Saved(stored)
        }
    }

    fun latest(limit: Int = Int.MAX_VALUE): List<CaptureCandidate> = synchronized(lock) {
        runCatching { pruneExpiredLocked() }
        readLatestCandidates()
            .sortedBy { it.capturedAtMs }
            .takeLast(limit.coerceAtLeast(0))
    }

    fun findByImageFingerprint(fingerprint: String): CaptureCandidate? = synchronized(lock) {
        runCatching { pruneExpiredLocked() }
        readAll().lastOrNull { it.imageFingerprint == fingerprint }
    }

    /** Confirm that the exact source bytes remain readable from app-private storage. */
    fun hasReadableScreenshot(candidate: CaptureCandidate, expectedBytes: ByteArray?): Boolean = synchronized(lock) {
        if (expectedBytes == null || expectedBytes.isEmpty()) return@synchronized false
        val path = candidate.screenshotPath?.let(::canonicalStoredScreenshotPath)
            ?: return@synchronized false
        val file = File(path)
        file.canRead() && file.length() == expectedBytes.size.toLong() &&
            runCatching { file.readBytes().contentEquals(expectedBytes) }.getOrDefault(false)
    }

    /**
     * Check screenshot idempotency independently of the review inbox. The
     * compact index contains only hashes and capture times and follows the same
     * seven-day expiry boundary as screenshot evidence.
     */
    fun lookupImageFingerprint(
        fingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ImageFingerprintLookup = synchronized(lock) {
        if (!fingerprint.matches(Regex("[a-f0-9]{64}"))) {
            return@synchronized ImageFingerprintLookup.Unavailable("invalid fingerprint")
        }
        if (!recoverLegacyCandidateHistoryJournalLocked()) {
            return@synchronized knownFingerprintResultLocked(fingerprint, nowMs)
                ?: ImageFingerprintLookup.Unavailable("legacy candidate history recovery pending")
        }
        if (!recoverCandidateHistoryMetadataJournalLocked()) {
            markImageFingerprintIndexUnavailableLocked()
            return@synchronized knownFingerprintResultLocked(fingerprint, nowMs)
                ?: ImageFingerprintLookup.Unavailable("candidate history metadata recovery pending")
        }
        if (indexFile.isFile && !candidateHistoryIsCompleteLocked()) {
            val history = readLatestCandidatesStrict()
            if (history != null && !ensureLegacyCandidateHistoryMigrationLocked(nowMs, history)) {
                return@synchronized knownFingerprintResultLocked(fingerprint, nowMs)
                    ?: ImageFingerprintLookup.Unavailable("legacy candidate history checkpoint unavailable")
            }
        }
        finishLegacyCandidateHistoryMigrationIfReadyLocked(nowMs)
        if (File(dir, FINGERPRINT_UNAVAILABLE_NAME).exists()) {
            if (!recoverImageFingerprintsLocked(nowMs)) {
                return@synchronized knownFingerprintResultLocked(fingerprint, nowMs)
                    ?: ImageFingerprintLookup.Unavailable("fingerprint index unavailable")
            }
        }
        if ((imageFingerprintFile.exists() && !imageFingerprintFile.isFile) ||
            (indexFile.exists() && !indexFile.isFile) ||
            (candidateHistoryMetadataFile.exists() && !candidateHistoryMetadataFile.isFile) ||
            (legacyCandidateHistoryFile.exists() && !legacyCandidateHistoryFile.isFile)
        ) {
            markImageFingerprintIndexUnavailableLocked()
            return@synchronized knownFingerprintResultLocked(fingerprint, nowMs)
                ?: ImageFingerprintLookup.Unavailable("fingerprint index path is unusable")
        }
        if (imageFingerprintFile.isFile) {
            var records = readImageFingerprints() ?: run {
                markImageFingerprintIndexUnavailableLocked()
                if (!recoverImageFingerprintsLocked(nowMs)) {
                    return@synchronized ImageFingerprintLookup.Unavailable("fingerprint index unreadable")
                }
                readImageFingerprints()
                    ?: return@synchronized ImageFingerprintLookup.Unavailable("fingerprint index unreadable")
            }
            val evidence = readLatestCandidatesStrict()
            if (evidence == null) {
                markImageFingerprintIndexUnavailableLocked()
                return@synchronized if (records.any {
                        it.fingerprint == fingerprint &&
                            !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
                    }
                ) {
                    ImageFingerprintLookup.Match
                } else {
                    ImageFingerprintLookup.Unavailable("candidate evidence is unreadable")
                }
            }
            if (!candidateHistoryIsCompleteLocked()) {
                markImageFingerprintIndexUnavailableLocked()
                return@synchronized knownFingerprintResultLocked(fingerprint, nowMs)
                    ?: ImageFingerprintLookup.Unavailable("candidate history completeness is unverified")
            }
            val completeEvidence = fingerprintEvidence(evidence, nowMs)
            if (completeEvidence == null) {
                markImageFingerprintIndexUnavailableLocked()
                return@synchronized if (records.any {
                        it.fingerprint == fingerprint &&
                            !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
                    }
                ) ImageFingerprintLookup.Match else
                    ImageFingerprintLookup.Unavailable("candidate fingerprint evidence is incomplete")
            }
            val indexed = records.filterNot {
                ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
            }.associateBy { it.fingerprint }
            val expected = completeEvidence.associateBy { it.fingerprint }
            if (indexed != expected) {
                markImageFingerprintIndexUnavailableLocked()
                if (!recoverImageFingerprintsLocked(nowMs)) {
                    return@synchronized if (records.any {
                            it.fingerprint == fingerprint &&
                                !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
                        } || evidence.any {
                            it.imageFingerprint == fingerprint &&
                                !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
                        }
                    ) ImageFingerprintLookup.Match else
                        ImageFingerprintLookup.Unavailable("fingerprint index is incomplete")
                }
                records = readImageFingerprints()
                    ?: return@synchronized ImageFingerprintLookup.Unavailable("fingerprint index unreadable")
            }
            return@synchronized fingerprintResult(records, fingerprint, nowMs)
        }

        // A missing or interrupted index is rebuilt only from a strictly parsed
        // candidate index. A positive candidate match is safe even if rebuilding
        // fails; every other result stays unavailable.
        if (indexFile.exists() || candidateHistoryMetadataFile.exists() ||
            imageFingerprintMetadataFile.exists() ||
            File(dir, FINGERPRINT_TMP_NAME).exists()
        ) {
            markImageFingerprintIndexUnavailableLocked()
            if (recoverImageFingerprintsLocked(nowMs)) {
                val records = readImageFingerprints()
                    ?: return@synchronized ImageFingerprintLookup.Unavailable("fingerprint index unreadable")
                return@synchronized fingerprintResult(records, fingerprint, nowMs)
            }
            return@synchronized knownFingerprintResultLocked(fingerprint, nowMs)
                ?: ImageFingerprintLookup.Unavailable("fingerprint index missing or incomplete")
        }
        if (dir.exists()) ImageFingerprintLookup.Unavailable("capture history is missing")
        else ImageFingerprintLookup.CleanNoMatch
    }

    /** Compatibility query. Callers making booking decisions must use the typed lookup above. */
    fun containsImageFingerprint(
        fingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = lookupImageFingerprint(fingerprint, nowMs) == ImageFingerprintLookup.Match

    /**
     * Every waiting card, oldest first.
     *
     * One screenshot can hold two payments and they stay two cards: collapsing
     * them by [CaptureCandidate.imageFingerprint] hid the second one, which then
     * "reappeared" after the first was ignored and looked like a closed card
     * coming back. Repeated imports of the same image are stopped upstream by
     * the importer's repeatImage rule, so candidateId is the only key needed.
     */
    fun pending(limit: Int = Int.MAX_VALUE): List<CaptureCandidate> =
        latest()
            .filter { it.status == CaptureCandidate.STATUS_PENDING }
            .distinctBy { it.candidateId }
            .sortedBy { it.capturedAtMs }
            .take(limit.coerceAtLeast(0))

    /**
     * Cards that were ignored, newest first, for the recovery list.
     *
     * Same identity rule as [pending]: one screenshot can hold two payments and
     * they stay two rows, so restoring one never hides or resurrects the other.
     * The store has a single IGNORED status for both user taps and automatic
     * skips, so both kinds show up here; the user decides which ones to bring
     * back.
     */
    fun ignored(limit: Int = Int.MAX_VALUE): List<CaptureCandidate> =
        latest()
            .filter { it.status == CaptureCandidate.STATUS_IGNORED }
            .distinctBy { it.candidateId }
            .sortedByDescending { it.capturedAtMs }
            .take(limit.coerceAtLeast(0))

    /**
     * Put one ignored card back into review.
     *
     * Only a PENDING status record is written: nothing here touches the ledger,
     * so the user still has to confirm the card. [nowMs] is a parameter so the
     * retention boundary is testable without waiting seven days, and the lookup
     * deliberately runs before [pruneExpiredLocked] so a record that is expired
     * but still on disk answers [RestoreOutcome.EXPIRED] instead of vanishing
     * into "not found".
     */
    fun restore(candidateId: String, nowMs: Long = System.currentTimeMillis()): RestoreOutcome =
        synchronized(lock) {
            if (candidateId.isBlank()) return@synchronized RestoreOutcome.NOT_FOUND
            if (!recoverLegacyCandidateHistoryJournalLocked() ||
                !recoverCandidateHistoryMetadataJournalLocked()
            ) return@synchronized RestoreOutcome.WRITE_FAILED
            val latest = readLatestCandidatesStrict() ?: return@synchronized RestoreOutcome.WRITE_FAILED
            val record = latest.lastOrNull { it.candidateId == candidateId }
            when {
                record == null -> RestoreOutcome.NOT_FOUND
                ScreenshotRetention.isExpired(record.capturedAtMs, nowMs) ->
                    RestoreOutcome.EXPIRED
                record.status != CaptureCandidate.STATUS_IGNORED -> RestoreOutcome.NOT_IGNORED
                !dir.exists() && !dir.mkdirs() -> RestoreOutcome.WRITE_FAILED
                else -> {
                    val updated = record.copy(status = CaptureCandidate.STATUS_PENDING)
                    val written = writeCandidateIndexLocked(
                        latest.filterNot { it.candidateId == candidateId } + updated,
                    )
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
        ScreenshotWaitingCorrelation.settleTarget(incoming, latest(), nowMs)
    }

    /**
     * Apply a monotonic status update. Terminal states cannot be reopened by a
     * delayed callback; an ignored card can only return through [restore].
     */
    fun mark(candidate: CaptureCandidate, status: String): Boolean {
        if (status !in CaptureCandidate.STATUSES) return false
        synchronized(lock) {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!recoverLegacyCandidateHistoryJournalLocked() ||
                !recoverCandidateHistoryMetadataJournalLocked()
            ) return false
            val latest = readLatestCandidatesStrict() ?: return false
            val current = latest.lastOrNull { it.candidateId == candidate.candidateId }
                ?: return false
            if (ScreenshotRetention.isExpired(current.capturedAtMs, System.currentTimeMillis())) return false
            if (!canTransition(current.status, status)) return false
            val updated = if (status == CaptureCandidate.STATUS_PENDING) {
                current.copy(
                    status = status,
                    reason = candidate.reason,
                    matchedTransactionId = candidate.matchedTransactionId,
                    screenshotUnavailableReason = candidate.screenshotUnavailableReason
                        ?: current.screenshotUnavailableReason,
                )
            } else {
                current.copy(status = status)
            }
            if (current.status == status &&
                (status != CaptureCandidate.STATUS_PENDING || updated == current)
            ) return true
            return runCatching {
                check(writeCandidateIndexLocked(
                    latest.filterNot { it.candidateId == current.candidateId } + updated,
                ))
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

    private fun readLatestCandidates(): List<CaptureCandidate> {
        val latest = linkedMapOf<String, CaptureCandidate>()
        readAll().forEach { latest[it.candidateId] = it }
        return latest.values.toList()
    }

    /** Null means some retained evidence is unreadable; writers must not replace it. */
    private fun readLatestCandidatesStrict(): List<CaptureCandidate>? {
        if (!indexFile.exists()) return emptyList()
        if (!indexFile.isFile) return null
        return runCatching {
            val latest = linkedMapOf<String, CaptureCandidate>()
            indexFile.readLines().forEach { line ->
                if (line.isBlank()) error("blank candidate evidence line")
                val candidate = CaptureCandidate.fromJson(org.json.JSONObject(line))
                    ?: error("invalid candidate evidence")
                if (!candidate.isValid()) error("invalid candidate evidence")
                latest[candidate.candidateId] = candidate
            }
            latest.values.sortedBy { it.capturedAtMs }
        }.getOrNull()
    }

    private fun writeCandidateIndexLocked(
        candidates: List<CaptureCandidate>,
        allowInitialHistory: Boolean = false,
        allowLegacyMigration: Boolean = false,
    ): Boolean {
        if (!recoverLegacyCandidateHistoryJournalLocked() ||
            !recoverCandidateHistoryMetadataJournalLocked()
        ) return false

        val existing = readLatestCandidatesStrict() ?: return false
        if (indexFile.isFile && !candidateHistoryIsCompleteLocked() &&
            !legacyCandidateHistoryFile.exists()
        ) {
            if (!ensureLegacyCandidateHistoryMigrationLocked(System.currentTimeMillis(), existing)) return false
        }
        val legacyBarrier = if (legacyCandidateHistoryFile.exists()) {
            readLegacyHistoryBarrierLocked()?.takeIf(::legacyHistoryCheckpointMatchesIndex) ?: return false
        } else {
            null
        }
        val ordered = candidates
            .distinctBy { it.candidateId }
            .sortedBy { it.capturedAtMs }
        val content = encodeCandidates(ordered)
        val current = readLatestCandidatesStrict()
        if (!candidateIndexWithinBudget(ordered)) {
            // Existing oversized stores may be compacted, pruned, or updated
            // without growing. They must not lose retained rows just to satisfy
            // the new limit, and no newly added row may enlarge such a store.
            val currentIds = current?.mapTo(mutableSetOf()) { it.candidateId } ?: return false
            if (ordered.any { it.candidateId !in currentIds } ||
                content.size > (if (indexFile.isFile) indexFile.length() else 0L)
            ) return false
        }
        val canAdvanceHistoryMetadata = candidateHistoryIsCompleteLocked() ||
            (allowInitialHistory && hasNoCandidateHistoryArtifactsLocked()) || allowLegacyMigration
        if (!canAdvanceHistoryMetadata && legacyBarrier == null) return false
        val metadataJournalRequired = canAdvanceHistoryMetadata && legacyBarrier == null
        val oldHistoryState = if (metadataJournalRequired) {
            readCandidateHistoryIndexStateLocked() ?: return false
        } else {
            null
        }
        val newHistoryState = if (metadataJournalRequired) {
            candidateHistoryIndexState(ordered.size, content)
        } else {
            null
        }
        if (metadataJournalRequired) {
            if (!writeCandidateHistoryMetadataJournalLocked(oldHistoryState!!, newHistoryState!!)) return false
            writeFaultInjector?.invoke(
                CandidateHistoryWriteFaultPoint.AFTER_CANDIDATE_METADATA_JOURNAL_REPLACEMENT,
            )
        }
        if (legacyBarrier != null) {
            val oldBytes = runCatching { indexFile.readBytes() }.getOrNull() ?: return false
            val oldRows = readLatestCandidatesStrict() ?: return false
            val oldState = CandidateIndexState(oldRows.size, oldBytes.size.toLong(), sha256(oldBytes))
            val newState = CandidateIndexState(ordered.size, content.size.toLong(), sha256(content))
            if (!writeLegacyCandidateHistoryJournalLocked(legacyBarrier.expiresAtMs, oldState, newState)) {
                return false
            }
            writeFaultInjector?.invoke(CandidateHistoryWriteFaultPoint.AFTER_JOURNAL_REPLACEMENT)
        }
        if (!replaceFileAtomically(File(dir, INDEX_TMP_NAME), indexFile, content)) return false
        if (metadataJournalRequired) {
            writeFaultInjector?.invoke(
                CandidateHistoryWriteFaultPoint.AFTER_CANDIDATE_METADATA_INDEX_REPLACEMENT,
            )
        }
        if (legacyBarrier != null) {
            writeFaultInjector?.invoke(CandidateHistoryWriteFaultPoint.AFTER_INDEX_REPLACEMENT)
        }
        val verified = readLatestCandidatesStrict() ?: return false
        if (verified != ordered) return false
        if (legacyBarrier != null && !writeLegacyHistoryCheckpointLocked(legacyBarrier.expiresAtMs, ordered.size, content)) {
            return false
        }
        if (legacyBarrier != null) {
            val updatedBarrier = readLegacyHistoryBarrierLocked() ?: return false
            val expected = CandidateIndexState(ordered.size, content.size.toLong(), sha256(content))
            if (!legacyHistoryCheckpointMatchesState(updatedBarrier, expected)) return false
            if (!deleteLegacyCandidateHistoryJournalLocked()) return false
        }
        if (canAdvanceHistoryMetadata) {
            if (!writeCandidateHistoryMetadataLocked(ordered.size, content)) {
                markImageFingerprintIndexUnavailableLocked()
                return false
            }
            if (metadataJournalRequired && !deleteCandidateHistoryMetadataJournalLocked()) return false
        }
        return true
    }

    private fun writeCandidateHistoryMetadataLocked(candidateCount: Int, content: ByteArray): Boolean {
        val metadata = (fingerprintMetadata(candidateCount, content) + "\n")
            .toByteArray(Charsets.UTF_8)
        if (metadata.size > MAX_CANDIDATE_METADATA_BYTES) return false
        writeFaultInjector?.invoke(CandidateHistoryWriteFaultPoint.BEFORE_CANDIDATE_METADATA_REPLACEMENT)
        if (!replaceFileAtomically(File(dir, CANDIDATE_HISTORY_METADATA_TMP_NAME), candidateHistoryMetadataFile, metadata)) {
            return false
        }
        return runCatching {
            candidateHistoryMetadataFile.isFile &&
                candidateHistoryMetadataFile.readText().trim() == fingerprintMetadata(candidateCount, content)
        }.getOrDefault(false)
    }

    private fun candidateHistoryIsCompleteLocked(): Boolean {
        if (!indexFile.isFile || !candidateHistoryMetadataFile.isFile) return false
        return runCatching {
            val bytes = indexFile.readBytes()
            val lines = indexFile.readLines()
            readLatestCandidatesStrict() != null &&
                candidateHistoryMetadataFile.readText().trim() == fingerprintMetadata(lines.size, bytes)
        }.getOrDefault(false)
    }

    /**
     * Candidate-history genesis is independent of the screenshot fingerprint index.
     * A corrupt fingerprint-only index must not prevent saving the FIRST review card.
     * It is still treated as unavailable by lookupImageFingerprint: this check
     * never declares the fingerprint history clean or deletes its evidence.
     */
    private fun hasNoCandidateHistoryArtifactsLocked(): Boolean =
        !indexFile.exists() &&
            !File(dir, INDEX_TMP_NAME).exists() &&
            !candidateHistoryMetadataFile.exists() &&
            !File(dir, CANDIDATE_HISTORY_METADATA_TMP_NAME).exists() &&
            !candidateHistoryMetadataJournalFile.exists() &&
            !File(dir, CANDIDATE_HISTORY_METADATA_JOURNAL_TMP_NAME).exists() &&
            !legacyCandidateHistoryFile.exists() &&
            !legacyCandidateHistoryJournalFile.exists()

    private fun isNewCandidateHistoryLocked(): Boolean =
        hasNoCandidateHistoryArtifactsLocked() &&
            dir.listFiles().orEmpty().none {
                it.name.startsWith("shot_") && it.extension.equals("jpg", ignoreCase = true)
            }

    private data class CandidateHistoryIndexState(
        val exists: Boolean,
        val candidateCount: Int,
        val indexBytes: Long,
        val indexSha256: String,
    )

    private data class CandidateHistoryMetadataJournal(
        val oldIndex: CandidateHistoryIndexState,
        val newIndex: CandidateHistoryIndexState,
    )

    private fun candidateHistoryIndexState(candidateCount: Int, content: ByteArray) =
        CandidateHistoryIndexState(true, candidateCount, content.size.toLong(), sha256(content))

    private fun readCandidateHistoryIndexStateLocked(): CandidateHistoryIndexState? = runCatching {
        if (!indexFile.exists()) {
            return@runCatching CandidateHistoryIndexState(
                exists = false,
                candidateCount = 0,
                indexBytes = 0L,
                indexSha256 = sha256(ByteArray(0)),
            )
        }
        if (!indexFile.isFile || readLatestCandidatesStrict() == null) return@runCatching null
        val content = indexFile.readBytes()
        CandidateHistoryIndexState(
            exists = true,
            candidateCount = indexFile.readLines().size,
            indexBytes = content.size.toLong(),
            indexSha256 = sha256(content),
        )
    }.getOrNull()

    private fun candidateHistoryMetadataMatchesStateLocked(state: CandidateHistoryIndexState): Boolean {
        if (!state.exists) return !indexFile.exists() && !candidateHistoryMetadataFile.exists()
        if (!indexFile.isFile || !candidateHistoryMetadataFile.isFile) return false
        return runCatching {
            val content = indexFile.readBytes()
            content.size.toLong() == state.indexBytes &&
                sha256(content) == state.indexSha256 &&
                indexFile.readLines().size == state.candidateCount &&
                candidateHistoryMetadataFile.readText().trim() == fingerprintMetadata(state.candidateCount, content)
        }.getOrDefault(false)
    }

    private fun writeCandidateHistoryMetadataJournalLocked(
        oldIndex: CandidateHistoryIndexState,
        newIndex: CandidateHistoryIndexState,
    ): Boolean {
        if (candidateHistoryMetadataJournalFile.exists() || !newIndex.exists) return false
        val content = candidateHistoryMetadataJournal(oldIndex, newIndex)
        if (content.size > MAX_CANDIDATE_HISTORY_JOURNAL_BYTES) return false
        return replaceFileAtomically(
            File(dir, CANDIDATE_HISTORY_METADATA_JOURNAL_TMP_NAME),
            candidateHistoryMetadataJournalFile,
            content,
        ) && candidateHistoryMetadataJournalFile.isFile &&
            runCatching { candidateHistoryMetadataJournalFile.readBytes().contentEquals(content) }.getOrDefault(false)
    }

    private fun candidateHistoryMetadataJournal(
        oldIndex: CandidateHistoryIndexState,
        newIndex: CandidateHistoryIndexState,
    ): ByteArray = listOf(
        "v1",
        if (oldIndex.exists) 1 else 0,
        oldIndex.candidateCount,
        oldIndex.indexBytes,
        oldIndex.indexSha256,
        if (newIndex.exists) 1 else 0,
        newIndex.candidateCount,
        newIndex.indexBytes,
        newIndex.indexSha256,
    ).joinToString(":", postfix = "\n").toByteArray(Charsets.UTF_8)

    private fun readCandidateHistoryMetadataJournalLocked(): CandidateHistoryMetadataJournal? {
        if (!candidateHistoryMetadataJournalFile.isFile ||
            candidateHistoryMetadataJournalFile.length() > MAX_CANDIDATE_HISTORY_JOURNAL_BYTES
        ) return null
        return runCatching {
            val fields = candidateHistoryMetadataJournalFile.readText().trim().split(':')
            if (fields.size != 9 || fields[0] != "v1") return@runCatching null
            fun state(offset: Int): CandidateHistoryIndexState? {
                val exists = when (fields[offset]) {
                    "0" -> false
                    "1" -> true
                    else -> return null
                }
                val count = fields[offset + 1].toInt().takeIf { it >= 0 } ?: return null
                val bytes = fields[offset + 2].toLong().takeIf { it >= 0L } ?: return null
                val digest = fields[offset + 3].takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    ?: return null
                if (!exists && (count != 0 || bytes != 0L || digest != sha256(ByteArray(0)))) return null
                return CandidateHistoryIndexState(exists, count, bytes, digest)
            }
            val oldIndex = state(1) ?: return@runCatching null
            val newIndex = state(5)?.takeIf { it.exists } ?: return@runCatching null
            CandidateHistoryMetadataJournal(oldIndex, newIndex)
        }.getOrNull()
    }

    /** Repair only the exact old/new index state captured by the durable intent. */
    private fun recoverCandidateHistoryMetadataJournalLocked(): Boolean {
        if (!candidateHistoryMetadataJournalFile.exists()) {
            val temporary = File(dir, CANDIDATE_HISTORY_METADATA_JOURNAL_TMP_NAME)
            if (!temporary.exists()) return true
            if (!temporary.isFile) return false
            return temporary.delete() || !temporary.exists()
        }
        if (!candidateHistoryMetadataJournalFile.isFile) return false
        val journal = readCandidateHistoryMetadataJournalLocked() ?: return false
        val current = readCandidateHistoryIndexStateLocked() ?: return false
        val currentIsOld = current == journal.oldIndex
        val currentIsNew = current == journal.newIndex
        if (!currentIsOld && !currentIsNew) return false

        val expected = if (currentIsNew) journal.newIndex else journal.oldIndex
        if (expected.exists) {
            val content = runCatching { indexFile.readBytes() }.getOrNull() ?: return false
            if (content.size.toLong() != expected.indexBytes ||
                sha256(content) != expected.indexSha256 ||
                indexFile.readLines().size != expected.candidateCount
            ) return false
            if (!candidateHistoryMetadataMatchesStateLocked(expected) &&
                !writeCandidateHistoryMetadataLocked(expected.candidateCount, content)
            ) return false
            if (!candidateHistoryMetadataMatchesStateLocked(expected)) return false
        } else if (indexFile.exists() || candidateHistoryMetadataFile.exists()) {
            return false
        }
        val indexTemporary = File(dir, INDEX_TMP_NAME)
        if (indexTemporary.exists() && !indexTemporary.delete() && indexTemporary.exists()) return false
        return deleteCandidateHistoryMetadataJournalLocked()
    }

    private fun deleteCandidateHistoryMetadataJournalLocked(): Boolean {
        if (candidateHistoryMetadataJournalFile.exists() &&
            !candidateHistoryMetadataJournalFile.delete() && candidateHistoryMetadataJournalFile.exists()
        ) return false
        return !candidateHistoryMetadataJournalFile.exists()
    }

    private fun candidateIndexWithinBudget(candidates: List<CaptureCandidate>): Boolean {
        val ordered = canonicalCandidates(candidates)
        return ordered.size <= candidateCountBudget.coerceAtLeast(0) &&
            encodeCandidates(ordered).size.toLong() <= candidateIndexBudgetBytes.coerceAtLeast(0L)
    }

    /**
     * A legacy index without candidates.meta is not complete evidence yet.
     * Record a conservative expiry barrier and keep misses unavailable until
     * every pre-upgrade row could have aged out under the seven-day policy.
     */
    private fun ensureLegacyCandidateHistoryMigrationLocked(
        nowMs: Long,
        candidates: List<CaptureCandidate>,
    ): Boolean {
        if (!recoverLegacyCandidateHistoryJournalLocked() ||
            !recoverCandidateHistoryMetadataJournalLocked()
        ) return false
        if (!indexFile.exists()) return !candidateHistoryMetadataFile.exists() && !legacyCandidateHistoryFile.exists()
        if (!indexFile.isFile || (candidateHistoryMetadataFile.exists() && !candidateHistoryMetadataFile.isFile)) {
            return false
        }
        if (candidateHistoryIsCompleteLocked()) return true
        if (legacyCandidateHistoryFile.exists()) {
            val barrier = readLegacyHistoryBarrierLocked() ?: return false
            return legacyHistoryCheckpointMatchesIndex(barrier)
        }
        val legacyFingerprintRecords = readLegacyImageFingerprintsLocked() ?: return false
        val latestLegacyEvidenceMs = maxOf(
            nowMs,
            candidates.maxOfOrNull { it.capturedAtMs } ?: nowMs,
            legacyFingerprintRecords.maxOfOrNull { it.capturedAtMs } ?: nowMs,
        )
        val barrierMs = retentionDeadline(latestLegacyEvidenceMs)
        val indexBytes = runCatching { indexFile.readBytes() }.getOrNull() ?: return false
        if (!writeLegacyHistoryCheckpointLocked(barrierMs, candidates.size, indexBytes)) return false
        val barrier = readLegacyHistoryBarrierLocked() ?: return false
        return legacyHistoryCheckpointMatchesIndex(barrier)
    }

    private fun finishLegacyCandidateHistoryMigrationIfReadyLocked(nowMs: Long) {
        if (!recoverLegacyCandidateHistoryJournalLocked() ||
            !recoverCandidateHistoryMetadataJournalLocked()
        ) return
        val barrier = readLegacyHistoryBarrierLocked() ?: return
        if (!legacyHistoryCheckpointMatchesIndex(barrier)) return
        if (candidateHistoryMetadataFile.exists() && candidateHistoryIsCompleteLocked()) {
            legacyCandidateHistoryFile.delete()
            return
        }
        if (nowMs < barrier.expiresAtMs) return
        if (!runCatching { pruneExpiredLocked(nowMs) }.isSuccess) return
        val retained = readLatestCandidatesStrict() ?: return
        if (!writeCandidateIndexLocked(retained, allowLegacyMigration = true)) return
        if (candidateHistoryIsCompleteLocked()) {
            legacyCandidateHistoryFile.delete()
            File(dir, LEGACY_CANDIDATE_HISTORY_TMP_NAME).delete()
        }
    }

    private data class LegacyHistoryBarrier(
        val expiresAtMs: Long,
        val candidateCount: Int,
        val indexBytes: Long,
        val indexSha256: String,
    )

    private data class CandidateIndexState(
        val candidateCount: Int,
        val indexBytes: Long,
        val indexSha256: String,
    )

    private data class LegacyHistoryJournal(
        val expiresAtMs: Long,
        val oldIndex: CandidateIndexState,
        val newIndex: CandidateIndexState,
    )

    /**
     * Recover only the exact old or intended new index state recorded by the
     * journal. Parseability alone never upgrades candidate history completeness.
     */
    private fun recoverLegacyCandidateHistoryJournalLocked(): Boolean {
        if (!legacyCandidateHistoryJournalFile.exists()) return true
        if (!legacyCandidateHistoryJournalFile.isFile) return false
        val journal = readLegacyHistoryJournalLocked() ?: return false
        val current = readCandidateIndexStateLocked() ?: return false
        val currentIsOld = candidateIndexStateMatches(current, journal.oldIndex)
        val currentIsNew = candidateIndexStateMatches(current, journal.newIndex)
        if (!currentIsOld && !currentIsNew) return false

        val checkpoint = readLegacyHistoryBarrierLocked()
        if (checkpoint == null) {
            // The journal is the durable source for the seven-day expiry. Rebuild
            // the checkpoint only for the exact old or intended-new index state.
            val state = if (currentIsNew) journal.newIndex else journal.oldIndex
            val content = runCatching { indexFile.readBytes() }.getOrNull() ?: return false
            if (!runCatching {
                    writeLegacyHistoryCheckpointLocked(
                        journal.expiresAtMs,
                        state.candidateCount,
                        content,
                    )
                }.getOrDefault(false)
            ) return false
            val repaired = readLegacyHistoryBarrierLocked() ?: return false
            if (repaired.expiresAtMs != journal.expiresAtMs ||
                !legacyHistoryCheckpointMatchesState(repaired, state)
            ) return false
            return deleteLegacyCandidateHistoryJournalLocked()
        }
        if (checkpoint.expiresAtMs != journal.expiresAtMs) return false
        val checkpointIsOld = legacyHistoryCheckpointMatchesState(checkpoint, journal.oldIndex)
        val checkpointIsNew = legacyHistoryCheckpointMatchesState(checkpoint, journal.newIndex)
        if (!checkpointIsOld && !checkpointIsNew) return false

        if (currentIsOld) {
            if (!checkpointIsOld) return false
            return deleteLegacyCandidateHistoryJournalLocked()
        }

        if (!checkpointIsNew) {
            val content = runCatching { indexFile.readBytes() }.getOrNull() ?: return false
            val repaired = runCatching {
                writeLegacyHistoryCheckpointLocked(
                    journal.expiresAtMs,
                    journal.newIndex.candidateCount,
                    content,
                )
            }.getOrDefault(false)
            if (!repaired) return false
            val repairedCheckpoint = readLegacyHistoryBarrierLocked() ?: return false
            if (!legacyHistoryCheckpointMatchesState(repairedCheckpoint, journal.newIndex)) return false
        }
        return deleteLegacyCandidateHistoryJournalLocked()
    }

    private fun readCandidateIndexStateLocked(): CandidateIndexState? = runCatching {
        if (!indexFile.isFile) return@runCatching null
        val rows = readLatestCandidatesStrict() ?: return@runCatching null
        val bytes = indexFile.readBytes()
        CandidateIndexState(rows.size, bytes.size.toLong(), sha256(bytes))
    }.getOrNull()

    private fun candidateIndexStateMatches(actual: CandidateIndexState, expected: CandidateIndexState): Boolean =
        actual == expected

    private fun legacyHistoryCheckpointMatchesState(
        barrier: LegacyHistoryBarrier,
        state: CandidateIndexState,
    ): Boolean = barrier.candidateCount == state.candidateCount &&
        barrier.indexBytes == state.indexBytes && barrier.indexSha256 == state.indexSha256

    private fun writeLegacyCandidateHistoryJournalLocked(
        expiresAtMs: Long,
        oldIndex: CandidateIndexState,
        newIndex: CandidateIndexState,
    ): Boolean {
        val content = legacyHistoryJournal(expiresAtMs, oldIndex, newIndex)
        val temporary = File(dir, LEGACY_CANDIDATE_HISTORY_JOURNAL_TMP_NAME)
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.fd.sync()
            }
            // This rename must finish before the candidate index can move.
            if (!temporary.renameTo(legacyCandidateHistoryJournalFile)) return@runCatching false
            legacyCandidateHistoryJournalFile.isFile &&
                legacyCandidateHistoryJournalFile.readBytes().contentEquals(content)
        }.getOrDefault(false)
    }

    private fun readLegacyHistoryJournalLocked(): LegacyHistoryJournal? {
        if (!legacyCandidateHistoryJournalFile.isFile) return null
        return runCatching {
            val fields = legacyCandidateHistoryJournalFile.readText().trim().split(':')
            if (fields.size != 8 || fields[0] != "v1") return@runCatching null
            val expiry = fields[1].toLong().takeIf { it > 0L } ?: return@runCatching null
            fun state(offset: Int): CandidateIndexState? {
                val count = fields[offset].toInt().takeIf { it >= 0 } ?: return null
                val bytes = fields[offset + 1].toLong().takeIf { it >= 0L } ?: return null
                val digest = fields[offset + 2].takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    ?: return null
                return CandidateIndexState(count, bytes, digest)
            }
            val old = state(2) ?: return@runCatching null
            val next = state(5) ?: return@runCatching null
            LegacyHistoryJournal(expiry, old, next)
        }.getOrNull()
    }

    private fun legacyHistoryJournal(
        expiresAtMs: Long,
        oldIndex: CandidateIndexState,
        newIndex: CandidateIndexState,
    ): ByteArray = listOf(
        "v1",
        expiresAtMs,
        oldIndex.candidateCount,
        oldIndex.indexBytes,
        oldIndex.indexSha256,
        newIndex.candidateCount,
        newIndex.indexBytes,
        newIndex.indexSha256,
    ).joinToString(":", postfix = "\n").toByteArray(Charsets.UTF_8)

    private fun deleteLegacyCandidateHistoryJournalLocked(): Boolean {
        if (legacyCandidateHistoryJournalFile.exists() &&
            !legacyCandidateHistoryJournalFile.delete() && legacyCandidateHistoryJournalFile.exists()
        ) return false
        return !legacyCandidateHistoryJournalFile.exists()
    }

    private fun readLegacyHistoryBarrierLocked(): LegacyHistoryBarrier? {
        if (!legacyCandidateHistoryFile.isFile) return null
        return runCatching {
            val fields = legacyCandidateHistoryFile.readText().trim().split(':')
            if (fields.size != 5 || fields[0] != "v1") return@runCatching null
            val expiry = fields[1].toLong().takeIf { it > 0L } ?: return@runCatching null
            val count = fields[2].toInt().takeIf { it >= 0 } ?: return@runCatching null
            val bytes = fields[3].toLong().takeIf { it >= 0L } ?: return@runCatching null
            val digest = fields[4].takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                ?: return@runCatching null
            LegacyHistoryBarrier(expiry, count, bytes, digest)
        }.getOrNull()
    }

    private fun legacyHistoryCheckpointMatchesIndex(barrier: LegacyHistoryBarrier): Boolean = runCatching {
        if (!indexFile.isFile) return@runCatching false
        val bytes = indexFile.readBytes()
        val rows = readLatestCandidatesStrict() ?: return@runCatching false
        bytes.size.toLong() == barrier.indexBytes &&
            rows.size == barrier.candidateCount &&
            sha256(bytes) == barrier.indexSha256
    }.getOrDefault(false)

    private fun legacyHistoryCheckpoint(
        expiresAtMs: Long,
        candidateCount: Int,
        content: ByteArray,
    ): ByteArray = "v1:$expiresAtMs:$candidateCount:${content.size}:${sha256(content)}\n"
        .toByteArray(Charsets.UTF_8)

    private fun writeLegacyHistoryCheckpointLocked(
        expiresAtMs: Long,
        candidateCount: Int,
        content: ByteArray,
    ): Boolean {
        writeFaultInjector?.invoke(CandidateHistoryWriteFaultPoint.BEFORE_CHECKPOINT_REPLACEMENT)
        return replaceFileAtomically(
            File(dir, LEGACY_CANDIDATE_HISTORY_TMP_NAME),
            legacyCandidateHistoryFile,
            legacyHistoryCheckpoint(expiresAtMs, candidateCount, content),
        )
    }

    /** Parse timestamps conservatively even when no checksum sidecar exists. */
    private fun readLegacyImageFingerprintsLocked(): List<ImageFingerprintRecord>? {
        if (!imageFingerprintFile.exists()) return emptyList()
        if (!imageFingerprintFile.isFile) return null
        return runCatching {
            imageFingerprintFile.readLines().map { line ->
                if (line.isBlank()) error("blank legacy fingerprint evidence line")
                val json = org.json.JSONObject(line)
                val fingerprint = json.optString("fingerprint")
                    .takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    ?: error("invalid legacy fingerprint")
                val capturedAtMs = json.optLong("capturedAtMs", Long.MIN_VALUE)
                if (capturedAtMs <= 0L) error("invalid legacy fingerprint time")
                ImageFingerprintRecord(fingerprint, capturedAtMs)
            }
        }.getOrNull()
    }

    private fun retentionDeadline(capturedAtMs: Long): Long =
        if (capturedAtMs > Long.MAX_VALUE - ScreenshotRetention.RETENTION_MS) {
            Long.MAX_VALUE
        } else {
            capturedAtMs + ScreenshotRetention.RETENTION_MS
        }

    private fun isStoredScreenshotFile(path: String): Boolean = runCatching {
        canonicalStoredScreenshotPath(path) != null
    }.getOrDefault(false)

    private fun canonicalStoredScreenshotPath(path: String): String? = runCatching {
        val target = File(path).canonicalFile
        target.path.takeIf {
            target.parentFile == dir.canonicalFile &&
                target.isFile && target.name.startsWith("shot_") &&
                target.extension.equals("jpg", ignoreCase = true)
        }
    }.getOrNull()

    private fun isWithinScreenshotQuota(
        path: String,
        candidates: List<CaptureCandidate>,
        nowMs: Long,
    ): Boolean {
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val alreadyReferenced = candidates.any { candidate ->
            !ScreenshotRetention.isExpired(candidate.capturedAtMs, nowMs) &&
                candidate.screenshotPath?.let { existing ->
                    canonicalStoredScreenshotPath(existing) == target.path
                } == true
        }
        return alreadyReferenced ||
            screenshotBytesInUse(candidates, nowMs) + target.length() <=
            screenshotStorageQuotaBytes.coerceAtLeast(0L)
    }

    private fun screenshotBytesInUse(candidates: List<CaptureCandidate>, nowMs: Long): Long {
        val filesByPath = linkedMapOf<String, File>()
        candidates.asSequence()
            .filterNot { ScreenshotRetention.isExpired(it.capturedAtMs, nowMs) }
            .mapNotNull { it.screenshotPath }
            .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            .filter { it.parentFile == runCatching { dir.canonicalFile }.getOrNull() && it.isFile }
            .forEach { filesByPath[it.absolutePath] = it }
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .forEach fileLoop@ { file ->
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@fileLoop
                filesByPath[canonical.absolutePath] = canonical
            }
        return filesByPath.values.sumOf { it.length() }
    }

    private fun deleteScreenshotIfUnreferenced(
        path: String,
        candidates: List<CaptureCandidate>,
        nowMs: Long,
    ) {
        val canonicalPath = canonicalStoredScreenshotPath(path)
        val stillReferenced = candidates.any { candidate ->
            !ScreenshotRetention.isExpired(candidate.capturedAtMs, nowMs) &&
                canonicalPath != null &&
                candidate.screenshotPath?.let(::canonicalStoredScreenshotPath) == canonicalPath
        }
        if (!stillReferenced) deleteOwnedFile(path)
    }

    private data class ImageFingerprintRecord(
        val fingerprint: String,
        val capturedAtMs: Long,
    ) {
        fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put("fingerprint", fingerprint)
            put("capturedAtMs", capturedAtMs)
        }
    }

    private fun readImageFingerprints(): List<ImageFingerprintRecord>? {
        if (!imageFingerprintFile.isFile || !imageFingerprintMetadataFile.isFile) return null
        return runCatching {
            val content = imageFingerprintFile.readBytes()
            val records = imageFingerprintFile.readLines().map { line ->
                if (line.isBlank()) error("blank fingerprint evidence line")
                val json = org.json.JSONObject(line)
                val fingerprint = json.optString("fingerprint")
                    .takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: error("invalid fingerprint")
                val capturedAtMs = json.optLong("capturedAtMs", Long.MIN_VALUE)
                if (capturedAtMs <= 0L) error("invalid capture time")
                ImageFingerprintRecord(fingerprint, capturedAtMs)
            }.toList()
            if (imageFingerprintMetadataFile.readText().trim() !=
                fingerprintMetadata(records.size, content)
            ) error("fingerprint index metadata mismatch")
            records
        }.getOrNull()
    }

    private fun recordImageFingerprintLocked(fingerprint: String, capturedAtMs: Long, nowMs: Long) {
        if (File(dir, FINGERPRINT_UNAVAILABLE_NAME).exists()) {
            if (!recoverImageFingerprintsLocked(nowMs)) return
        }
        if (!imageFingerprintFile.isFile && indexFile.isFile) {
            if (!recoverImageFingerprintsLocked(nowMs)) {
                markImageFingerprintIndexUnavailableLocked()
                return
            }
        }
        val current = when {
            imageFingerprintFile.isFile -> readImageFingerprints()
            imageFingerprintFile.exists() || imageFingerprintMetadataFile.exists() -> null
            else -> emptyList()
        } ?: run {
            markImageFingerprintIndexUnavailableLocked()
            return
        }
        val updated = current.filterNot {
            ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
        }.toMutableList()
        val existing = updated.lastOrNull { it.fingerprint == fingerprint }
        if (existing == null || capturedAtMs > existing.capturedAtMs) {
            updated.removeAll { it.fingerprint == fingerprint }
            updated += ImageFingerprintRecord(fingerprint, capturedAtMs)
        }
        if (!writeImageFingerprintsLocked(normalizeFingerprintRecords(updated))) {
            markImageFingerprintIndexUnavailableLocked()
        }
    }

    private fun reconcileImageFingerprintsLocked(nowMs: Long) {
        if (File(dir, FINGERPRINT_UNAVAILABLE_NAME).exists()) {
            recoverImageFingerprintsLocked(nowMs)
            return
        }
        if (!indexFile.exists() && !candidateHistoryMetadataFile.exists() &&
            !legacyCandidateHistoryFile.exists() && !imageFingerprintFile.exists() &&
            !imageFingerprintMetadataFile.exists()
        ) return

        val evidence = readLatestCandidatesStrict()
        val expected = evidence?.let { fingerprintEvidence(it, nowMs) }
        if (expected == null) {
            if (imageFingerprintFile.exists() || imageFingerprintMetadataFile.exists()) {
                markImageFingerprintIndexUnavailableLocked()
            }
            return
        }
        val current = when {
            imageFingerprintFile.isFile -> readImageFingerprints()
            imageFingerprintFile.exists() || imageFingerprintMetadataFile.exists() -> null
            else -> emptyList()
        }
        if (current == null) {
            markImageFingerprintIndexUnavailableLocked()
            recoverImageFingerprintsLocked(nowMs)
            return
        }
        val expectedByFingerprint = expected.associateBy { it.fingerprint }
        val activeCurrent = current.filterNot {
            ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
        }
        if (activeCurrent.any { it.fingerprint !in expectedByFingerprint }) {
            // An index-only retained fingerprint has no candidate evidence to
            // rebuild from, so it must remain unavailable instead of being lost.
            markImageFingerprintIndexUnavailableLocked()
            return
        }
        val currentByFingerprint = activeCurrent.associateBy { it.fingerprint }
        val missing = expected.any { it.fingerprint !in currentByFingerprint }
        val expired = current.size != activeCurrent.size
        if (missing || expired || !imageFingerprintFile.isFile) {
            if (!writeImageFingerprintsLocked(expected)) {
                markImageFingerprintIndexUnavailableLocked()
            }
        }
    }

    private fun recoverImageFingerprintsLocked(nowMs: Long): Boolean {
        if (!indexFile.isFile || !candidateHistoryIsCompleteLocked()) return false
        val candidates = readLatestCandidatesStrict() ?: return false
        val expected = fingerprintEvidence(candidates, nowMs) ?: return false

        // If a readable index knows about a still-retained fingerprint that the
        // candidate log cannot explain, rebuilding would turn missing evidence
        // into a false clean result. Preserve Unavailable in that case.
        val previous = readImageFingerprints()
        if (previous != null) {
            val expectedFingerprints = expected.mapTo(mutableSetOf()) { it.fingerprint }
            if (previous.any {
                    !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs) &&
                        it.fingerprint !in expectedFingerprints
                }
            ) return false
        }

        if (!writeImageFingerprintsLocked(expected)) {
            markImageFingerprintIndexUnavailableLocked()
            return false
        }
        if (readImageFingerprints() != expected) {
            markImageFingerprintIndexUnavailableLocked()
            return false
        }
        val marker = File(dir, FINGERPRINT_UNAVAILABLE_NAME)
        if (marker.exists() && !marker.delete() && marker.exists()) return false
        return true
    }

    private fun knownFingerprintResultLocked(
        fingerprint: String,
        nowMs: Long,
    ): ImageFingerprintLookup? {
        val knownInCandidates = runCatching { readAll() }.getOrDefault(emptyList()).any {
            it.imageFingerprint == fingerprint &&
                !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
        }
        val knownInIndex = readImageFingerprints()?.any {
            it.fingerprint == fingerprint &&
                !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
        } == true
        return if (knownInCandidates || knownInIndex) ImageFingerprintLookup.Match else null
    }

    private fun fingerprintEvidence(
        candidates: List<CaptureCandidate>,
        nowMs: Long,
    ): List<ImageFingerprintRecord>? {
        val latestByFingerprint = linkedMapOf<String, ImageFingerprintRecord>()
        candidates.forEach { candidate ->
            if (ScreenshotRetention.isExpired(candidate.capturedAtMs, nowMs)) return@forEach
            val screenshotUnavailable = candidate.screenshotUnavailableReason != null ||
                candidate.reason.startsWith(SCREENSHOT_UNAVAILABLE_PREFIX) ||
                candidate.reason == "screenshot decode failed" ||
                candidate.reason == "screenshot start failed" ||
                candidate.reason.startsWith("screenshot error ")
            val requiresFingerprint = candidate.screenshotPath != null || screenshotUnavailable ||
                candidate.intakeMethod in setOf(
                    CaptureCandidate.INTAKE_MEDIASTORE,
                    CaptureCandidate.INTAKE_SHARE,
                    CaptureCandidate.INTAKE_PHOTO_PICKER,
                )
            val fingerprint = candidate.imageFingerprint
            if (requiresFingerprint && fingerprint == null) return null
            if (fingerprint != null) {
                val existing = latestByFingerprint[fingerprint]
                if (existing == null || candidate.capturedAtMs > existing.capturedAtMs) {
                    latestByFingerprint[fingerprint] = ImageFingerprintRecord(
                        fingerprint,
                        candidate.capturedAtMs,
                    )
                }
            }
        }
        return normalizeFingerprintRecords(latestByFingerprint.values.toList())
    }

    private fun normalizeFingerprintRecords(
        records: List<ImageFingerprintRecord>,
    ): List<ImageFingerprintRecord> {
        val latest = linkedMapOf<String, ImageFingerprintRecord>()
        records.forEach { record ->
            val existing = latest[record.fingerprint]
            if (existing == null || record.capturedAtMs > existing.capturedAtMs) {
                latest[record.fingerprint] = record
            }
        }
        return latest.values.sortedWith(compareBy<ImageFingerprintRecord> { it.capturedAtMs }.thenBy { it.fingerprint })
    }

    private fun writeImageFingerprintsLocked(records: List<ImageFingerprintRecord>): Boolean {
        val normalized = normalizeFingerprintRecords(records)
        val content = normalized.joinToString("\n", postfix = if (normalized.isEmpty()) "" else "\n") {
            it.toJson().toString()
        }.toByteArray(Charsets.UTF_8)
        if (!replaceFile(File(dir, FINGERPRINT_TMP_NAME), imageFingerprintFile, content)) return false
        val metadata = (fingerprintMetadata(normalized.size, content) + "\n")
            .toByteArray(Charsets.UTF_8)
        if (!replaceFile(File(dir, FINGERPRINT_METADATA_TMP_NAME), imageFingerprintMetadataFile, metadata)) {
            return false
        }
        return readImageFingerprints() == normalized
    }

    private fun fingerprintMetadata(recordCount: Int, content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "$recordCount:$digest"
    }

    private fun markImageFingerprintIndexUnavailableLocked() {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, FINGERPRINT_UNAVAILABLE_NAME).writeText("unavailable\n")
        }
    }

    private fun replaceFile(temp: File, target: File, content: ByteArray): Boolean = runCatching {
        temp.writeBytes(content)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.isFile && target.readBytes().contentEquals(content)
    }.getOrDefault(false)

    private fun replaceFileAtomically(temp: File, target: File, content: ByteArray): Boolean = runCatching {
        FileOutputStream(temp).use { output ->
            output.write(content)
            output.fd.sync()
        }
        if (!temp.renameTo(target)) return@runCatching false
        target.isFile && target.readBytes().contentEquals(content)
    }.getOrDefault(false)

    private fun fingerprintResult(
        records: List<ImageFingerprintRecord>,
        fingerprint: String,
        nowMs: Long,
    ): ImageFingerprintLookup = if (records.any {
            it.fingerprint == fingerprint && !ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
        }
    ) ImageFingerprintLookup.Match else ImageFingerprintLookup.CleanNoMatch

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun encodeCandidates(candidates: List<CaptureCandidate>): ByteArray =
        candidates.joinToString("\n", postfix = if (candidates.isEmpty()) "" else "\n") {
            it.toJson().toString()
        }.toByteArray(Charsets.UTF_8)

    private fun canonicalCandidates(candidates: List<CaptureCandidate>): List<CaptureCandidate> =
        candidates.distinctBy { it.candidateId }.sortedBy { it.capturedAtMs }

    private fun replaceCandidateIndexIfNeeded(
        candidates: List<CaptureCandidate>,
        allowLegacyMigration: Boolean = false,
    ) {
        if (!indexFile.isFile) return
        val ordered = canonicalCandidates(candidates)
        if (runCatching { indexFile.readBytes().contentEquals(encodeCandidates(ordered)) }.getOrDefault(false)) {
            return
        }
        check(writeCandidateIndexLocked(ordered, allowLegacyMigration = allowLegacyMigration)) {
            "candidate index compaction failed"
        }
    }

    private fun canTransition(current: String, next: String): Boolean = when (current) {
        CaptureCandidate.STATUS_PENDING -> true
        CaptureCandidate.STATUS_WAITING -> next == CaptureCandidate.STATUS_WAITING ||
            next == CaptureCandidate.STATUS_LINKED
        CaptureCandidate.STATUS_APPROVED,
        CaptureCandidate.STATUS_IGNORED,
        CaptureCandidate.STATUS_LINKED -> next == current
        else -> false
    }

    private fun pruneExpiredLocked(nowMs: Long = System.currentTimeMillis()) {
        if (!dir.isDirectory) return
        if (!recoverLegacyCandidateHistoryJournalLocked()) error("legacy candidate history recovery failed")
        if (!recoverCandidateHistoryMetadataJournalLocked()) error("candidate history metadata recovery failed")
        if (indexFile.exists() && !indexFile.isFile) error("candidate index path is unusable")
        val candidates = readLatestCandidatesStrict() ?: error("candidate evidence is unreadable")
        if (indexFile.isFile && !candidateHistoryIsCompleteLocked() &&
            !ensureLegacyCandidateHistoryMigrationLocked(nowMs, candidates)
        ) error("candidate history migration could not be checkpointed")
        val retained = candidates.filterNot {
            ScreenshotRetention.isExpired(it.capturedAtMs, nowMs)
        }
        val referenced = mutableSetOf<String>()
        val expiredPaths = candidates.asSequence()
            .filter { ScreenshotRetention.isExpired(it.capturedAtMs, nowMs) }
            .mapNotNull { it.screenshotPath }
            .mapNotNull(::canonicalStoredScreenshotPath)
            .toSet()
        retained.mapNotNullTo(referenced) { it.screenshotPath?.let(::canonicalStoredScreenshotPath) }
        if (indexFile.isFile) {
            replaceCandidateIndexIfNeeded(retained)
        }
        // The independent index can be rebuilt only when every retained
        // candidate line is readable and carries any required image evidence.
        reconcileImageFingerprintsLocked(nowMs)
        expiredPaths.filterNot(referenced::contains).forEach(::deleteOwnedFile)
        val files = dir.listFiles().orEmpty()
        val referencedNames = files.filter {
            runCatching { it.canonicalPath }.getOrNull() in referenced
        }.map { it.name }.toSet()
        ScreenshotRetention.orphans(
            files = files.map { it.name },
            referencedNames = referencedNames,
            keepNames = setOf(
                indexFile.name,
                INDEX_TMP_NAME,
                candidateHistoryMetadataFile.name,
                CANDIDATE_HISTORY_METADATA_TMP_NAME,
                candidateHistoryMetadataJournalFile.name,
                CANDIDATE_HISTORY_METADATA_JOURNAL_TMP_NAME,
                legacyCandidateHistoryFile.name,
                LEGACY_CANDIDATE_HISTORY_TMP_NAME,
                legacyCandidateHistoryJournalFile.name,
                LEGACY_CANDIDATE_HISTORY_JOURNAL_TMP_NAME,
                imageFingerprintFile.name,
                FINGERPRINT_TMP_NAME,
                FINGERPRINT_METADATA_TMP_NAME,
                FINGERPRINT_UNAVAILABLE_NAME,
                imageFingerprintMetadataFile.name,
            ),
        ).forEach { name -> File(dir, name).deleteRecursively() }
    }

    private fun deleteOwnedFile(path: String) {
        runCatching {
            val target = File(path).canonicalFile
            if (target.parentFile == dir.canonicalFile &&
                target.name.startsWith("shot_") &&
                target.extension.equals("jpg", ignoreCase = true)
            ) target.delete()
        }
    }

    companion object {
        // Store instances share the same on-disk index, including background cleanup.
        private val lock = Any()
        private const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
        const val MAX_SCREENSHOT_STORAGE_QUOTA_BYTES = 64L * 1024 * 1024
        /** Candidate metadata budget: 16 MiB of JSONL plus at most 10,000 retained rows. */
        const val MAX_CANDIDATE_INDEX_BUDGET_BYTES = 16L * 1024 * 1024
        const val MAX_CANDIDATE_COUNT = 10_000
        private const val MAX_CANDIDATE_METADATA_BYTES = 128
        private const val MAX_CANDIDATE_HISTORY_JOURNAL_BYTES = 512
        const val SCREENSHOT_UNAVAILABLE_PREFIX = "image unavailable:"
        const val SCREENSHOT_UNAVAILABLE_QUOTA = CaptureCandidate.SCREENSHOT_UNAVAILABLE_QUOTA
        const val SCREENSHOT_UNAVAILABLE_IO = CaptureCandidate.SCREENSHOT_UNAVAILABLE_IO
        const val SCREENSHOT_UNAVAILABLE_INVALID = CaptureCandidate.SCREENSHOT_UNAVAILABLE_INVALID
        const val SCREENSHOT_UNAVAILABLE_MISSING = CaptureCandidate.SCREENSHOT_UNAVAILABLE_MISSING
        private const val INDEX_TMP_NAME = "candidates.jsonl.tmp"
        private const val CANDIDATE_HISTORY_METADATA_NAME = "candidates.meta"
        private const val CANDIDATE_HISTORY_METADATA_TMP_NAME = "candidates.meta.tmp"
        private const val CANDIDATE_HISTORY_METADATA_JOURNAL_NAME = "candidates.meta-write-ahead"
        private const val CANDIDATE_HISTORY_METADATA_JOURNAL_TMP_NAME = "candidates.meta-write-ahead.tmp"
        private const val LEGACY_CANDIDATE_HISTORY_NAME = "candidates.legacy-window"
        private const val LEGACY_CANDIDATE_HISTORY_TMP_NAME = "candidates.legacy-window.tmp"
        private const val LEGACY_CANDIDATE_HISTORY_JOURNAL_NAME = "candidates.legacy-write-ahead"
        private const val LEGACY_CANDIDATE_HISTORY_JOURNAL_TMP_NAME = "candidates.legacy-write-ahead.tmp"
        private const val FINGERPRINT_TMP_NAME = "image-fingerprints.jsonl.tmp"
        private const val FINGERPRINT_METADATA_TMP_NAME = "image-fingerprints.meta.tmp"
        private const val FINGERPRINT_UNAVAILABLE_NAME = "image-fingerprints.unavailable"
    }
}

sealed interface CandidateSaveResult {
    data class Saved(val candidate: CaptureCandidate) : CandidateSaveResult
    data class Failed(val reason: CandidateSaveFailure) : CandidateSaveResult
}

enum class CandidateSaveFailure {
    INVALID_CANDIDATE,
    STORAGE_UNAVAILABLE,
    INDEX_FULL,
}

/** A missing/corrupt index is not evidence that an image has never been seen. */
sealed interface ImageFingerprintLookup {
    data object Match : ImageFingerprintLookup
    data object CleanNoMatch : ImageFingerprintLookup
    data class Unavailable(val reason: String) : ImageFingerprintLookup
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

