package com.bookkeeping.app.billimport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal class BillImportFileTooLargeException : IOException()

/** Reads at most [maxBytes] plus one byte, so an oversized stream is detected without draining it. */
internal object BillImportFileReader {
    fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream(minOf(buffer.size, maxBytes + 1))
        while (true) {
            val remaining = maxBytes + 1 - output.size()
            if (remaining <= 0) throw BillImportFileTooLargeException()
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) return output.toByteArray()
            if (read == 0) {
                val byte = input.read()
                if (byte < 0) return output.toByteArray()
                output.write(byte)
            } else {
                output.write(buffer, 0, read)
            }
            if (output.size() > maxBytes) throw BillImportFileTooLargeException()
        }
    }
}
