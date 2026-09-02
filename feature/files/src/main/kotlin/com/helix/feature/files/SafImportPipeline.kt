package com.helix.feature.files

import com.helix.core.workspace.AbandonedWrite
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.SymlinkEscapesRoot
import com.helix.core.workspace.SymlinkInPath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.core.workspace.WorkspaceQuota
import com.helix.core.workspace.WorkspaceQuotaPolicy
import com.helix.core.workspace.resolveFileScopePath
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * The metadata a ContentProvider REPORTS about a source document (size / MIME / display name).
 * Every field is untrusted input (doc 07: SAF provider 谎报 size/MIME/display name): the pipeline
 * only uses it for the pre-stream admission gates, then re-verifies everything against the
 * actual bytes that arrive.
 *
 * @property sizeBytes reported size, or -1 when the provider reports none (unknown).
 * @property mimeType reported MIME, or null when the provider reports none.
 * @property displayName reported display name, or null.
 */
data class SafSourceMetadata(
    val sizeBytes: Long,
    val mimeType: String?,
    val displayName: String?,
)

/** Opens the byte stream of a source document URI. Production: `ContentResolver.openInputStream`. */
fun interface SafSourceOpener {
    fun openStream(uri: String): InputStream
}

/** Admission limits for a SAF import. Both caps are hard: the stream is aborted mid-copy if
 *  the provider under-reported and delivers more than they allow. */
data class SafImportLimits(
    val maxImportBytes: Long = DEFAULT_MAX_IMPORT_BYTES,
    val quotaMaxBytes: Long = WorkspaceQuotaPolicy.DEFAULT_MAX_WORKSPACE_BYTES,
) {
    init {
        require(maxImportBytes > 0) { "maxImportBytes must be > 0" }
        require(quotaMaxBytes > 0) { "quotaMaxBytes must be > 0" }
    }

    companion object {
        /** Default per-file import cap: 256 MiB (well inside the default 1 GiB workspace quota). */
        const val DEFAULT_MAX_IMPORT_BYTES: Long = 256L shl 20
    }
}

enum class ImportStatus {
    COMPLETED,
    REFUSED,
    CANCELLED,
}

/** Stable, sanitized refusal codes (doc 13: bounded output — the detail is a fixed string,
 *  never a raw exception message or a real path). */
enum class ImportRefusal {
    INVALID_TARGET,
    SCOPE_UNAVAILABLE,
    DESTINATION_EXISTS,
    REPORTED_SIZE_EXCEEDS_LIMIT,
    QUOTA_EXCEEDED,
    SOURCE_UNOPENABLE,
    STREAM_SIZE_MISMATCH,
    STREAM_LIMIT_EXCEEDED,
    IO_FAILURE,
}

/**
 * The outcome of a SAF import. On success the only path-shaped value is [targetModelRef]
 * (`scope:<scopeId>:input/<name>` — the model-safe reference, doc 10); no real path ever
 * appears in this type.
 */
@Suppress("LongParameterList") // flat outcome record: the fields are heterogeneous
class SafImportOutcome(
    val status: ImportStatus,
    val refusal: ImportRefusal? = null,
    val detail: String? = null,
    val targetModelRef: String? = null,
    val targetName: String? = null,
    val sizeBytes: Long = -1L,
    val sha256: String? = null,
    val mimeType: String? = null,
    val isText: Boolean = false,
    val artifactRegistered: Boolean = false,
)

/**
 * Imports one SAF document into a workspace `input/` region (HXA-044; PRD: SAF scope 默认
 * 复制到应用私有目录处理). The whole pipeline is fail-closed against a lying provider:
 *
 * 1. the reported display name is sanitized ([SafNameSanitizer]) before it becomes a file name;
 * 2. an existing destination is refused (the caller, i.e. the user, decides renames/overwrite);
 * 3. the reported size is used only for admission: above [SafImportLimits.maxImportBytes] or
 *     above the workspace quota headroom → refused before a single byte is read;
 * 4. the copy is chunked, so per-chunk the cancel signal is checked and a HARD byte cap
 *     (`min(maxImportBytes, quota headroom)`) is enforced — a provider that under-reports its
 *     size is aborted mid-stream and the temp file is deleted (nothing half-written survives);
 * 5. at EOF the delivered size must EXACTLY match the reported size (when known); a mismatch
 *     means the provider lied and the just-published file is deleted again;
 * 6. publish is [AtomicFileWriter.writeAtomicStream] (temp + fsync + atomic rename);
 * 7. the MIME recorded is re-detected from the actual bytes ([ContentProbe]) — the reported
 *     MIME is never trusted;
 * 8. optional artifact registration ([WorkspaceArtifactStore.ArtifactSink]) happens after the
 *     file is durable (same convention as [WorkspaceArtifactStore.writeArtifact]: it only runs
 *     when both sink and session are supplied; a failing sink is reported, not fatal).
 */
