package com.helix.app.ui

import android.content.Intent
import androidx.core.content.FileProvider
import com.helix.app.FeatureFiles
import com.helix.app.R
import com.helix.app.files.ConflictPolicy
import com.helix.app.files.ExportTarget
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileManagerService.BatchResult
import com.helix.app.files.FileManagerService.FileEntry
import com.helix.app.files.FileManagerService.FileOpResult
import com.helix.app.files.FileManagerService.TrashEntryView
import com.helix.feature.files.SafTreeScopeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Event handlers share state, but do not own composition or ActivityResult launchers. */
@Suppress("TooManyFunctions")
internal class FilesScreenActions(
    val state: FilesScreenState,
    val fileManager: FileManagerService,
    val safTree: SafTreeScopeService,
    val featureFiles: FeatureFiles,
    val scope: kotlinx.coroutines.CoroutineScope,
    val context: android.content.Context,
    private val resources: android.content.res.Resources,
) {
    fun str(
        resId: Int,
        vararg args: Any,
    ): String = resources.getString(resId, *args)

    @JvmName("strWithArgs")
    @Suppress("SpreadOperator") // Resources is the localization boundary.
    fun str(
        resId: Int,
        args: Array<out Any>,
    ): String = resources.getString(resId, *args)

    fun runImportSingle(
        uri: String,
        policy: ConflictPolicy,
    ) {
        with(state) {
            scope.launch {
                importBusy = true
                importResult = null
                importCancel.set(false)
                importLabel = str(R.string.files_importing_metadata)
                val (name, size) =
                    withContext(Dispatchers.IO) {
                        runCatching { featureFiles.metadataReader.metadata(uri) }
                            .fold(
                                onSuccess = { it.displayName to it.sizeBytes },
                                onFailure = { null to -1L },
                            )
                    }
                importLabel =
                    if (name != null) {
                        str(R.string.files_importing_named, name, formatSize(size))
                    } else {
                        str(R.string.files_importing_plain)
                    }
                val result =
                    withContext(Dispatchers.IO) {
                        fileManager.importSingleDocument(uri, policy, importCancel::get) { done, total ->
                            if (total > 0) {
                                importLabel =
                                    str(R.string.files_importing_progress, formatSize(done), formatSize(total))
                            } else {
                                importLabel = str(R.string.files_importing_done, formatSize(done))
                            }
                        }
                    }
                importBusy = false
                importLabel = null
                importResult = result
                val (summaryRes, summaryArgs) = transferSummary(str(R.string.files_verb_import), result)
                status = str(summaryRes, summaryArgs)
                reloadTick++
            }
        }
    }

    fun runImportTree(
        uri: String,
        policy: ConflictPolicy,
    ) {
        with(state) {
            scope.launch {
                importBusy = true
                importResult = null
                importCancel.set(false)
                importLabel = str(R.string.files_enumerating_folder)
                val result =
                    withContext(Dispatchers.IO) {
                        fileManager.importTree(uri, policy, importCancel::get) { done, total ->
                            importLabel = str(R.string.files_importing_file_number, done + 1, total)
                        }
                    }
                importBusy = false
                importLabel = null
                importResult = result
                val (summaryRes, summaryArgs) = transferSummary(str(R.string.files_verb_import), result)
                status = str(summaryRes, summaryArgs)
                reloadTick++
            }
        }
    }

    fun runExport(
        fileRel: String,
        target: ExportTarget,
        policy: ConflictPolicy,
    ) {
        with(state) {
            scope.launch {
                exportBusy = true
                exportResult = null
                exportCancel.set(false)
                exportLabel = str(R.string.files_exporting_plain)
                val result =
                    withContext(Dispatchers.IO) {
                        fileManager.exportDocument(fileRel, target, policy, exportCancel::get) { done, total ->
                            if (total > 0) {
                                exportLabel =
                                    str(R.string.files_exporting_progress, formatSize(done), formatSize(total))
                            } else {
                                exportLabel = str(R.string.files_exporting_done, formatSize(done))
                            }
                        }
                    }
                exportBusy = false
                exportLabel = null
                exportResult = result
                val (summaryRes, summaryArgs) = transferSummary(str(R.string.files_verb_export), result)
                status = str(summaryRes, summaryArgs)
            }
        }
    }

    fun onEntry(entry: FileEntry) {
        with(state) {
            if (entry.isDirectory) {
                currentPath = entry.relativePath
            } else {
                openFile = entry
            }
        }
    }

    fun toggleSelect(entry: FileEntry) {
        with(state) {
            selected =
                if (entry.relativePath in selected) {
                    selected - entry.relativePath
                } else {
                    selected + entry.relativePath
                }
        }
    }

    fun doRename(
        srcRel: String,
        dstRel: String,
        overwrite: Boolean,
    ) {
        with(state) {
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) { fileManager.rename(selectedScopeId, srcRel, dstRel, overwrite) }
                when (result) {
                    is FileOpResult.Ok -> {
                        status = str(R.string.files_renamed_status)
                        openFile = null
                        reloadTick++
                    }

                    FileOpResult.Conflict -> {
                        conflictTarget = srcRel to dstRel
                    }

                    is FileOpResult.NotFound -> {
                        status = result.message
                    }

                    is FileOpResult.Error -> {
                        status = result.message
                    }
                }
            }
        }
    }

    fun startBatch(
        total: Int,
        work: (
            progress: (Int, Int) -> Unit,
            cancel: () -> Boolean,
        ) -> BatchResult,
    ) {
        with(state) {
            scope.launch {
                batchBusy = true
                cancelFlag.set(false)
                batchLabel = str(R.string.files_batch_progress_zero, total)
                batchFailures = emptyList()
                val result =
                    withContext(Dispatchers.IO) {
                        work(
                            { done, t -> batchLabel = str(R.string.files_batch_progress, done, t) },
                            { cancelFlag.get() },
                        )
                    }
                batchLabel = null
                batchBusy = false
                status =
                    str(
                        R.string.files_batch_complete,
                        result.succeeded.size,
                        result.failures.size,
                    )
                batchFailures = result.failures
                selected = emptySet()
                reloadTick++
            }
        }
    }

    fun startCopyMove(
        sourceRels: List<String>,
        destDir: String,
        policy: ConflictPolicy,
        move: Boolean,
    ) {
        with(state) {
            if (destDir.isBlank()) {
                status = str(R.string.files_dest_dir_required)
                return
            }
            startBatch(sourceRels.size) { progress, cancel ->
                fileManager.batchMoveOrCopy(selectedScopeId, sourceRels, destDir, policy, move, progress, cancel)
            }
        }
    }

    fun startTrash(sourceRels: List<String>) {
        with(state) {
            if (!canMutate) {
                status = str(R.string.files_source_read_only)
                return
            }
            startBatch(sourceRels.size) { progress, cancel ->
                fileManager.batchTrash(selectedScopeId, sourceRels, progress, cancel)
            }
        }
    }

    fun restoreTrashEntry(entry: TrashEntryView) {
        with(state) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { fileManager.restore(selectedScopeId, entry.entryName) }
                when (result) {
                    is FileOpResult.Ok -> status = str(R.string.files_restored_status)
                    FileOpResult.Conflict -> status = str(R.string.files_restore_conflict)
                    is FileOpResult.NotFound -> status = result.message
                    is FileOpResult.Error -> status = result.message
                }
                reloadTick++
            }
        }
    }

    fun purgeTrashEntry(entry: TrashEntryView) {
        with(state) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { fileManager.purge(selectedScopeId, entry.entryName) }
                when (result) {
                    is FileOpResult.Ok -> status = str(R.string.files_purged_status)
                    is FileOpResult.NotFound -> status = result.message
                    is FileOpResult.Error -> status = result.message
                    FileOpResult.Conflict -> status = str(R.string.files_purge_unavailable)
                }
                reloadTick++
            }
        }
    }

    fun emptyTrashPanel() {
        with(state) {
            scope.launch {
                val count = withContext(Dispatchers.IO) { fileManager.emptyTrash(selectedScopeId) }
                status = str(R.string.files_trash_emptied, count)
                reloadTick++
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // share maps any I/O or FileProvider failure to a status string
    fun share(file: FileEntry) {
        with(state) {
            scope.launch {
                try {
                    val realFile =
                        withContext(Dispatchers.IO) { fileManager.realFileFor(selectedScopeId, file.relativePath) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", realFile)
                    val mime =
                        withContext(Dispatchers.IO) { fileManager.mimeTypeFor(selectedScopeId, file.relativePath) }
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = mime.ifEmpty { "application/octet-stream" }
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(send, str(R.string.files_share_chooser_title)))
                } catch (e: Exception) {
                    status = str(R.string.files_share_failed, e.message.orEmpty())
                }
            }
        }
    }

    fun createDirectory(name: String) {
        with(state) {
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        fileManager.makeDirectory(selectedScopeId, currentPath, name)
                    }
                when (result) {
                    is FileOpResult.Ok -> {
                        status = str(R.string.files_folder_created_status)
                        reloadTick++
                    }

                    FileOpResult.Conflict -> {
                        status = str(R.string.files_folder_exists_status)
                    }

                    is FileOpResult.NotFound -> {
                        status = result.message
                    }

                    is FileOpResult.Error -> {
                        status = result.message
                    }
                }
            }
        }
    }

    fun revokeScope(source: com.helix.feature.files.SafTreeSource) {
        with(state) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    safTree.revoke(source.scopeId)
                    sources = fileManager.sources()
                }
                status = str(R.string.files_saf_removed, source.displayName)
            }
        }
    }
}
