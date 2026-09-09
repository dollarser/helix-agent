package com.helix.app.files

import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafExportVerifier
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafTreeScopeAccess

/**
 * The destination of an export (HXA-058 文件管理导出入口):
 * - [Document]: a document URI the user just created in the OS picker (`ACTION_CREATE_DOCUMENT`);
 *   the name/conflict handling there belongs to the OS dialog (the app streams into the URI it
 *   was given, truncate mode);
 * - [TreeDestination]: a directory ("" = root) inside a persisted, user-authorized SAF tree
 *   scope (HXA-057); the document is CREATED there, and the conflict policy applies here
 *   (never a default overwrite).
 *
 * A `content://` URI in [Document] is consumed only by the export pipeline's destination
 * opener; it is never rendered as text, logged or passed to the model (doc 10).
 */
sealed class ExportTarget {
    data class Document(
        val uri: String,
        val label: String,
    ) : ExportTarget()

    data class TreeDestination(
        val scopeId: String,
        val parentPath: String,
    ) : ExportTarget()
}

/** The per-item outcome of a file-manager transfer (import/export). Stable, model-safe codes. */
enum class TransferItemStatus {
    COMPLETED,

    /** The destination exists and the chosen policy does not resolve it (ASK) — shown, not clobbered. */
    CONFLICT,

    /** The policy (or the plan) skipped this item; the reason is in [TransferItem.detail]. */
    SKIPPED,

    /** The user cancelled (this item, or the whole operation before it ran). */
    CANCELLED,

    /** A fail-closed refusal; the reason is a stable string in [TransferItem.detail]. */
    FAILED,
}

/**
 * One item of a [TransferResult]. Every string here is model-safe / display-safe: workspace
 * relative paths, sanitized or provider display names, stable Chinese detail texts — never a
 * `content://` URI, a real path or a raw exception message.
 */
data class TransferItem(
    val sourceLabel: String,
    val targetLabel: String,
    val status: TransferItemStatus,
    val detail: String? = null,
    val sizeBytes: Long = -1L,
    val sha256: String? = null,
    /** Export only: the platform re-checked the destination's self-reported size and it matched. */
    val sizeVerified: Boolean = false,
    /** Export only: the bytes were RE-READ after the export and are hash-equal (verified). */
    val verified: Boolean = false,
)

/**
 * The result of a file-manager transfer operation. A tree import carries one item per listed
 * file (planned + skipped + cancelled), so the user always sees exactly what happened — a
 * 部分结果清单, never a silent omission. [reclaimedTempFiles] reports the temp files a previous
 * interrupted (e.g. process-killed) transfer left behind and this operation reclaimed.
 */
data class TransferResult(
    val items: List<TransferItem>,
    val reclaimedTempFiles: Int,
) {
    val completed: List<TransferItem> get() = items.filter { it.status == TransferItemStatus.COMPLETED }

    val problems: List<TransferItem> get() = items.filter { it.status != TransferItemStatus.COMPLETED }
}

/**
 * HXA-058 文件管理器导入/导出入口: the import/export operations of the file manager.
 *
 * These are explicit FILE-MANAGEMENT actions driven by the user (picker results + this seam):
 * they never create a chat message, never call a Provider, and never expand the Agent scope
 * (no artifact registration, no session binding, no tree grant persistence on import). They
 * REUSE the HXA-044 restricted pipelines — the lying-provider defenses, the atomic publish,
 * the region gate and the post-write size re-check are the HXA-044 contract, applied unchanged.
 *
 * - 导入: a single document (`ACTION_OPEN_DOCUMENT`) or a whole folder
 *   (`ACTION_OPEN_DOCUMENT_TREE`, bounded enumeration + fail-closed name mapping) is COPIED
 *   into the workspace `input/` region through the import pipeline;
 * - 导出: a workspace file (`input/`/`work/`/`output/` — `.helix/` never) is streamed to a
 *   user-created document (`ACTION_CREATE_DOCUMENT`) or INTO a persisted, re-verified
 *   (WRITE mode) user-authorized SAF tree through the export pipeline;
 * - verified: an export shows "verified" ONLY when the bytes are re-read after the write and
 *   are hash-equal ([SafExportVerifier]); otherwise only the platform-confirmed result
 *   (size re-check) is reported;
 * - 临时文件回收: every operation first reclaims the abandoned temps a previous interrupted
 *   transfer (process kill / crash) may have left in the workspace.
 */
class FileManagerTransfers(
    store: WorkspaceArtifactStore,
    workspaceScopeId: String,
    saf: SafTreeScopeAccess?,
    transfers: SafImportExportAccess,
    strings: (Int, Array<out Any>) -> String,
) {
    private val imports = FileManagerImports(store, workspaceScopeId, transfers, strings)
    private val exports = FileManagerExports(store, workspaceScopeId, transfers, strings, saf)

    fun importSingleDocument(
        sourceUri: String,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onProgress: (Long, Long) -> Unit,
    ): TransferResult = imports.importSingleDocument(sourceUri, policy, cancel, onProgress)

    fun importTree(
        treeUri: String,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onFileProgress: (Int, Int) -> Unit,
    ): TransferResult = imports.importTree(treeUri, policy, cancel, onFileProgress)

    fun exportDocument(
        sourceRelativePath: String,
        target: ExportTarget,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onProgress: (Long, Long) -> Unit,
    ): TransferResult = exports.exportDocument(sourceRelativePath, target, policy, cancel, onProgress)
}
