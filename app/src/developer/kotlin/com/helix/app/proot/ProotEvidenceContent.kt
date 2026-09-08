package com.helix.app.proot

import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.ZipJobExtractor
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files

/** Reads complete artifact bytes from an already persisted archive, without contacting the Runtime. */
internal object ProotEvidenceContent {
    const val MAX_BYTES = 1024 * 1024

    fun read(
        archive: File,
        scratch: File,
        path: String,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): ByteArray {
        checkCancelled(cancelled)
        check(scratch.mkdirs() || scratch.isDirectory)
        val directory = Files.createTempDirectory(scratch.toPath(), "proot-evidence-").toFile()
        try {
            val extracted = ZipJobExtractor.extract(archive, directory)
            checkCancelled(cancelled)
            val entry = requireNotNull(extracted.manifest.entries.singleOrNull { it.path == path })
            require(entry.size in 0..MAX_BYTES.toLong()) { "artifact exceeds evidence limit" }
            val bytes = ByteArray(entry.size.toInt())
            File(directory, entry.path).inputStream().use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    checkCancelled(cancelled)
                    val count = input.read(bytes, offset, minOf(DEFAULT_BUFFER_SIZE, bytes.size - offset))
                    require(count > 0) { "artifact is truncated" }
                    offset += count
                }
                require(input.read() == -1) { "artifact exceeds manifest size" }
            }
            checkCancelled(cancelled)
            require(FileContentStore.sha256Hex(bytes) == entry.sha256) { "artifact content changed" }
            return bytes
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedIOException("artifact evidence read interrupted")
    }
}
