package com.helix.core.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The single production facade for workspace file persistence (HXA-041).
 *
 * Responsibilities, all fail-closed:
 * - directory layout creation ([WorkspaceLayout]);
 * - atomic publish: temp write + fsync + atomic replace ([AtomicFileWriter]);
 * - 前置 hash: a guarded write only proceeds when the current target hash matches;
 * - quota: aggregate usage checked before admit and re-checked after publish;
 * - bounded content probing ([ContentProbe]);
 * - artifact registration ([ArtifactRecord]) in a caller-supplied [ArtifactSink], which is the
 *   seam where the Room `artifacts` table (doc 02 §8) is plugged in. The file is written and
 *   hashed *before* the sink is invoked, matching "产物先写文件并计算哈希，再插入 artifacts".
 *
 * The store resolves [FileScopePath] to real paths through [resolveFileScopePath] (the HXA-040
 * boundary) — a model reference can never smuggle a path out of the scope, and the real path is
 * never handed back to the model.
 */
class WorkspaceArtifactStore(
    private val rootResolver: ScopeRootResolver,
    private val quotaPolicy: WorkspaceQuotaPolicy = WorkspaceQuotaPolicy.default,
    private val linkPolicy: LinkPolicy = LinkPolicy.REJECT_SYMLINKS,
) {
    /**
     * A registered artifact (doc 02 §8 `artifacts` row shape: id, relativePath, mediaType, size,
     * sha256). [sessionId] is supplied by the caller at registration.
     */
    data class ArtifactRecord(
        val id: String,
        val relativePath: String,
        val mediaType: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    /**
     * Receives artifact registrations. Implementations (the Room-backed repository) insert the
     * row; a failing sink must leave the file on disk (the file is the source of truth and the
     * registration is retryable — see doc 02 §9.2).
     */
    fun interface ArtifactSink {
        fun register(
            sessionId: String,
            record: ArtifactRecord,
        )
    }

    /**
     * Creates any missing layout directories under the scope root (input/, work/, output/,
     * .helix/, .helix/trash/, .helix/executions/). Idempotent.
     * @throws ScopeNotAvailable when the scope cannot be resolved.
     * @throws PathResolutionError on a forbidden symlink or escaping segment.
     */
    fun ensureLayout(scope: String) {
        val root = resolve(scope)
        val targets =
            listOf(
                WorkspaceLayout.INPUT,
                WorkspaceLayout.WORK,
                WorkspaceLayout.OUTPUT,
                WorkspaceLayout.HELIX,
                WorkspaceLayout.TRASH,
                WorkspaceLayout.EXECUTIONS,
            )
        for (region in targets) {
            val dir = PathResolution.join(root, region)
            if (!Files.exists(dir)) {
                Files.createDirectories(dir)
            } else {
                require(Files.isDirectory(dir)) { "layout entry $region is not a directory" }
            }
        }
        // A pre-existing metadata.json is preserved; a missing one is created atomically.
        val metadata = PathResolution.join(root, WorkspaceLayout.METADATA)
        if (!Files.exists(metadata)) {
            AtomicFileWriter.writeAtomic(metadata, "{}".toByteArray())
        }
    }

    /**
     * Writes [bytes] to [path] atomically under the scope quota and optional 前置 hash, then
     * probes and registers the artifact.
     *
     * Order (fail-closed at every step): scope resolve → path containment → layout region check →
     * quota pre-check (the admission point; no temp is created on rejection) → 前置 hash → temp
     * write + fsync + atomic replace → hash of written bytes → content probe → [sink].register.
     *
     * The quota is a *pre-admission* check and, for a serialized writer, a hard guarantee (see
     * [WorkspaceQuota]): after `usageBefore + bytes ≤ max` the atomic replace yields
     * `usageAfter = usageBefore − oldBytes + bytes ≤ usageBefore + bytes ≤ max`, so a single
     * well-ordered writer can never overshoot. The `usageBytesAfter` reported by the outcome is
     * that figure, surfaced for the UI. A *concurrent* writer in a different session (doc §12
     * serializes writes per session, not across sessions) can push the total over the cap; that
     * is detected by the *next* write's pre-check, which then refuses. There is no rollback after
     * the atomic replace — the file is durably published by then, and deleting it to "un-overflow"
     * would discard a successful write, so overshoot under concurrency is reported, never reverted.
     *
     * @param path the model reference (scope:...) of the destination.
     * @param region one of [WorkspaceLayout.regions]; the destination must stay inside it so a
     *   tool can never write into `.helix/` internals or outside the layout.
     * @param expectedPreviousSha256 前置 hash; non-null requires the current file to match.
     * @param sessionId owning session for the artifact row (may be null to register as orphan).
     * @return the published [ContentProbe.Result] plus the final [ArtifactRecord].
     */
    @Suppress("LongParameterList") // each parameter is a distinct, documented safety input
    fun writeArtifact(
        path: FileScopePath,
        bytes: ByteArray,
        region: String,
        expectedPreviousSha256: String? = null,
        sessionId: String? = null,
        sink: ArtifactSink? = null,
    ): WriteOutcome {
        require(WorkspaceLayout.isRegion(region)) { "destination region must be one of ${WorkspaceLayout.regions}" }
        require(WorkspaceLayout.regionOf(path.relativePath) == region) {
            "destination must stay inside the $region region"
        }
        val root = resolve(path.scopeId)
        val target = resolveContained(path, root)

        // Quota admission before touching the disk (fail-closed; no temp is created on rejection).
        WorkspaceQuota.ensureRoom(root, bytes.size.toLong(), quotaPolicy.maxWorkspaceBytes)

        val writtenHash = AtomicFileWriter.writeAtomic(target, bytes, expectedPreviousSha256)

        // Post-publish usage: under a serialized writer this is provably ≤ the cap (see the
        // class KDoc); it is the figure the UI shows and the next pre-check re-reads. It is
        // reported, not asserted, because a cross-session concurrent writer can only push it over
        // the cap (reverted would discard this successful write).
        val usageAfter = WorkspaceQuota.usageBytes(root)
        val probe = ContentProbe.probe(target)
        val record =
            ArtifactRecord(
                id = newArtifactId(),
                relativePath = path.relativePath,
                mediaType = probe.mimeType,
                sizeBytes = probe.sizeBytes,
                sha256 = writtenHash,
            )
        if (sink != null && sessionId != null) {
            sink.register(sessionId, record)
        }
        return WriteOutcome(record, probe, usageAfter)
    }

    /**
     * Reads [path] (containment-enforced) and returns its full bytes. Intentionally unbounded in
     * *this* API — the tool layer (HXA-042 `read`) imposes offset/maxBytes; this method is the
     * internal whole-file accessor the tool uses after its own bounds check.
     * @throws IOException on read failure.
     */
    fun readAll(path: FileScopePath): ByteArray {
        val root = resolve(path.scopeId)
        val target = resolveContained(path, root)
        if (!Files.exists(target) || !Files.isRegularFile(target)) return ByteArray(0)
        return Files.readAllBytes(target)
    }

    /**
     * Probes [path] without reading the full file (bounded prefix). A missing path is reported,
     * not thrown.
     */
    fun probe(path: FileScopePath): ContentProbe.Result {
        val root = resolve(path.scopeId)
        val target = resolveContained(path, root)
        return ContentProbe.probe(target)
    }

    /** Current aggregate usage of the scope, in bytes. */
    fun usageBytes(scope: String): Long = WorkspaceQuota.usageBytes(resolve(scope))

    /**
     * Reclaims every abandoned temp file anywhere under the scope (recursively, including nested
     * directories an interrupted write may have used and the `.helix/` metadata temp). The walk is
     * rooted at the scope and never follows a symlinked directory, so it cannot leave the scope.
     * @return the number of temp files removed.
     */
    fun reclaimTempFiles(scope: String): Int = AtomicFileWriter.cleanupRecursively(resolve(scope))

    private fun resolve(scope: String): Path = rootResolver.resolveRoot(scope)

    private fun resolveContained(
        path: FileScopePath,
        root: Path,
    ): Path = PathResolution.resolveWithinRoot(root, PathResolution.join(root, path.relativePath), linkPolicy)

    private fun newArtifactId(): String = "art_" + UUID.randomUUID().toString().replace("-", "")
}

/** Outcome of a successful [WorkspaceArtifactStore.writeArtifact]. */
data class WriteOutcome(
    val record: WorkspaceArtifactStore.ArtifactRecord,
    val probe: ContentProbe.Result,
    val usageBytesAfter: Long,
)
