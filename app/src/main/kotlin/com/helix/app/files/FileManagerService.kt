package com.helix.app.files

import com.helix.app.R
import com.helix.app.allfiles.AllFilesModule
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.core.workspace.resolveFileScopePath
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafTreeScopeAccess
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest

/**
 * The app's user-facing file facade (HXA-046 文件管理 UI). This is the file manager's execution
 * seam — deliberately NOT the model tool pipeline: the user drives it directly, so there is no
 * per-call approval gate here (doc 09 section 4.3 approvals bind *model* ToolCalls, not the user
 * operating their own files). It wraps [WorkspaceArtifactStore] — the same nio, containment-
 * enforced, atomic-publish store the `files.*` tools use — with the browse / sort / preview /
 * mutate / trash shapes the UI needs.
 *
 * The class is **nio-pure on purpose**: its logic is unit-testable on the JVM (a temp-dir store,
 * exactly like the store's own tests). The Android-only concerns — SAF document pickers, image
 * decode into a [android.graphics.ImageBitmap], and the share `FileProvider` URI — live in the
 * Compose layer, which owns the [android.content.Context]. [realFileFor] is the one method that
 * hands back a real [File]: it exists solely so the share action can mint a `content://` URI (an
 * OS-level handoff to another app). That [File] is consumed transiently by the share flow and is
 * never rendered as text, logged, or placed into model context (doc 10 constrains the model-side
 * surfaces, not an OS share).
 *
 * Sources: the app-private [workspaceScopeId] is always present and fully mutable. The developer
 * flavor's enabled all-files roots are appended read-only in this milestone — their layout is not
 * a workspace region (`input/`/`work/`/`output/`), so the store's region-gated mutations refuse
 * them (see [moveOrCopy]); browse / sort / preview / share all work.
 */
