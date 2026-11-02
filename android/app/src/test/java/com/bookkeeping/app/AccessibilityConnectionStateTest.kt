package com.bookkeeping.app

import com.bookkeeping.app.notification.accessibility.AccessibilityConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityConnectionStateTest {
    @Test fun permissionOffNeverReportsRunning() {
        assertEquals(
            AccessibilityConnectionState.PERMISSION_OFF,
            AccessibilityConnectionState.resolve(authorized = false, connected = true),
        )
    }

    @Test fun permissionOnDoesNotGuaranteeAConnection() {
        assertEquals(
            AccessibilityConnectionState.AUTHORIZED_NOT_CONNECTED,
            AccessibilityConnectionState.resolve(authorized = true, connected = false),
        )
    }

    @Test fun connectedCallbackPlusPermissionIsRequiredToReportRunning() {
        assertEquals(
            AccessibilityConnectionState.CONNECTED,
            AccessibilityConnectionState.resolve(authorized = true, connected = true),
        )
    }
}
