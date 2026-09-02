package com.helix.core.workspace

import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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
@Suppress("TooManyFunctions") // one small method per file operation; the store is the HXA-041 facade
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
     * Bounded, containment-enforced read of a window of [path] (HXA-042 `read`). Delegates the
     * offset/maxBytes/encoding/EOF semantics to [ReadWindow.read] after proving the path stays
     * inside the scope. A missing or non-regular file is a fail-closed [IOException]; an offset
     * past the end is a stable terminal window (see [ReadWindow]).
     * @throws ScopeNotAvailable when the scope cannot be resolved.
     * @throws PathResolutionError on a forbidden symlink or escaping segment.
     * @throws IOException when the target exists but is not a readable regular file.
     */
    fun readWindow(
        path: FileScopePath,
        offset: Long,
        maxBytes: Long,
    ): ReadWindow {
        val root = resolve(path.scopeId)
        val target = resolveContained(path, root)
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw java.io.FileNotFoundException("not a regular file: ${path.toModelReference()}")
        }
        return ReadWindow.read(target, offset, maxBytes)
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

    /**
     * Stat metadata for [path] (HXA-042 `files.stat`). Bounded and existence-agnostic: a missing
     * path is reported (exists=false, size -1), not thrown. The regular/symlink flags are read
     * without following links ([LinkOption.NOFOLLOW_LINKS]) so a symlinked file is reported as a
     * symlink, not as its target. Only containment is enforced here — reads are region-agnostic.
     * @throws ScopeNotAvailable when the scope cannot be resolved.
     * @throws PathResolutionError on a forbidden symlink or escaping segment.
     */
    fun stat(path: FileScopePath): StatInfo {
        val root = resolve(path.scopeId)
        val target = resolveContained(path, root)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return StatInfo(false, -1L, false, false, false)
        }
        val attrs: BasicFileAttributes =
            Files.readAttributes(
                target,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        return StatInfo(true, attrs.size(), attrs.isDirectory(), attrs.isRegularFile(), attrs.isSymbolicLink())
    }

    /**
     * Lists the immediate children of a directory referenced by [path] (HXA-042 `files.list`).
     * The referenced path must be an existing directory. Returns at most [maxEntries] entries
     * (the first [maxEntries] in filesystem order), reporting [truncated] when more remain — a
     * bounded listing the model can page by name. Only containment is enforced here; the tool
     * layer decides whether to hide `.helix/` internals.
     * @throws FileNotFoundException when [path] does not exist or is not a directory.
     * @throws ScopeNotAvailable when the scope cannot be resolved.
     * @throws PathResolutionError on a forbidden symlink or escaping segment.
     */
    fun listDir(
        path: FileScopePath,
        maxEntries: Int,
    ): ListResult {
        val root = resolve(path.scopeId)
        val dir = resolveContained(path, root)
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw FileNotFoundException("not a directory: ${path.toModelReference()}")
        }
        val names =
            Files.list(dir).use { stream ->
                stream
                    .sorted()
                    .map { it.fileName.toString() }
                    .toList()
            }
        val page = if (maxEntries >= names.size) names else names.subList(0, maxEntries)
        return ListResult(page, names.size > maxEntries)
    }

    /**
     * Searches the files under a directory referenced by [base] for those whose names contain
     * [needle] as a case-insensitive substring (HXA-042 `files.search`). Returns matching
     * [FileScopePath]s relative to [base], at most [maxResults]. The walk is depth-first and
     * bounded by [maxScan] entries total and stops at the caps — a large tree is probed, not
     * exhausted. Symlinked directories are never followed, so the walk cannot leave [base].
     * @throws FileNotFoundException when [base] does not exist or is not a directory.
     * @throws ScopeNotAvailable when the scope cannot be resolved.
     * @throws PathResolutionError on a forbidden symlink or escaping segment (the [base] itself).
     */
    fun search(
        base: FileScopePath,
        needle: String,
        maxResults: Int,
        maxScan: Int,
    ): SearchResult {
        require(needle.isNotEmpty()) { "search needle must be non-empty" }
        require(maxResults in 1..MAX_SEARCH_RESULTS) { "maxResults must be 1..$MAX_SEARCH_RESULTS (got $maxResults)" }
        require(maxScan in 1..MAX_SEARCH_SCAN) { "maxScan must be 1..$MAX_SEARCH_SCAN (got $maxScan)" }
        val root = resolve(base.scopeId)
        val baseDir = resolveContained(base, root)
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            throw FileNotFoundException("not a directory: ${base.toModelReference()}")
        }
        val needleLower = needle.lowercase()
        // Relativize against baseDir (not the resolved root) so the result is correct even when
        // the scope root is itself a symlink (macOS /var -> /private/var): baseDir and the walked
        // paths come from the same starting object, so relativize is valid.
        val baseRel = base.relativePath.removeSuffix("/")
        val prefix = if (baseRel.isEmpty()) "" else "$baseRel/"
        return Files.walk(baseDir).use { stream ->
            boundedWalk(stream.iterator(), baseDir, base.scopeId, prefix, needleLower, maxResults, maxScan)
        }
    }

    /**
     * Creates a directory referenced by [path] (HXA-042 `files.mkdir`). Intermediate segments are
     * created as needed. Fails closed when the target already exists (a file or a directory) so an
     * accidental `mkdir` never clobbers content. [region], when non-null, must be a writable user
     * region that the path stays inside (the tool layer enforces this; the store enforces
     * containment regardless).
     * @throws FileAlreadyExistsException when the target exists.
     * @throws ScopeNotAvailable when the scope cannot be resolved.
     * @throws PathResolutionError on a forbidden symlink or escaping segment.
     */
    fun mkdir(
        path: FileScopePath,
        region: String?,
    ) {
        if (region != null) {
            require(WorkspaceLayout.isRegion(region)) { "region must be one of ${WorkspaceLayout.regions}" }
            require(WorkspaceLayout.regionOf(path.relativePath) == region) {
                "destination must stay inside the $region region"
            }
        }
        val root = resolve(path.scopeId)
        val target = resolveContained(path, root)
        if (Files.exists(target)) {
            throw java.nio.file.FileAlreadyExistsException(path.toModelReference())
        }
        Files.createDirectories(target)
    }

    private fun resolve(scope: String): Path = rootResolver.resolveRoot(scope)

    private fun resolveContained(
        path: FileScopePath,
        root: Path,
    ): Path = PathResolution.resolveWithinRoot(root, PathResolution.join(root, path.relativePath), linkPolicy)

    private fun newArtifactId(): String = "art_" + UUID.randomUUID().toString().replace("-", "")

    companion object {
        /** Hard ceiling on `files.search` matches returned in one call. */
        const val MAX_SEARCH_RESULTS: Int = 512

        /** Hard ceiling on total entries walked by one `files.search` call. */
        const val MAX_SEARCH_SCAN: Int = 20_000
    }
}

