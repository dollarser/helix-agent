package com.helix.runtime.cli.client

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream

object CliPfdChannel {
    fun read(readEnd: ParcelFileDescriptor, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= limit) { "PFD payload exceeds limit" }
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }

    fun write(writeEnd: ParcelFileDescriptor, bytes: ByteArray, limit: Int) {
        require(bytes.size <= limit)
        ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(bytes) }
    }
}
