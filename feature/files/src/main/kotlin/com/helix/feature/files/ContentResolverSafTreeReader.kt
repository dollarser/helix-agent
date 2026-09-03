package com.helix.feature.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.helix.core.workspace.ScopeNotAvailable
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Production [SafTreeReader] (HXA-057: SAF 树只读浏览后端). BROWSes a SAF document tree READ-ONLY
 * through `ContentResolver`/`DocumentsContract`. The file manager and the model address a document
 * by a path-like [relativePath] (folder/file names joined by `/`); this adapter maps it to the
 * tree's OPAQUE SAF document ids internally (a walk from the tree root, one name match per
 * segment). A document id and a `content://` URI never reach the model (doc 10).
 *
 * Fail closed at every step: an unknown scope (no grant) raises [ScopeNotAvailable]; a segment that
 * matches no child — or matches several (an ambiguous name) — raises [FileNotFoundException]; a
 * document larger than a copy cap raises [SafTreeReadLimitExceeded]. The grant is re-verified by
 * the caller ([SafTreeScopeService.resolve]) before every call; this adapter is the governed read
 * path, never a direct external-file accessor.
 */
@Suppress("TooManyFunctions") // a DocumentsContract adapter: many small, focused private helpers (walk/row/stream)
class ContentResolverSafTreeReader(
    private val resolver: ContentResolver,
    private val grants: SafTreeGrantRegistry,
) : SafTreeReader {
    override fun list(
        scopeId: String,
        relativePath: String,
    ): List<SafTreeFileEntry> {
        val (authority, rootDocId) = treeIdentity(scopeId)
        val dirDocId = walkToDirectory(authority, rootDocId, relativePath)
        return children(authority, dirDocId).map { c ->
            SafTreeFileEntry(c.displayName, c.isDirectory, c.sizeBytes, c.lastModifiedEpochMillis)
        }
    }

    override fun read(
        scopeId: String,
        relativePath: String,
        offset: Long,
        maxBytes: Long,
    ): ByteArray {
        require(offset >= 0) { "offset must be >= 0 (got $offset)" }
        require(maxBytes in 1..MAX_READ_BYTES) { "maxBytes must be 1..$MAX_READ_BYTES (got $maxBytes)" }
        val (authority, rootDocId) = treeIdentity(scopeId)
        val docId = walkToDocument(authority, rootDocId, relativePath)
        resolver.openInputStream(DocumentsContract.buildDocumentUri(authority, docId))?.use { input ->
            skipExact(input, offset)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK)
            var total = 0L
            while (total < maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, (maxBytes - total).toInt()))
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        } ?: throw FileNotFoundException("SAF document not readable")
    }

    override fun stat(
        scopeId: String,
        relativePath: String,
    ): SafTreeStat {
        val (authority, rootDocId) = treeIdentity(scopeId)
        return runCatching {
            val (docId, isDirectory) = walkToDocumentMetadata(authority, rootDocId, relativePath)
            SafTreeStat(true, isDirectory, docId.sizeBytes, docId.lastModifiedEpochMillis)
        }.recoverCatching { e ->
            if (e is FileNotFoundException) SafTreeStat(false, false, -1L, -1L) else throw e
        }.getOrThrow()
    }

    override fun copyToAppPrivate(
        scopeId: String,
        relativePath: String,
        target: Path,
        maxBytes: Long,
    ): Long {
        require(maxBytes > 0) { "maxBytes must be > 0 (got $maxBytes)" }
        val (authority, rootDocId) = treeIdentity(scopeId)
        val docId = walkToDocument(authority, rootDocId, relativePath)
        target.parent?.let { Files.createDirectories(it) }
        return try {
            Files.newOutputStream(target).use { out ->
                val uri = DocumentsContract.buildDocumentUri(authority, docId)
                resolver.openInputStream(uri)?.use { input -> boundedCopy(input, out, maxBytes) }
                    ?: throw FileNotFoundException("SAF document not readable")
            }
        } catch (e: SafTreeReadLimitExceeded) {
            // Best effort: a failing delete leaves an unreferenced orphan in the app-private cache.
            runCatching { Files.deleteIfExists(target) }
            throw e
        }
    }

    /**
     * Drains [input] into [out] in bounded chunks; @throws SafTreeReadLimitExceeded when the
     * stream exceeds [maxBytes] (fail closed — the caller deletes the partial [target]).
     */
    private fun boundedCopy(
        input: InputStream,
        out: java.io.OutputStream,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw SafTreeReadLimitExceeded("SAF document exceeds the copy cap")
            out.write(buffer, 0, n)
        }
        return total
    }

    // ── path-like relativePath → opaque SAF document id (the walk) ──────────────────────

    /**
     * The (authority, tree-root document id) of [scopeId]'s grant. @throws ScopeNotAvailable when
     * the grant is unknown or its URI is malformed (fail closed).
     */
    private fun treeIdentity(scopeId: String): Pair<String, String> {
        val grant = grants.find(scopeId) ?: throw notAvailable(scopeId)
        val authority = treeUriAuthority(grant.treeUri)
        val rootDocId = treeUriRootDocumentId(grant.treeUri)
        if (authority == null || rootDocId == null) throw notAvailable(scopeId)
        return authority to rootDocId
    }

    /** Walks to the DIRECTORY at [relativePath] ("" = tree root). */
    private fun walkToDirectory(
        authority: String,
        rootDocId: String,
        relativePath: String,
    ): String {
        var docId = rootDocId
        for (segment in segments(relativePath)) {
            docId = findChildMetadata(authority, docId, segment)?.documentId ?: throw notFound(relativePath)
        }
        return docId
    }

    /** Walks to the exact DOCUMENT (file) at [relativePath]; refuses a directory. */
    private fun walkToDocument(
        authority: String,
        rootDocId: String,
        relativePath: String,
    ): String {
        require(relativePath.isNotEmpty()) { "relativePath must name a file" }
        val (dir, name) = splitLastSegment(relativePath)
        val parent = if (dir.isEmpty()) rootDocId else walkToDirectory(authority, rootDocId, dir)
        val child = findChildMetadata(authority, parent, name) ?: throw notFound(relativePath)
        // A directory is not readable as a file (fail closed).
        if (child.isDirectory) throw notFound(relativePath)
        return child.documentId
    }

    /** Walks to the DOCUMENT at [relativePath] (file or directory) with its metadata. */
    private fun walkToDocumentMetadata(
        authority: String,
        rootDocId: String,
        relativePath: String,
    ): Pair<SafTreeChild, Boolean> {
        val target =
            if (relativePath.isEmpty()) {
                documentMetadata(authority, rootDocId) ?: throw notFound(relativePath)
            } else {
                val (dir, name) = splitLastSegment(relativePath)
                val parent = if (dir.isEmpty()) rootDocId else walkToDirectory(authority, rootDocId, dir)
                findChildMetadata(authority, parent, name) ?: throw notFound(relativePath)
            }
        return target to target.isDirectory
    }

    private fun findChildMetadata(
        authority: String,
        parentDocId: String,
        name: String,
    ): SafTreeChild? {
        val matches = children(authority, parentDocId).filter { it.displayName == name }
        return when (matches.size) {
            1 -> matches.single()
            else -> throw notFound(name)
        }
    }

    /** Lists the immediate children of [parentDocId]. */
    private fun children(
        authority: String,
        parentDocId: String,
    ): List<SafTreeChild> {
        val cursor = queryChildDocuments(authority, parentDocId)
        return cursor?.use { collectChildren(it) } ?: emptyList()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failing provider read fails closed per call
    private fun queryChildDocuments(
        authority: String,
        parentDocId: String,
    ): Cursor? {
        val uri = DocumentsContract.buildChildDocumentsUri(authority, parentDocId)
        return try {
            resolver.query(uri, DOC_COLUMNS, null, null, null)
        } catch (e: Exception) {
            throw notFound(parentDocId)
        }
    }

    private fun collectChildren(cursor: Cursor): List<SafTreeChild> {
        val out = mutableListOf<SafTreeChild>()
        while (cursor.moveToNext()) {
            val child = rowOrNull(cursor)
            if (child != null) out += child
        }
        return out
    }

    /** Reads one cursor row (fixed column order) into a [SafTreeChild], or null when id/name is missing. */
    private fun rowOrNull(cursor: Cursor): SafTreeChild? {
        val id = cursor.getString(0)
        val name = cursor.getString(1)
        if (id == null || name == null) return null
        val mime = cursor.getString(2)
        return SafTreeChild(
            documentId = id,
            displayName = name,
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = cursor.longOrNull(3) ?: -1L,
            lastModifiedEpochMillis = cursor.longOrNull(4) ?: -1L,
        )
    }

    /** A single document's metadata, or null when it cannot be read. */
    private fun documentMetadata(
        authority: String,
        docId: String,
    ): SafTreeChild? {
        val uri = DocumentsContract.buildDocumentUri(authority, docId)
        return runCatching {
            resolver.query(uri, DOC_COLUMNS, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                SafTreeChild(
                    documentId = docId,
                    displayName = cursor.getString(1) ?: docId,
                    isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = cursor.longOrNull(3) ?: -1L,
                    lastModifiedEpochMillis = cursor.longOrNull(4) ?: -1L,
                )
            }
        }.getOrNull()
    }

    private fun segments(relativePath: String): List<String> = relativePath.split('/').filter { it.isNotEmpty() }

    /**
     * Splits [relativePath] into (parent-directory path, final segment). A path WITHOUT a `/`
     * yields an EMPTY parent — `String.substringBeforeLast` would return the whole string
     * instead (Kotlin no-delimiter semantics), which would walk INTO the file's own id.
     */
    private fun splitLastSegment(relativePath: String): Pair<String, String> {
        val slash = relativePath.lastIndexOf('/')
        return if (slash <
            0
        ) {
            "" to relativePath
        } else {
            relativePath.substring(0, slash) to relativePath.substring(slash + 1)
        }
    }

    private fun notFound(what: String): FileNotFoundException = FileNotFoundException("SAF path not found: $what")

    private fun notAvailable(scopeId: String): ScopeNotAvailable =
        ScopeNotAvailable("SAF tree scope not available: $scopeId")

    private fun Cursor.longOrNull(index: Int): Long? {
        if (isNull(index)) return null
        return getLong(index)
    }

    /** A child document's resolved metadata (document id + display name + bounded facts). */
    private class SafTreeChild(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedEpochMillis: Long,
    )

    /** Drains [input] exactly [n] bytes (no short-skip, no `skipNBytes` — API 29 safe). */
    private fun skipExact(
        input: InputStream,
        n: Long,
    ) {
        val buffer = ByteArray(CHUNK)
        var remaining = n
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw FileNotFoundException("SAF document truncated below offset")
            remaining -= read
        }
    }

    private companion object {
        const val CHUNK: Int = 64 * 1024
        const val MAX_READ_BYTES: Long = 8L * 1024 * 1024

        // Fixed query projection order — the reader reads rows by position, so this order is a
        // contract with [rowOrNull] (document id, display name, mime, size, last modified).
        val DOC_COLUMNS: Array<String> =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