@Suppress("TooManyFunctions", "LargeClass") // one cohesive store facade: splitting moves size, not coupling
class FileManagerService(
    private val store: WorkspaceArtifactStore,
    private val roots: ScopeRootResolver,
    private val workspaceScopeId: String,
    // HXA-057: the governed SAF tree scope access (browse/preview/share, read-only). Null only in
    // tests that do not exercise SAF scopes; a SAF scope id with a null access fails closed.
    private val saf: SafTreeScopeAccess? = null,
    // HXA-058: the restricted HXA-044 import/export pipelines + platform seams (the file
    // manager's 导入/导出 entries). Null only in tests that do not exercise transfers; a transfer
    // call with a null access fails closed.
    private val transfers: SafImportExportAccess? = null,
    // HXA-069: localizes the STABLE string-resource ids this facade emits (user-visible
    // status/detail texts) to the CURRENT locale at emit time; the production site passes the
    // app Context's getString. The JVM default (no Context in unit tests) resolves the id itself,
    // keeping the pure-JVM seam testable without an Android runtime.
    private val strings: (Int, Array<out Any>) -> String = { id, _ -> id.toString() },
) {
    /** Localizes a stable string-resource id (+ positional args) to the current locale (HXA-069). */
    private fun loc(
        id: Int,
        vararg args: Any,
    ): String = strings(id, args)

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

    // --- 导入/导出 (HXA-058: 文件管理导入/导出入口) ---
    //
    // Explicit FILE-MANAGEMENT actions driven by the user's picker results: they reuse the
    // HXA-044 restricted pipelines (fail-closed, lying-provider-proof) and NEVER create a chat
    // message, call a Provider, or expand the Agent scope (no artifact registration, no session
    // binding, no grant persistence on import). A null [transfers] access fails closed.

    private val transferOps: FileManagerTransfers? =
        transfers?.let { FileManagerTransfers(store, workspaceScopeId, saf, it, strings) }

    /**
     * Imports ONE picked SAF document (the `ACTION_OPEN_DOCUMENT` result) into the workspace
     * `input/` region under [policy] (never a default overwrite). The user-facing contract
     * (source / target / name / size / policy / progress / cancel / final result) is surfaced
     * through the returned [TransferResult] + [onProgress].
     */
    fun importSingleDocument(
        sourceUri: String,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onProgress: (
            Long,
            Long,
        ) -> Unit,
    ): TransferResult =
        transferOps?.importSingleDocument(sourceUri, policy, cancel, onProgress)
            ?: TransferResult(
                listOf(
                    TransferItem(
                        loc(R.string.files_source_saf_document),
                        "Workspace input/",
                        TransferItemStatus.FAILED,
                        loc(R.string.files_transfer_not_wired),
                    ),
                ),
                0,
            )

    /**
     * Imports a WHOLE picked SAF folder (the `ACTION_OPEN_DOCUMENT_TREE` result) into the
     * workspace `input/` (structure recreated under `input/`). Bounded enumeration + fail-closed
     * name mapping; one [onFileProgress] tick (done, total) before each file; every skipped or
     * failed file is reported in the result — nothing is silently omitted.
     */
    fun importTree(
        treeUri: String,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onFileProgress: (
            Int,
            Int,
        ) -> Unit,
    ): TransferResult =
        transferOps?.importTree(treeUri, policy, cancel, onFileProgress)
            ?: TransferResult(
                listOf(
                    TransferItem(
                        loc(R.string.files_source_saf_folder),
                        "Workspace input/",
                        TransferItemStatus.FAILED,
                        loc(R.string.files_transfer_not_wired),
                    ),
                ),
                0,
            )

    /**
     * Exports ONE workspace file to [target] (an `ACTION_CREATE_DOCUMENT` document or an
     * authorized SAF tree directory) under [policy]. An export to a tree re-verifies the grant in
     * WRITE mode in real time (a read-only or revoked grant fails closed before any byte is
     * written). The result reports the platform-confirmed facts, and "verified" only when the
     * bytes are re-read after the write and are hash-equal.
     */
    fun exportDocument(
        sourceRelativePath: String,
        target: ExportTarget,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onProgress: (
            Long,
            Long,
        ) -> Unit,
    ): TransferResult =
        transferOps?.exportDocument(sourceRelativePath, target, policy, cancel, onProgress)
            ?: TransferResult(
                listOf(
                    TransferItem(
                        sourceRelativePath,
                        loc(R.string.files_source_saf_target),
                        TransferItemStatus.FAILED,
                        loc(R.string.files_transfer_not_wired),
                    ),
                ),
                0,
            )
    // --- Sources (来源标识) ---

    /**
     * The browsable sources (HXA-046 + HXA-057): the workspace (always, mutable) + any enabled
     * all-files roots (developer, read-only) + any SAF tree scopes STILL LIVE right now (read-only).
     * A SAF grant whose provider no longer answers / whose root changed is re-verified here and
     * omitted (fail closed: a source the resolver cannot resolve is never offered for browsing).
     */
    fun sources(): List<FileSource> {
        val list =
            mutableListOf(
                FileSource(workspaceScopeId, "Workspace", FileSourceKind.WORKSPACE, supportsMutation = true),
            )
        if (AllFilesModule.AVAILABLE) {
            AllFilesModule.allFilesSources().forEach {
                list.add(FileSource(it.scopeId, it.displayName, FileSourceKind.ALL_FILES, supportsMutation = false))
            }
        }
        saf?.service?.liveSources()?.forEach {
            list.add(FileSource(it.scopeId, it.displayName, FileSourceKind.SAF, supportsMutation = false))
        }
        return list
    }

    // --- Browse / sort (路径 + 排序) ---

    /**
     * Lists [relativePath]'s immediate children with per-entry metadata (size + mtime via
     * [WorkspaceArtifactStore.stat]) and sorts by [sort]. At the workspace root the `.helix/`
     * internals (metadata + trash) are hidden — the trash has its own dedicated panel. Directories
     * sort before files in every mode.
     *
     * @throws java.io.FileNotFoundException when [relativePath] is not an existing directory.
     * @throws com.helix.core.workspace.ScopeNotAvailable when the scope cannot be resolved.
     */
    fun list(
        scopeId: String,
        relativePath: String,
        sort: SortKey = SortKey.NAME,
    ): List<FileEntry> {
        if (isSaf(scopeId)) return safList(scopeId, relativePath, sort)
        val names = store.listDir(FileScopePath(scopeId, relativePath), MAX_LIST_ENTRIES).entries
        val atWorkspaceRoot = scopeId == workspaceScopeId && relativePath.isEmpty()
        val visible = if (atWorkspaceRoot) names.filter { it != WorkspaceLayout.HELIX } else names
        val entries =
            visible.map { name ->
                val rel = joinPath(relativePath, name)
                val s = store.stat(FileScopePath(scopeId, rel))
                FileEntry(name, rel, s.isDirectory, s.sizeBytes, s.mtimeEpochMillis)
            }
        return entries.sortedWith(comparatorFor(sort))
    }

    /**
     * SAF tree directory listing (HXA-057): the grant is re-verified in real time (fail closed),
     * then the read-only backend enumerates the directory. The model/UI see only display names +
     * bounded metadata — no document id, no `content://` URI (doc 10).
     */
    private fun safList(
        scopeId: String,
        relativePath: String,
        sort: SortKey,
    ): List<FileEntry> {
        val access = verifySaf(scopeId, SafAccessMode.READ)
        val entries =
            access
                .reader
                .list(scopeId, relativePath)
                .map { e ->
                    FileEntry(e.name, joinPath(relativePath, e.name), e.isDirectory, e.sizeBytes, e.mtimeEpochMillis)
                }
        return entries.sortedWith(comparatorFor(sort))
    }

    /** A browsable entry with the metadata the UI shows (HXA-046: 名称/时间/大小排序). */
    data class FileEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val mtimeEpochMillis: Long,
    )

    private fun comparatorFor(sort: SortKey): Comparator<FileEntry> {
        val secondary =
            when (sort) {
                SortKey.NAME -> compareBy<FileEntry>({ it.name.lowercase() }, FileEntry::name)
                SortKey.TIME -> compareByDescending<FileEntry> { it.mtimeEpochMillis }
                SortKey.SIZE -> compareByDescending<FileEntry> { it.sizeBytes }
            }
        return compareByDescending<FileEntry> { it.isDirectory }.thenComparing(secondary)
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
    ): FileMeta {
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
        return FileMeta(s.sizeBytes, s.mtimeEpochMillis, probe.mimeType, probe.isText, sha, omitted)
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
    ): FileMeta {
        val access = verifySaf(scopeId, SafAccessMode.READ)
        val stat = access.reader.stat(scopeId, relativePath)
        if (!stat.exists || stat.isDirectory) {
            return if (!stat.exists) {
                FileMeta(-1L, -1L, "application/octet-stream", false, null, false)
            } else {
                FileMeta(stat.sizeBytes, stat.mtimeEpochMillis, "inode/directory", true, null, true)
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
        return FileMeta(stat.sizeBytes, stat.mtimeEpochMillis, probe.mimeType, probe.isText, sha, omitted)
    }

    data class FileMeta(
        val sizeBytes: Long,
        val mtimeEpochMillis: Long,
        val mimeType: String,
        val isText: Boolean,
        val sha256: String?,
        val hashOmittedBecauseTooLarge: Boolean,
    )

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

    // --- Mutations (rename / copy / move, explicit conflict, NO default overwrite) ---

    /**
     * The outcome of a single rename/copy/move. [Conflict] means the destination exists and
     * [overwrite] was false — the caller (the UI) then asks the user for the policy (询问/跳过/
     * 重命名/覆盖); the store never overwrites by default (doc 09 section 4.2: 禁止默认覆盖).
     */
    sealed class FileOpResult {
        data class Ok(
            val destinationRelativePath: String,
            val overwritten: Boolean,
        ) : FileOpResult()

        /** The destination already exists; the user must choose how to proceed. */
        data object Conflict : FileOpResult()

        data class NotFound(
            val message: String,
        ) : FileOpResult()

        data class Error(
            val message: String,
        ) : FileOpResult()
    }

    /** Renames [srcRel] to [newRel] (a same-scope move). Refuses an existing [newRel] unless [overwrite]. */
    fun rename(
        scopeId: String,
        srcRel: String,
        newRel: String,
        overwrite: Boolean,
    ): FileOpResult = moveOrCopy(scopeId, srcRel, newRel, overwrite, move = true)

    /** Copies [srcRel] to [dstRel]. Refuses an existing [dstRel] unless [overwrite]. */
    fun copy(
        scopeId: String,
        srcRel: String,
        dstRel: String,
        overwrite: Boolean,
    ): FileOpResult = moveOrCopy(scopeId, srcRel, dstRel, overwrite, move = false)

    /**
     * One rename/move/copy through the store. The destination region is derived from the
     * destination path and MUST be a user region — this is what keeps all-files scopes (whose
     * relative paths are not workspace regions) read-only in this milestone: a non-region
     * destination fails closed to [FileOpResult.Error] rather than touching a file.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    private fun moveOrCopy(
        scopeId: String,
        srcRel: String,
        dstRel: String,
        overwrite: Boolean,
        move: Boolean,
    ): FileOpResult {
        // HXA-057: SAF tree scopes are read-only in this milestone (the all-files precedent). The
        // UI hides these actions for SAF sources; this guard is defense-in-depth so a direct call
        // on a SAF scope fails closed rather than touching an external document (fail closed, never
        // a false success).
        if (isSaf(scopeId)) return FileOpResult.Error(loc(R.string.files_saf_read_only))
        val src = FileScopePath(scopeId, srcRel)
        val dst = FileScopePath(scopeId, dstRel)
        return try {
            val region =
                requireNotNull(WorkspaceLayout.regionOf(dstRel)) { loc(R.string.files_error_region_required) }
            require(WorkspaceLayout.isRegion(region)) { loc(R.string.files_error_region_required) }
            val out =
                if (move) {
                    store.moveFile(
                        src,
                        dst,
                        region,
                        overwrite,
                    )
                } else {
                    store.copyFile(src, dst, region, overwrite)
                }
            FileOpResult.Ok(out.destinationRelativePath, out.overwritten)
        } catch (e: FileAlreadyExistsException) {
            FileOpResult.Conflict
        } catch (e: FileNotFoundException) {
            FileOpResult.NotFound(loc(R.string.files_error_source_missing))
        } catch (e: IllegalArgumentException) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_invalid_operation))
        } catch (e: Exception) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_operation_failed))
        }
    }

    /** Creates a directory [name] under [parentRel] (inside a user region). Refuses an existing path. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun makeDirectory(
        scopeId: String,
        parentRel: String,
        name: String,
    ): FileOpResult {
        if (isSaf(scopeId)) return FileOpResult.Error(loc(R.string.files_saf_read_only))
        val rel = joinPath(parentRel, name)
        val region = WorkspaceLayout.regionOf(rel)
        return try {
            store.mkdir(FileScopePath(scopeId, rel), region)
            FileOpResult.Ok(rel, overwritten = false)
        } catch (e: FileAlreadyExistsException) {
            FileOpResult.Conflict
        } catch (e: Exception) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_mkdir_failed))
        }
    }

    /**
     * A non-conflicting sibling of [baseRel] in the same directory: `name (1).ext`, `name (2).ext`,
     * … the "重命名" conflict policy's auto-suffix.
     */
    fun nextAvailableName(
        scopeId: String,
        baseRel: String,
    ): String {
        val dir = baseRel.substringBeforeLast('/')
        val name = baseRel.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = joinPath(dir, "$stem ($i)$ext")
            if (!store.stat(FileScopePath(scopeId, candidate)).exists) return candidate
            i++
        }
    }

    // --- Trash (删除到回收站 / 恢复 / 永久删除 / 清空) ---

    /** Moves the regular file at [relativePath] into the scope's trash (restorable). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun trash(
        scopeId: String,
        relativePath: String,
    ): FileOpResult {
        if (isSaf(scopeId)) return FileOpResult.Error(loc(R.string.files_saf_read_only))
        val fsp = FileScopePath(scopeId, relativePath)
        return try {
            store.moveToTrash(fsp)
            FileOpResult.Ok(relativePath, overwritten = false)
        } catch (e: FileNotFoundException) {
            FileOpResult.NotFound(loc(R.string.files_error_file_missing))
        } catch (e: Exception) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_trash_failed))
        }
    }

    /** One trash entry: the stored entry name + the decoded original path (for display/restore). */
    data class TrashEntryView(
        val entryName: String,
        val originalRelativePath: String,
        val sizeBytes: Long,
    )

    /** The current trash contents, newest storage order; empty when there is none. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed empty list
    fun listTrash(scopeId: String): List<TrashEntryView> {
        val trashDir = FileScopePath(scopeId, WorkspaceLayout.TRASH)
        return try {
            store
                .listDir(trashDir, MAX_LIST_ENTRIES)
                .entries
                .mapNotNull { entryName ->
                    val original = decodeTrashEntryName(entryName) ?: return@mapNotNull null
                    val s = store.stat(FileScopePath(scopeId, joinPath(WorkspaceLayout.TRASH, entryName)))
                    TrashEntryView(entryName, original, s.sizeBytes)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Restores a trash entry to its original path. [FileOpResult.Conflict] when the original path
     * is now occupied (the entry stays in the trash) — the user resolves it (skip/rename/overwrite).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun restore(
        scopeId: String,
        entryName: String,
    ): FileOpResult {
        val ref = FileScopePath(scopeId, joinPath(WorkspaceLayout.TRASH, entryName))
        return try {
            val out = store.restoreFromTrash(ref)
            FileOpResult.Ok(out.restoredRelativePath, overwritten = false)
        } catch (e: FileAlreadyExistsException) {
            FileOpResult.Conflict
        } catch (e: FileNotFoundException) {
            FileOpResult.NotFound(loc(R.string.files_error_trash_entry_missing))
        } catch (e: Exception) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_restore_failed))
        }
    }

    /** Permanently deletes ONE trash entry (the physical-empty half). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun purge(
        scopeId: String,
        entryName: String,
    ): FileOpResult {
        val ref = FileScopePath(scopeId, joinPath(WorkspaceLayout.TRASH, entryName))
        return try {
            val out = store.purgeTrashEntry(ref)
            FileOpResult.Ok(out.purgedRelativePath, overwritten = false)
        } catch (e: FileNotFoundException) {
            FileOpResult.NotFound(loc(R.string.files_error_trash_entry_missing))
        } catch (e: Exception) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_purge_failed))
        }
    }

    /** Permanently deletes every trash entry; @return the number of entries purged. */
    fun emptyTrash(scopeId: String): Int =
        listTrash(scopeId)
            .also { entries -> entries.forEach { purge(scopeId, it.entryName) } }
            .size

    // --- Batch (多选) with a conflict policy + partial-failure list ---

    /**
     * Applies [policy] per item to a multi-select copy/move. [ASK] is the single-item interactive
     * policy (the UI dialog); inside a batch it fails closed to [BatchItem.Outcome.SKIPPED] so the
     * user still gets a 部分失败清单 rather than a silent overwrite (禁止默认覆盖).
     *
     * [progress] is invoked after each item (done, total) so the UI can show a 长操作进度 bar;
     * [shouldCancel] is checked before each item — once it returns true the remaining items are
     * recorded as [BatchItem.Outcome.SKIPPED] ("已取消") and the batch stops, giving a real cancel
     * for a multi-item operation. The completed items are not rolled back (each is an independent
     * atomic store op); the partial-failure list reports exactly what happened.
     */
    fun batchMoveOrCopy(
        scopeId: String,
        sources: List<String>,
        destinationDir: String,
        policy: ConflictPolicy,
        move: Boolean,
        progress: (
            done: Int,
            total: Int,
        ) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): BatchResult {
        val total = sources.size
        val items =
            sources.mapIndexed { index, srcRel ->
                val item =
                    if (shouldCancel()) {
                        BatchItem(srcRel, BatchItem.Outcome.SKIPPED, loc(R.string.files_detail_cancelled))
                    } else {
                        processBatchItem(scopeId, srcRel, destinationDir, policy, move)
                    }
                progress(index + 1, total)
                item
            }
        return BatchResult(items)
    }

    /** One batched item under [policy]: the destination is the folder + the item's own base name. */
    private fun processBatchItem(
        scopeId: String,
        srcRel: String,
        destinationDir: String,
        policy: ConflictPolicy,
        move: Boolean,
    ): BatchItem {
        val dstRel = joinPath(destinationDir, srcRel.substringAfterLast('/'))
        return when (policy) {
            ConflictPolicy.OVERWRITE -> {
                mapItem(srcRel, moveOrCopy(scopeId, srcRel, dstRel, overwrite = true, move))
            }

            ConflictPolicy.RENAME -> {
                val first = moveOrCopy(scopeId, srcRel, dstRel, overwrite = false, move)
                if (first is FileOpResult.Conflict) {
                    val renamed = nextAvailableName(scopeId, dstRel)
                    val second = moveOrCopy(scopeId, srcRel, renamed, overwrite = false, move)
                    if (second is FileOpResult.Ok) {
                        BatchItem(srcRel, BatchItem.Outcome.RENAMED, second.destinationRelativePath)
                    } else {
                        mapItem(srcRel, second)
                    }
                } else {
                    mapItem(srcRel, first)
                }
            }

            // ASK (batch) and SKIP: try without overwrite; a conflict is reported, never clobbered.
            ConflictPolicy.ASK,
            ConflictPolicy.SKIP,
            -> {
                mapItem(
                    srcRel,
                    moveOrCopy(scopeId, srcRel, dstRel, overwrite = false, move),
                    conflictIsSkipped = true,
                )
            }
        }
    }

    /** One batched item's outcome. [detail] is the destination (on success/rename) or the reason. */
    data class BatchItem(
        val sourceRelativePath: String,
        val outcome: Outcome,
        val detail: String,
    ) {
        enum class Outcome { SUCCEEDED, RENAMED, SKIPPED, FAILED }
    }

    data class BatchResult(
        val items: List<BatchItem>,
    ) {
        val succeeded: List<BatchItem> get() =
            items.filter { it.outcome == BatchItem.Outcome.SUCCEEDED || it.outcome == BatchItem.Outcome.RENAMED }

        /** The 部分失败清单: skipped (conflict) + failed items. */
        val failures: List<BatchItem> get() =
            items.filter { it.outcome == BatchItem.Outcome.SKIPPED || it.outcome == BatchItem.Outcome.FAILED }
    }

    private fun mapItem(
        srcRel: String,
        result: FileOpResult,
        conflictIsSkipped: Boolean = false,
    ): BatchItem =
        when (result) {
            is FileOpResult.Ok -> {
                BatchItem(srcRel, BatchItem.Outcome.SUCCEEDED, result.destinationRelativePath)
            }

            FileOpResult.Conflict -> {
                BatchItem(
                    srcRel,
                    if (conflictIsSkipped) BatchItem.Outcome.SKIPPED else BatchItem.Outcome.FAILED,
                    loc(R.string.files_error_destination_exists),
                )
            }

            is FileOpResult.NotFound -> {
                BatchItem(srcRel, BatchItem.Outcome.FAILED, result.message)
            }

            is FileOpResult.Error -> {
                BatchItem(srcRel, BatchItem.Outcome.FAILED, result.message)
            }
        }

    /**
     * Deletes a multi-select of regular files into the trash (restorable), one at a time with the
     * same 长操作进度 ([progress]) and cooperative [shouldCancel] as [batchMoveOrCopy]. A
     * non-file (a directory) or a missing path is a [BatchItem.Outcome.FAILED] item, never a throw.
     */
    fun batchTrash(
        scopeId: String,
        relativePaths: List<String>,
        progress: (
            done: Int,
            total: Int,
        ) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): BatchResult {
        val total = relativePaths.size
        val items =
            relativePaths.mapIndexed { index, rel ->
                val item =
                    if (shouldCancel()) {
                        BatchItem(rel, BatchItem.Outcome.SKIPPED, loc(R.string.files_detail_cancelled))
                    } else {
                        mapItem(rel, trash(scopeId, rel))
                    }
                progress(index + 1, total)
                item
            }
        return BatchResult(items)
    }

    // --- Trash-entry name decoding (the exact inverse of the store's `encodeTrashPath`) ---

    private fun decodeTrashEntryName(entryName: String): String? {
        val match = WorkspaceArtifactStore.TRASH_ENTRY_NAME.matchEntire(entryName) ?: return null
        return decodeTrashPath(match.groupValues[3])
    }

    private fun decodeTrashPath(encoded: String): String? {
        val out = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            val decoded =
                when {
                    i + 2 >= encoded.length -> null
                    else -> decodeEscape(encoded, i)
                } ?: return null
            out.append(decoded)
            i += 3
        }
        return out.toString()
    }

    private fun decodeEscape(
        encoded: String,
        at: Int,
    ): Char? =
        when (encoded.substring(at + 1, at + 3)) {
            "25" -> '%'
            "2F" -> '/'
            else -> null
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun joinPath(
        dir: String,
        name: String,
    ): String = if (dir.isEmpty()) name else "$dir/$name"

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

/** The access source a browsable entry belongs to (HXA-046: 显示当前访问来源). */
enum class FileSourceKind {
    WORKSPACE,
    ALL_FILES,
    SAF,
}

/**
 * A browsable source shown in the file manager's source selector. [supportsMutation] is false for
 * all-files roots in this milestone (their layout is not a workspace region), so the UI hides
 * rename/copy/move/trash for them but keeps browse/sort/preview/share.
 */
data class FileSource(
    val scopeId: String,
    val displayName: String,
    val kind: FileSourceKind,
    val supportsMutation: Boolean,
)

/** File-list sort keys (HXA-046: 名称/时间/大小排序). */
enum class SortKey {
    NAME,
    TIME,
    SIZE,
}

/**
 * Conflict policy for copy/move/rename (HXA-046: 询问/跳过/重命名/覆盖). [ASK] is the default and
 * is NEVER an implicit overwrite — it surfaces the conflict to the user.
 */
enum class ConflictPolicy {
    ASK,
    SKIP,
    RENAME,
    OVERWRITE,
}