class SafImportPipeline(
    private val scopeRoots: ScopeRootResolver,
    private val opener: SafSourceOpener,
    private val limits: SafImportLimits = SafImportLimits(),
) {
    @Suppress("ReturnCount", "SwallowedException") // every exit is a distinct sanitized outcome
    fun importDocument(
        workspaceScopeId: String,
        sourceUri: String,
        reported: SafSourceMetadata,
        targetNameOverride: String?,
        cancel: SafCancelToken,
        sink: WorkspaceArtifactStore.ArtifactSink? = null,
        sessionId: String? = null,
    ): SafImportOutcome {
        if (cancel.isCancelled()) return cancelled()
        try {
            val located = locateTarget(workspaceScopeId, targetNameOverride ?: reported.displayName)
            val hardLimit = admit(reported.sizeBytes, located.root)
            val sourceStream = openSource(sourceUri)
            val streamed = streamToTarget(sourceStream, located.targetPath, hardLimit, cancel)
            if (reported.sizeBytes >= 0 && streamed.bytesWritten != reported.sizeBytes) {
                // The provider lied (or the stream was truncated): the bytes on disk do not
                // match what the user was told. Fail closed — the target did not exist before
                // (checked in locateTarget) and writes are serialized per session, so deleting
                // it is safe.
                try {
                    Files.deleteIfExists(located.targetPath)
                } catch (e: IOException) {
                    // Best effort: a failing delete leaves an unreferenced orphan in input/.
                }
                throw Refusal(
                    ImportRefusal.STREAM_SIZE_MISMATCH,
                    "the delivered size does not match the reported size",
                )
            }
            return finish(located.target, located.targetPath, streamed, sink, sessionId)
        } catch (e: AbandonedWrite.Cancelled) {
            return cancelled()
        } catch (e: AbandonedWrite.LimitExceeded) {
            return refused(
                ImportRefusal.STREAM_LIMIT_EXCEEDED,
                "the provider delivered more bytes than the limit allows",
            )
        } catch (e: Refusal) {
            return refused(e.refusal, e.message.orEmpty())
        } catch (e: IOException) {
            return refused(ImportRefusal.IO_FAILURE, "reading or writing the document failed")
        } catch (e: SecurityException) {
            return refused(ImportRefusal.SOURCE_UNOPENABLE, "the source document cannot be opened")
        }
    }

    /**
     * Opens the source stream, fail-closed: a hostile or broken source provider may throw
     * anything on open (not just SecurityException / IOException), so every open failure maps
     * to one [ImportRefusal.SOURCE_UNOPENABLE] refusal — the same scoped guard the export
     * pipeline applies to its destination opener (SafExportPipeline.copyAndVerify).
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun openSource(sourceUri: String): InputStream =
        try {
            opener.openStream(sourceUri)
        } catch (e: Exception) {
            throw Refusal(ImportRefusal.SOURCE_UNOPENABLE, "the source document cannot be opened")
        }

    /**
     * Chunked copy into a temp file with the atomic publish; per chunk: cancel check, then the
     * hard byte cap (the lying-size defense for unknown or under-reported sizes). The source
     * stream is closed on every path. [AbandonedWrite.Cancelled]/[AbandonedWrite.LimitExceeded]
     * escape to [importDocument] so it can distinguish them from an I/O refusal.
     */
    private fun streamToTarget(
        source: InputStream,
        targetPath: Path,
        hardLimit: Long,
        cancel: SafCancelToken,
    ): Streamed {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        source.use { input ->
            AtomicFileWriter.writeAtomicStream(targetPath) { out ->
                written = copyInto(input, out, digest, hardLimit, cancel)
            }
        }
        return Streamed(written, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Reads [input] into [output] in [CHUNK_SIZE] chunks, hashing on the way. */
    private fun copyInto(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        hardLimit: Long,
        cancel: SafCancelToken,
    ): Long {
        val buffer = ByteArray(CHUNK_SIZE)
        var written = 0L
        while (true) {
            if (cancel.isCancelled()) throw AbandonedWrite.Cancelled()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            written += n
            if (written > hardLimit) throw AbandonedWrite.LimitExceeded()
        }
        return written
    }

    /** A located import target: model reference + scope root + resolved real path. */
    private class Located(
        val target: FileScopePath,
        val root: Path,
        val targetPath: Path,
    )

    @Suppress("ThrowsCount", "SwallowedException") // Refusal carries the stable code; one throw site per gate
    private fun locateTarget(
        workspaceScopeId: String,
        rawName: String?,
    ): Located {
        val target =
            try {
                FileScopePath(
                    workspaceScopeId,
                    WorkspaceLayout.INPUT + "/" + SafNameSanitizer.sanitize(rawName),
                )
            } catch (e: IllegalArgumentException) {
                throw Refusal(
                    ImportRefusal.INVALID_TARGET,
                    "target reference is not a valid workspace path",
                )
            }
        val root: Path
        val targetPath: Path
        try {
            root = scopeRoots.resolveRoot(target.scopeId)
            targetPath = resolveFileScopePath(target, scopeRoots)
        } catch (e: ScopeNotAvailable) {
            throw Refusal(ImportRefusal.SCOPE_UNAVAILABLE, "workspace scope is not available")
        } catch (e: SymlinkEscapesRoot) {
            throw Refusal(ImportRefusal.SCOPE_UNAVAILABLE, "workspace scope is not available")
        } catch (e: SymlinkInPath) {
            throw Refusal(ImportRefusal.SCOPE_UNAVAILABLE, "workspace scope is not available")
        }
        if (!Files.isDirectory(targetPath.parent)) {
            throw Refusal(ImportRefusal.SCOPE_UNAVAILABLE, "the workspace layout is not available")
        }
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw Refusal(
                ImportRefusal.DESTINATION_EXISTS,
                "a file with that name already exists in input/",
            )
        }
        return Located(target, root, targetPath)
    }

    /**
     * The pre-stream admission gates on the (untrusted) reported size. Returns the hard cap
     * the stream must then enforce: `min(maxImportBytes, quota headroom)`.
     */
    private fun admit(
        reportedSizeBytes: Long,
        root: Path,
    ): Long {
        if (reportedSizeBytes > limits.maxImportBytes) {
            throw Refusal(
                ImportRefusal.REPORTED_SIZE_EXCEEDS_LIMIT,
                "reported size exceeds the import limit",
            )
        }
        val headroom = limits.quotaMaxBytes - WorkspaceQuota.usageBytes(root)
        val hardLimit = minOf(limits.maxImportBytes, headroom)
        if (headroom <= 0 || reportedSizeBytes > hardLimit) {
            throw Refusal(ImportRefusal.QUOTA_EXCEEDED, "workspace quota has no room for this file")
        }
        return hardLimit
    }

    /** Post-publish: re-probe the real bytes, optionally register the artifact. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    // sink contract (doc 02 §9.2): a failing sink is non-fatal, the file is durable
    private fun finish(
        target: FileScopePath,
        targetPath: Path,
        streamed: Streamed,
        sink: WorkspaceArtifactStore.ArtifactSink?,
        sessionId: String?,
    ): SafImportOutcome {
        val probe = ContentProbe.probe(targetPath)
        var registered = false
        if (sink != null && sessionId != null) {
            try {
                sink.register(
                    sessionId,
                    WorkspaceArtifactStore.ArtifactRecord(
                        id = "art_" + UUID.randomUUID().toString().replace("-", ""),
                        relativePath = target.relativePath,
                        mediaType = probe.mimeType,
                        sizeBytes = streamed.bytesWritten,
                        sha256 = streamed.sha256,
                    ),
                )
                registered = true
            } catch (e: Exception) {
                // Contract (doc 02 §9.2): a failing sink leaves the file on disk; registration
                // is retryable. The file is durable and hash-verified at this point.
            }
        }
        return SafImportOutcome(
            status = ImportStatus.COMPLETED,
            targetModelRef = target.toModelReference(),
            targetName = target.name,
            sizeBytes = streamed.bytesWritten,
            sha256 = streamed.sha256,
            mimeType = probe.mimeType,
            isText = probe.isText,
            artifactRegistered = registered,
        )
    }

    private fun cancelled() =
        SafImportOutcome(
            status = ImportStatus.CANCELLED,
            detail = "the import was cancelled; nothing was published",
        )

    private fun refused(
        refusal: ImportRefusal,
        detail: String,
    ) = SafImportOutcome(status = ImportStatus.REFUSED, refusal = refusal, detail = detail)

    /** Stream outcome: the published byte count plus the SHA-256 of the bytes as written. */
    private class Streamed(
        val bytesWritten: Long,
        val sha256: String,
    )

    /** Internal control-flow marker carrying a stable refusal (message is a fixed string). */
    private class Refusal(
        val refusal: ImportRefusal,
        detail: String,
    ) : RuntimeException(detail)

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}
