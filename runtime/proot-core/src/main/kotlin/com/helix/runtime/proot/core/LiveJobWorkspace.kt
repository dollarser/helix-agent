package com.helix.runtime.proot.core

import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Original directory identity and no-link relative paths for the local in-place backend. */
object LiveJobWorkspace {
    const val OUTPUT_MANIFEST = "__helix_live_outputs.json"

    fun witness(directory: File): String {
        require(directory.path == directory.canonicalPath && directory.isDirectory) { "Workspace unavailable" }
        val attributes =
            Files.readAttributes(
                directory.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        require(attributes.isDirectory && !attributes.isSymbolicLink)
        // Android may expose ctime as creationTime; adding files then changes it.
        // Directory identity is the filesystem key, not a mutable content timestamp.
        return requireNotNull(attributes.fileKey()).toString()
    }

    fun verify(
        directory: File,
        expected: String,
    ) {
        require(witness(directory) == expected) { "Workspace identity changed" }
    }

    fun resolve(
        directory: File,
        relative: String,
    ): File {
        JobPath.validate(relative)
        var current = directory
        relative.split('/').forEach { segment ->
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) { "Workspace path contains a symbolic link" }
        }
        require(current.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
        return current
    }

    fun observe(
        directory: File,
        relative: String,
        deadlineNanos: Long,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): JobManifestEntry {
        val file = resolve(directory, relative)
        require(file.isFile) { "Declared artifact unavailable" }
        val size = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (isCancelled() || System.nanoTime() >= deadlineNanos) {
                    throw InterruptedIOException("Artifact observation cancelled or expired")
                }
                val read = input.read(buffer)
                if (read == -1) break
                bytes += read
                require(bytes <= size) { "Artifact grew during observation" }
                digest.update(buffer, 0, read)
            }
        }
        require(bytes == size && file.length() == size) { "Artifact changed during observation" }
        return JobManifestEntry(relative, digest.digest().joinToString("") { "%02x".format(it) }, size)
    }

    fun observeAvailable(
        directory: File,
        relative: String,
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
    ): JobManifestEntry? =
        try {
            observe(directory, relative, deadlineNanos, isCancelled)
        } catch (cancelled: InterruptedIOException) {
            if (isCancelled()) throw cancelled
            null // Expired observation is an unavailable artifact, not lost command output.
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
}
