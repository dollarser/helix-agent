package com.helix.runtime.cli.client

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a single upload; cleanup never replays or reports a failed upload as successful. */
internal class CliRequestPipe(
    payload: ByteArray,
    jobId: String,
) : AutoCloseable {
    private val failed = AtomicBoolean(false)
    private val pipe =
        run {
            require(payload.size <= CliModelRequestCodec.MAX_BYTES)
            ParcelFileDescriptor.createPipe()
        }
    val readEnd: ParcelFileDescriptor get() = pipe[0]
    val hasFailed: Boolean get() = failed.get()
    private val writer =
        Thread(
            {
                try {
                    CliPfdChannel.write(pipe[1], payload, CliModelRequestCodec.MAX_BYTES)
                } catch (_: IOException) {
                    failed.set(true)
                }
            },
            "cli-request-$jobId",
        ).also { it.start() }

    override fun close() {
        closeEnd(pipe[0])
        try {
            writer.join(1_000)
        } catch (_: InterruptedException) {
            failed.set(true)
            Thread.currentThread().interrupt()
        }
        if (writer.isAlive) failed.set(true)
        closeEnd(pipe[1])
    }

    private fun closeEnd(end: ParcelFileDescriptor) {
        try {
            end.close()
        } catch (_: IOException) {
            failed.set(true)
        }
    }
}
