package com.bookkeeping.app.capture.screenshot

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenshotBoundedReaderTest {
    @Test
    fun `unknown-size input exceeding max reads only one sentinel byte and returns no prefix`() {
        val source = CountingInputStream(size = 8)

        val result = ScreenshotBoundedReader.read(source, maxBytes = 5L)

        assertSame(ScreenshotBoundedReader.Result.Oversized, result)
        assertEquals(6, source.bytesRead)
    }

    @Test
    fun `input exactly at max is returned complete`() {
        val source = CountingInputStream(size = 5)

        val result = ScreenshotBoundedReader.read(source, maxBytes = 5L)

        assertEquals(
            listOf(1, 2, 3, 4, 5),
            (result as ScreenshotBoundedReader.Result.Complete).bytes.map { it.toInt() },
        )
        assertEquals(5, source.bytesRead)
    }

    private class CountingInputStream(private val size: Int) : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int = if (bytesRead >= size) -1 else ++bytesRead

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= size) return -1
            val count = minOf(length, size - bytesRead)
            repeat(count) { index -> buffer[offset + index] = (bytesRead + index + 1).toByte() }
            bytesRead += count
            return count
        }
    }
}
