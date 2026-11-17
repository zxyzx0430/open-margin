package com.bookkeeping.app.billimport

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BillImportFileReaderTest {
    @Test
    fun acceptsFileExactlyAtLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(bytes, BillImportFileReader.readAtMost(ByteArrayInputStream(bytes), 4))
    }

    @Test
    fun detectsOversizeAfterReadingOnlyOneBytePastLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val input = ByteArrayInputStream(bytes)

        try {
            BillImportFileReader.readAtMost(input, 4)
            throw AssertionError("Expected an oversized-file exception")
        } catch (_: BillImportFileTooLargeException) {
            assertEquals(5, bytes.size - input.available())
        }
    }
}
