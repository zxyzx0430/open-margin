package com.bookkeeping.app.capture.screenshot

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most maxBytes + 1 bytes so a missing size hint can still reject oversize input. */
internal object ScreenshotBoundedReader {
    sealed interface Result {
        data class Complete(val bytes: ByteArray) : Result
        object Oversized : Result
    }

    fun read(stream: InputStream?, maxBytes: Long): Result {
        require(maxBytes in 0L until Long.MAX_VALUE)
        if (stream == null) return Result.Complete(ByteArray(0))
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val remainingThroughSentinel = maxBytes + 1L - total
                if (remainingThroughSentinel <= 0L) return Result.Oversized
                val read = input.read(
                    buffer,
                    0,
                    minOf(buffer.size.toLong(), remainingThroughSentinel).toInt(),
                )
                if (read < 0) break
                if (read == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    total++
                    if (total > maxBytes) return Result.Oversized
                    output.write(byte)
                    continue
                }
                total += read
                if (total > maxBytes) return Result.Oversized
                output.write(buffer, 0, read)
            }
            return Result.Complete(output.toByteArray())
        }
    }
}
