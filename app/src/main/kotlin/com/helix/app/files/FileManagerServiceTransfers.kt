package com.helix.app.files

import com.helix.app.R
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.feature.files.ExportRefusal
import com.helix.feature.files.ExportStatus
import com.helix.feature.files.ImportRefusal
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafExportOutcome
import com.helix.feature.files.SafExportVerifier
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafImportOutcome
import com.helix.feature.files.SafNameSanitizer
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.SafTreeImportEntry
import com.helix.feature.files.SafTreeImportPlanner
import com.helix.feature.files.SafTreeScopeAccess
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

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
@Suppress("TooManyFunctions") // one cohesive transfer seam: single import, tree import, export + policy mapping
class FileManagerTransfers(
    private val store: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val saf: SafTreeScopeAccess?,
    private val transfers: SafImportExportAccess,
    // HXA-069: localizes the STABLE string-resource ids the transfer results carry (user-visible
    // status/detail texts) to the CURRENT locale at emit time (threaded through from
    // [FileManagerService], which owns the app Context's getString in production).
    private val strings: (Int, Array<out Any>) -> String,
) {
    /** Localizes a stable string-resource id (+ positional args) to the current locale (HXA-069). */
    private fun loc(
        id: Int,
        vararg args: Any,
    ): String = strings(id, args)

    /**
     * Imports ONE picked SAF document into the workspace `input/` region.
     *
     * Conflict policy (never a default overwrite): ASK reports the conflict (CONFLICT item),
     * SKIP skips it, RENAME retries under the next available name, OVERWRITE first moves the
     * existing file to the trash (restorable) and then imports.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException") // one fail-closed exit per fault path
    fun importSingleDocument(
        sourceUri: String,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onProgress: (
            Long,
            Long,
        ) -> Unit,
    ): TransferResult {
        val reclaimed = reclaimWorkspaceTemps()
        val metadata: SafSourceMetadata =
            try {
                transfers.sourceMetadata.metadata(sourceUri)
            } catch (e: Exception) {
                return TransferResult(
                    listOf(
                        TransferItem(
                            loc(R.string.files_source_saf_document),
                            "Workspace input/",
                            TransferItemStatus.FAILED,
                            loc(R.string.files_import_detail_source_metadata_failed),
                        ),
                    ),
                    reclaimed,
                )
            }
        val baseName = SafNameSanitizer.sanitize(metadata.displayName)
        var targetRel = WorkspaceLayout.INPUT + "/" + baseName
        var nameOverride: String? = null
        if (store.stat(FileScopePath(workspaceScopeId, targetRel)).exists) {
            when (policy) {
                ConflictPolicy.OVERWRITE -> {
                    if (!trashExisting(targetRel)) {
                        val item =
                            TransferItem(
                                metadata.displayName.orEmpty(),
                                targetRel,
                                TransferItemStatus.FAILED,
                                loc(R.string.files_import_detail_trash_failed),
                            )
                        return TransferResult(listOf(item), reclaimed)
                    }
                }

                ConflictPolicy.RENAME -> {
                    targetRel = nextAvailable(targetRel)
                    nameOverride = targetRel.substringAfterLast('/')
                }

                // ASK / SKIP: the pipeline's own existence check is authoritative (race-safe).
                ConflictPolicy.ASK,
                ConflictPolicy.SKIP,
                -> {
                    Unit
                }
            }
        }
        val outcome =
            transfers
                .importPipeline
                .importDocument(workspaceScopeId, sourceUri, metadata, nameOverride, cancel, onProgress = onProgress)
        return TransferResult(listOf(mapImportOutcome(outcome, metadata.displayName, targetRel, policy)), reclaimed)
    }

    /**
     * Imports a WHOLE picked SAF folder into the workspace `input/` (the folder structure is
     * recreated under `input/`). Bounded enumeration + fail-closed name mapping
     * ([SafTreeImportPlanner]): every skipped file is reported with a stable reason, so nothing
     * is silently omitted. One progress tick (done, total) is reported before each file.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any enumeration failure aborts the whole import
    fun importTree(
        treeUri: String,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onFileProgress: (
            Int,
            Int,
        ) -> Unit,
    ): TransferResult {
        val reclaimed = reclaimWorkspaceTemps()
        val entries: List<SafTreeImportEntry> =
            try {
                transfers.treeLister.listTree(treeUri)
            } catch (e: Exception) {
                val item =
                    TransferItem(
                        loc(R.string.files_source_saf_folder),
                        "Workspace input/",
                        TransferItemStatus.FAILED,
                        loc(R.string.files_import_detail_tree_enum_failed),
                    )
                return TransferResult(listOf(item), reclaimed)
            }
        val plan = SafTreeImportPlanner.plan(entries)
        val items = mutableListOf<TransferItem>()
        plan.planned.forEachIndexed { index, planned ->
            if (cancel.isCancelled()) {
                val item =
                    TransferItem(
                        planned.sourceLabel,
                        planned.targetRelativePath,
                        TransferItemStatus.CANCELLED,
                        loc(R.string.files_detail_cancelled),
                    )
                items.add(item)
                return@forEachIndexed
            }
            onFileProgress(index, plan.planned.size)
            items.add(importPlannedFile(transfers, planned, policy, cancel))
        }
        plan.skipped.forEach { skipped ->
            items.add(
                TransferItem(
                    skipped.sourceLabel,
                    "—",
                    TransferItemStatus.SKIPPED,
                    when (skipped.reason) {
                        SafTreeImportPlanner.ImportSkipReason.AMBIGUOUS_NAME -> {
                            loc(R.string.files_import_skip_ambiguous_name)
                        }

                        SafTreeImportPlanner.ImportSkipReason.TOO_DEEP -> {
                            loc(R.string.files_import_skip_too_deep)
                        }

                        SafTreeImportPlanner.ImportSkipReason.TOO_MANY_FILES -> {
                            loc(R.string.files_import_skip_too_many)
                        }
                    },
                ),
            )
        }
        return TransferResult(items, reclaimed)
    }

    /**
     * Exports ONE workspace file to [target] (HXA-058). The source must be inside
     * `input/`/`work/`/`output/` (the HXA-044 region gate, unchanged). For a
     * [ExportTarget.TreeDestination] the grant is re-verified in WRITE mode in real time (a
     * read-only or revoked grant fails closed before any byte is written) and the document is
     * created in the tree under the chosen conflict policy (never a default overwrite).
     *
     * The result reports the platform-confirmed facts, and "verified" ONLY when the bytes were
     * re-read after the write and are hash-equal.
     */
    @Suppress(
        "ReturnCount",
        "LongMethod",
        "TooGenericExceptionCaught",
        "SwallowedException",
    ) // one fail-closed exit per fault path; HXA-069 i18n wraps pushed the body past 60
    fun exportDocument(
        sourceRelativePath: String,
        target: ExportTarget,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onProgress: (
            Long,
            Long,
        ) -> Unit,
    ): TransferResult {
        val source = FileScopePath(workspaceScopeId, sourceRelativePath)
        val resolved =
            when (target) {
                is ExportTarget.Document -> {
                    ExportDestinationResolved(target.uri, target.label)
                }

                is ExportTarget.TreeDestination -> {
                    val access: SafTreeScopeAccess =
                        try {
                            verifiedWriteAccess(target.scopeId)
                        } catch (e: ScopeNotAvailable) {
                            return TransferResult(
                                listOf(
                                    TransferItem(
                                        sourceRelativePath,
                                        loc(R.string.files_transfer_saf_source, target.scopeId),
                                        TransferItemStatus.FAILED,
                                        loc(R.string.files_export_detail_saf_not_writable),
                                    ),
                                ),
                                0,
                            )
                        }
                    val destDir = target.parentPath.ifEmpty { loc(R.string.files_transfer_root_dir) }
                    val scopeName = access.service.source(target.scopeId)?.displayName ?: target.scopeId
                    val label = loc(R.string.files_transfer_saf_source, "$scopeName/$destDir")
                    val name = sourceRelativePath.substringAfterLast('/')
                    val mime =
                        try {
                            store.probe(source).mimeType
                        } catch (e: Exception) {
                            "application/octet-stream"
                        }
                    when (val r = resolveTreeDestination(transfers, access, target, name, mime, policy)) {
                        is TreeDestinationResolved -> {
                            ExportDestinationResolved(r.uri, "$label/${r.finalName}")
                        }

                        is TreeDestinationConflict -> {
                            ExportDestinationConflict(label, loc(R.string.files_export_detail_saf_exists_conflict))
                        }

                        is TreeDestinationSkipped -> {
                            ExportDestinationSkipped(label, loc(R.string.files_export_detail_saf_exists_skipped))
                        }

                        is TreeDestinationFailure -> {
                            ExportDestinationFailed(label, r.detail)
                        }
                    }
                }
            }
        val (destUri, destLabel) =
            when (resolved) {
                is ExportDestinationResolved -> resolved.uri to resolved.label
                is ExportDestinationConflict -> return problemResult(sourceRelativePath, resolved)
                is ExportDestinationFailed -> return problemResult(sourceRelativePath, resolved)
                is ExportDestinationSkipped -> return problemResult(sourceRelativePath, resolved)
            }
        val outcome = transfers.exportPipeline.exportFile(source, destUri, cancel, onProgress)
        return TransferResult(listOf(mapExportOutcome(outcome, sourceRelativePath, destLabel, destUri, transfers)), 0)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    /** Reclaims the abandoned temps a previous interrupted transfer may have left (进程回收). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // reclaim failure must not block the transfer
    private fun reclaimWorkspaceTemps(): Int =
        try {
            store.reclaimTempFiles(workspaceScopeId)
        } catch (e: Exception) {
            0
        }

    /** Moves the existing file at [targetRel] to the trash. @return false on a hard failure. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a hard trash failure just aborts this import
    private fun trashExisting(targetRel: String): Boolean =
        try {
            store.moveToTrash(FileScopePath(workspaceScopeId, targetRel))
            true
        } catch (e: FileNotFoundException) {
            true // a race: the existing file is gone; import it fresh
        } catch (e: Exception) {
            false
        }

    /** The next available sibling of [baseRel] (same convention as the facade's rename flow). */
    private fun nextAvailable(baseRel: String): String {
        val dir = baseRel.substringBeforeLast('/')
        val name = baseRel.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$dir/$stem ($i)$ext"
            if (!store.stat(FileScopePath(workspaceScopeId, candidate)).exists) return candidate
            i++
        }
    }

    /** The SAF access with a WRITE-mode re-verified grant (fail closed), or [ScopeNotAvailable]. */
    private fun verifiedWriteAccess(scopeId: String): SafTreeScopeAccess {
        val access = saf ?: throw ScopeNotAvailable("SAF tree scope not available: $scopeId")
        access.service.resolve(scopeId, SafAccessMode.WRITE)
        return access
    }

    /** One planned tree file through the pipeline, under [policy]. */
    @Suppress("ReturnCount") // each conflict policy has one distinct visible exit
    private fun importPlannedFile(
        transfers: SafImportExportAccess,
        planned: SafTreeImportPlanner.PlannedImport,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
    ): TransferItem {
        var targetRel = planned.targetRelativePath
        if (store.stat(FileScopePath(workspaceScopeId, targetRel)).exists) {
            when (policy) {
                ConflictPolicy.OVERWRITE -> {
                    if (!trashExisting(targetRel)) {
                        return TransferItem(
                            planned.sourceLabel,
                            targetRel,
                            TransferItemStatus.FAILED,
                            loc(R.string.files_import_detail_trash_failed),
                        )
                    }
                }

                ConflictPolicy.RENAME -> {
                    targetRel = nextAvailable(targetRel)
                }

                ConflictPolicy.ASK -> {
                    return TransferItem(
                        planned.sourceLabel,
                        targetRel,
                        TransferItemStatus.CONFLICT,
                        loc(R.string.files_import_detail_workspace_exists_conflict),
                    )
                }

                ConflictPolicy.SKIP -> {
                    return TransferItem(
                        planned.sourceLabel,
                        targetRel,
                        TransferItemStatus.SKIPPED,
                        loc(R.string.files_import_detail_workspace_exists_skipped),
                    )
                }
            }
        }
        val leaf = targetRel.substringAfterLast('/')
        val reported = SafSourceMetadata(planned.sizeBytes, null, leaf)
        val outcome =
            transfers
                .importPipeline
                .importDocument(
                    workspaceScopeId,
                    planned.documentUri,
                    reported,
                    null,
                    cancel,
                    targetRelativePath = targetRel,
                )
        return mapImportOutcome(outcome, planned.sourceLabel, targetRel, policy)
    }

    /** The conflict-aware resolution of an export destination inside an authorized tree. */
    @Suppress("ReturnCount", "SwallowedException") // each platform error becomes one stable refusal
    private fun resolveTreeDestination(
        transfers: SafImportExportAccess,
        access: SafTreeScopeAccess,
        target: ExportTarget.TreeDestination,
        name: String,
        mime: String,
        policy: ConflictPolicy,
    ): TreeDestinationResolution {
        var current = name
        var attempts = 0
        while (true) {
            try {
                val uri = createInTree(transfers, target, current, mime, overwrite = false)
                return TreeDestinationResolved(uri, current)
            } catch (e: FileAlreadyExistsException) {
                when (val step = onTreeConflict(access, target, current, policy, attempts)) {
                    is Overwrite -> {
                        return TreeDestinationResolved(
                            createInTree(transfers, target, current, mime, overwrite = true),
                            current,
                        )
                    }

                    is Rename -> {
                        attempts++
                        current = step.nextName
                    }

                    is Conflict -> {
                        return TreeDestinationConflict
                    }

                    is Skipped -> {
                        return TreeDestinationSkipped
                    }

                    is GiveUp -> {
                        return TreeDestinationFailure(loc(R.string.files_export_detail_no_free_name))
                    }
                }
            } catch (e: FileNotFoundException) {
                return TreeDestinationFailure(loc(R.string.files_export_detail_saf_dir_missing))
            } catch (e: ScopeNotAvailable) {
                return TreeDestinationFailure(loc(R.string.files_export_detail_saf_unavailable))
            } catch (e: IOException) {
                return TreeDestinationFailure(loc(R.string.files_export_detail_saf_create_failed))
            }
        }
    }

    /** Creates (or, for [overwrite], reuses) the [displayName] document in the target directory. */
    private fun createInTree(
        transfers: SafImportExportAccess,
        target: ExportTarget.TreeDestination,
        displayName: String,
        mime: String,
        overwrite: Boolean,
    ): String =
        transfers.treeDestination.destinationUri(
            target.scopeId,
            target.parentPath,
            displayName,
            mime,
            overwrite = overwrite,
        )

    /** The next step when the destination name already exists in the tree (never a default overwrite). */
    private fun onTreeConflict(
        access: SafTreeScopeAccess,
        target: ExportTarget.TreeDestination,
        current: String,
        policy: ConflictPolicy,
        attempts: Int,
    ): ConflictStep =
        when (policy) {
            ConflictPolicy.OVERWRITE -> {
                Overwrite
            }

            ConflictPolicy.ASK -> {
                Conflict
            }

            ConflictPolicy.SKIP -> {
                Skipped
            }

            ConflictPolicy.RENAME -> {
                if (attempts + 1 > MAX_RENAME_ATTEMPTS) {
                    GiveUp
                } else {
                    Rename(nextRenamedName(access, target.scopeId, target.parentPath, current))
                }
            }
        }

    /**
     * The next free sibling of [current] under the RENAME policy. The tree's LIVE listing is the
     * truth for what already exists; an unlistable parent defers to create-document's own
     * same-name check (a RENAME that collides fails closed there, it never clobbers).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // an unlistable parent defers to the create check
    private fun nextRenamedName(
        access: SafTreeScopeAccess,
        scopeId: String,
        parentPath: String,
        current: String,
    ): String {
        val existing =
            try {
                access.reader
                    .list(scopeId, parentPath)
                    .map { it.name }
                    .toSet()
            } catch (e: Exception) {
                emptySet()
            }
        var i = 1
        while (suffixedName(current, i) in existing) i++
        return suffixedName(current, i)
    }

    private fun suffixedName(
        name: String,
        i: Int,
    ): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($i)${name.substring(dot)}"
        } else {
            "$name ($i)"
        }
    }

    private sealed class TreeDestinationResolution

    private data class TreeDestinationResolved(
        val uri: String,
        val finalName: String,
    ) : TreeDestinationResolution()

    private data object TreeDestinationConflict : TreeDestinationResolution()

    private data object TreeDestinationSkipped : TreeDestinationResolution()

    private data class TreeDestinationFailure(
        val detail: String,
    ) : TreeDestinationResolution()

    private sealed class ExportDestinationOutcome

    private data class ExportDestinationResolved(
        val uri: String,
        val label: String,
    ) : ExportDestinationOutcome()

    /** A destination that could NOT be resolved to a writable document (never a silent clobber). */
    private sealed class ExportDestinationProblem : ExportDestinationOutcome() {
        abstract val label: String
        abstract val detail: String
    }

    private data class ExportDestinationConflict(
        override val label: String,
        override val detail: String,
    ) : ExportDestinationProblem()

    private data class ExportDestinationFailed(
        override val label: String,
        override val detail: String,
    ) : ExportDestinationProblem()

    private data class ExportDestinationSkipped(
        override val label: String,
        override val detail: String,
    ) : ExportDestinationProblem()

    /** The next action after a same-name collision inside an authorized tree. */
    private sealed class ConflictStep

    private object Overwrite : ConflictStep()

    private data class Rename(
        val nextName: String,
    ) : ConflictStep()

    private object Conflict : ConflictStep()

    private object Skipped : ConflictStep()

    private object GiveUp : ConflictStep()

    /** The fail-closed result for an export destination that could not be resolved. */
    private fun problemResult(
        sourceRelativePath: String,
        problem: ExportDestinationProblem,
    ): TransferResult {
        val status =
            when (problem) {
                is ExportDestinationConflict -> TransferItemStatus.CONFLICT
                is ExportDestinationSkipped -> TransferItemStatus.SKIPPED
                else -> TransferItemStatus.FAILED
            }
        return TransferResult(
            listOf(TransferItem(sourceRelativePath, problem.label, status, problem.detail)),
            0,
        )
    }

    private fun mapImportOutcome(
        outcome: SafImportOutcome,
        sourceLabel: String?,
        targetRel: String,
        policy: ConflictPolicy,
    ): TransferItem =
        when (outcome.status) {
            ImportStatus.COMPLETED -> {
                val label = outcome.targetModelRef?.removePrefix("scope:$workspaceScopeId:") ?: targetRel
                TransferItem(
                    sourceLabel.orEmpty(),
                    label,
                    TransferItemStatus.COMPLETED,
                    loc(R.string.files_transfer_imported),
                    outcome.sizeBytes,
                    outcome.sha256,
                )
            }

            ImportStatus.CANCELLED -> {
                TransferItem(
                    sourceLabel.orEmpty(),
                    targetRel,
                    TransferItemStatus.CANCELLED,
                    loc(R.string.files_import_cancelled_nothing_written),
                )
            }

            ImportStatus.REFUSED -> {
                if (outcome.refusal == ImportRefusal.DESTINATION_EXISTS) {
                    if (policy == ConflictPolicy.SKIP) {
                        TransferItem(
                            sourceLabel.orEmpty(),
                            targetRel,
                            TransferItemStatus.SKIPPED,
                            loc(R.string.files_import_detail_exists_skipped),
                        )
                    } else {
                        TransferItem(
                            sourceLabel.orEmpty(),
                            targetRel,
                            TransferItemStatus.CONFLICT,
                            loc(R.string.files_import_detail_exists_conflict),
                        )
                    }
                } else {
                    TransferItem(
                        sourceLabel.orEmpty(),
                        targetRel,
                        TransferItemStatus.FAILED,
                        importDetail(outcome.refusal),
                    )
                }
            }
        }

    private fun mapExportOutcome(
        outcome: SafExportOutcome,
        sourceRelativePath: String,
        destLabel: String,
        destUri: String,
        transfers: SafImportExportAccess,
    ): TransferItem =
        when (outcome.status) {
            ExportStatus.COMPLETED -> {
                // Verified ONLY when the bytes are re-read after the write and hash-equal;
                // otherwise only the platform-confirmed result is reported (roadmap HXA-058).
                val verified =
                    SafExportVerifier.reReadVerified(
                        destUri,
                        transfers.destinationReReader,
                        outcome.sha256,
                        outcome.sizeBytes,
                    )
                TransferItem(
                    sourceRelativePath,
                    destLabel,
                    TransferItemStatus.COMPLETED,
                    if (verified) {
                        loc(R.string.files_export_detail_verified)
                    } else if (outcome.sizeVerified) {
                        loc(R.string.files_export_detail_size_checked)
                    } else {
                        loc(R.string.files_export_detail_platform_only)
                    },
                    outcome.sizeBytes,
                    outcome.sha256,
                    sizeVerified = outcome.sizeVerified,
                    verified = verified,
                )
            }

            ExportStatus.CANCELLED -> {
                TransferItem(
                    sourceRelativePath,
                    destLabel,
                    TransferItemStatus.CANCELLED,
                    loc(R.string.files_export_cancelled_partial),
                )
            }

            ExportStatus.REFUSED -> {
                TransferItem(
                    sourceRelativePath,
                    destLabel,
                    TransferItemStatus.FAILED,
                    exportDetail(outcome.refusal),
                )
            }
        }

    private fun importDetail(refusal: ImportRefusal?): String =
        when (refusal) {
            ImportRefusal.INVALID_TARGET -> loc(R.string.files_import_refusal_invalid_target)
            ImportRefusal.SCOPE_UNAVAILABLE -> loc(R.string.files_workspace_unavailable)
            ImportRefusal.DESTINATION_EXISTS -> loc(R.string.files_import_detail_exists_conflict)
            ImportRefusal.REPORTED_SIZE_EXCEEDS_LIMIT -> loc(R.string.files_import_refusal_too_large)
            ImportRefusal.QUOTA_EXCEEDED -> loc(R.string.files_import_refusal_quota)
            ImportRefusal.SOURCE_UNOPENABLE -> loc(R.string.files_import_refusal_source_unopenable)
            ImportRefusal.STREAM_SIZE_MISMATCH -> loc(R.string.files_import_refusal_size_mismatch)
            ImportRefusal.STREAM_LIMIT_EXCEEDED -> loc(R.string.files_import_refusal_too_large)
            ImportRefusal.IO_FAILURE -> loc(R.string.files_import_refusal_io)
            null -> loc(R.string.files_import_failed)
        }

    private fun exportDetail(refusal: ExportRefusal?): String =
        when (refusal) {
            ExportRefusal.OUTSIDE_USER_REGIONS -> loc(R.string.files_export_refusal_outside_regions)
            ExportRefusal.SCOPE_UNAVAILABLE -> loc(R.string.files_workspace_unavailable)
            ExportRefusal.SOURCE_NOT_FOUND -> loc(R.string.files_error_source_missing)
            ExportRefusal.NOT_A_FILE -> loc(R.string.files_export_refusal_not_a_file)
            ExportRefusal.SOURCE_EXCEEDS_LIMIT -> loc(R.string.files_export_refusal_too_large)
            ExportRefusal.DESTINATION_UNOPENABLE -> loc(R.string.files_export_refusal_dest_unopenable)
            ExportRefusal.IO_FAILURE -> loc(R.string.files_export_refusal_io)
            ExportRefusal.SIZE_VERIFICATION_MISMATCH -> loc(R.string.files_export_refusal_size_mismatch)
            null -> loc(R.string.files_export_failed)
        }

    private companion object {
        const val MAX_RENAME_ATTEMPTS = 1000
    }
}
