package com.helix.app.files

import com.helix.app.R
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.feature.files.ImportRefusal
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafImportOutcome
import com.helix.feature.files.SafNameSanitizer
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.SafTreeImportEntry
import com.helix.feature.files.SafTreeImportPlanner
import java.io.FileNotFoundException

/** User-picked document/tree imports; owns workspace conflict handling. */
internal class FileManagerImports(
    private val store: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val transfers: SafImportExportAccess,
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
}
