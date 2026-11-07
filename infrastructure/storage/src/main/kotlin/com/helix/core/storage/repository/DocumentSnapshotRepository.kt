package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.DocumentSnapshotDao
import com.helix.core.storage.entity.DocumentSnapshotEntity
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Immutable, session-owned source bytes; no process-local map is an authority. */
class DocumentSnapshotRepository(
    private val dao: DocumentSnapshotDao,
    private val content: FileContentStore,
    private val transaction: (() -> Unit) -> Unit,
) {
    class Unavailable(
        val code: String,
    ) : java.io.IOException(code)

    fun create(
        session: String,
        path: String,
        open: (String) -> InputStream,
        checkActive: () -> Unit,
    ): DocumentSnapshotEntity =
        content.withPublication {
            require(session.isNotBlank() && path.startsWith("scope:"))
            val format = path.substringAfterLast('.', "").lowercase()
            if (format !in setOf("pdf", "docx")) throw Unavailable("UNSUPPORTED_FORMAT")
            val ref =
                open(path).use { input ->
                    try {
                        content.writeBinary(input, MAX_BYTES, checkActive)
                    } catch (
                        _: IllegalArgumentException,
                    ) {
                        throw Unavailable("RESOURCE_LIMIT")
                    }
                }
            val file = content.verifiedBinary(ref, MAX_BYTES, checkActive)
            val magic = ByteArray(5)
            val count = file.inputStream().use { it.read(magic) }
            val valid =
                if (format == "pdf") {
                    count == 5 && magic.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
                } else {
                    count >= 4 && magic[0] == 80.toByte() && magic[1] == 75.toByte() && magic[2] == 3.toByte() &&
                        magic[3] == 4.toByte()
                }
            if (!valid) throw Unavailable("UNSUPPORTED_FORMAT")
            checkActive()
            val entity =
                DocumentSnapshotEntity(UUID.randomUUID().toString(), session, path, format, ref.toStorageString())
            transaction { dao.insert(entity) }
            entity
        }

    fun resolve(
        session: String,
        reference: String,
        open: (String) -> InputStream,
        checkActive: () -> Unit,
    ): DocumentSnapshotEntity {
        checkActive()
        val entity = dao.find(session, reference) ?: throw Unavailable("REF_EXPIRED")
        // Revalidate the current source backend/grant; never use cached content as a bearer grant.
        open(entity.sourcePath).use { }
        file(session, reference, checkActive)
        return entity
    }

    fun file(
        session: String,
        reference: String,
        checkActive: () -> Unit,
    ): File {
        val entity = dao.find(session, reference) ?: throw Unavailable("REF_EXPIRED")
        return try {
            content.verifiedBinary(ContentRef.parse(entity.contentRef), MAX_BYTES, checkActive)
        } catch (
            _: IllegalArgumentException,
        ) {
            throw Unavailable("REF_EXPIRED")
        }
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
