package com.helix.runtime.proot.app

import com.helix.runtime.proot.core.JobArchiveLimits
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Enforce the existing archive ceiling during transport, not only after filling disk. */
@Suppress("ThrowsCount") // Stop, stalled stream and size overflow are distinct transport failures.
internal fun copyJobInput(
    input: InputStream,
    output: OutputStream,
    stopped: () -> Boolean,
) {
    val buffer = ByteArray(65536)
    var copied = 0L
    while (true) {
        if (stopped()) throw IOException("Job input transfer stopped before launch")
        val count = input.read(buffer)
        if (count < 0) return
        if (count == 0) throw IOException("Job input made no progress")
        copied += count
        if (copied > JobArchiveLimits.MAX_TOTAL_BYTES) throw IOException("Job input exceeds archive limit")
        output.write(buffer, 0, count)
    }
}
