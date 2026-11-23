package com.bookkeeping.app.capture.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptEvidenceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun archiveBytes_deduplicatesIdenticalContentAndSupportsDelete() {
        val store = ReceiptEvidenceStore(tempFolder.root)
        val payload = "fake-receipt-image-bytes".toByteArray()

        val first = store.archiveBytes(payload)
        val second = store.archiveBytes(payload)

        assertNotNull(first)
        assertEquals(first, second)
        assertNotNull(store.readablePath(first))
        assertTrue(store.totalSizeBytes() > 0L)

        assertTrue(store.deleteIfPresent(first))
        assertNull(store.readablePath(first))
    }

    @Test
    fun sanitizeFileName_rejectsPathTraversal() {
        assertNull(ReceiptEvidenceStore.sanitizeFileName("../secret.webp"))
        assertNull(ReceiptEvidenceStore.sanitizeFileName("ev_short.webp"))
        val valid = "ev_" + "a".repeat(64) + ".webp"
        assertEquals(valid, ReceiptEvidenceStore.sanitizeFileName(valid))
    }

    @Test
    fun planPrune_removesOrphansExpiredAndOverQuotaUnderNinetyDayMode() {
        val now = 1_000_000_000_000L
        val dayMs = 24L * 60 * 60 * 1000
        val orphan = ReceiptEvidenceStore.EvidenceFileEntry("ev_${"1".repeat(64)}.webp", 50, now - dayMs)
        val expired = ReceiptEvidenceStore.EvidenceFileEntry("ev_${"2".repeat(64)}.webp", 50, now - 91 * dayMs)
        val oldActive = ReceiptEvidenceStore.EvidenceFileEntry("ev_${"3".repeat(64)}.webp", 70, now - 10 * dayMs)
        val newActive = ReceiptEvidenceStore.EvidenceFileEntry("ev_${"4".repeat(64)}.webp", 60, now - dayMs)

        val toDelete = ReceiptEvidenceStore.planPrune(
            files = listOf(orphan, expired, oldActive, newActive),
            activeImageNames = setOf(expired.name, oldActive.name, newActive.name),
            retentionMode = ReceiptEvidenceSettings.RetentionMode.NINETY_DAYS,
            nowMs = now,
            retentionMs = 90 * dayMs,
            maxQuotaBytes = 100L,
        )

        assertEquals(listOf(orphan.name, expired.name, oldActive.name), toDelete)
    }

    @Test
    fun planPrune_foreverModeOnlyRemovesOrphans() {
        val now = 1_000_000_000_000L
        val dayMs = 24L * 60 * 60 * 1000
        val orphan = ReceiptEvidenceStore.EvidenceFileEntry("ev_${"1".repeat(64)}.webp", 50, now - dayMs)
        val oldActive = ReceiptEvidenceStore.EvidenceFileEntry("ev_${"2".repeat(64)}.webp", 500, now - 400 * dayMs)

        val toDelete = ReceiptEvidenceStore.planPrune(
            files = listOf(orphan, oldActive),
            activeImageNames = setOf(oldActive.name),
            retentionMode = ReceiptEvidenceSettings.RetentionMode.FOREVER,
            nowMs = now,
            maxQuotaBytes = 100L,
        )

        assertEquals(listOf(orphan.name), toDelete)
        assertFalse(oldActive.name in toDelete)
    }
}
