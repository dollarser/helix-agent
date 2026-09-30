package com.helix.runtime.quickjs

import java.io.File
import java.io.IOException

/** Bounds allocation before reading, then checks EOF again to detect a concurrently growing file. */
internal object JsBoundedOutput {
    private fun validateLength(
        file: File,
        expectedBytes: Long,
        maxBytes: Long,
    ) {
        if (maxBytes !in 0..Int.MAX_VALUE.toLong() || expectedBytes !in 0..maxBytes || file.length() != expectedBytes) {
            throw IOException("Output length is invalid or exceeds its trusted limit")
        }
    }

    fun read(
        file: File,
        expectedBytes: Long,
        maxBytes: Long,
    ): ByteArray {
        validateLength(file, expectedBytes, maxBytes)
        return file.inputStream().use { input ->
            val bytes = ByteArray(expectedBytes.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Output ended before its declared length")
                offset += count
            }
            if (input.read() != -1) throw IOException("Output grew beyond its declared length")
            bytes
        }
    }
}