/**
 * Bounded depth-first name walk used by [WorkspaceArtifactStore.search]: consumes [it] and stops
 * after [maxScan] entries or [maxResults] matches, whichever comes first. Reports
 * [SearchResult.truncated] when a cap stopped the walk while further entries (and possibly
 * matches) remain. The walked paths are relativized against [baseDir] by the caller's prefix.
 */
private fun boundedWalk(
    it: Iterator<Path>,
    baseDir: Path,
    scopeId: String,
    prefix: String,
    needleLower: String,
    maxResults: Int,
    maxScan: Int,
): SearchResult {
    val matches = mutableListOf<FileScopePath>()
    var scanned = 0
    var truncated = false
    while (scanned < maxScan && it.hasNext()) {
        val p = it.next()
        scanned++
        if (p != baseDir && p.fileName.toString().contains(needleLower, ignoreCase = true)) {
            matches += FileScopePath(scopeId, prefix + baseDir.relativize(p).joinToString("/"))
            if (matches.size >= maxResults) {
                truncated = true
                break
            }
        }
    }
    if (!truncated && it.hasNext()) truncated = true
    return SearchResult(matches, truncated)
}

/** Outcome of [WorkspaceArtifactStore.stat] (HXA-042 `files.stat`). */
data class StatInfo(
    val exists: Boolean,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val isSymlink: Boolean,
)

/** One bounded page of [WorkspaceArtifactStore.listDir] (HXA-042 `files.list`). */
data class ListResult(
    val entries: List<String>,
    val truncated: Boolean,
)

/** Bounded matches from [WorkspaceArtifactStore.search] (HXA-042 `files.search`). */
data class SearchResult(
    val matches: List<FileScopePath>,
    val truncated: Boolean,
)

/** Outcome of a successful [WorkspaceArtifactStore.writeArtifact]. */
data class WriteOutcome(
    val record: WorkspaceArtifactStore.ArtifactRecord,
    val probe: ContentProbe.Result,
    val usageBytesAfter: Long,
)
