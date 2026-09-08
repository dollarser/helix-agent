package com.helix.runtime.proot.app

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keep the complete Runtime copy before delivering to the caller's transient descriptor. */
internal object ProotOutputDelivery {
    data class Delivery(
        val manifestDocument: String,
        val delivered: Boolean,
    )

    fun persistAndDeliver(
        target: File,
        output: ParcelFileDescriptor,
        build: (File) -> String,
    ): Delivery {
        val temporary = File(target.parentFile, "output.zip.pending")
        try {
            val manifest = build(temporary)
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            val delivered = deliver(target, output)
            if (!delivered) {
                File(target.parentFile, "output-delivery-failed.txt")
                    .writeText("initialTransfer=failed; retainedArchive=true")
            }
            return Delivery(manifest, delivered)
        } finally {
            temporary.delete()
        }
    }

    private fun deliver(
        target: File,
        output: ParcelFileDescriptor,
    ): Boolean =
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { destination ->
                target.inputStream().use { it.copyTo(destination) }
            }
            true
        } catch (_: IOException) {
            // The durable archive remains available to fetch; this transfer did not succeed.
            false
        }
}
