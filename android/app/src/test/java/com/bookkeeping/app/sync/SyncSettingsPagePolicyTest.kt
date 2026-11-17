package com.bookkeeping.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSettingsPagePolicyTest {
    @Test
    fun statusHeadlineDistinguishesUnconfiguredDisabledAndErrors() {
        assertEquals(
            "未配置",
            syncSettingsHeadline(SyncStateKind.NOT_ENABLED, syncing = false, configured = false),
        )
        assertEquals(
            "同步已关闭",
            syncSettingsHeadline(SyncStateKind.NOT_ENABLED, syncing = false, configured = true),
        )
        assertEquals(
            "同步中",
            syncSettingsHeadline(SyncStateKind.RETRY, syncing = true, configured = true),
        )
        assertEquals(
            "同步异常",
            syncSettingsHeadline(SyncStateKind.AUTH, syncing = false, configured = true),
        )
    }

    @Test
    fun longAddressEllipsizesInTheMiddle() {
        assertEquals("abcd…wxyz", middleEllipsize("abcdefghijklmnopqrstuvwxyz", maxChars = 9))
        assertEquals("short", middleEllipsize("short", maxChars = 9))
    }
}
