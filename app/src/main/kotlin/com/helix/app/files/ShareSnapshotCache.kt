package com.helix.app.files

import com.helix.core.workspace.ContentProbe
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** Private transport copies only. Never removes a source or a young, already handed-off snapshot. */
internal class ShareSnapshotCache(
    private val root: File,
) {
    data class Source(
        val name: String,
        val open: () -> InputStream,
    )

    data class Snapshot(
        val directory: File,
        val files: List<File>,
    )

    @Suppress("TooGenericExceptionCaught") // Clean our partial transport copy, then rethrow the original failure.
    fun stage(
        sources: List<Source>,
        maxBytes: Long,
        cancelled: () -> Boolean = { false },
    ): Snapshot =
        synchronized(LOCK) {
            require(sources.size in 1..8 && maxBytes in 1..POOL_BYTES)
            check(root.mkdirs() || root.isDirectory)
            check(!Files.isSymbolicLink(root.toPath()))
            expire()
            val used =
                Files.walk(root.toPath()).use { paths ->
                    paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
                }
            if (used > POOL_BYTES) throw IOException("Sharing cache limit exceeded")
            val directory = File(root, "handoff-${UUID.randomUUID()}")
            check(directory.mkdir())
            var remaining = minOf(maxBytes, POOL_BYTES - used)
            try {
                val files =
                    sources.mapIndexed { index, source ->
                        require(
                            source.name.isNotBlank() && '/' !in source.name && '\\' !in source.name &&
                                source.name !in setOf(".", ".."),
                        )
                        val extension =
                            source.name
                                .substringAfterLast('.', "bin")
                                .takeIf { it.matches(Regex("[A-Za-z0-9]{1,16}")) } ?: "bin"
                        val file = File(directory, "$index.$extension")
                        remaining -= copy(source, file, remaining, cancelled)
                        file
                    }
                if (cancelled()) throw ShareCancelled()
                Snapshot(directory, files)
            } catch (failure: Exception) {
                remove(directory.toPath())
                throw failure
            }
        }

    private fun copy(
        source: Source,
        file: File,
        remaining: Long,
        cancelled: () -> Boolean,
    ): Long {
        var total = 0L
        source.open().use { input ->
            file.outputStream().use { output ->
                total = copyBytes(input, output, remaining, cancelled)
            }
        }
        return total
    }

    private fun copyBytes(
        input: InputStream,
        output: java.io.OutputStream,
        remaining: Long,
        cancelled: () -> Boolean,
    ): Long {
        var total = 0L
        val bytes = ByteArray(8192)
        while (true) {
            if (cancelled()) throw ShareCancelled()
            val count = input.read(bytes)
            if (count < 0) break
            total += count
            if (total > remaining) throw IOException("Sharing cache or file limit exceeded")
            output.write(bytes, 0, count)
        }
        return total
    }

    private fun expire() {
        val expired = System.currentTimeMillis() - RETAIN_MS
        root
            .listFiles()
            .orEmpty()
            .filter {
                it.name.matches(OWNED_NAME) && it.lastModified() < expired
            }.forEach { remove(it.toPath()) }
    }

    private fun remove(path: Path) {
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    companion object {
        private val LOCK = Any()
        private val OWNED_NAME = Regex("handoff-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val POOL_BYTES = 512L * 1024 * 1024
        private const val RETAIN_MS = 24L * 60 * 60 * 1000

        fun mime(file: File): String {
            val probe = ContentProbe.probe(file.toPath())
            return if (probe.isText && probe.mimeType == "application/octet-stream") "text/plain" else probe.mimeType
        }
    }
}

internal class ShareCancelled : IOException("Sharing cancelled")
