package com.helix.core.storage.content

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Single-owner, non-replayable staging attempt. Its lease ends only after IO has exited. */
internal class BinaryContentStaging(
    private val content: FileContentStore,
) : Closeable {
    class LimitExceeded : IllegalArgumentException("Binary content limit")

    val file: File =
        content.withPublication {
            val directory = File(content.root, "content")
            check(directory.mkdirs() || directory.isDirectory)
            val stage = File(directory, "binary.tmp-${UUID.randomUUID()}")
            check(stage.createNewFile())
            ContentLifecycle.lease(stage)
            stage
        }
    private var reference: ContentRef? = null
    private var copied = false
    private var closed = false

    fun copyFrom(
        source: InputStream,
        maxBytes: Long,
        checkActive: () -> Unit,
    ) {
        check(!copied && !closed)
        copied = true
        require(maxBytes > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkActive()
                val count = source.read(buffer)
                if (count < 0) break
                require(count > 0) { "Binary source made no progress" }
                if (count > maxBytes - size) throw LimitExceeded()
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                size += count
            }
        }
        checkActive()
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val ref = ContentRef(ContentRef.expectedPath(hash), size, hash)
        content.verifyBinaryFile(file, ref, checkActive)
        reference = ref
    }

    /** Only canonical publication/reuse and its referring transaction share the root lock. */
    fun <T> publish(
        checkActive: () -> Unit,
        commit: (ContentRef) -> T,
    ): T =
        content.withPublication {
            check(!closed)
            checkActive() // Cancellation while waiting for the monitor must prevent publication.
            val ref = checkNotNull(reference)
            val destination = File(content.root, ref.relativePath)
            if (destination.exists()) {
                // Reuse retains a local, lock-held integrity check. Metadata is not a hash proof,
                // and corrupt existing bytes must fail closed rather than be overwritten.
                content.verifyBinaryFile(destination, ref, checkActive)
                // Cleanup failure must be known before the authoritative reference commits.
                // After a successful commit close() then has no fallible file deletion to do.
                removeStaging()
            } else {
                check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                Files.move(file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            checkActive()
            commit(ref)
        }

    override fun close() {
        if (closed) return
        content.withPublication {
            try {
                removeStaging()
            } finally {
                closed = true
                ContentLifecycle.release(file)
            }
        }
    }

    private fun removeStaging() {
        check(!file.exists() || file.delete()) { "Cannot remove binary staging file" }
    }
}
