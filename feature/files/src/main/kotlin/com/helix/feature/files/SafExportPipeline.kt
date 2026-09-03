package com.helix.feature.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.SymlinkEscapesRoot
import com.helix.core.workspace.SymlinkInPath
import com.helix.core.workspace.WorkspaceLayout
import com.helix.core.workspace.resolveFileScopePath
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Opens a writable stream at a destination document URI (one the user chose, e.g. via
 * ACTION_CREATE_DOCUMENT, or a document inside a persisted tree grant). Production:
 * `ContentResolver.openOutputStream`.
 */
fun interface SafDestinationOpener {
    fun openStream(uri: String): OutputStream
}

/**
 * Reports the size a destination provider claims for a document after an export (post-write
 * verification). Must return -1 (unknown) instead of throwing when the size is unreadable;
 * the production implementation catches provider failures.
 */
fun interface SafDestinationVerifier {
    fun reportedSize(uri: String): Long
}

enum class ExportStatus {
    COMPLETED,
    REFUSED,
    CANCELLED,
}

/** Stable, sanitized refusal codes (doc 13: bounded output). */
enum class ExportRefusal {
    OUTSIDE_USER_REGIONS,
    SCOPE_UNAVAILABLE,
    SOURCE_NOT_FOUND,
    NOT_A_FILE,
    SOURCE_EXCEEDS_LIMIT,
    DESTINATION_UNOPENABLE,
    IO_FAILURE,
    SIZE_VERIFICATION_MISMATCH,
}

/**
 * The outcome of a SAF export. [sizeVerified] is true only when the destination provider
 * reported a size AND it matched the bytes written; a destination that under- or over-reports
 * after the write is a fail-closed refusal (a lying destination is as untrusted as a lying
 * source, doc 07). No real path ever appears in this type.
 */
class SafExportOutcome(
    val status: ExportStatus,
    val refusal: ExportRefusal? = null,
    val detail: String? = null,
    val sizeBytes: Long = -1L,
    val sha256: String? = null,
    val sizeVerified: Boolean = false,
)

/**
 * Exports one workspace file to a user-chosen SAF destination (HXA-044; PRD: 外部导出必须由
 * SAF/明确文件管理动作/已审批 scope 写入 — the destination URI itself is that explicit user
 * action). Fail-closed throughout:
 *
 * 1. the source must be inside `input/`/`work/`/`output/` of the scope (`.helix/` internals are
 *    never addressable, same region gate as the HXA-043 mutation tools);
 * 2. containment is enforced by [resolveFileScopePath] (no escaping path, no symlinks);
 * 3. the copy is chunked with a per-chunk cancel check (大流取消); a cancel leaves a PARTIAL
 *    destination document (a content URI cannot be truncated from here) and reports so;
 * 4. after the write, the destination's reported size is re-checked when readable; a mismatch
 *    is a refusal (the exported bytes cannot be trusted to have arrived intact).
 */
