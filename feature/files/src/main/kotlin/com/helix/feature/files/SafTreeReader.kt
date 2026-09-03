package com.helix.feature.files

import java.io.FileNotFoundException
import java.nio.file.Path

/**
 * A browsable SAF tree directory entry (HXA-057). The entry carries only a display name and
 * bounded metadata — NO document id and NO `content://` URI reach the model (doc 10: the model and
 * the file manager see the path-like [SafTreeReader] address, not the provider's opaque id).
 */
data class SafTreeFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mtimeEpochMillis: Long,
)

/** Bounded metadata for a SAF tree document (HXA-057; the file manager's MIME/size/time info). */
data class SafTreeStat(
    val exists: Boolean,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mtimeEpochMillis: Long,
)

/**
 * The document is larger than the bounded cap a copy/read allows (HXA-057: the share action's hard
 * cap). A fail-closed terminal, never a truncated false success.
 */
class SafTreeReadLimitExceeded(
    message: String,
) : RuntimeException(message)

/**
 * The JVM-testable seam for browsing a SAF tree READ-ONLY (HXA-057, following the all-files
 * read-only precedent: browse / sort / preview / share available; write mutations hidden).
 *
 * Production: [ContentResolverSafTreeReader] over `ContentResolver`/`DocumentsContract`; tests
 * inject fakes. [relativePath] is the path-like address the file manager and the model use (folder
 * and file names joined by `/`; `""` = the tree root). The production impl maps it to the tree's
 * OPAQUE SAF document ids internally — a document id and a `content://` URI never reach the model
 * (doc 10). Every operation is fail-closed: a path that does not exist in the tree, or a document
 * that disappears mid-use, is reported (a missing file raises [FileNotFoundException], an over-cap
 * copy raises [SafTreeReadLimitExceeded]), never a false success.
 *
 * The caller (the file manager) re-verifies the grant through [SafTreeScopeService.resolve] before
 * every call; this seam is the governed read path, never a direct external-file accessor.
 */
interface SafTreeReader {
    /** The immediate children of the directory at [relativePath] ("" = tree root). */
    fun list(
        scopeId: String,
        relativePath: String,
    ): List<SafTreeFileEntry>

    /**
     * A bounded read of the file at [relativePath]: up to [maxBytes] bytes from [offset]. The caller
     * decides the text/image preview.
     * @throws FileNotFoundException when [relativePath] does not exist or is a directory.
     */
    fun read(
        scopeId: String,
        relativePath: String,
        offset: Long,
        maxBytes: Long,
    ): ByteArray

    /** Bounded metadata for [relativePath]; a missing document reports [SafTreeStat.exists]=false. */
    fun stat(
        scopeId: String,
        relativePath: String,
    ): SafTreeStat

    /**
     * Streams the file at [relativePath] into the app-private [target] (chunked, bounded by
     * [maxBytes]) for the file manager's share action (the real path is never shown; the share flow
     * mints a `content://` URI from this app-private file, doc 10). @return the bytes written.
     * @throws FileNotFoundException when [relativePath] does not exist or is a directory.
     * @throws SafTreeReadLimitExceeded when the document is larger than [maxBytes].
     */
    fun copyToAppPrivate(
        scopeId: String,
        relativePath: String,
        target: Path,
        maxBytes: Long,
    ): Long
}
