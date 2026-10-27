package com.helix.app.files

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CancellationException

/** One bounded immutable input snapshot. File names and MIME never grant access or select a parser. */
internal object ImportFileCapture {
    fun read(
        input: InputStream,
        limit: Long,
        cancelled: () -> Boolean = { false },
    ): ByteArray {
        require(limit in 1..32L * 1024 * 1024)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var emptyReads = 0
        while (true) {
            checkCancellation(cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads > 8) throw java.io.IOException("Import stream made no progress")
            } else {
                emptyReads = 0
                require(output.size().toLong() + count <= limit) { "CONNECTOR_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
        }
        checkCancellation(cancelled)
        return output.toByteArray()
    }

    fun checkCancellation(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("Import cancelled")
    }
}
