package com.bookkeeping.app.capture.screenshot

/**
 * Seven-day retention rules for screenshot evidence. Pure functions so the
 * boundary and orphan behaviour are testable without the filesystem.
 *
 * Only files inside the app-private capture directory are ever considered;
 * the system gallery is out of scope by construction.
 */
object ScreenshotRetention {
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /** At exactly seven days the record is expired: the cap is "at most 7 days". */
    fun isExpired(capturedAtMs: Long, nowMs: Long, retentionMs: Long = RETENTION_MS): Boolean =
        capturedAtMs <= nowMs - retentionMs

    /**
     * Files that no live candidate references. [files] are bare names inside the
     * capture directory; the index itself is never returned.
     */
    fun orphans(
        files: List<String>,
        referencedNames: Set<String>,
        keepNames: Set<String> = emptySet(),
    ): List<String> = files.filterNot { it in referencedNames || it in keepNames }
}