class SafExportPipeline(
    private val scopeRoots: ScopeRootResolver,
    private val destinationOpener: SafDestinationOpener,
    private val destinationVerifier: SafDestinationVerifier,
    private val maxExportBytes: Long = SafImportLimits.DEFAULT_MAX_IMPORT_BYTES,
) {
    init {
        require(maxExportBytes > 0) { "maxExportBytes must be > 0" }
    }

    @Suppress("ReturnCount", "LongParameterList") // every early exit is a distinct sanitized refusal outcome
    fun exportFile(
        source: FileScopePath,
        destinationUri: String,
        cancel: SafCancelToken,
        // HXA-058: optional byte progress for the file-manager UI — (bytesWritten, sourceSize).
        // Purely additive: it never changes the region gate / cancel / size-re-check behavior
        // (the HXA-044 contract).
        onProgress: (
            Long,
            Long,
        ) -> Unit = { _, _ -> },
    ): SafExportOutcome {
        if (cancel.isCancelled()) return cancelled()
        if (!isUserRegion(source.relativePath)) {
            return refused(
                ExportRefusal.OUTSIDE_USER_REGIONS,
                "source must be inside input/, work/ or output/",
            )
        }
        val sourcePath =
            resolveSourcePath(source)
                ?: return refused(ExportRefusal.SCOPE_UNAVAILABLE, "workspace scope is not available")
        val gate = gateSource(sourcePath)
        if (gate != null) return gate
        return copyAndVerify(sourcePath, destinationUri, cancel, onProgress)
    }

    /** Only the user regions are exportable (`.helix/` internals are never addressable). */
    private fun isUserRegion(relativePath: String): Boolean {
        val region = WorkspaceLayout.regionOf(relativePath)
        return region != null && WorkspaceLayout.isRegion(region)
    }

    /** Null when the scope cannot be resolved safely (unknown scope, escaping or in-path symlink). */
    @Suppress("SwallowedException") // scope failures map to one stable refusal; details never leak
    private fun resolveSourcePath(source: FileScopePath): Path? =
        try {
            resolveFileScopePath(source, scopeRoots)
        } catch (e: ScopeNotAvailable) {
            null
        } catch (e: SymlinkEscapesRoot) {
            null
        } catch (e: SymlinkInPath) {
            null
        } catch (e: IOException) {
            null
        }

    /** The source gates: present, regular file, within the export cap; null when they all pass. */
    @Suppress("ReturnCount") // one return per distinct gate
    private fun gateSource(sourcePath: Path): SafExportOutcome? {
        if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            return refused(ExportRefusal.SOURCE_NOT_FOUND, "source file not found")
        }
        if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            return refused(ExportRefusal.NOT_A_FILE, "source is not a regular file")
        }
        if (Files.size(sourcePath) > maxExportBytes) {
            return refused(ExportRefusal.SOURCE_EXCEEDS_LIMIT, "source exceeds the export limit")
        }
        return null
    }

    /** Open the destination, stream the bytes (cancel-aware), then size-verify against the report. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount")
    // a hostile destination may throw anything; every exit is a distinct sanitized outcome
    private fun copyAndVerify(
        sourcePath: Path,
        destinationUri: String,
        cancel: SafCancelToken,
        onProgress: (
            Long,
            Long,
        ) -> Unit,
    ): SafExportOutcome {
        val destination: OutputStream
        try {
            destination = destinationOpener.openStream(destinationUri)
        } catch (e: Exception) {
            return refused(
                ExportRefusal.DESTINATION_UNOPENABLE,
                "the destination document cannot be opened",
            )
        }
        val total = Files.size(sourcePath)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            destination.use { out ->
                Files.newInputStream(sourcePath).use { input ->
                    written = copyChunked(input, out, digest, cancel, { done -> onProgress(done, total) })
                }
            }
        } catch (e: AbandonedWriteCancelled) {
            return cancelled()
        } catch (e: IOException) {
            return refused(ExportRefusal.IO_FAILURE, "reading or writing the document failed")
        }
        val reported = destinationVerifier.reportedSize(destinationUri)
        if (reported >= 0 && reported != written) {
            return refused(
                ExportRefusal.SIZE_VERIFICATION_MISMATCH,
                "the destination reported a different size after the export",
            )
        }
        return SafExportOutcome(
            status = ExportStatus.COMPLETED,
            sizeBytes = written,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            sizeVerified = reported == written,
        )
    }

    /** Streams [input] into [output] in chunks, hashing on the way; the cancel aborts mid-copy. */
    private fun copyChunked(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        cancel: SafCancelToken,
        onChunk: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(CHUNK_SIZE)
        var written = 0L
        while (true) {
            if (cancel.isCancelled()) throw AbandonedWriteCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            written += n
            onChunk(written)
        }
        return written
    }

    private fun cancelled() =
        SafExportOutcome(
            status = ExportStatus.CANCELLED,
            detail = "the export was cancelled; the destination may hold a partial document",
        )

    private fun refused(
        refusal: ExportRefusal,
        detail: String,
    ) = SafExportOutcome(status = ExportStatus.REFUSED, refusal = refusal, detail = detail)

    /** Internal cancel marker (kept local so the feature module stays framework-free). */
    private class AbandonedWriteCancelled : RuntimeException("export cancelled")

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}
