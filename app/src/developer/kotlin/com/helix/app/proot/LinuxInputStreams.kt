package com.helix.app.proot

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.JobManifestEntry
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest

/** Transfer bounds are independent of stdout/result.txt preview bounds. No whole-file allocation. */
internal class LinuxInputStreams(
    private val store: WorkspaceArtifactStore,
    private val cancelled: () -> Boolean,
) {
    private var total = 0L

    fun read(
        reference: String,
        name: String,
        target: File,
    ): JobManifestEntry {
        checkActive()
        val path = FileScopePath.fromModelReference(reference)
        val info = store.stat(path)
        if (!info.isRegularFile || info.sizeBytes > JobArchiveLimits.MAX_SINGLE_FILE_BYTES) {
            throw JobArchiveException("Input is missing, not a file or exceeds the transfer bound")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes =
            store.openRead(path).use { input ->
                target.outputStream().use { output -> copy(input, output, digest) }
            }
        if (bytes == 0L) throw JobArchiveException("Empty input file")
        return JobManifestEntry(name, digest.digest().joinToString("") { "%02x".format(it) }, bytes)
    }

    private fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        digest: MessageDigest,
    ): Long {
        val buffer = ByteArray(65536)
        var bytes = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0 || bytes + count > JobArchiveLimits.MAX_SINGLE_FILE_BYTES ||
                total + count > JobArchiveLimits.MAX_TOTAL_BYTES
            ) {
                throw JobArchiveException("Input exceeds transfer bounds or made no progress")
            }
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            bytes += count
            total += count
        }
        return bytes
    }

    fun checkActive() {
        if (cancelled() ||
            Thread.currentThread().isInterrupted
        ) {
            throw InterruptedIOException("Input snapshot cancelled")
        }
    }
}
