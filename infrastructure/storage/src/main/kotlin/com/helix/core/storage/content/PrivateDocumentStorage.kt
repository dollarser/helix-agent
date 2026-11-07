package com.helix.core.storage.content

import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.resolveFileScopePath
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Fixed private placement for Agent history/provenance. Never an alternate user-library mode. */
internal class PrivateDocumentStorage(
    private val root: Path,
) : DocumentStorage {
    private val lock = locks.computeIfAbsent(root.toAbsolutePath().normalize()) { Any() }

    init {
        Files.createDirectories(root)
    }

    private fun path(reference: String): Path =
        resolveFileScopePath(FileScopePath("private-documents", reference), ScopeRootResolver { root })

    @Synchronized
    override fun references(directory: String): List<String> {
        val folder = path(directory)
        if (!Files.exists(folder, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(folder, NOFOLLOW_LINKS))
        return Files.newDirectoryStream(folder).use { entries ->
            entries
                .filter { !it.fileName.toString().startsWith(".helix-tmp-") }
                .map {
                    require(Files.isRegularFile(it, NOFOLLOW_LINKS)) { "PRIVATE_DOCUMENT_TYPE" }
                    "$directory/${it.fileName}"
                }.also { require(it.size <= 4096) }
        }
    }

    override fun list(directory: String): List<DocumentStorage.File> = references(directory).map { read(it, MAX_BYTES) }

    @Synchronized
    override fun read(
        reference: String,
        maxBytes: Int,
    ): DocumentStorage.File {
        require(maxBytes in 1..MAX_BYTES)
        val file = path(reference)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= maxBytes)
        val bytes = Files.readAllBytes(file)
        require(bytes.size <= maxBytes)
        return DocumentStorage.File(reference, file.fileName.toString(), bytes, DocumentStorage.hash(bytes))
    }

    @Synchronized
    override fun save(
        directory: String,
        name: String,
        bytes: ByteArray,
        previous: DocumentStorage.File?,
        mimeType: String,
    ): DocumentStorage.File =
        synchronized(lock) {
            require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", ".."))
            require(bytes.size <= MAX_BYTES)
            val reference = "$directory/$name"
            val file = path(reference)
            Files.createDirectories(file.parent)
            check(
                (previous == null && !Files.exists(file, NOFOLLOW_LINKS)) ||
                    (
                        previous != null && previous.reference == reference && read(
                            reference,
                            MAX_BYTES,
                        ).hash == previous.hash
                    ),
            ) { "DOCUMENT_CONFLICT_RELOAD" }
            AtomicFileWriter.writeAtomic(file, bytes, previous?.hash)
            read(reference, MAX_BYTES)
        }

    @Synchronized
    override fun delete(previous: DocumentStorage.File) =
        synchronized(lock) {
            check(read(previous.reference, MAX_BYTES).hash == previous.hash) { "DOCUMENT_CONFLICT_RELOAD" }
            Files.delete(path(previous.reference))
        }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<Path, Any>()
        private const val MAX_BYTES = 1_048_576
    }
}
