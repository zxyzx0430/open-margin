package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.capture.RestoreOutcome
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "忽略" used to be a one-way door: the card flipped to IGNORED, `pending()` only
 * returns PENDING, and nothing ever flipped it back. A mis-tap therefore cost a
 * whole bill.
 *
 * These tests pin the two ways back that the review dialog now offers:
 *
 * 1. a five-second undo right after the ignore, and
 * 2. the "已忽略的截图" list, which restores a card to PENDING.
 *
 * Restoring only rewrites the candidate status. It must never book anything:
 * the ledger write lives in TransactionRepository, which this store and the
 * restore path never touch.
 */
class CaptureIgnoreUndoTest {

    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun newStore(): CaptureCandidateStore {
        val root = Files.createTempDirectory("capture-ignore-undo").toFile()
        roots += root
        return CaptureCandidateStore(root)
    }

    private fun newStoreRoot(): File {
        val root = Files.createTempDirectory("capture-ignore-undo-raw").toFile()
        roots += root
        return root
    }

    private fun candidate(
        id: String,
        capturedAtMs: Long = NOW,
        fingerprint: String? = null,
    ): CaptureCandidate = CaptureCandidate(
        packageName = CaptureCandidate.WECHAT,
        pageIdentity = "screenshot:wechat:PAYMENT:$id",
        amountCents = 2_000L,
        merchantCandidates = listOf("店铺$id"),
        orderId = null,
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.7f,
        reason = "test",
        imageFingerprint = fingerprint,
        candidateId = id,
    )

    /** Append one index record by hand, for states save() would prune immediately. */
    private fun writeIndexRecord(root: File, candidate: CaptureCandidate) {
        val dir = File(root, "page_capture")
        assertTrue(dir.exists() || dir.mkdirs())
        File(dir, "candidates.jsonl").appendText(candidate.toJson().toString() + "\n")
    }

    /** Every status record the store kept for one card, oldest first. */
    private fun statuses(root: File, candidateId: String): List<String> {
        val index = File(File(root, "page_capture"), "candidates.jsonl")
        if (!index.isFile) return emptyList()
        return index.readLines()
            .map { JSONObject(it) }
            .filter { it.optString("candidateId") == candidateId }
            .map { it.optString("status") }
    }

    @Test
    fun `ignoring a card takes it out of pending but keeps it findable`() {
        val store = newStore()
        val card = candidate("only")

        assertNotNull(store.save(card, null))
        assertTrue(store.mark(card, CaptureCandidate.STATUS_IGNORED))

        assertTrue("an ignored card must leave the review list", store.pending().isEmpty())
        assertEquals(
            "an ignored card must stay in the recovery list",
            listOf("only"),
            store.ignored().map { it.candidateId },
        )
        assertEquals(CaptureCandidate.STATUS_IGNORED, store.ignored().single().status)
    }

    @Test
    fun `undo puts the ignored card back into review`() {
        val store = newStore()
        val card = candidate("card")
        assertNotNull(store.save(card, null))
        assertTrue(store.mark(card, CaptureCandidate.STATUS_IGNORED))

        // Same path the dialog takes: the offer hands back the card only while open.
        val offer = CaptureIgnoreUndo.Offer(card, ignoredAtMs = 1_000L)
        val taken = offer.take(nowMs = 1_000L + 2_000L)
        assertNotNull("the undo offer must still hand the card back at 2 s", taken)

        assertEquals(RestoreOutcome.RESTORED, store.restore(taken!!.candidateId))

        assertEquals(
            "undo must put the card back in the review list",
            listOf("card"),
            store.pending().map { it.candidateId },
        )
        assertEquals(CaptureCandidate.STATUS_PENDING, store.pending().single().status)
        assertTrue("a restored card must leave the ignored list", store.ignored().isEmpty())
    }

    @Test
    fun `the undo window closes after five seconds`() {
        val offer = CaptureIgnoreUndo.Offer(candidate("card"), ignoredAtMs = 1_000L)

        assertTrue(offer.isOpen(1_000L))
        assertTrue(offer.isOpen(1_000L + CaptureIgnoreUndo.WINDOW_MS - 1))
        assertFalse(
            "at the five second mark the undo entry must already be gone",
            offer.isOpen(1_000L + CaptureIgnoreUndo.WINDOW_MS),
        )
        assertNull(
            "an expired window must hand back nothing instead of a stale card",
            offer.take(1_000L + CaptureIgnoreUndo.WINDOW_MS),
        )
        assertEquals(0L, offer.remainingMs(1_000L + CaptureIgnoreUndo.WINDOW_MS))
        assertEquals(CaptureIgnoreUndo.WINDOW_MS, offer.remainingMs(1_000L))
    }

