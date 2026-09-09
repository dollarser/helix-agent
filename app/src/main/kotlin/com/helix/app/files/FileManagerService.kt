package com.helix.app.files

import com.helix.app.R
import com.helix.app.allfiles.AllFilesModule
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafTreeScopeAccess
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.FileAlreadyExistsException

/**
 * User-operated browse, preview, transfer and trash facade. Manual operations do not create
 * model ToolCalls or expand Agent scopes. Workspace metadata remains private; shared storage
 * and writable SAF trees use independently injected, live-permission-checked backends.
 *
 * [WorkspaceArtifactStore] preserves existing workspace and import/export contracts.
 * [ManualFileOperations] handles directory transfers and external mutations. JVM tests inject
 * NIO backends; Android SAF and OS permissions are composed by AppFileServices, never by UI.
 * [realFileFor] supplies a transient sharing file, not a model-visible absolute path.
 */
@Suppress("TooManyFunctions", "LargeClass", "ReturnCount")
class FileManagerService(
    private val store: WorkspaceArtifactStore,
    private val roots: ScopeRootResolver,
    private val workspaceScopeId: String,
    // Governed SAF browse access; manual mutations use their own injected backend. Null only in
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
    private val sharedStorageGranted: () -> Boolean = { false },
    private val manual: ManualFileOperations? = null,
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
     * all-files roots (developer, read-only) + live SAF and manual shared-storage capabilities.
     * A SAF grant whose provider no longer answers / whose root changed is re-verified here and
     * omitted (fail closed: a source the resolver cannot resolve is never offered for browsing).
     */
    fun sources(): List<FileSource> {
        val list =
            mutableListOf(
                FileSource(workspaceScopeId, "Workspace", FileSourceKind.WORKSPACE, supportsMutation = true),
            )
        if (sharedStorageGranted()) {
            list.add(
                FileSource(
                    SharedStorageAccess.SCOPE_ID,
                    loc(R.string.files_shared),
                    FileSourceKind.ALL_FILES,
                    manual?.canWrite(SharedStorageAccess.SCOPE_ID) == true,
                ),
            )
        }
        if (AllFilesModule.AVAILABLE) {
            AllFilesModule.allFilesSources().forEach {
                list.add(FileSource(it.scopeId, it.displayName, FileSourceKind.ALL_FILES, supportsMutation = false))
            }
        }
        saf?.service?.liveSources()?.forEach {
            list.add(
                FileSource(
                    it.scopeId,
                    it.displayName,
                    FileSourceKind.SAF,
                    supportsMutation =
                        manual?.canWrite(it.scopeId) == true,
                ),
            )
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

    fun pendingTransfers(): List<FileTransferRecovery> = manual?.pendingTransfers().orEmpty()

    fun recoverTransfer(id: String): Boolean = requireNotNull(manual).recoverTransfer(id)

    private val preview = FileManagerPreview(store, roots, saf)

    fun previewText(
        scopeId: String,
        relativePath: String,
        maxBytes: Long = DEFAULT_PREVIEW_BYTES,
    ): String? = preview.previewText(scopeId, relativePath, maxBytes)

    fun previewImageBytes(
        scopeId: String,
        relativePath: String,
        maxBytes: Long = MAX_IMAGE_PREVIEW_BYTES,
    ): ByteArray = preview.previewImageBytes(scopeId, relativePath, maxBytes)

    fun mimeTypeFor(
        scopeId: String,
        relativePath: String,
    ): String = preview.mimeTypeFor(scopeId, relativePath)

    fun fileInfo(
        scopeId: String,
        relativePath: String,
        maxHashBytes: Long = MAX_HASH_BYTES,
    ): FileMeta = preview.fileInfo(scopeId, relativePath, maxHashBytes)

    fun realFileFor(
        scopeId: String,
        relativePath: String,
    ): File = preview.realFileFor(scopeId, relativePath)

    data class FileMeta(
        val sizeBytes: Long,
        val mtimeEpochMillis: Long,
        val mimeType: String,
        val isText: Boolean,
        val sha256: String?,
        val hashOmittedBecauseTooLarge: Boolean,
    )

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

    /** Manual operations use the injected backend; legacy workspace-only tests use the store. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    internal fun moveOrCopy(
        scopeId: String,
        srcRel: String,
        dstRel: String,
        overwrite: Boolean,
        move: Boolean,
        shouldCancel: () -> Boolean = { false },
    ): FileOpResult {
        if (manual != null) {
            return manualResult(
                dstRel,
            ) { manual.transfer(scopeId, srcRel, scopeId, dstRel, move, overwrite, shouldCancel) }
        }
        // A missing manual backend never falls through to a writable SAF implementation.
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

    @Suppress("TooGenericExceptionCaught")
    private fun manualResult(
        destination: String,
        operation: () -> Boolean,
    ): FileOpResult =
        try {
            FileOpResult.Ok(destination, operation())
        } catch (_: FileAlreadyExistsException) {
            FileOpResult.Conflict
        } catch (failure: Exception) {
            FileOpResult.Error(failure.message ?: loc(R.string.files_error_operation_failed))
        }

    /** Creates a directory [name] under [parentRel] (inside a user region). Refuses an existing path. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun makeDirectory(
        scopeId: String,
        parentRel: String,
        name: String,
    ): FileOpResult {
        if (manual != null) {
            return manualResult(joinPath(parentRel, name)) {
                manual.mkdir(scopeId, parentRel, name)
                false
            }
        }
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
        val dir = baseRel.substringBeforeLast('/', "")
        val name = baseRel.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = joinPath(dir, "$stem ($i)$ext")
            if (!(
                    manual?.exists(
                        scopeId,
                        candidate,
                    ) ?: store.stat(FileScopePath(scopeId, candidate)).exists
                )
            ) {
                return candidate
            }
            i++
        }
    }

    private val directoryTrash = ManualWorkspaceTrash(roots, workspaceScopeId)

    // --- Trash (删除到回收站 / 恢复 / 永久删除 / 清空) ---

    /** Moves the regular file at [relativePath] into the scope's trash (restorable). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun trash(
        scopeId: String,
        relativePath: String,
        shouldCancel: () -> Boolean = { false },
    ): FileOpResult {
        if (scopeId != workspaceScopeId &&
            manual != null
        ) {
            return manualResult(relativePath) {
                manual.delete(scopeId, relativePath, shouldCancel)
                false
            }
        }
        if (isSaf(scopeId)) return FileOpResult.Error(loc(R.string.files_saf_read_only))
        val fsp = FileScopePath(scopeId, relativePath)
        return try {
            if (scopeId == workspaceScopeId && directoryTrash.isDirectory(relativePath)) {
                directoryTrash.trash(relativePath)
                return FileOpResult.Ok(relativePath, false)
            }
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

    private val trashOps = FileManagerTrash(store, workspaceScopeId, directoryTrash, strings)

    fun listTrash(scopeId: String): List<TrashEntryView> = trashOps.listTrash(scopeId)

    fun restore(
        scopeId: String,
        entryName: String,
    ): FileOpResult = trashOps.restore(scopeId, entryName)

    fun purge(
        scopeId: String,
        entryName: String,
    ): FileOpResult = trashOps.purge(scopeId, entryName)

    fun emptyTrash(scopeId: String): Int = trashOps.emptyTrash(scopeId)

    // --- Batch (多选) with a conflict policy + partial-failure list ---

    fun batchMoveOrCopy(
        scopeId: String,
        sources: List<String>,
        destinationDir: String,
        policy: ConflictPolicy,
        move: Boolean,
        progress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): BatchResult =
        FileManagerBatchOperations(this) { loc(it) }
            .batchMoveOrCopy(scopeId, sources, destinationDir, policy, move, progress, shouldCancel)

    fun batchTrash(
        scopeId: String,
        relativePaths: List<String>,
        progress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): BatchResult =
        FileManagerBatchOperations(this) { loc(it) }
            .batchTrash(scopeId, relativePaths, progress, shouldCancel)

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
