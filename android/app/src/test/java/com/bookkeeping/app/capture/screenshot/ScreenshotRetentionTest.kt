package com.bookkeeping.app.capture.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seven-day cap and orphan cleanup rules. The system gallery is never touched. */
class ScreenshotRetentionTest {

    private val now = 1_763_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `records younger than seven days are kept`() {
        assertFalse(ScreenshotRetention.isExpired(now - 6 * day, now))
        assertFalse(ScreenshotRetention.isExpired(now - 7 * day + 1, now))
        assertFalse(ScreenshotRetention.isExpired(now, now))
    }

    @Test
    fun `exactly seven days old is expired`() {
        assertTrue(ScreenshotRetention.isExpired(now - 7 * day, now))
        assertTrue(ScreenshotRetention.isExpired(now - 8 * day, now))
    }

    @Test
    fun `only unreferenced files are orphans`() {
        val files = listOf("shot_a.jpg", "shot_b.jpg", "shot_c.jpg", "candidates.jsonl")
        val referenced = setOf("shot_a.jpg")
        val orphans = ScreenshotRetention.orphans(
            files = files,
            referencedNames = referenced,
            keepNames = setOf("candidates.jsonl"),
        )
        assertEquals(listOf("shot_b.jpg", "shot_c.jpg"), orphans)
    }

    @Test
    fun `index and in-flight intake files are never deleted`() {
        val orphans = ScreenshotRetention.orphans(
            files = listOf("candidates.jsonl", "candidates.jsonl.tmp", "intake_new.jpg"),
            referencedNames = emptySet(),
            keepNames = setOf("candidates.jsonl", "candidates.jsonl.tmp", "intake_new.jpg"),
        )
        assertTrue(orphans.isEmpty())
    }
}