    @Test
    fun `restoring from the ignored list never books the card`() {
        val root = newStoreRoot()
        val store = CaptureCandidateStore(root)
        val card = candidate("card")
        assertNotNull(store.save(card, null))
        assertTrue(store.mark(card, CaptureCandidate.STATUS_IGNORED))

        assertEquals(RestoreOutcome.RESTORED, store.restore("card"))

        assertEquals(CaptureCandidate.STATUS_PENDING, store.pending().single().status)
        assertEquals(
            "restore must only rewrite the candidate status",
            listOf(
                CaptureCandidate.STATUS_PENDING,
                CaptureCandidate.STATUS_IGNORED,
                CaptureCandidate.STATUS_PENDING,
            ),
            statuses(root, "card"),
        )
        assertFalse(
            "restore must never produce an APPROVED record; the ledger write is a separate user action",
            statuses(root, "card").contains(CaptureCandidate.STATUS_APPROVED),
        )
    }

    @Test
    fun `restoring a card that no longer exists fails explicitly`() {
        val store = newStore()
        assertNotNull(store.save(candidate("kept"), null))

        assertEquals(RestoreOutcome.NOT_FOUND, store.restore("ghost"))
        assertEquals(RestoreOutcome.NOT_FOUND, store.restore(""))
        assertEquals(
            CaptureReviewText.RESTORE_NOT_FOUND,
            CaptureReviewText.restore(RestoreOutcome.NOT_FOUND),
        )
        assertFalse(
            "a missing card must not report the success message",
            CaptureReviewText.restore(RestoreOutcome.NOT_FOUND) == CaptureReviewText.RESTORED,
        )
    }

    @Test
    fun `restoring an expired card reports expiry instead of success`() {
        val root = newStoreRoot()
        val store = CaptureCandidateStore(root)
        val expired = candidate(
            id = "expired",
            capturedAtMs = NOW - RETENTION_MS - 60_000L,
        ).copy(status = CaptureCandidate.STATUS_IGNORED)
        // Written straight to the index: save() would prune it on the spot, and
        // the point is the boundary between "too old" and "fine".
        writeIndexRecord(root, expired)

        assertEquals(RestoreOutcome.EXPIRED, store.restore("expired", nowMs = NOW))

        assertTrue(store.pending().isEmpty())
        assertEquals(
            CaptureReviewText.RESTORE_EXPIRED,
            CaptureReviewText.restore(RestoreOutcome.EXPIRED),
        )
        assertFalse(
            "an expired screenshot must not look like a successful restore",
            CaptureReviewText.restore(RestoreOutcome.EXPIRED) == CaptureReviewText.RESTORED,
        )
    }

    @Test
    fun `restoring is refused when the card is not ignored`() {
        val store = newStore()
        val card = candidate("card")
        assertNotNull(store.save(card, null))

        assertEquals(
            "a card that is still waiting has nothing to restore",
            RestoreOutcome.NOT_IGNORED,
            store.restore("card"),
        )

        assertTrue(store.mark(card, CaptureCandidate.STATUS_IGNORED))
        assertEquals(RestoreOutcome.RESTORED, store.restore("card"))
        assertEquals(
            "a second restore must not pretend to restore again",
            RestoreOutcome.NOT_IGNORED,
            store.restore("card"),
        )
    }

    @Test
    fun `two cards from one screenshot stay two in the ignored list`() {
        val store = newStore()
        val fingerprint = "a".repeat(64)
        val first = candidate("first", NOW, fingerprint)
        val second = candidate("second", NOW + 1_000L, fingerprint)
        assertNotNull(store.save(first, null))
        assertNotNull(store.save(second, null))
        assertTrue(store.mark(first, CaptureCandidate.STATUS_IGNORED))
        assertTrue(store.mark(second, CaptureCandidate.STATUS_IGNORED))

        assertEquals(
            "the recovery list must not collapse two payments into one row",
            setOf("first", "second"),
            store.ignored().map { it.candidateId }.toSet(),
        )
        assertEquals(2, store.ignored().size)

        assertEquals(RestoreOutcome.RESTORED, store.restore("first"))
        assertEquals(listOf("first"), store.pending().map { it.candidateId })
        assertEquals(
            "restoring one card must leave its sibling ignored",
            listOf("second"),
            store.ignored().map { it.candidateId },
        )
    }

    @Test
    fun `every undo and restore label has an English entry`() {
        val labels = mapOf(
            "不记这笔" to "Don't record this",
            "已忽略的截图" to "Ignored screenshots",
            "返回待确认" to "Back to review",
            "撤销" to "Undo",
            "恢复为待确认" to "Restore to review",
            "没有被忽略的截图" to "No ignored screenshots",
        )
        labels.forEach { (source, translated) ->
            assertTrue("missing English entry: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
        listOf(
            CaptureReviewText.UNDONE,
            CaptureReviewText.UNDO_EXPIRED,
            CaptureReviewText.UNDO_CLOSED,
            CaptureReviewText.RESTORED,
            CaptureReviewText.RESTORE_NOT_FOUND,
            CaptureReviewText.RESTORE_EXPIRED,
            CaptureReviewText.RESTORE_NOT_IGNORED,
        ).forEach { source ->
            assertTrue("missing English entry: $source", EnglishUiText.hasTranslation(source))
        }
    }

    private companion object {
        /** Bucket anchors on the wall clock: retention prunes anything older than seven days. */
        val NOW = System.currentTimeMillis()
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
