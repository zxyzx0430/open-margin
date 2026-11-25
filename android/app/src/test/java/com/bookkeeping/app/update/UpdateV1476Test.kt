package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class UpdateV1476Test {
    @Test
    fun `cleanupObsoleteApksInDir removes old installers and keeps target or unrelated files`() {
        val dir = createTempDirectory("apk-cleanup-test").toFile()
        try {
            val oldApk = File(dir, "open-margin-1.47.4-3c76bc7e.apk").apply { writeText("old") }
            val oldPart = File(dir, "open-margin-1.47.3-66f7b5b2.apk.part").apply { writeText("part") }
            val legacyApk = File(dir, "qingheng-v144-test.apk").apply { writeText("legacy") }
            val currentApk = File(dir, "open-margin-1.47.6-abcdef12.apk").apply { writeText("current") }
            val unrelated = File(dir, "notes.txt").apply { writeText("keep") }

            val removed = UpdateInstallFlow.cleanupObsoleteApksInDir(
                dir = dir,
                keepFileName = "open-margin-1.47.6-abcdef12.apk",
            )
            assertEquals(3, removed)
            assertFalse(oldApk.exists())
            assertFalse(oldPart.exists())
            assertFalse(legacyApk.exists())
            assertTrue(currentApk.exists())
            assertTrue(unrelated.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SiteUpdateCoordinator auto-prompts ready version once per session`() {
        SiteUpdateCoordinator.resetSessionForTest()
        assertTrue(SiteUpdateCoordinator.shouldAutoPromptReady(54))
        SiteUpdateCoordinator.markAutoPromptedReady(54)
        assertFalse(SiteUpdateCoordinator.shouldAutoPromptReady(54))
        assertTrue(SiteUpdateCoordinator.shouldAutoPromptReady(55))
    }
}
