package com.bookkeeping.app.notification.capture

import com.bookkeeping.app.capture.screenshot.ScreenshotRetention
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CaptureCandidateStoreSafetyTest {
    private fun candidate(
        id: String,
        capturedAtMs: Long = System.currentTimeMillis(),
        status: String = CaptureCandidate.STATUS_PENDING,
    ) = CaptureCandidate(
        packageName = null,
        pageIdentity = "fixture:$id",
        amountCents = 1_234L,
        merchantCandidates = listOf("Fixture shop"),
        orderId = null,
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.9f,
        reason = "fixture",
        status = status,
        imageFingerprint = "b".repeat(64),
        candidateId = id,
    )

    private fun withStore(block: (CaptureCandidateStore) -> Unit) {
        val root = Files.createTempDirectory("candidate-store-safety").toFile()
        try {
            block(CaptureCandidateStore(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeLegacyCandidate(root: Path, stored: CaptureCandidate) {
        val directory = root.resolve("page_capture")
        Files.createDirectories(directory)
        Files.write(
            directory.resolve("candidates.jsonl"),
            (stored.toJson().toString() + "\n").toByteArray(),
        )
    }

    @Test
    fun `approved status resists concurrent stale pending ignored and waiting writes`() = withStore { store ->
        val saved = candidate("approved-race")
        assertNotNull(store.save(saved, null))
        assertTrue(store.mark(saved, CaptureCandidate.STATUS_APPROVED))

        val statuses = listOf(
            CaptureCandidate.STATUS_PENDING,
            CaptureCandidate.STATUS_IGNORED,
            CaptureCandidate.STATUS_WAITING,
            CaptureCandidate.STATUS_LINKED,
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(statuses.size)
        try {
            val results = statuses.map { status ->
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    store.mark(saved, status)
                }
            }
            start.countDown()
            assertTrue(results.all { !it.get(5, TimeUnit.SECONDS) })
            assertEquals(CaptureCandidate.STATUS_APPROVED, store.latest().single().status)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `ignored card only returns to pending through explicit restore and pending retry stays allowed`() =
        withStore { store ->
            val saved = candidate("ignored-recovery")
            assertNotNull(store.save(saved, null))
            assertTrue(store.mark(saved, CaptureCandidate.STATUS_IGNORED))

            assertFalse("mark must not impersonate the explicit recovery action", store.mark(saved, CaptureCandidate.STATUS_PENDING))
            assertEquals(CaptureCandidate.STATUS_IGNORED, store.latest().single().status)
            assertEquals(RestoreOutcome.RESTORED, store.restore(saved.candidateId))
            assertTrue("failed writes remain retryable while pending", store.mark(saved, CaptureCandidate.STATUS_PENDING))
            assertEquals(CaptureCandidate.STATUS_PENDING, store.latest().single().status)
        }

    @Test
    fun `pending update can persist explicit review evidence`() = withStore { store ->
        val saved = candidate("review-evidence")
        assertNotNull(store.save(saved, null))
        val enriched = saved.copy(
            reason = CaptureCandidate.REASON_LIKELY_DUPLICATE,
            matchedTransactionId = 42L,
        )

        assertTrue(store.mark(enriched, CaptureCandidate.STATUS_PENDING))
        val current = store.latest().single()
        assertEquals(CaptureCandidate.REASON_LIKELY_DUPLICATE, current.reason)
        assertEquals(42L, current.matchedTransactionId)
        assertEquals(CaptureCandidate.STATUS_PENDING, current.status)
    }

    @Test
    fun `candidate image warning is backward compatible and does not replace duplicate reason`() {
        val legacy = candidate("legacy-no-image-reason")
        val legacyJson = legacy.toJson().apply { remove("screenshotUnavailableReason") }
        assertEquals(legacy, CaptureCandidate.fromJson(JSONObject(legacyJson.toString())))
        assertFalse(
            duplicateReasonFixture().copy(screenshotUnavailableReason = "IOException /private/fixture/path").isValid(),
        )

        val root = Files.createTempDirectory("candidate-store-duplicate-image-warning")
        try {
            val bytes = ByteArray(32) { it.toByte() }
            val store = CaptureCandidateStore(root.toFile(), screenshotStorageQuotaBytes = bytes.size.toLong())
            assertNotNull(store.save(candidate("image-quota-reservation"), bytes))
            val duplicate = candidate("duplicate-image-quota")
                .copy(reason = CaptureCandidate.REASON_LIKELY_DUPLICATE, matchedTransactionId = 42L)
            val saved = store.save(duplicate, ByteArray(bytes.size) { (it + 1).toByte() })

            assertEquals(CaptureCandidate.REASON_LIKELY_DUPLICATE, saved?.reason)
            assertEquals(CaptureCandidate.SCREENSHOT_UNAVAILABLE_QUOTA, saved?.screenshotUnavailableReason)
            assertEquals(saved, store.pending().single { it.candidateId == duplicate.candidateId })
            val malformedWarning = CaptureCandidate.fromJson(
                JSONObject(
                    duplicate.copy(screenshotUnavailableReason = "IOException /private/fixture/path")
                        .toJson().toString(),
                ),
            )
            assertNull("raw exception strings are discarded", malformedWarning?.screenshotUnavailableReason)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun duplicateReasonFixture() = candidate("duplicate-image-validation")
        .copy(reason = CaptureCandidate.REASON_LIKELY_DUPLICATE)

    @Test
    fun `image fingerprint lookup forgets an expired candidate`() = withStore { store ->
        val now = System.currentTimeMillis()
        val fingerprint = "c".repeat(64)
        val saved = candidate("expired-image", capturedAtMs = now)
            .copy(imageFingerprint = fingerprint)
        assertNotNull(store.save(saved, null))
        assertEquals(saved.candidateId, store.findByImageFingerprint(fingerprint)?.candidateId)

        assertTrue(store.pruneExpired(nowMs = now + ScreenshotRetention.RETENTION_MS))
        assertNull("expiration releases the fingerprint for a later legitimate import", store.findByImageFingerprint(fingerprint))

        val replacement = candidate("fresh-image", capturedAtMs = System.currentTimeMillis())
            .copy(imageFingerprint = fingerprint)
        assertNotNull(store.save(replacement, null))
        assertEquals(replacement.candidateId, store.findByImageFingerprint(fingerprint)?.candidateId)
    }

    @Test
    fun `image fingerprint survives more than two hundred other candidates until exact expiry`() = withStore { store ->
        val capturedAt = System.currentTimeMillis()
        val targetFingerprint = "a".repeat(64)
        assertNotNull(
            store.save(
                candidate("original-image", capturedAtMs = capturedAt)
                    .copy(imageFingerprint = targetFingerprint),
                null,
            ),
        )
        repeat(250) { index ->
            val fingerprint = index.toString().padStart(64, '0')
            assertNotNull(
                store.save(
                    candidate("other-$index", capturedAtMs = capturedAt + index + 1L)
                        .copy(imageFingerprint = fingerprint),
                    null,
                ),
            )
        }

        assertEquals("all retained candidates remain available", 251, store.latest(500).size)
        assertEquals("the pending review inbox is not capped at 200", 251, store.pending().size)
        assertTrue("the independent image evidence index keeps the original", store.containsImageFingerprint(targetFingerprint, capturedAt + 250L))
        assertFalse(
            "the original fingerprint expires at the same seven-day boundary as its evidence",
            store.containsImageFingerprint(targetFingerprint, capturedAt + ScreenshotRetention.RETENTION_MS),
        )
        assertTrue(store.pruneExpired(nowMs = capturedAt + ScreenshotRetention.RETENTION_MS))
        assertFalse(store.containsImageFingerprint(targetFingerprint, capturedAt + ScreenshotRetention.RETENTION_MS))
    }

    @Test
    fun `image fingerprint index backfills live cards saved by the previous store version`() {
        val root = Files.createTempDirectory("candidate-store-backfill")
        try {
            val store = CaptureCandidateStore(root.toFile())
            val capturedAt = System.currentTimeMillis()
            val fingerprint = "d".repeat(64)
            assertNotNull(
                store.save(
                    candidate("legacy-card", capturedAtMs = capturedAt)
                        .copy(imageFingerprint = fingerprint),
                    null,
                ),
            )
            Files.deleteIfExists(root.resolve("page_capture/image-fingerprints.jsonl"))

            assertTrue("live legacy cards seed the new independent index", store.containsImageFingerprint(fingerprint, capturedAt + 1L))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt fingerprint index is distinct from a clean no match`() {
        val root = Files.createTempDirectory("candidate-store-corrupt-fingerprint")
        try {
            assertEquals(
                ImageFingerprintLookup.CleanNoMatch,
                CaptureCandidateStore(root.resolve("clean").toFile())
                    .lookupImageFingerprint("e".repeat(64)),
            )
            val directory = root.resolve("page_capture")
            Files.createDirectories(directory)
            directory.resolve("image-fingerprints.jsonl").toFile().writeText("{broken fixture\n")
            val result = CaptureCandidateStore(root.toFile())
                .lookupImageFingerprint("e".repeat(64))

            assertTrue(result is ImageFingerprintLookup.Unavailable)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `empty fingerprint index recovers from complete retained candidate evidence`() {
        val root = Files.createTempDirectory("candidate-store-empty-fingerprint")
        try {
            val store = CaptureCandidateStore(root.toFile())
            val knownFingerprint = "f".repeat(64)
            val saved = candidate("known-image")
                .copy(imageFingerprint = knownFingerprint)
            assertNotNull(store.save(saved, null))
            assertTrue(
                "a fresh save must establish candidate-history completeness metadata",
                Files.isRegularFile(root.resolve("page_capture/candidates.meta")),
            )
            Files.write(root.resolve("page_capture/image-fingerprints.jsonl"), ByteArray(0))

            assertEquals(
                ImageFingerprintLookup.CleanNoMatch,
                store.lookupImageFingerprint("e".repeat(64)),
            )
            assertEquals(ImageFingerprintLookup.Match, store.lookupImageFingerprint(knownFingerprint))
            assertFalse(Files.exists(root.resolve("page_capture/image-fingerprints.unavailable")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `oldest pending card remains stored and reachable after two hundred newer cards`() {
        val root = Files.createTempDirectory("candidate-store-large-inbox")
        try {
            val store = CaptureCandidateStore(root.toFile())
            val capturedAt = System.currentTimeMillis()
            repeat(225) { index ->
                assertNotNull(
                    "candidate $index should be persisted",
                    store.save(
                        candidate("pending-$index", capturedAtMs = capturedAt + index),
                        null,
                    ),
                )
            }

            assertEquals(225, store.latest().size)
            assertEquals(225, store.pending().size)
            assertEquals("pending-0", store.pending().first().candidateId)
            assertEquals("pending-0", store.latest().first().candidateId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `candidate save reports failure when the retained index path is unusable`() {
        val root = Files.createTempDirectory("candidate-store-write-failure")
        try {
            Files.createDirectories(root.resolve("page_capture/candidates.jsonl"))
            val store = CaptureCandidateStore(root.toFile())

            assertNull(store.save(candidate("not-saved"), null))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `null or invalid retained candidate evidence prevents index replacement`() {
        listOf(
            "null parser result" to "{}",
            "parsed invalid candidate" to candidate("invalid-evidence").copy(pageIdentity = "")
                .toJson().toString(),
        ).forEachIndexed { index, (description, invalidEvidence) ->
            val root = Files.createTempDirectory("candidate-store-invalid-evidence").toFile()
            try {
                val store = CaptureCandidateStore(root)
                val retained = candidate("retained-$index")
                assertNotNull(store.save(retained, null))
                val indexFile = root.toPath().resolve("page_capture/candidates.jsonl")
                Files.write(
                    indexFile,
                    (String(Files.readAllBytes(indexFile), Charsets.UTF_8) + invalidEvidence + "\n")
                        .toByteArray(Charsets.UTF_8),
                )
                val evidenceBeforeWrite = Files.readAllBytes(indexFile)

                assertEquals(
                    "$description must fail closed",
                    CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE),
                    store.saveWithResult(candidate("must-not-replace-$index"), null),
                )
                assertTrue(
                    "$description must leave retained evidence unchanged",
                    evidenceBeforeWrite.contentEquals(Files.readAllBytes(indexFile)),
                )
                assertEquals(listOf(retained.candidateId), store.latest().map { it.candidateId })
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `candidate index capacity rejects only new rows and retry succeeds after expiry`() {
        val root = Files.createTempDirectory("candidate-store-index-budget")
        try {
            val store = CaptureCandidateStore(root.toFile(), candidateCountBudget = 2)
            val capturedAt = System.currentTimeMillis()
            val pending = candidate("budget-pending", capturedAtMs = capturedAt)
            val ignored = candidate("budget-ignored", capturedAtMs = capturedAt + 1L)
            val overflow = candidate("budget-overflow", capturedAtMs = capturedAt + 2L)

            val pendingSave = store.saveWithResult(pending, null, nowMs = capturedAt)
            assertTrue(pendingSave is CandidateSaveResult.Saved)
            assertEquals("budget-pending", (pendingSave as CandidateSaveResult.Saved).candidate.candidateId)
            assertTrue(store.saveWithResult(ignored, null, nowMs = capturedAt + 1L) is CandidateSaveResult.Saved)
            assertTrue(store.mark(ignored, CaptureCandidate.STATUS_IGNORED))
            val result = store.saveWithResult(overflow, null, nowMs = capturedAt + 2L)

            assertEquals(
                CandidateSaveResult.Failed(CandidateSaveFailure.INDEX_FULL),
                result,
            )
            assertEquals(
                listOf("budget-pending"),
                store.pending().map { it.candidateId },
            )
            assertEquals(
                listOf("budget-ignored"),
                store.ignored().map { it.candidateId },
            )
            assertFalse("overflow must not be recorded as ignored", store.latest().any {
                it.candidateId == "budget-overflow"
            })

            val retryAt = capturedAt + ScreenshotRetention.RETENTION_MS + 1L
            val retry = candidate("budget-retry", capturedAtMs = retryAt)
            assertTrue(store.pruneExpired(nowMs = retryAt))
            val retryResult = store.saveWithResult(retry, null, nowMs = retryAt)
            assertTrue(retryResult is CandidateSaveResult.Saved)
            assertEquals("budget-retry", (retryResult as CandidateSaveResult.Saved).candidate.candidateId)
            assertEquals(listOf("budget-retry"), store.pending().map { it.candidateId })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `candidate json byte budget is independent from screenshot quota`() {
        val root = Files.createTempDirectory("candidate-store-byte-budget")
        try {
            val store = CaptureCandidateStore(
                filesRoot = root.toFile(),
                screenshotStorageQuotaBytes = CaptureCandidateStore.MAX_SCREENSHOT_STORAGE_QUOTA_BYTES,
                candidateIndexBudgetBytes = 1L,
            )
            val at = System.currentTimeMillis()
            val result = store.saveWithResult(candidate("byte-overflow", capturedAtMs = at), null, nowMs = at)

            assertEquals(CandidateSaveResult.Failed(CandidateSaveFailure.INDEX_FULL), result)
            assertTrue("the candidate byte cap does not consume or modify the screenshot quota", store.pending().isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy candidate history remains unavailable until old evidence horizon then recovers safely`() {
        val root = Files.createTempDirectory("candidate-store-legacy-history")
        try {
            val directory = root.resolve("page_capture")
            Files.createDirectories(directory)
            val start = System.currentTimeMillis()
            val oldFingerprint = "a".repeat(64)
            val newFingerprint = "c".repeat(64)
            val unknownFingerprint = "e".repeat(64)
            val legacy = candidate("legacy-import", capturedAtMs = start)
                .copy(imageFingerprint = oldFingerprint)
            directory.resolve("candidates.jsonl").toFile().writeText(legacy.toJson().toString() + "\n")
            val store = CaptureCandidateStore(root.toFile())

            assertEquals(
                "a known old fingerprint remains a positive match during migration",
                ImageFingerprintLookup.Match,
                store.lookupImageFingerprint(oldFingerprint, nowMs = start + 100L),
            )
            val horizon = start + 100L + ScreenshotRetention.RETENTION_MS
            assertTrue(
                "parseable legacy JSON does not prove negative completeness",
                store.lookupImageFingerprint(unknownFingerprint, nowMs = horizon - 1L) is ImageFingerprintLookup.Unavailable,
            )
            assertFalse("completeness metadata must wait through the legacy window", Files.exists(directory.resolve("candidates.meta")))

            val postUpgradeAt = horizon - 1_000L
            val postUpgrade = candidate("post-upgrade", capturedAtMs = postUpgradeAt)
                .copy(imageFingerprint = newFingerprint)
            assertTrue(store.saveWithResult(postUpgrade, null, nowMs = postUpgradeAt) is CandidateSaveResult.Saved)
            assertTrue(
                CaptureCandidateStore(root.toFile())
                    .lookupImageFingerprint(unknownFingerprint, nowMs = horizon - 1L) is ImageFingerprintLookup.Unavailable,
            )
            assertEquals(ImageFingerprintLookup.Match, store.lookupImageFingerprint(newFingerprint, nowMs = horizon - 1L))

            assertEquals(
                "the old evidence has expired before completeness is established",
                ImageFingerprintLookup.CleanNoMatch,
                store.lookupImageFingerprint(unknownFingerprint, nowMs = horizon + 1L),
            )
            assertEquals(ImageFingerprintLookup.Match, store.lookupImageFingerprint(newFingerprint, nowMs = horizon + 1L))
            assertFalse("the expired legacy candidate is pruned", store.findByImageFingerprint(oldFingerprint) != null)
            assertTrue("the complete post-upgrade history gets verified metadata", Files.exists(directory.resolve("candidates.meta")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy checkpoint journal recovers a crash after candidate index replacement`() {
        val root = Files.createTempDirectory("candidate-store-crash-after-index")
        try {
            val now = System.currentTimeMillis()
            val oldFingerprint = "1".repeat(64)
            val interruptedFingerprint = "2".repeat(64)
            val old = candidate("wal-old", capturedAtMs = now).copy(imageFingerprint = oldFingerprint)
            val graceDeadline = now + 1L + ScreenshotRetention.RETENTION_MS
            val interruptedAt = graceDeadline - 1_000L
            writeLegacyCandidate(root, old)
            val journal = root.resolve("page_capture/candidates.legacy-write-ahead")

            assertEquals(ImageFingerprintLookup.Match, CaptureCandidateStore(root.toFile())
                .lookupImageFingerprint(oldFingerprint, now + 1L))
            val interruptedStore = CaptureCandidateStore(
                filesRoot = root.toFile(),
                writeFaultInjector = { point ->
                    if (point == CandidateHistoryWriteFaultPoint.AFTER_INDEX_REPLACEMENT) {
                        throw IllegalStateException("fixture process termination")
                    }
                },
            )
            var terminated = false
            try {
                interruptedStore.saveWithResult(
                    candidate("wal-interrupted", capturedAtMs = interruptedAt)
                        .copy(imageFingerprint = interruptedFingerprint),
                    null,
                    nowMs = interruptedAt,
                )
            } catch (_: IllegalStateException) {
                terminated = true
            }
            assertTrue("fault injection stops after the new index reached disk", terminated)
            assertTrue(Files.exists(journal))

            val reopened = CaptureCandidateStore(root.toFile())
            assertEquals(ImageFingerprintLookup.Match, reopened.lookupImageFingerprint(oldFingerprint, interruptedAt + 1L))
            assertEquals(ImageFingerprintLookup.Match, reopened.lookupImageFingerprint(interruptedFingerprint, interruptedAt + 1L))
            assertFalse("recovery consumes the journal once its checkpoint is repaired", Files.exists(journal))
            assertTrue(
                reopened.saveWithResult(
                    candidate("wal-retry", capturedAtMs = interruptedAt + 2L),
                    null,
                    nowMs = interruptedAt + 2L,
                ) is CandidateSaveResult.Saved,
            )
            assertEquals(
                listOf("wal-old", "wal-interrupted", "wal-retry"),
                reopened.pending().map { it.candidateId },
            )
            assertTrue("reopening again is idempotent", CaptureCandidateStore(root.toFile()).pending().size == 3)
            assertTrue(
                "legacy negative lookup remains fail-closed throughout its seven-day grace",
                reopened.lookupImageFingerprint("f".repeat(64), interruptedAt + 3L) is ImageFingerprintLookup.Unavailable,
            )
            assertEquals(
                "completeness resumes only after the checkpoint's seven-day deadline",
                ImageFingerprintLookup.CleanNoMatch,
                reopened.lookupImageFingerprint("f".repeat(64), graceDeadline + 1L),
            )
            assertEquals(ImageFingerprintLookup.Match, reopened.lookupImageFingerprint(interruptedFingerprint, graceDeadline + 1L))
            assertEquals(ImageFingerprintLookup.CleanNoMatch, reopened.lookupImageFingerprint(oldFingerprint, graceDeadline + 1L))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing and malformed checkpoints recover from exact old or new journal index`() {
        listOf(false, true).forEach { malformedCheckpoint ->
            listOf(false, true).forEach { currentIndexIsNew ->
                val root = Files.createTempDirectory("candidate-store-checkpoint-rebuild").toFile()
                try {
                    val directory = root.toPath().resolve("page_capture")
                    val now = System.currentTimeMillis()
                    val deadline = now + 1L + ScreenshotRetention.RETENTION_MS
                    val oldFingerprint = "6".repeat(64)
                    val newFingerprint = "7".repeat(64)
                    writeLegacyCandidate(
                        root.toPath(),
                        candidate("rebuild-old", capturedAtMs = now).copy(imageFingerprint = oldFingerprint),
                    )
                    assertEquals(
                        ImageFingerprintLookup.Match,
                        CaptureCandidateStore(root).lookupImageFingerprint(oldFingerprint, now + 1L),
                    )

                    var interrupted = false
                    try {
                        CaptureCandidateStore(
                            filesRoot = root,
                            writeFaultInjector = { point ->
                                val target = if (currentIndexIsNew) {
                                    CandidateHistoryWriteFaultPoint.AFTER_INDEX_REPLACEMENT
                                } else {
                                    CandidateHistoryWriteFaultPoint.AFTER_JOURNAL_REPLACEMENT
                                }
                                if (point == target) throw IllegalStateException("fixture process interruption")
                            },
                        ).saveWithResult(
                            candidate("rebuild-interrupted", capturedAtMs = now + 2L)
                                .copy(imageFingerprint = newFingerprint),
                            null,
                            nowMs = now + 2L,
                        )
                    } catch (_: IllegalStateException) {
                        interrupted = true
                    }
                    assertTrue("fault injection leaves a valid journal", interrupted)
                    val checkpoint = directory.resolve("candidates.legacy-window")
                    if (malformedCheckpoint) {
                        Files.write(checkpoint, "malformed checkpoint fixture\n".toByteArray())
                    } else {
                        Files.deleteIfExists(checkpoint)
                    }

                    val recovered = CaptureCandidateStore(root)
                    assertTrue(
                        recovered.saveWithResult(
                            candidate("rebuild-retry", capturedAtMs = now + 3L),
                            null,
                            nowMs = now + 3L,
                        ) is CandidateSaveResult.Saved,
                    )
                    assertFalse(Files.exists(directory.resolve("candidates.legacy-write-ahead")))
                    assertEquals(
                        deadline,
                        String(Files.readAllBytes(checkpoint), Charsets.UTF_8).trim().split(':')[1].toLong(),
                    )
                    assertEquals(
                        if (currentIndexIsNew) {
                            listOf("rebuild-old", "rebuild-interrupted", "rebuild-retry")
                        } else {
                            listOf("rebuild-old", "rebuild-retry")
                        },
                        recovered.pending().map { it.candidateId },
                    )
                    assertTrue(
                        recovered.lookupImageFingerprint("8".repeat(64), deadline - 1L) is ImageFingerprintLookup.Unavailable,
                    )
                    assertEquals(
                        ImageFingerprintLookup.CleanNoMatch,
                        recovered.lookupImageFingerprint("8".repeat(64), deadline + 1L),
                    )
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun `journal recovery stays fail closed when strict index matches neither recorded state`() {
        val root = Files.createTempDirectory("candidate-store-journal-unmatched-index")
        try {
            val now = System.currentTimeMillis()
            val oldFingerprint = "9".repeat(64)
            writeLegacyCandidate(
                root,
                candidate("unmatched-old", capturedAtMs = now).copy(imageFingerprint = oldFingerprint),
            )
            assertEquals(
                ImageFingerprintLookup.Match,
                CaptureCandidateStore(root.toFile()).lookupImageFingerprint(oldFingerprint, now + 1L),
            )

            var interrupted = false
            try {
                CaptureCandidateStore(
                    filesRoot = root.toFile(),
                    writeFaultInjector = { point ->
                        if (point == CandidateHistoryWriteFaultPoint.AFTER_INDEX_REPLACEMENT) {
                            throw IllegalStateException("fixture process interruption")
                        }
                    },
                ).saveWithResult(candidate("unmatched-new", capturedAtMs = now + 2L), null, now + 2L)
            } catch (_: IllegalStateException) {
                interrupted = true
            }
            assertTrue(interrupted)
            writeLegacyCandidate(
                root,
                candidate("unrecorded-index", capturedAtMs = now + 3L),
            )

            val store = CaptureCandidateStore(root.toFile())
            assertEquals(
                CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE),
                store.saveWithResult(candidate("must-not-write"), null, now + 4L),
            )
            assertTrue(root.resolve("page_capture/candidates.legacy-write-ahead").toFile().isFile)
            assertEquals(listOf("unrecorded-index"), store.pending().map { it.candidateId })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy journal removes itself after a crash before index replacement and allows retry`() {
        val root = Files.createTempDirectory("candidate-store-crash-before-index")
        try {
            val now = System.currentTimeMillis()
            val fingerprint = "3".repeat(64)
            val old = candidate("wal-before-old", capturedAtMs = now).copy(imageFingerprint = fingerprint)
            writeLegacyCandidate(root, old)
            val directory = root.resolve("page_capture")
            val oldIndexBytes = Files.readAllBytes(directory.resolve("candidates.jsonl"))
            val journal = directory.resolve("candidates.legacy-write-ahead")
            assertEquals(
                ImageFingerprintLookup.Match,
                CaptureCandidateStore(root.toFile()).lookupImageFingerprint(fingerprint, now + 1L),
            )

            var terminated = false
            try {
                CaptureCandidateStore(
                    filesRoot = root.toFile(),
                    writeFaultInjector = { point ->
                        if (point == CandidateHistoryWriteFaultPoint.AFTER_JOURNAL_REPLACEMENT) {
                            throw IllegalStateException("fixture process termination")
                        }
                    },
                ).saveWithResult(candidate("wal-before-retry", capturedAtMs = now + 2L), null, nowMs = now + 2L)
            } catch (_: IllegalStateException) {
                terminated = true
            }
            assertTrue(terminated)
            assertTrue(Files.exists(journal))
            assertTrue(oldIndexBytes.contentEquals(Files.readAllBytes(directory.resolve("candidates.jsonl"))))

            val reopened = CaptureCandidateStore(root.toFile())
            assertTrue(
                reopened.saveWithResult(
                    candidate("wal-before-retry", capturedAtMs = now + 2L),
                    null,
                    nowMs = now + 2L,
                ) is CandidateSaveResult.Saved,
            )
            assertFalse(Files.exists(journal))
            assertEquals(listOf("wal-before-old", "wal-before-retry"), reopened.pending().map { it.candidateId })
            assertEquals(ImageFingerprintLookup.Match, reopened.lookupImageFingerprint(fingerprint, now + 3L))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed checkpoint repair remains retryable without losing either index version rows`() {
        val root = Files.createTempDirectory("candidate-store-checkpoint-repair-failure")
        try {
            val now = System.currentTimeMillis()
            val oldFingerprint = "4".repeat(64)
            val newFingerprint = "5".repeat(64)
            writeLegacyCandidate(
                root,
                candidate("repair-old", capturedAtMs = now).copy(imageFingerprint = oldFingerprint),
            )
            assertEquals(
                ImageFingerprintLookup.Match,
                CaptureCandidateStore(root.toFile()).lookupImageFingerprint(oldFingerprint, now + 1L),
            )
            var terminated = false
            try {
                CaptureCandidateStore(
                    filesRoot = root.toFile(),
                    writeFaultInjector = { point ->
                        if (point == CandidateHistoryWriteFaultPoint.AFTER_INDEX_REPLACEMENT) {
                            throw IllegalStateException("fixture process termination")
                        }
                    },
                ).saveWithResult(
                    candidate("repair-interrupted", capturedAtMs = now + 2L)
                        .copy(imageFingerprint = newFingerprint),
                    null,
                    nowMs = now + 2L,
                )
            } catch (_: IllegalStateException) {
                terminated = true
            }
            assertTrue("fault injection stops after index replacement", terminated)

            val journal = root.resolve("page_capture/candidates.legacy-write-ahead")
            Files.delete(root.resolve("page_capture/candidates.legacy-window"))
            val failingRepair = CaptureCandidateStore(
                filesRoot = root.toFile(),
                writeFaultInjector = { point ->
                    if (point == CandidateHistoryWriteFaultPoint.BEFORE_CHECKPOINT_REPLACEMENT) {
                        throw IllegalStateException("fixture checkpoint write failure")
                    }
                },
            )
            assertEquals(ImageFingerprintLookup.Match, failingRepair.lookupImageFingerprint(oldFingerprint, now + 3L))
            assertTrue(Files.exists(journal))
            assertEquals(
                CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE),
                failingRepair.saveWithResult(candidate("repair-retry", capturedAtMs = now + 4L), null, now + 4L),
            )
            assertEquals(
                listOf("repair-old", "repair-interrupted"),
                failingRepair.pending().map { it.candidateId },
            )

            val recovered = CaptureCandidateStore(root.toFile())
            assertTrue(
                recovered.saveWithResult(
                    candidate("repair-retry", capturedAtMs = now + 4L),
                    null,
                    nowMs = now + 4L,
                ) is CandidateSaveResult.Saved,
            )
            assertEquals(
                listOf("repair-old", "repair-interrupted", "repair-retry"),
                recovered.pending().map { it.candidateId },
            )
            assertEquals(ImageFingerprintLookup.Match, recovered.lookupImageFingerprint(newFingerprint, now + 5L))
            assertTrue(recovered.lookupImageFingerprint("f".repeat(64), now + 5L) is ImageFingerprintLookup.Unavailable)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parseable legacy index truncation cannot complete migration`() {
        val root = Files.createTempDirectory("candidate-store-legacy-truncation")
        try {
            val directory = root.resolve("page_capture")
            Files.createDirectories(directory)
            val start = System.currentTimeMillis()
            val first = candidate("legacy-first", capturedAtMs = start)
                .copy(imageFingerprint = "1".repeat(64))
            val second = candidate("legacy-second", capturedAtMs = start + 1L)
                .copy(imageFingerprint = "2".repeat(64))
            val index = directory.resolve("candidates.jsonl")
            Files.write(index, (first.toJson().toString() + "\n" + second.toJson().toString() + "\n").toByteArray())
            val store = CaptureCandidateStore(root.toFile())

            assertEquals(ImageFingerprintLookup.Match, store.lookupImageFingerprint("1".repeat(64), start))
            Files.write(index, (first.toJson().toString() + "\n").toByteArray())

            assertTrue(
                store.lookupImageFingerprint(
                    "e".repeat(64),
                    start + ScreenshotRetention.RETENTION_MS + 2L,
                ) is ImageFingerprintLookup.Unavailable,
            )
            assertFalse("a parseable truncated file must not receive completeness metadata", Files.exists(directory.resolve("candidates.meta")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `identical screenshots share one retained image file`() {
        val root = Files.createTempDirectory("candidate-store-shared-image")
        try {
            val store = CaptureCandidateStore(root.toFile())
            val bytes = ByteArray(256) { (it % 17).toByte() }
            assertNotNull(store.save(candidate("image-card-a"), bytes))
            assertNotNull(store.save(candidate("image-card-b"), bytes))

            val candidates = store.pending()
            assertEquals(2, candidates.size)
            assertEquals(1, candidates.map { it.screenshotPath }.distinct().size)
            assertEquals(
                1,
                root.resolve("page_capture").toFile().listFiles().orEmpty()
                    .count { it.name.startsWith("shot_") },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unavailable fingerprint marker recovers from complete retained candidate evidence`() {
        val root = Files.createTempDirectory("candidate-store-fingerprint-recovery")
        try {
            val store = CaptureCandidateStore(root.toFile())
            // A fingerprint must be SHA-256 hex; keep this fixture valid so the
            // test exercises recovery rather than candidate validation.
            val fingerprint = "a".repeat(64)
            assertNotNull(store.save(candidate("recovery-source").copy(imageFingerprint = fingerprint), null))
            val marker = root.resolve("page_capture/image-fingerprints.unavailable")
            Files.write(marker, "unavailable\n".toByteArray())

            assertEquals(ImageFingerprintLookup.CleanNoMatch, store.lookupImageFingerprint("e".repeat(64)))
            assertEquals(ImageFingerprintLookup.Match, store.lookupImageFingerprint(fingerprint))
            assertFalse("marker clears only after the rebuilt index verifies", Files.exists(marker))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `candidate metadata journal reconstructs missing metadata for the exact old index after interruption`() {
        val root = Files.createTempDirectory("candidate-store-meta-wal-old-state")
        try {
            val first = candidate("meta-old-first", capturedAtMs = System.currentTimeMillis())
                .copy(imageFingerprint = "3".repeat(64))
            val second = candidate("meta-old-second", capturedAtMs = first.capturedAtMs + 1L)
                .copy(imageFingerprint = "4".repeat(64))
            assertTrue(CaptureCandidateStore(root.toFile()).saveWithResult(first, null) is CandidateSaveResult.Saved)

            var interrupted = false
            try {
                CaptureCandidateStore(
                    filesRoot = root.toFile(),
                    writeFaultInjector = { point ->
                        if (point == CandidateHistoryWriteFaultPoint.AFTER_CANDIDATE_METADATA_JOURNAL_REPLACEMENT) {
                            throw IllegalStateException("fixture process interruption")
                        }
                    },
                ).saveWithResult(second, null)
            } catch (_: IllegalStateException) {
                interrupted = true
            }
            assertTrue(interrupted)

            val directory = root.resolve("page_capture")
            val journal = directory.resolve("candidates.meta-write-ahead")
            assertTrue(Files.isRegularFile(journal))
            Files.delete(directory.resolve("candidates.meta"))

            val reopened = CaptureCandidateStore(root.toFile())
            assertEquals(ImageFingerprintLookup.CleanNoMatch, reopened.lookupImageFingerprint("e".repeat(64)))
            assertFalse("old-state recovery consumes the journal", Files.exists(journal))
            assertEquals(listOf(first.candidateId), reopened.pending().map { it.candidateId })
            assertTrue(reopened.saveWithResult(second, null) is CandidateSaveResult.Saved)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `candidate metadata journal repairs a corrupt sidecar for the exact new index after interruption`() {
        val root = Files.createTempDirectory("candidate-store-meta-wal-new-state")
        try {
            val first = candidate("meta-new-first", capturedAtMs = System.currentTimeMillis())
                .copy(imageFingerprint = "5".repeat(64))
            val second = candidate("meta-new-second", capturedAtMs = first.capturedAtMs + 1L)
                .copy(imageFingerprint = "6".repeat(64))
            assertTrue(CaptureCandidateStore(root.toFile()).saveWithResult(first, null) is CandidateSaveResult.Saved)

            var interrupted = false
            try {
                CaptureCandidateStore(
                    filesRoot = root.toFile(),
                    writeFaultInjector = { point ->
                        if (point == CandidateHistoryWriteFaultPoint.AFTER_CANDIDATE_METADATA_INDEX_REPLACEMENT) {
                            throw IllegalStateException("fixture process interruption")
                        }
                    },
                ).saveWithResult(second, null)
            } catch (_: IllegalStateException) {
                interrupted = true
            }
            assertTrue(interrupted)

            val directory = root.resolve("page_capture")
            val journal = directory.resolve("candidates.meta-write-ahead")
            assertTrue(Files.isRegularFile(journal))
            Files.write(directory.resolve("candidates.meta"), "broken metadata\n".toByteArray())

            val reopened = CaptureCandidateStore(root.toFile())
            assertEquals(ImageFingerprintLookup.Match, reopened.lookupImageFingerprint("6".repeat(64)))
            assertEquals(ImageFingerprintLookup.CleanNoMatch, reopened.lookupImageFingerprint("e".repeat(64)))
            assertFalse("new-state recovery consumes the journal", Files.exists(journal))
            assertEquals(
                listOf(first.candidateId, second.candidateId),
                reopened.pending().map { it.candidateId },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `candidate metadata journal remains fail closed for an index matching neither recorded state`() {
        val root = Files.createTempDirectory("candidate-store-meta-wal-unmatched")
        try {
            val capturedAt = System.currentTimeMillis()
            val first = candidate("meta-mismatch-first", capturedAtMs = capturedAt)
                .copy(imageFingerprint = "7".repeat(64))
            val second = candidate("meta-mismatch-second", capturedAtMs = capturedAt + 1L)
            assertTrue(CaptureCandidateStore(root.toFile()).saveWithResult(first, null) is CandidateSaveResult.Saved)

            try {
                CaptureCandidateStore(
                    filesRoot = root.toFile(),
                    writeFaultInjector = { point ->
                        if (point == CandidateHistoryWriteFaultPoint.AFTER_CANDIDATE_METADATA_INDEX_REPLACEMENT) {
                            throw IllegalStateException("fixture process interruption")
                        }
                    },
                ).saveWithResult(second, null)
            } catch (_: IllegalStateException) {
                // The new index is on disk, but the write-ahead intent remains.
            }

            val directory = root.resolve("page_capture")
            val journal = directory.resolve("candidates.meta-write-ahead")
            assertTrue(Files.isRegularFile(journal))
            val unrecorded = candidate("meta-mismatch-unrecorded", capturedAtMs = capturedAt + 2L)
            Files.write(
                directory.resolve("candidates.jsonl"),
                (unrecorded.toJson().toString() + "\n").toByteArray(),
            )

            val reopened = CaptureCandidateStore(root.toFile())
            assertTrue(reopened.lookupImageFingerprint("e".repeat(64)) is ImageFingerprintLookup.Unavailable)
            assertTrue("unmatched index state must retain the write-ahead intent", Files.exists(journal))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `metadata replacement failure is reported and exact journal state recovers after restart`() {
        val root = Files.createTempDirectory("candidate-store-meta-write-failure")
        try {
            val directory = root.resolve("page_capture")
            val store = CaptureCandidateStore(root.toFile())
            val first = candidate("metadata-first", capturedAtMs = System.currentTimeMillis())
                .copy(imageFingerprint = "1".repeat(64))
            val second = candidate("metadata-second", capturedAtMs = first.capturedAtMs + 1L)
                .copy(imageFingerprint = "2".repeat(64))
            assertTrue(store.saveWithResult(first, null) is CandidateSaveResult.Saved)
            assertTrue(
                "the first save must have a verified metadata sidecar before inducing replacement failure",
                Files.isRegularFile(directory.resolve("candidates.meta")),
            )

            // Force replaceFile(candidates.meta.tmp, candidates.meta, ...) to
            // fail after the new candidate index has been atomically replaced.
            Files.createDirectory(directory.resolve("candidates.meta.tmp"))
            assertEquals(
                CandidateSaveResult.Failed(CandidateSaveFailure.STORAGE_UNAVAILABLE),
                store.saveWithResult(second, null),
            )

            Files.delete(directory.resolve("candidates.meta.tmp"))
            val reopened = CaptureCandidateStore(root.toFile())
            assertEquals(ImageFingerprintLookup.Match, reopened.lookupImageFingerprint("2".repeat(64)))
            assertEquals(ImageFingerprintLookup.CleanNoMatch, reopened.lookupImageFingerprint("e".repeat(64)))
            assertEquals(
                listOf("metadata-first", "metadata-second"),
                reopened.pending().map { it.candidateId },
            )
            assertFalse("a repaired journal is consumed", Files.exists(directory.resolve("candidates.meta-write-ahead")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unavailable fingerprint marker stays when candidate evidence is corrupt`() {
        val root = Files.createTempDirectory("candidate-store-fingerprint-incomplete")
        try {
            val store = CaptureCandidateStore(root.toFile())
            assertNotNull(store.save(candidate("corrupt-source"), null))
            val directory = root.resolve("page_capture")
            Files.write(directory.resolve("candidates.jsonl"), "{broken fixture\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
            val marker = directory.resolve("image-fingerprints.unavailable")
            Files.write(marker, "unavailable\n".toByteArray())

            assertTrue(store.lookupImageFingerprint("e".repeat(64)) is ImageFingerprintLookup.Unavailable)
            assertTrue("incomplete evidence must leave the fail-closed marker", Files.exists(marker))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unavailable fingerprint marker stays when retained screenshot evidence lacks a fingerprint`() {
        val root = Files.createTempDirectory("candidate-store-fingerprint-missing-evidence")
        try {
            val store = CaptureCandidateStore(root.toFile())
            val screenshotCandidate = candidate("missing-fingerprint")
                .copy(intakeMethod = CaptureCandidate.INTAKE_SHARE, imageFingerprint = null)
            assertNotNull(store.save(screenshotCandidate, null))
            val marker = root.resolve("page_capture/image-fingerprints.unavailable")
            Files.write(marker, "unavailable\n".toByteArray())

            assertTrue(store.lookupImageFingerprint("e".repeat(64)) is ImageFingerprintLookup.Unavailable)
            assertTrue("missing fingerprint evidence must remain unavailable", Files.exists(marker))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `empty candidate history cannot clear unavailable fingerprint state`() {
        val root = Files.createTempDirectory("candidate-store-empty-candidate-history")
        try {
            val store = CaptureCandidateStore(root.toFile())
            assertNotNull(store.save(candidate("empty-history-source"), null))
            val directory = root.resolve("page_capture")
            Files.write(directory.resolve("candidates.jsonl"), ByteArray(0))
            val marker = directory.resolve("image-fingerprints.unavailable")
            Files.write(marker, "unavailable\n".toByteArray())

            assertTrue(store.lookupImageFingerprint("e".repeat(64)) is ImageFingerprintLookup.Unavailable)
            assertTrue("empty candidate evidence is not proof of no prior images", Files.exists(marker))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `valid prefix truncation cannot be recovered as a clean history`() {
        val root = Files.createTempDirectory("candidate-store-truncated-candidate-history")
        try {
            val store = CaptureCandidateStore(root.toFile())
            val first = candidate("truncated-prefix", capturedAtMs = System.currentTimeMillis())
                .copy(imageFingerprint = "1".repeat(64))
            val second = candidate("truncated-tail", capturedAtMs = first.capturedAtMs + 1L)
                .copy(imageFingerprint = "2".repeat(64))
            assertNotNull(store.save(first, null))
            assertNotNull(store.save(second, null))
            val candidateFile = root.resolve("page_capture/candidates.jsonl")
            val validPrefix = Files.readAllLines(candidateFile).first() + "\n"
            Files.write(candidateFile, validPrefix.toByteArray())
            val marker = root.resolve("page_capture/image-fingerprints.unavailable")
            Files.write(marker, "unavailable\n".toByteArray())

            assertEquals(ImageFingerprintLookup.Match, store.lookupImageFingerprint("1".repeat(64)))
            assertTrue(store.lookupImageFingerprint("e".repeat(64)) is ImageFingerprintLookup.Unavailable)
            assertTrue("truncated history keeps its fail-closed marker", Files.exists(marker))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy two hundred candidate history without completeness metadata stays unavailable`() {
        val root = Files.createTempDirectory("candidate-store-legacy-capped-history")
        try {
            val directory = root.resolve("page_capture")
            Files.createDirectories(directory)
            val capturedAt = System.currentTimeMillis()
            val legacyRows = (0 until 200).map { index ->
                candidate("legacy-$index", capturedAtMs = capturedAt + index)
                    .copy(imageFingerprint = index.toString().padStart(64, '0'))
                    .toJson()
                    .toString()
            }
            Files.write(directory.resolve("candidates.jsonl"), (legacyRows.joinToString("\n") + "\n").toByteArray())
            val marker = directory.resolve("image-fingerprints.unavailable")
            Files.write(marker, "unavailable\n".toByteArray())

            val store = CaptureCandidateStore(root.toFile())
            assertTrue(store.lookupImageFingerprint("e".repeat(64)) is ImageFingerprintLookup.Unavailable)
            assertTrue("legacy capped evidence must not be certified by parsing", Files.exists(marker))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing or unreadable candidate history metadata keeps lookup unavailable`() {
        listOf(null, "broken metadata\n").forEachIndexed { index, metadata ->
            val root = Files.createTempDirectory("candidate-store-candidate-metadata-$index")
            try {
                val store = CaptureCandidateStore(root.toFile())
                assertNotNull(store.save(candidate("metadata-$index"), null))
                val directory = root.resolve("page_capture")
                val metadataFile = directory.resolve("candidates.meta")
                assertTrue("the saved candidate must have established metadata before this corruption test", Files.isRegularFile(metadataFile))
                if (metadata == null) {
                    Files.delete(metadataFile)
                } else {
                    Files.write(metadataFile, metadata.toByteArray())
                }
                val marker = directory.resolve("image-fingerprints.unavailable")
                Files.write(marker, "unavailable\n".toByteArray())

                assertTrue(store.lookupImageFingerprint("e".repeat(64)) is ImageFingerprintLookup.Unavailable)
                assertTrue(Files.exists(marker))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `screenshot quota preserves candidate metadata and marks image unavailable`() {
        val root = Files.createTempDirectory("candidate-store-screenshot-quota")
        try {
            val bytes = ByteArray(32) { it.toByte() }
            val store = CaptureCandidateStore(root.toFile(), screenshotStorageQuotaBytes = bytes.size.toLong())
            val first = store.save(candidate("quota-first"), bytes)
            val shared = store.save(candidate("quota-shared"), bytes)
            val second = store.save(
                candidate("quota-second").copy(imageFingerprint = null),
                ByteArray(bytes.size) { (it + 1).toByte() },
            )

            assertNotNull(first?.screenshotPath)
            assertEquals(first?.screenshotPath, shared?.screenshotPath)
            assertNotNull(second)
            assertNull(second?.screenshotPath)
            assertEquals("fixture", second?.reason)
            assertEquals(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA, second?.screenshotUnavailableReason)
            assertNotNull("the candidate retains a hash of the unavailable image", second?.imageFingerprint)
            assertEquals(3, store.pending().size)
            assertEquals(
                bytes.size.toLong(),
                root.resolve("page_capture").toFile().listFiles().orEmpty()
                    .filter { it.name.startsWith("shot_") }
                    .sumOf { it.length() },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shared screenshot bytes remain until the final live candidate expires`() {
        val root = Files.createTempDirectory("candidate-store-shared-image-cleanup")
        try {
            val capturedAt = System.currentTimeMillis()
            val bytes = ByteArray(128) { (it % 23).toByte() }
            val store = CaptureCandidateStore(root.toFile())
            val first = store.save(candidate("shared-expiring", capturedAtMs = capturedAt), bytes)
            val second = store.save(candidate("shared-live", capturedAtMs = capturedAt + 1L), bytes)
            val sharedPath = requireNotNull(first?.screenshotPath)
            assertEquals(sharedPath, second?.screenshotPath)

            assertTrue(store.pruneExpired(nowMs = capturedAt + ScreenshotRetention.RETENTION_MS))
            assertTrue("one live reference keeps the shared bytes", File(sharedPath).isFile)
            assertEquals("shared-live", store.pending().single().candidateId)

            assertTrue(store.pruneExpired(nowMs = capturedAt + 1L + ScreenshotRetention.RETENTION_MS))
            assertFalse("the bytes are removed after the last reference expires", File(sharedPath).exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
