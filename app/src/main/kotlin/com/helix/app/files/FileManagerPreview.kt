package com.helix.app.files

import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.resolveFileScopePath
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeScopeAccess
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Bounded preview, metadata and transient sharing; no mutation or transfer ownership. */
@Suppress("TooManyFunctions") // One bounded read responsibility with platform-specific adapters.
internal class FileManagerPreview(
    private val store: WorkspaceArtifactStore,
    private val roots: ScopeRootResolver,
    private val saf: SafTreeScopeAccess?,
) {
    /** True when [scopeId] names a SAF tree scope (`saf-<12hex>`; the only model-safe form, doc 10). */
    private fun isSaf(scopeId: String): Boolean = scopeId.startsWith(SafGrantStore.SCOPE_ID_PREFIX)

    /** The SAF access, fail-closed when the scope is SAF but the access is absent. */
    private fun requireSaf(scopeId: String): SafTreeScopeAccess =
        saf ?: throw ScopeNotAvailable("SAF tree scope not available: $scopeId")

    /** Re-verifies a SAF grant in real time (fail closed) before any browse/read; returns the scope. */
    private fun verifySaf(
        scopeId: String,
        mode: SafAccessMode,
    ): SafTreeScopeAccess {
        requireSaf(scopeId).service.resolve(scopeId, mode)
        return requireSaf(scopeId)
    }

    // --- Preview (预览: 文本 + 图片) and info (MIME/大小/哈希) ---

    /** The leading text of a text file, or null when it is not a text file (image/binary/missing). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed null
    fun previewText(
        scopeId: String,
        relativePath: String,
        maxBytes: Long = DEFAULT_PREVIEW_BYTES,
    ): String? =
        if (isSaf(scopeId)) {
            safPreviewText(scopeId, relativePath, maxBytes)
        } else {
            val fsp = FileScopePath(scopeId, relativePath)
            val probe = store.probe(fsp)
            if (!probe.isText || probe.sizeBytes < 0) {
                null
            } else {
                runCatching { store.readWindow(fsp, 0, maxBytes).text }.getOrNull()
            }
        }

    /** SAF tree text preview (HXA-057): re-verified, bounded read, probed for text, then decoded. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed null
    private fun safPreviewText(
        scopeId: String,
        relativePath: String,
        maxBytes: Long,
    ): String? {
        val access = verifySaf(scopeId, SafAccessMode.READ)
        return try {
            val cap = minOf(maxBytes, SAF_READ_CAP)
            val bytes = access.reader.read(scopeId, relativePath, 0, cap)
            if (!ContentProbe.probeBytes(bytes, bytes.size.toLong()).isText) return null
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The raw bytes of an image for the preview, or an empty array when the file is not an image,
     * is missing, or exceeds [maxBytes] (a too-large image is not previewed — it is still
     * shareable / exportable). The Compose layer decodes the bytes into a bitmap.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed empty array
    fun previewImageBytes(
        scopeId: String,
        relativePath: String,
        maxBytes: Long = MAX_IMAGE_PREVIEW_BYTES,
    ): ByteArray =
        if (isSaf(scopeId)) {
            safPreviewImageBytes(scopeId, relativePath, maxBytes)
        } else {
            val fsp = FileScopePath(scopeId, relativePath)
            val probe = store.probe(fsp)
            if (!probe.mimeType.startsWith("image/") || probe.sizeBytes < 0 || probe.sizeBytes > maxBytes) {
                ByteArray(0)
            } else {
                runCatching { store.readAll(fsp) }.getOrDefault(ByteArray(0))
            }
        }

    /** SAF tree image preview (HXA-057): re-verified, bounded read, probed for an image mime. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed empty array
    private fun safPreviewImageBytes(
        scopeId: String,
        relativePath: String,
        maxBytes: Long,
    ): ByteArray {
        val access = verifySaf(scopeId, SafAccessMode.READ)
        return try {
            val cap = minOf(maxBytes, SAF_READ_CAP)
            val bytes = access.reader.read(scopeId, relativePath, 0, cap)
            if (!ContentProbe.probeBytes(bytes, bytes.size.toLong()).mimeType.startsWith("image/")) {
                return ByteArray(0)
            }
            bytes
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /**
     * The best-effort MIME of a file from a bounded prefix probe only (cheap — no full read, no
     * hash). Used to set the share intent's MIME type.
     */
    fun mimeTypeFor(
        scopeId: String,
        relativePath: String,
    ): String {
        if (isSaf(scopeId)) return safMimeTypeFor(scopeId, relativePath)
        return store.probe(FileScopePath(scopeId, relativePath)).mimeType
    }

    /** SAF tree best-effort MIME from a bounded prefix (HXA-057). A non-file/dir failure → octet. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed octet-stream
    private fun safMimeTypeFor(
        scopeId: String,
        relativePath: String,
    ): String {
        val access = verifySaf(scopeId, SafAccessMode.READ)
        return try {
            val bytes = access.reader.read(scopeId, relativePath, 0, DEFAULT_PREVIEW_BYTES)
            ContentProbe.probeBytes(bytes, bytes.size.toLong()).mimeType
        } catch (e: Exception) {
            "application/octet-stream"
        }
    }

    /** Bounded per-file metadata (HXA-046: MIME/大小/哈希信息). The hash is a real SHA-256, computed
     * on demand, omitted when the file exceeds [maxHashBytes]. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure omits the hash rather than throwing
    fun fileInfo(
        scopeId: String,
        relativePath: String,
        maxHashBytes: Long = MAX_HASH_BYTES,
    ): FileManagerService.FileMeta {
        if (isSaf(scopeId)) return safFileInfo(scopeId, relativePath, maxHashBytes)
        val fsp = FileScopePath(scopeId, relativePath)
        val s = store.stat(fsp)
        val probe = store.probe(fsp)
        var sha: String? = null
        var omitted = false
        if (s.exists && s.isRegularFile && probe.sizeBytes in 0..maxHashBytes) {
            try {
                sha = sha256Hex(store.readAll(fsp))
            } catch (e: Exception) {
                omitted = true
            }
        } else if (s.exists && s.isRegularFile) {
            omitted = true
        }
        return FileManagerService.FileMeta(s.sizeBytes, s.mtimeEpochMillis, probe.mimeType, probe.isText, sha, omitted)
    }

    /**
     * SAF tree per-file metadata (HXA-057): re-verified, [SafTreeReader.stat] for size/mtime, a
     * bounded prefix probe for mime/text, and a real SHA-256 when the file fits the hash cap AND
     * the SAF read window (a larger SAF file omits the hash, exactly as a too-large workspace file).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure omits the hash rather than throwing
    private fun safFileInfo(
        scopeId: String,
        relativePath: String,
        maxHashBytes: Long,
    ): FileManagerService.FileMeta {
        val access = verifySaf(scopeId, SafAccessMode.READ)
        val stat = access.reader.stat(scopeId, relativePath)
        if (!stat.exists || stat.isDirectory) {
            return if (!stat.exists) {
                FileManagerService.FileMeta(-1L, -1L, "application/octet-stream", false, null, false)
            } else {
                FileManagerService.FileMeta(stat.sizeBytes, stat.mtimeEpochMillis, "inode/directory", true, null, true)
            }
        }
        val prefix = access.reader.read(scopeId, relativePath, 0, DEFAULT_PREVIEW_BYTES)
        val probe =
            try {
                ContentProbe.probeBytes(prefix, stat.sizeBytes)
            } catch (e: Exception) {
                ContentProbe.probeBytes(ByteArray(0), stat.sizeBytes)
            }
        val hashCap = minOf(maxHashBytes, stat.sizeBytes)
        var sha: String? = null
        var omitted = false
        if (stat.sizeBytes in 1..hashCap && hashCap <= SAF_READ_CAP) {
            val all =
                try {
                    access.reader.read(scopeId, relativePath, 0, hashCap)
                } catch (e: Exception) {
                    null
                }
            if (all != null && all.size == hashCap.toInt()) {
                sha = sha256Hex(all)
            } else {
                omitted = true
            }
        } else {
            omitted = true
        }
        return FileManagerService.FileMeta(
            stat.sizeBytes,
            stat.mtimeEpochMillis,
            probe.mimeType,
            probe.isText,
            sha,
            omitted,
        )
    }

    /**
     * The real [File] behind [relativePath], for the share action only (see the class KDoc).
     * Containment- and symlink-checked via [resolveFileScopePath]; never meant to be displayed.
     * @throws FileNotFoundException when the target is not an existing regular file.
     */
    fun realFileFor(
        scopeId: String,
        relativePath: String,
    ): File {
        if (isSaf(scopeId)) return safRealFileFor(scopeId, relativePath)
        val fsp = FileScopePath(scopeId, relativePath)
        val real = resolveFileScopePath(fsp, roots)
        if (!java.nio.file.Files
                .isRegularFile(real)
        ) {
            throw FileNotFoundException("not a regular file: ${fsp.toModelReference()}")
        }
        return real.toFile()
    }

    /**
     * The app-private staged copy of a SAF tree document, for the share action only (HXA-057). A
     * SAF document has no `java.io.File`; it is chunk-copied into the app-private [shareDir]
     * (bounded by [SAF_SHARE_CAP]) and that file is handed to the FileProvider for a transient
     * `content://` share URI — the real document id / `content://` URI is never rendered (doc 10).
     * @throws FileNotFoundException when the document is missing or a directory.
     * @throws SafTreeReadLimitExceeded when it exceeds [SAF_SHARE_CAP] (fail closed, not truncated).
     */
    private fun safRealFileFor(
        scopeId: String,
        relativePath: String,
    ): File {
        val access = verifySaf(scopeId, SafAccessMode.READ)
        val staged = access.shareDir.resolve("share-${System.nanoTime()}.bin")
        // copyToAppPrivate deletes its own partial target on the limit-exceeded failure; a missing
        // document / revoked scope throws before any file is created, so no cleanup is needed here.
        access.reader.copyToAppPrivate(scopeId, relativePath, staged, SAF_SHARE_CAP)
        return staged.toFile()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_LIST_ENTRIES = 500
        const val DEFAULT_PREVIEW_BYTES = 64L * 1024
        const val MAX_IMAGE_PREVIEW_BYTES = 4L * 1024 * 1024
        const val MAX_HASH_BYTES = 64L * 1024 * 1024

        // The SAF read backend's single-read cap (HXA-057): SAF documents are streamed in bounded
        // windows, so previews/mime/hash read at most this many bytes (a larger SAF file is
        // previewed as a prefix and its hash omitted, exactly as a too-large workspace file).
        const val SAF_READ_CAP = 8L * 1024 * 1024

        // App-private staging cap for the SAF share action (HXA-057): a SAF document larger than
        // this is not share-staged (fail closed) — sharing an arbitrarily large external file to an
        // app-private cache would be unbounded.
        const val SAF_SHARE_CAP = 512L * 1024 * 1024
    }
}
