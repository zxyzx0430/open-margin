package com.bookkeeping.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallFlowRangeTest {
    @Test
    fun `isValidPartialRange accepts exact byte range matching expected total`() {
        assertTrue(
            UpdateInstallFlow.isValidPartialRange(
                contentRange = "bytes 1024-9999/10000",
                expectedStart = 1024L,
                expectedTotal = 10000L,
            ),
        )
    }

    @Test
    fun `isValidPartialRange rejects wrong start offset or mismatched total`() {
        assertFalse(
            UpdateInstallFlow.isValidPartialRange(
                contentRange = "bytes 0-9999/10000",
                expectedStart = 1024L,
                expectedTotal = 10000L,
            ),
        )
        assertFalse(
            UpdateInstallFlow.isValidPartialRange(
                contentRange = "bytes 1024-9999/12000",
                expectedStart = 1024L,
                expectedTotal = 10000L,
            ),
        )
        assertFalse(
            UpdateInstallFlow.isValidPartialRange(
                contentRange = null,
                expectedStart = 1024L,
                expectedTotal = 10000L,
            ),
        )
    }
}
