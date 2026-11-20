package com.bookkeeping.app.capture.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotWatchPermissionPolicyTest {
    @Test
    fun `partial photo access never enables MediaStore watcher`() {
        val state = ScreenshotWatchPermissionPolicy.state(
            available = true,
            enabled = true,
            access = ScreenshotWatchPermissionPolicy.Access.PARTIAL,
        )

        assertEquals(ScreenshotWatchPermissionPolicy.State.PERMISSION_PARTIAL, state)
        assertFalse(ScreenshotWatchPermissionPolicy.mayWatch(ScreenshotWatchPermissionPolicy.Access.PARTIAL))
    }

    @Test
    fun `full photo access enables watcher only after opt in`() {
        assertEquals(
            ScreenshotWatchPermissionPolicy.State.OFF,
            ScreenshotWatchPermissionPolicy.state(
                available = true,
                enabled = false,
                access = ScreenshotWatchPermissionPolicy.Access.FULL,
            ),
        )
        val enabled = ScreenshotWatchPermissionPolicy.state(
            available = true,
            enabled = true,
            access = ScreenshotWatchPermissionPolicy.Access.FULL,
        )
        assertEquals(ScreenshotWatchPermissionPolicy.State.ACTIVE, enabled)
        assertTrue(ScreenshotWatchPermissionPolicy.mayWatch(ScreenshotWatchPermissionPolicy.Access.FULL))
    }
}
