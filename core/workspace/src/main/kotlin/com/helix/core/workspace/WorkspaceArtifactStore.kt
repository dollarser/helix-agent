package com.helix.core.workspace

import java.io.FileNotFoundException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.stream.Collectors

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
            return StatInfo(false, -1L, false, false, false, -1L)
        }
        val attrs: BasicFileAttributes =
            Files.readAttributes(
                target,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        return StatInfo(
            true,
            attrs.size(),
            attrs.isDirectory(),
            attrs.isRegularFile(),
            attrs.isSymbolicLink(),
            attrs.lastModifiedTime().toMillis(),
        )
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
                // Collectors.toList(), NOT Stream.toList(): that default method is Java 16 /
                // Android API 31+ and throws NoSuchMethodError on API 29 devices (minSdk 29).
                stream
                    .sorted()
                    .map { it.fileName.toString() }
                    .collect(Collectors.toList())
            }
        val page = if (maxEntries >= names.size) names else names.subList(0, maxEntries)
        return ListResult(page, names.size > maxEntries)
    }

    /**
     * Searches the files under a directory referenced by [base] for those whose names contain
     * [needle] as a case-insensitive substring (HXA-042 `files.search`). Returns matching
     * [FileScopePath]s relative to [base], at most [maxResults]. A match whose name cannot be a
     * legal [FileScopePath] (a control character or an over-long reference) is skipped, so one
     * such file never aborts the search. The walk is depth-first and
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

    /**
     * Copies the regular file at [src] to [dst] (HXA-043 `files.copy`). [dst] must stay inside
     * the user [region]; [src] is containment-enforced against its own scope root (the tool layer
     * decides whether [src] may come from a user region). The conflict policy is explicit: an
     * existing [dst] (file OR directory) is refused unless [overwrite] is set, and an
     * [overwrite] into a directory is refused regardless. Cross-scope copies are supported —
     * both scope roots are resolved independently.
     *
     * Order (fail-closed): region check → source resolve/containment → destination
     * resolve/containment → conflict pre-check → destination-scope quota pre-check (no temp on
     * rejection) → streaming copy + hash → atomic publish (the source is never held whole in
     * memory — a quota-sized file would OOM a full read). The source is never modified.
     * @throws FileNotFoundException when [src] does not exist or is not a regular file.
     * @throws FileAlreadyExistsException when [dst] exists and [overwrite] is false, or [dst]
     *   is a directory.
     * @throws WorkspaceQuota.QuotaExceeded when the destination scope cannot admit the bytes.
     */
    fun copyFile(
        src: FileScopePath,
        dst: FileScopePath,
        region: String,
        overwrite: Boolean,
    ): CopyMoveOutcome {
        require(WorkspaceLayout.isRegion(region)) { "destination region must be one of ${WorkspaceLayout.regions}" }
        require(WorkspaceLayout.regionOf(dst.relativePath) == region) {
            "destination must stay inside the $region region"
        }
        val srcRoot = resolve(src.scopeId)
        val dstRoot = resolve(dst.scopeId)
        val source = resolveContained(src, srcRoot)
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw FileNotFoundException("source not found: ${src.toModelReference()}")
        }
        val target = resolveContained(dst, dstRoot)
        ensureWritableTarget(target, overwrite)
        val overwritten = Files.exists(target)
        val size = Files.size(source)
        WorkspaceQuota.ensureRoom(dstRoot, size, quotaPolicy.maxWorkspaceBytes)
        val sha = streamCopyAndHash(source, target)
        return CopyMoveOutcome(dst.relativePath, size, sha, WorkspaceQuota.usageBytes(dstRoot), overwritten)
    }

    /**
     * Moves the regular file at [src] to [dst] (HXA-043 `files.move`). Same conflict and region
     * rules as [copyFile]. A same-scope move is a single (preferred-atomic) rename — no quota
     * check is needed because the scope's aggregate usage can only shrink. A cross-scope move is
     * copy-then-delete and is NOT atomic: the destination is quota-checked and fully published
     * before the source is deleted, so a failure in between leaves the source intact (a
     * destination temp may remain, reclaimed by [reclaimTempFiles]).
     * @throws FileNotFoundException when [src] does not exist or is not a regular file.
     * @throws FileAlreadyExistsException when [dst] exists and [overwrite] is false, or [dst]
     *   is a directory.
     * @throws WorkspaceQuota.QuotaExceeded for a cross-scope move the destination scope cannot
     *   admit (the source is left untouched).
     */
    @Suppress("SwallowedException") // same-scope fallback: non-atomic move on filesystems without ATOMIC_MOVE
    fun moveFile(
        src: FileScopePath,
        dst: FileScopePath,
        region: String,
        overwrite: Boolean,
    ): CopyMoveOutcome {
        require(WorkspaceLayout.isRegion(region)) { "destination region must be one of ${WorkspaceLayout.regions}" }
        require(WorkspaceLayout.regionOf(dst.relativePath) == region) {
            "destination must stay inside the $region region"
        }
        val srcRoot = resolve(src.scopeId)
        val dstRoot = resolve(dst.scopeId)
        val source = resolveContained(src, srcRoot)
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw FileNotFoundException("source not found: ${src.toModelReference()}")
        }
        val target = resolveContained(dst, dstRoot)
        ensureWritableTarget(target, overwrite)
        val overwritten = Files.exists(target)
        val size = Files.size(source)
        if (srcRoot == dstRoot) {
            val sha = AtomicFileWriter.sha256Hex(source)
            if (overwritten) {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } else {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(source, target)
                }
            }
            return CopyMoveOutcome(dst.relativePath, size, sha, WorkspaceQuota.usageBytes(dstRoot), overwritten)
        }
        // Cross-scope: publish into the destination scope (quota-gated) before the source is
        // deleted, so the source can never be lost to an admission failure.
        WorkspaceQuota.ensureRoom(dstRoot, size, quotaPolicy.maxWorkspaceBytes)
        val sha = streamCopyAndHash(source, target)
        Files.delete(source)
        return CopyMoveOutcome(dst.relativePath, size, sha, WorkspaceQuota.usageBytes(dstRoot), overwritten)
    }

    /**
     * Moves the regular file at [path] into the scope's `.helix/trash/` (HXA-043 `files.delete`)
     * as a single rename: the file's bytes and size are unchanged, the scope's aggregate usage
     * is unchanged (the trash lives inside the scope), and the original path is gone.
     *
     * The trash entry name is `<epochMillis>-<8-hex>__<encoded original relative path>`: the
     * encoded path is reversible (only `%` and `/` are escaped), so [restoreFromTrash] can find
     * the original location without any sidecar metadata, and the timestamp+id prefix makes a
     * name collision effectively impossible — a collision is still re-drawn, never clobbered.
     * @throws FileNotFoundException when [path] does not exist or is not a regular file.
     */
    fun moveToTrash(path: FileScopePath): TrashEntry {
        val root = resolve(path.scopeId)
        val source = resolveContained(path, root)
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw FileNotFoundException("not a regular file: ${path.toModelReference()}")
        }
        val size = Files.size(source)
        val sha = AtomicFileWriter.sha256Hex(source)
        val entryDir = PathResolution.join(root, WorkspaceLayout.TRASH)
        val entryName = uniqueTrashEntryName(entryDir, path.relativePath)
        Files.move(source, entryDir.resolve(entryName), StandardCopyOption.ATOMIC_MOVE)
        return TrashEntry(path.relativePath, entryName, size, sha)
    }

    /**
     * Restores a trash entry to its ORIGINAL relative path (HXA-043; the counterpart to
     * [moveToTrash], deliberately a SEPARATE operation from [purgeTrashEntry]). Fails closed
     * when the original path is currently occupied (the entry stays in the trash) or when the
     * referenced path is not a well-formed trash entry.
     * @throws FileNotFoundException when the trash entry does not exist.
     * @throws FileAlreadyExistsException when the original path is occupied.
     * @throws IllegalArgumentException when the reference is not a trash entry.
     */
    fun restoreFromTrash(trashRef: FileScopePath): TrashRestoreOutcome {
        require(isTrashReference(trashRef.relativePath)) { "path is not a trash entry" }
        val root = resolve(trashRef.scopeId)
        val entry = resolveContained(trashRef, root)
        if (!Files.exists(entry) || !Files.isRegularFile(entry)) {
            throw FileNotFoundException("trash entry not found: ${trashRef.toModelReference()}")
        }
        val originalRel = decodeTrashEntryName(entry.fileName.toString())
        val original = resolveContained(FileScopePath(trashRef.scopeId, originalRel), root)
        if (Files.exists(original)) {
            throw java.nio.file.FileAlreadyExistsException("original location is occupied: $originalRel")
        }
        original.parent?.let { Files.createDirectories(it) }
        Files.move(entry, original, StandardCopyOption.ATOMIC_MOVE)
        return TrashRestoreOutcome(originalRel, WorkspaceQuota.usageBytes(root))
    }

    /**
     * Permanently deletes ONE trash entry (HXA-043) — the physical-empty half, deliberately
     * separate from [restoreFromTrash]. Only well-formed trash entries under `.helix/trash/`
     * are purgable; anything else is refused, so this can never reach a user-region file.
     * @throws FileNotFoundException when the trash entry does not exist.
     * @throws IllegalArgumentException when the reference is not a trash entry.
     */
    fun purgeTrashEntry(trashRef: FileScopePath): PurgeOutcome {
        require(isTrashReference(trashRef.relativePath)) { "path is not a trash entry" }
        val root = resolve(trashRef.scopeId)
        val entry = resolveContained(trashRef, root)
        if (!Files.exists(entry) || !Files.isRegularFile(entry)) {
            throw FileNotFoundException("trash entry not found: ${trashRef.toModelReference()}")
        }
        Files.delete(entry)
        return PurgeOutcome(trashRef.relativePath, WorkspaceQuota.usageBytes(root))
    }

    /** Fail-closed conflict pre-check for [copyFile]/[moveFile]: a directory is never a target. */
    private fun ensureWritableTarget(
        target: Path,
        overwrite: Boolean,
    ) {
        if (Files.isDirectory(target)) {
            throw java.nio.file.FileAlreadyExistsException("target is a directory")
        }
        if (Files.exists(target) && !overwrite) {
            throw java.nio.file.FileAlreadyExistsException("target already exists")
        }
    }

    private fun uniqueTrashEntryName(
        entryDir: Path,
        originalRelativePath: String,
    ): String {
        repeat(4) {
            val name =
                "${System.currentTimeMillis()}-${UUID.randomUUID().toString().replace("-", "").take(8)}__" +
                    encodeTrashPath(originalRelativePath)
            if (!Files.exists(entryDir.resolve(name))) return name
        }
        error("unable to allocate a unique trash entry name")
    }

    /** Reversible encoding for a trash entry name: escapes `%` then `/` (the layout separator). */
    private fun encodeTrashPath(relativePath: String): String = relativePath.replace("%", "%25").replace("/", "%2F")

    /** The exact inverse of [encodeTrashPath]; an unrecognized escape is a malformed entry. */
    private fun decodeTrashPath(encoded: String): String {
        val out = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%') {
                require(i + 2 < encoded.length) { "malformed trash entry name" }
                val escape = encoded.substring(i + 1, i + 3)
                require(escape == "25" || escape == "2F") { "malformed trash entry name" }
                out.append(if (escape == "25") '%' else '/')
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Parses a trash entry name back to its original relative path. */
    private fun decodeTrashEntryName(entryName: String): String {
        val match =
            TRASH_ENTRY_NAME.matchEntire(entryName)
                ?: throw IllegalArgumentException("malformed trash entry name")
        return decodeTrashPath(match.groupValues[3])
    }

    /** True for a path pointing directly at an entry inside `.helix/trash/`. */
    private fun isTrashReference(relativePath: String): Boolean {
        if (!relativePath.startsWith(WorkspaceLayout.TRASH + "/")) return false
        val name = relativePath.removePrefix(WorkspaceLayout.TRASH + "/")
        return name.isNotEmpty() && !name.contains('/') && TRASH_ENTRY_NAME.matches(name)
    }

    private fun resolve(scope: String): Path = rootResolver.resolveRoot(scope)

    private fun resolveContained(
        path: FileScopePath,
        root: Path,
    ): Path = PathResolution.resolveWithinRoot(root, PathResolution.join(root, path.relativePath), linkPolicy)

    /**
     * Streams [source] into [target] through the writer's temp+fsync+rename path, hashing on the
     * way, so a quota-sized file is never held whole in memory (a full `readAllBytes` of a
     * 1 GiB file would OOM the process; the disk quota does not bound heap allocation).
     * Any failure deletes the temp and publishes nothing.
     * @return the SHA-256 (hex) of the bytes as written.
     */
    private fun streamCopyAndHash(
        source: Path,
        target: Path,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        AtomicFileWriter.writeAtomicStream(target) { out ->
            Files.newInputStream(source).use { input ->
                val buffer = ByteArray(COPY_CHUNK_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun newArtifactId(): String = "art_" + UUID.randomUUID().toString().replace("-", "")

    companion object {
        /** Hard ceiling on `files.search` matches returned in one call. */
        const val MAX_SEARCH_RESULTS: Int = 512

        /** Hard ceiling on total entries walked by one `files.search` call. */
        const val MAX_SEARCH_SCAN: Int = 20_000

        /** Copy chunk for [streamCopyAndHash] (a whole file never sits in memory at once). */
        const val COPY_CHUNK_SIZE: Int = 64 * 1024

        /**
         * A trash entry name (HXA-043): `<13-digit epoch millis>-<8 hex>__<encoded original
         * relative path>`. The prefix is allocation metadata; the encoded suffix is reversible
         * ([decodeTrashPath]) so a restore needs no sidecar record.
         */
        val TRASH_ENTRY_NAME: Regex = Regex("""^(\d{13})-([0-9a-f]{8})__(.+)$""")
    }
}

/**
 * Bounded depth-first name walk used by [WorkspaceArtifactStore.search]: consumes [it] and stops
 * after [maxScan] entries or [maxResults] matches, whichever comes first. Reports
 * [SearchResult.truncated] when a cap stopped the walk while further entries (and possibly
 * matches) remain. The walked paths are relativized against [baseDir] by the caller's prefix; a
 * match whose name is not a legal [FileScopePath] is skipped so the search never aborts on it.
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
        val ref = candidateRef(p, baseDir, scopeId, prefix, needleLower)
        if (ref != null) {
            matches += ref
            if (matches.size >= maxResults) {
                truncated = true
                break
            }
        }
    }
    if (!truncated && it.hasNext()) truncated = true
    return SearchResult(matches, truncated)
}

/**
 * The [FileScopePath] for [p] when its name is a case-insensitive match for [needleLower], else
 * null. A match whose reference is not a legal [FileScopePath] (a C0/C1 control character in a
 * segment, or over the canonical length bound) is unaddressable and also reports null: it is
 * skipped so the remaining valid matches survive (fail-safe; the consumer scope never yields such
 * names, so this guards only a hostile all-files scope).
 */
@Suppress("SwallowedException") // an unaddressable match is skipped, not a bug
private fun candidateRef(
    p: Path,
    baseDir: Path,
    scopeId: String,
    prefix: String,
    needleLower: String,
): FileScopePath? {
    val isMatch = p != baseDir && p.fileName.toString().contains(needleLower, ignoreCase = true)
    if (!isMatch) return null
    return try {
        FileScopePath(scopeId, prefix + baseDir.relativize(p).joinToString("/"))
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Outcome of [WorkspaceArtifactStore.stat] (HXA-042 `files.stat`). [mtimeEpochMillis] is the
 * last-modified time (epoch ms), or -1 when the path does not exist (HXA-046: the file manager's
 * time sort and the doc 09 `files.stat` mtime field).
 */
data class StatInfo(
    val exists: Boolean,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val isSymlink: Boolean,
    val mtimeEpochMillis: Long,
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

/** Outcome of [WorkspaceArtifactStore.copyFile] / [moveFile] (HXA-043). */
data class CopyMoveOutcome(
    val destinationRelativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val usageBytesAfter: Long,
    val overwritten: Boolean,
)

/** A file moved into Helix trash (HXA-043): the original location is restorable. */
data class TrashEntry(
    val originalRelativePath: String,
    val trashName: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Outcome of [WorkspaceArtifactStore.restoreFromTrash] (HXA-043). */
data class TrashRestoreOutcome(
    val restoredRelativePath: String,
    val usageBytesAfter: Long,
)

/** Outcome of [WorkspaceArtifactStore.purgeTrashEntry] (HXA-043). */
data class PurgeOutcome(
    val purgedRelativePath: String,
    val usageBytesAfter: Long,
)
