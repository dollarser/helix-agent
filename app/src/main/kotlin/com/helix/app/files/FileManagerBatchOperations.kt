package com.helix.app.files

import com.helix.app.R
import com.helix.app.files.FileManagerService.BatchItem
import com.helix.app.files.FileManagerService.BatchResult
import com.helix.app.files.FileManagerService.FileOpResult

internal class FileManagerBatchOperations(
    private val files: FileManagerService,
    private val loc: (Int) -> String,
) {
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
                        processBatchItem(scopeId, srcRel, destinationDir, policy, move, shouldCancel)
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
        shouldCancel: () -> Boolean,
    ): BatchItem {
        val dstRel = joinPath(destinationDir, srcRel.substringAfterLast('/'))
        return when (policy) {
            ConflictPolicy.OVERWRITE -> {
                mapItem(srcRel, files.moveOrCopy(scopeId, srcRel, dstRel, overwrite = true, move, shouldCancel))
            }

            ConflictPolicy.RENAME -> {
                val first = files.moveOrCopy(scopeId, srcRel, dstRel, overwrite = false, move, shouldCancel)
                if (first is FileOpResult.Conflict) {
                    val renamed = files.nextAvailableName(scopeId, dstRel)
                    val second = files.moveOrCopy(scopeId, srcRel, renamed, overwrite = false, move, shouldCancel)
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
                    files.moveOrCopy(scopeId, srcRel, dstRel, overwrite = false, move, shouldCancel),
                    conflictIsSkipped = true,
                )
            }
        }
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
                        mapItem(rel, files.trash(scopeId, rel, shouldCancel))
                    }
                progress(index + 1, total)
                item
            }
        return BatchResult(items)
    }

    private fun joinPath(
        dir: String,
        name: String,
    ): String = if (dir.isEmpty()) name else "$dir/$name"
}
