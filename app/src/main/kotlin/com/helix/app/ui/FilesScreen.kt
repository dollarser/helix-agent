@file:Suppress("TooManyFunctions") // the import/export transfer actions live with the state they drive

package com.helix.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.helix.app.FeatureFiles
import com.helix.app.R
import com.helix.app.files.ConflictPolicy
import com.helix.app.files.ExportTarget
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileManagerService.BatchItem
import com.helix.app.files.FileManagerService.BatchResult
import com.helix.app.files.FileManagerService.FileEntry
import com.helix.app.files.FileManagerService.FileMeta
import com.helix.app.files.FileManagerService.FileOpResult
import com.helix.app.files.FileManagerService.TrashEntryView
import com.helix.app.files.FileSource
import com.helix.app.files.FileSourceKind
import com.helix.app.files.SortKey
import com.helix.app.files.TransferItemStatus
import com.helix.app.files.TransferResult
import com.helix.feature.files.SafTreeScopeService
import com.helix.feature.files.SafTreeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The file-manager screen (HXA-046 文件管理 UI, roadmap line 233-235). Drives the always-available
 * access sources — the app-private **Workspace** (mutable) and, in the developer flavor, the enabled
 * **all-files** roots (read-only this milestone, since their layout is not a workspace region).
 *
 * Delivered against the roadmap scope, each fail-closed through [FileManagerService] (the same
 * containment-enforced store the `files.*` tools use):
 * - 路径面包屑: [currentPath] is a tappable breadcrumb (root + each segment);
 * - 来源标识: the current source is always shown and switchable (HXA-045 roots);
 * - 排序: name / time / size, with directories first in every mode;
 * - 列表和网格视图: list and adaptive-grid rendering of the same entries;
 * - 多选: a checkbox per entry feeds the batch copy / move / delete bar;
 * - 预览 + MIME/大小/哈希: a per-file dialog shows a text or image preview plus size, mtime, MIME and
 *   a real SHA-256;
 * - 冲突: rename triggers the interactive 询问 dialog (跳过 / 重命名 / 覆盖 — never a default
 *   overwrite); batch copy/move take an explicit up-front policy;
 * - 长操作进度/取消: batch ops run off the UI thread with a live "x / n" bar and a cancel that stops
 *   the remaining items (recorded as skipped, not lost), plus a partial-failure list;
 * - trash: a dedicated panel lists entries and restores / purges / empties them;
 * - 分享: hands the file to another app via an unexported FileProvider `content://` URI — the real
 *   path is never rendered as text (doc 10).
 *
 * 导入/导出 (HXA-058, doc 09 section 4.2): the file-manager entries over the HXA-044 restricted
 * pipelines — 导入 copies a user-picked document (ACTION_OPEN_DOCUMENT) or folder
 * (ACTION_OPEN_DOCUMENT_TREE) into the Workspace `input/`; 导出 streams a Workspace file to a
 * user-created document (ACTION_CREATE_DOCUMENT) or an authorized SAF tree. The dialog shows
 * 来源/目标/名称/大小/冲突策略/进度/取消/最终结果; an export is "verified" only when its bytes are
 * re-read after the write. These are explicit file-management actions: no chat message, no
 * Provider call, no Agent scope expansion. "无权限时可完整使用 Workspace" stays the honesty
 * contract (the consumer build, with no all-files grant, still has the full Workspace here).
 */
@Composable
@Suppress("FunctionName", "LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "LongParameterList")
fun FilesScreen(
    fileManager: FileManagerService,
    // HXA-057: the SAF tree scope service drives the add/re-authorize + remove entries.
    safTree: SafTreeScopeService,
    // HXA-058: the SAF adapter bundle (source metadata for the pre-copy display).
    featureFiles: FeatureFiles,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // HXA-069: the local event-handler functions below are non-@Composable (coroutines and
    // onClick callbacks), so they resolve labels through this helper. `resources` is the
    // composition-captured Resources (LocalResources — NOT LocalContext.getString, which would go
    // stale across a Configuration/locale change, lint: LocalContextGetResourceValueCall); it is
    // read once per composition and reused by the non-@Composable handlers. The composable UI in
    // this same body uses the same helper for uniformity.
    val resources = LocalResources.current

    fun str(
        resId: Int,
        vararg args: Any,
    ): String = resources.getString(resId, *args)

    // HXA-069: array overload for the transfer-summary results (a pre-built arg array from
    // [transferSummary]); the single spread lives here (detekt SpreadOperator), not at the 3 call
    // sites. Kotlin prefers this direct match over the vararg overload, so `str(id, arr)` is unambiguous.
    @Suppress("SpreadOperator") // discrete transfer-summary resolve; getString's vararg API has no array overload
    fun str(
        resId: Int,
        args: Array<out Any>,
    ): String = resources.getString(resId, *args)

    // The browsable sources (workspace + developer all-files + LIVE SAF scopes). Mutable so a
    // grant/revoke re-verifies and refreshes the list (a revoked SAF grant disappears).
    var sources by remember { mutableStateOf(fileManager.sources()) }
    var selectedScopeId by remember { mutableStateOf(sources.first().scopeId) }
    var currentPath by remember { mutableStateOf("") }
    var sortKey by remember { mutableStateOf(SortKey.NAME) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var reloadTick by remember { mutableIntStateOf(0) }

    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember { mutableStateOf<String?>(null) }
    var batchFailures by remember { mutableStateOf<List<BatchItem>>(emptyList()) }

    // HXA-057: the SAF management panel (重新授权 / 移除). SAF scopes are read-only here; the
    // panel is the only place a grant is added or revoked, and both re-verify through the service.
    // Declared after `status`/`sources` so the picker callback can set them.
    var safPanelOpen by remember { mutableStateOf(false) }
    var safSources by remember { mutableStateOf<List<SafTreeSource>>(emptyList()) }
    val treePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                scope.launch {
                    // The picker's tree URI is stored model-opaquely (doc 10); the display name is a
                    // best-effort root folder name, sanitized by the store. Never logged.
                    val name =
                        withContext(Dispatchers.IO) {
                            val lastSegment = uri.lastPathSegment?.let { Uri.decode(it) }
                            val treeName = lastSegment ?: str(R.string.files_saf_directory_fallback)
                            safTree.grant(uri.toString(), treeName).displayName
                        }
                    sources = withContext(Dispatchers.IO) { fileManager.sources() }
                    status = str(R.string.files_saf_granted, name)
                }
            }
        }

    var trashOpen by remember { mutableStateOf(false) }
    var trashEntries by remember { mutableStateOf<List<TrashEntryView>>(emptyList()) }

    var openFile by remember { mutableStateOf<FileEntry?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var fileInfo by remember { mutableStateOf<FileMeta?>(null) }

    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var copyMove by remember { mutableStateOf<CopyMoveTarget?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var conflictTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var suggestedName by remember { mutableStateOf<String?>(null) }

    var batchBusy by remember { mutableStateOf(false) }
    var batchLabel by remember { mutableStateOf<String?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }

    // ── HXA-058: 导入/导出 state (dialog + progress + result) ─────────────────────────────
    // Import: one dialog covers 来源选择 (单个文件 / 文件夹) + 冲突策略 + 进度/取消 + 最终结果.
    var importOpen by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf(ImportMode.FILE) }
    var importPolicy by remember { mutableStateOf(ConflictPolicy.ASK) }
    var importBusy by remember { mutableStateOf(false) }
    var importLabel by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<TransferResult?>(null) }
    val importCancel = remember { AtomicBoolean(false) }
    // Export: destination 新建文档 (ACTION_CREATE_DOCUMENT) or 已授权 SAF 目录 (HXA-057 tree).
    var exportOpen by remember { mutableStateOf(false) }
    var exportMode by remember { mutableStateOf(ExportMode.NEW_DOC) }
    var exportScopeId by remember { mutableStateOf<String?>(null) }
    var exportParent by remember { mutableStateOf("") }
    var exportPolicy by remember { mutableStateOf(ConflictPolicy.ASK) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportLabel by remember { mutableStateOf<String?>(null) }
    var exportResult by remember { mutableStateOf<TransferResult?>(null) }
    val exportCancel = remember { AtomicBoolean(false) }
    var exportSources by remember { mutableStateOf<List<SafTreeSource>>(emptyList()) }

    // The file the export dialog is about (set by the preview dialog's 导出 button).
    var exportFile by remember { mutableStateOf<FileEntry?>(null) }

    val currentSource = sources.first { it.scopeId == selectedScopeId }
    val canMutate = currentSource.supportsMutation

    fun runImportSingle(
        uri: String,
        policy: ConflictPolicy,
    ) {
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
                            importLabel = str(R.string.files_importing_progress, formatSize(done), formatSize(total))
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

    fun runImportTree(
        uri: String,
        policy: ConflictPolicy,
    ) {
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

    fun runExport(
        fileRel: String,
        target: ExportTarget,
        policy: ConflictPolicy,
    ) {
        scope.launch {
            exportBusy = true
            exportResult = null
            exportCancel.set(false)
            exportLabel = str(R.string.files_exporting_plain)
            val result =
                withContext(Dispatchers.IO) {
                    fileManager.exportDocument(fileRel, target, policy, exportCancel::get) { done, total ->
                        if (total > 0) {
                            exportLabel = str(R.string.files_exporting_progress, formatSize(done), formatSize(total))
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

    // HXA-058: the transfer pickers (one-shot grants; a null result = the user backed out — the
    // dialog simply stays open, nothing is imported/exported).
    val importFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runImportSingle(uri.toString(), importPolicy)
        }
    val importFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) runImportTree(uri.toString(), importPolicy)
        }
    val exportDocPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
            if (uri != null) {
                exportFile?.let { file ->
                    val label = uri.lastPathSegment ?: str(R.string.files_new_document_fallback)
                    runExport(file.relativePath, ExportTarget.Document(uri.toString(), label), exportPolicy)
                }
            }
        }

    // ── Data loading (all file access is containment-enforced by the service; IO off the UI thread) ──
    LaunchedEffect(selectedScopeId, currentPath, sortKey, reloadTick, trashOpen) {
        if (trashOpen) return@LaunchedEffect
        loadError = null
        val result =
            withContext(Dispatchers.IO) {
                runCatching { fileManager.list(selectedScopeId, currentPath, sortKey) }
            }
        result.fold(
            onSuccess = {
                entries = it
                selected = emptySet()
            },
            onFailure = { loadError = it.message ?: str(R.string.files_read_directory_error) },
        )
    }

    LaunchedEffect(trashOpen, selectedScopeId, reloadTick) {
        if (!trashOpen) return@LaunchedEffect
        val result =
            withContext(Dispatchers.IO) {
                runCatching { fileManager.listTrash(selectedScopeId) }
            }
        trashEntries = result.getOrDefault(emptyList())
    }

    // The open file's preview + metadata (text/image first, then the bounded info incl. SHA-256).
    LaunchedEffect(openFile?.relativePath, selectedScopeId) {
        val file = openFile ?: return@LaunchedEffect
        previewText = null
        previewImage = null
        fileInfo = null
        if (file.isDirectory) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val text = fileManager.previewText(selectedScopeId, file.relativePath)
            if (text != null) {
                previewText = text
            } else {
                val bytes = fileManager.previewImageBytes(selectedScopeId, file.relativePath)
                if (bytes.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) previewImage = bitmap.asImageBitmap()
                }
            }
            fileInfo = fileManager.fileInfo(selectedScopeId, file.relativePath)
        }
    }

    // The conflict dialog's suggested "重命名" target (the next non-colliding sibling).
    LaunchedEffect(conflictTarget) {
        val target = conflictTarget ?: return@LaunchedEffect
        suggestedName =
            withContext(Dispatchers.IO) { fileManager.nextAvailableName(selectedScopeId, target.second) }
    }

    // HXA-057: when the SAF panel opens, re-verify the live grants (a revoked / dead grant is
    // dropped) — the list never offers a scope the resolver cannot actually resolve.
    LaunchedEffect(safPanelOpen) {
        if (!safPanelOpen) return@LaunchedEffect
        safSources = withContext(Dispatchers.IO) { runCatching { safTree.liveSources() }.getOrDefault(emptyList()) }
    }

    // HXA-058: when the export dialog opens — and each time the destination shape switches to
    // the authorized-tree mode — re-verify the live SAF grants (the export-to-tree destination
    // list never offers a scope the resolver cannot resolve; a WRITE re-verification still
    // happens at export time, fail closed).
    LaunchedEffect(exportOpen, exportMode) {
        if (!exportOpen || exportMode != ExportMode.TREE) return@LaunchedEffect
        exportSources = withContext(Dispatchers.IO) { runCatching { safTree.liveSources() }.getOrDefault(emptyList()) }
    }

    // ── Actions (each delegates to the service; no direct file or DAO access) ─────────────────
    fun onEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            currentPath = entry.relativePath
        } else {
            openFile = entry
        }
    }

    fun toggleSelect(entry: FileEntry) {
        selected =
            if (entry.relativePath in selected) {
                selected - entry.relativePath
            } else {
                selected + entry.relativePath
            }
    }

    fun doRename(
        srcRel: String,
        dstRel: String,
        overwrite: Boolean,
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { fileManager.rename(selectedScopeId, srcRel, dstRel, overwrite) }
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

    fun startBatch(
        total: Int,
        work: (
            progress: (Int, Int) -> Unit,
            cancel: () -> Boolean,
        ) -> BatchResult,
    ) {
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

    fun startCopyMove(
        sourceRels: List<String>,
        destDir: String,
        policy: ConflictPolicy,
        move: Boolean,
    ) {
        if (destDir.isBlank()) {
            status = str(R.string.files_dest_dir_required)
            return
        }
        startBatch(sourceRels.size) { progress, cancel ->
            fileManager.batchMoveOrCopy(selectedScopeId, sourceRels, destDir, policy, move, progress, cancel)
        }
    }

    fun startTrash(sourceRels: List<String>) {
        if (!canMutate) {
            status = str(R.string.files_source_read_only)
            return
        }
        startBatch(sourceRels.size) { progress, cancel ->
            fileManager.batchTrash(selectedScopeId, sourceRels, progress, cancel)
        }
    }

    fun restoreTrashEntry(entry: TrashEntryView) {
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

    fun purgeTrashEntry(entry: TrashEntryView) {
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

    fun emptyTrashPanel() {
        scope.launch {
            val count = withContext(Dispatchers.IO) { fileManager.emptyTrash(selectedScopeId) }
            status = str(R.string.files_trash_emptied, count)
            reloadTick++
        }
    }

    @Suppress("TooGenericExceptionCaught") // share maps any I/O or FileProvider failure to a status string
    fun share(file: FileEntry) {
        scope.launch {
            try {
                val realFile =
                    withContext(Dispatchers.IO) { fileManager.realFileFor(selectedScopeId, file.relativePath) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", realFile)
                val mime = withContext(Dispatchers.IO) { fileManager.mimeTypeFor(selectedScopeId, file.relativePath) }
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

    /** The transfer (导入/导出) final-result panel: per-item outcome + the temp-reclaim count. */
    @Composable
    fun TransferResultPanel(
        result: TransferResult,
        tag: String,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.testTag(tag),
        ) {
            Text(
                str(
                    R.string.files_transfer_summary,
                    result.completed.size,
                    result.problems.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.reclaimedTempFiles > 0) {
                Text(
                    str(R.string.files_temp_reclaimed, result.reclaimedTempFiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            result.items.take(MAX_FAILURE_DETAIL_LINES).forEach { item ->
                val mark =
                    when (item.status) {
                        TransferItemStatus.COMPLETED -> str(R.string.files_transfer_item_completed)
                        TransferItemStatus.CONFLICT -> str(R.string.files_transfer_item_conflict)
                        TransferItemStatus.SKIPPED -> str(R.string.files_transfer_item_skipped)
                        TransferItemStatus.CANCELLED -> str(R.string.files_transfer_item_cancelled)
                        TransferItemStatus.FAILED -> str(R.string.files_transfer_item_failed)
                    }
                val detail =
                    item.detail?.let { str(R.string.files_transfer_item_detail, it) } ?: ""
                val verified = if (item.verified) str(R.string.files_transfer_item_verified) else ""
                Text(
                    str(
                        R.string.files_transfer_item_line,
                        mark,
                        item.sourceLabel,
                        item.targetLabel,
                        detail,
                        verified,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.items.size > MAX_FAILURE_DETAIL_LINES) {
                Text(
                    str(R.string.files_items_overflow, result.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("screen-files"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 来源标识: switchable source chips + the always-shown current source.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            sources.forEach { source ->
                TextButton(
                    onClick = {
                        if (source.scopeId != selectedScopeId) {
                            selectedScopeId = source.scopeId
                            currentPath = ""
                            trashOpen = false
                        }
                    },
                    modifier = Modifier.testTag("files-source-${source.scopeId}"),
                ) {
                    Text(source.displayName)
                }
            }
        }
        Text(
            str(R.string.files_current_source, currentSource.displayName) +
                if (canMutate) "" else str(R.string.files_read_only_suffix),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("files-source-current"),
        )

        // 路径面包屑 + 排序 + 视图 + 工具按钮.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("files-breadcrumb"),
            ) {
                BreadcrumbCrumb(str(R.string.files_root_directory), isRoot = true, onClick = { currentPath = "" })
                currentPath
                    .split("/")
                    .filter { it.isNotEmpty() }
                    .mapIndexed { index, segment ->
                        val prefix =
                            currentPath
                                .split("/")
                                .filter { it.isNotEmpty() }
                                .take(index + 1)
                                .joinToString("/")
                        BreadcrumbCrumb(segment, isRoot = false, onClick = { currentPath = prefix })
                    }
            }
            // Three rows: sort / view toggles / actions. A single row overflows the narrow phone
            // toolbar and clips the trailing buttons off-screen / zero-width (device-verified:
            // with 视图+动作 on one row the 导入 button measured 0dp wide on the 1080px gate
            // device and was unclickable); each row here fits with margin to spare.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortButton(str(R.string.files_sort_name), SortKey.NAME, sortKey, onPick = { sortKey = it })
                SortButton(str(R.string.files_sort_time), SortKey.TIME, sortKey, onPick = { sortKey = it })
                SortButton(str(R.string.files_sort_size), SortKey.SIZE, sortKey, onPick = { sortKey = it })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { viewMode = ViewMode.LIST }, modifier = Modifier.testTag("files-view-list")) {
                    Text(str(R.string.files_view_list))
                }
                TextButton(onClick = { viewMode = ViewMode.GRID }, modifier = Modifier.testTag("files-view-grid")) {
                    Text(str(R.string.files_view_grid))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { trashOpen = true }, modifier = Modifier.testTag("files-trash-open")) {
                    Text(str(R.string.files_trash_button))
                }
                if (canMutate) {
                    TextButton(onClick = { newFolderOpen = true }, modifier = Modifier.testTag("files-newfolder")) {
                        Text(str(R.string.files_new_folder_button))
                    }
                }
                // HXA-057: the visible 重新授权 / 移除 entry for SAF tree scopes.
                TextButton(onClick = { safPanelOpen = true }, modifier = Modifier.testTag("files-saf-open")) {
                    Text(str(R.string.files_saf_button))
                }
                // HXA-058: the 导入 entry (a single document or a folder into the Workspace).
                TextButton(
                    onClick = {
                        importResult = null
                        importOpen = true
                    },
                    modifier = Modifier.testTag("files-import-open"),
                ) {
                    Text(str(R.string.files_import_button))
                }
            }
        }

        // 长操作进度/取消 + 状态 + 部分失败清单.
        if (batchBusy) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    batchLabel ?: str(R.string.files_batch_in_progress),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("files-batch-progress"),
                )
                TextButton(onClick = { cancelFlag.set(true) }, modifier = Modifier.testTag("files-batch-cancel")) {
                    Text(str(R.string.common_cancel))
                }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("files-status"))
        }
        if (batchFailures.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.testTag("files-failure-list"),
            ) {
                batchFailures.take(MAX_FAILURE_DETAIL_LINES).forEach { item ->
                    val detail = item.detail.ifBlank { "-" }
                    Text(
                        "· ${item.sourceRelativePath} — ${str(item.outcomeLabel())}：$detail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (batchFailures.size > MAX_FAILURE_DETAIL_LINES) {
                    Text(
                        str(R.string.files_items_overflow, batchFailures.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()

        // Body: the trash panel, or the directory listing (list / grid).
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (trashOpen) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(str(R.string.files_trash_title), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { trashOpen = false }, modifier = Modifier.testTag("files-trash-back")) {
                            Text(str(R.string.files_back))
                        }
                        if (canMutate) {
                            TextButton(
                                onClick = { emptyTrashPanel() },
                                modifier = Modifier.testTag("files-trash-empty"),
                            ) {
                                Text(str(R.string.files_empty_trash))
                            }
                        }
                    }
                    if (trashEntries.isEmpty()) {
                        Text(
                            str(R.string.files_trash_empty_state),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("files-trash-empty-state"),
                        )
                    }
                    trashEntries.forEach { entry ->
                        TrashRow(
                            entry,
                            canMutate,
                            onRestore = { restoreTrashEntry(it) },
                            onPurge = { purgeTrashEntry(it) },
                        )
                    }
                }
            } else if (loadError != null) {
                Text(
                    loadError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("files-error"),
                )
            } else if (entries.isEmpty()) {
                Text(
                    str(R.string.files_empty_directory),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("files-empty"),
                )
            } else if (viewMode == ViewMode.LIST) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entries.forEach { entry ->
                        FileRow(
                            entry,
                            selected.contains(entry.relativePath),
                            onToggle = { toggleSelect(entry) },
                            onClick = { onEntry(entry) },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries) { entry ->
                        GridFileItem(
                            entry,
                            selected.contains(entry.relativePath),
                            onToggle = { toggleSelect(entry) },
                            onClick = { onEntry(entry) },
                        )
                    }
                }
            }
        }

        // 多选 action bar.
        if (selected.isNotEmpty() && !trashOpen && canMutate) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.files_selected_count, selected.size), style = MaterialTheme.typography.bodyMedium)
                val selectedRels = selected.toList()
                TextButton(
                    onClick = { copyMove = CopyMoveTarget(move = false, selectedRels) },
                    modifier = Modifier.testTag("files-batch-copy"),
                ) {
                    Text(str(R.string.files_copy))
                }
                TextButton(
                    onClick = { copyMove = CopyMoveTarget(move = true, selectedRels) },
                    modifier = Modifier.testTag("files-batch-move"),
                ) {
                    Text(str(R.string.files_move))
                }
                TextButton(onClick = { startTrash(selectedRels) }, modifier = Modifier.testTag("files-batch-trash")) {
                    Text(str(R.string.files_delete))
                }
                TextButton(onClick = { selected = emptySet() }, modifier = Modifier.testTag("files-batch-clear")) {
                    Text(str(R.string.files_deselect))
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────────────
    openFile?.let { file ->
        if (!file.isDirectory) {
            AlertDialog(
                onDismissRequest = { openFile = null },
                title = { Text(file.name) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            previewImage != null -> {
                                Image(
                                    bitmap = previewImage!!,
                                    contentDescription = file.name,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                            .testTag("files-preview-image"),
                                )
                            }

                            previewText != null -> {
                                Text(
                                    previewText.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier =
                                        Modifier
                                            .testTag("files-preview-text")
                                            .verticalScroll(rememberScrollState())
                                            .padding(4.dp),
                                )
                            }

                            else -> {
                                Text(
                                    str(R.string.files_no_preview),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("files-preview-none"),
                                )
                            }
                        }
                        fileInfo?.let { meta ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    str(R.string.files_info_size, formatSize(meta.sizeBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    str(R.string.files_info_modified, formatTime(meta.mtimeEpochMillis)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    str(R.string.files_info_type, meta.mimeType),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    str(
                                        R.string.files_info_sha256,
                                        meta.sha256 ?: str(R.string.files_sha_omitted),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("files-info-sha"),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    // Two rows: the AlertDialog confirmButton is a single narrow strip; one row
                    // overflows it and clips the trailing buttons zero-width (device-verified:
                    // 导出 measured 0dp wide on the 1080px gate device and was unclickable).
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (canMutate) {
                                TextButton(
                                    onClick = {
                                        openFile = null
                                        renameTarget = file
                                    },
                                    modifier = Modifier.testTag("files-action-rename"),
                                ) {
                                    Text(str(R.string.files_rename))
                                }
                                TextButton(
                                    onClick = { copyMove = CopyMoveTarget(move = false, listOf(file.relativePath)) },
                                    modifier = Modifier.testTag("files-action-copy"),
                                ) {
                                    Text(str(R.string.files_copy))
                                }
                                TextButton(
                                    onClick = { copyMove = CopyMoveTarget(move = true, listOf(file.relativePath)) },
                                    modifier = Modifier.testTag("files-action-move"),
                                ) {
                                    Text(str(R.string.files_move))
                                }
                            }
                            TextButton(
                                onClick = {
                                    openFile = null
                                    startTrash(listOf(file.relativePath))
                                },
                                modifier = Modifier.testTag("files-action-trash"),
                            ) {
                                Text(str(R.string.files_trash_action))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { share(file) }, modifier = Modifier.testTag("files-action-share")) {
                                Text(str(R.string.files_share))
                            }
                            // HXA-058: the 导出 entry — Workspace files only (the HXA-044 export region
                            // gate: input/work/output; SAF/all-files sources are never export sources).
                            if (currentSource.kind == FileSourceKind.WORKSPACE) {
                                TextButton(
                                    onClick = {
                                        exportFile = file
                                        exportResult = null
                                        exportOpen = true
                                    },
                                    modifier = Modifier.testTag("files-action-export"),
                                ) {
                                    Text(str(R.string.files_export))
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { openFile = null }, modifier = Modifier.testTag("files-preview-close")) {
                        Text(str(R.string.files_close))
                    }
                },
                modifier = Modifier.testTag("files-preview-dialog"),
            )
        }
    }

    renameTarget?.let { target ->
        // Start empty: the user types the new name fresh (the label hints at it) rather than editing
        // a pre-filled name, which keeps the entry unambiguous.
        var newName by remember(target.relativePath) { mutableStateOf("") }
        val renameFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { renameFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(str(R.string.files_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(str(R.string.files_new_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.focusRequester(renameFocus).testTag("files-rename-field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chosen = newName.trim()
                        if (chosen.isEmpty()) {
                            status = str(R.string.files_name_required)
                            renameTarget = null
                        } else {
                            val dir = target.relativePath.substringBeforeLast('/')
                            val dstRel = if (dir.isEmpty()) chosen else "$dir/$chosen"
                            renameTarget = null
                            openFile = null
                            doRename(target.relativePath, dstRel, overwrite = false)
                        }
                    },
                    modifier = Modifier.testTag("files-rename-confirm"),
                ) {
                    Text(str(R.string.files_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }, modifier = Modifier.testTag("files-rename-cancel")) {
                    Text(str(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag("files-rename-dialog"),
        )
    }

    copyMove?.let { cm ->
        var destDir by remember { mutableStateOf(currentPath) }
        var policy by remember { mutableStateOf(ConflictPolicy.ASK) }
        AlertDialog(
            onDismissRequest = { copyMove = null },
            title = { Text(if (cm.move) str(R.string.files_move_selected) else str(R.string.files_copy_selected)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = destDir,
                        onValueChange = { destDir = it },
                        label = { Text(str(R.string.files_dest_field)) },
                        singleLine = true,
                        modifier = Modifier.testTag("files-dest-field"),
                    )
                    Text(
                        str(R.string.files_conflict_policy),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ConflictPolicy.entries.forEach { p ->
                            TextButton(
                                onClick = { policy = p },
                                modifier = Modifier.testTag("files-policy-${p.name}"),
                            ) {
                                Text(str(policyLabel(p)))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val srcs = cm.sourceRels
                        copyMove = null
                        startCopyMove(srcs, destDir.trim(), policy, cm.move)
                    },
                    modifier = Modifier.testTag("files-dest-confirm"),
                ) {
                    Text(str(R.string.files_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { copyMove = null }, modifier = Modifier.testTag("files-dest-cancel")) {
                    Text(str(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag("files-dest-dialog"),
        )
    }

    if (newFolderOpen) {
        var folderName by remember { mutableStateOf("") }
        val newFolderFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { newFolderFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { newFolderOpen = false },
            title = { Text(str(R.string.files_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(str(R.string.files_folder_name_field)) },
                    singleLine = true,
                    modifier = Modifier.focusRequester(newFolderFocus).testTag("files-newfolder-field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = folderName.trim()
                        newFolderOpen = false
                        if (name.isEmpty()) {
                            status = str(R.string.files_name_required)
                            return@TextButton
                        }
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
                    },
                    modifier = Modifier.testTag("files-newfolder-confirm"),
                ) {
                    Text(str(R.string.files_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { newFolderOpen = false }, modifier = Modifier.testTag("files-newfolder-cancel")) {
                    Text(str(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag("files-newfolder-dialog"),
        )
    }

    conflictTarget?.let { (srcRel, dstRel) ->
        AlertDialog(
            onDismissRequest = { conflictTarget = null },
            title = { Text(str(R.string.files_conflict_title)) },
            text = {
                Text(
                    str(
                        R.string.files_conflict_message,
                        dstRel.substringAfterLast('/'),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val suggested = suggestedName
                        conflictTarget = null
                        if (suggested != null) doRename(srcRel, suggested, overwrite = false)
                    },
                    modifier = Modifier.testTag("files-conflict-rename"),
                ) {
                    Text(str(R.string.files_rename))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            conflictTarget = null
                            doRename(srcRel, dstRel, overwrite = true)
                        },
                        modifier = Modifier.testTag("files-conflict-overwrite"),
                    ) {
                        Text(str(R.string.files_overwrite))
                    }
                    TextButton(
                        onClick = {
                            conflictTarget = null
                            status = str(R.string.files_skipped_status)
                        },
                        modifier = Modifier.testTag("files-conflict-skip"),
                    ) {
                        Text(str(R.string.files_skip))
                    }
                }
            },
            modifier = Modifier.testTag("files-conflict-dialog"),
        )
    }

    // ── HXA-058: the 导入 dialog (来源 / 目标 / 冲突策略 / 进度 / 取消 / 最终结果) ──────────
    if (importOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!importBusy) importOpen = false
            },
            title = { Text(str(R.string.files_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!importBusy && importResult == null) {
                        Text(
                            str(R.string.files_import_source_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { importMode = ImportMode.FILE },
                                modifier = Modifier.testTag("files-import-file"),
                            ) {
                                Text(
                                    if (importMode == ImportMode.FILE) {
                                        str(R.string.files_import_single_selected)
                                    } else {
                                        str(R.string.files_import_single)
                                    },
                                )
                            }
                            TextButton(
                                onClick = { importMode = ImportMode.FOLDER },
                                modifier = Modifier.testTag("files-import-folder"),
                            ) {
                                Text(
                                    if (importMode == ImportMode.FOLDER) {
                                        str(R.string.files_import_folder_selected)
                                    } else {
                                        str(R.string.files_import_folder)
                                    },
                                )
                            }
                        }
                        Text(
                            str(R.string.files_import_target),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("files-import-target"),
                        )
                        Text(
                            str(R.string.files_conflict_policy),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConflictPolicy.entries.forEach { p ->
                                TextButton(
                                    onClick = { importPolicy = p },
                                    modifier = Modifier.testTag("files-import-policy-${p.name}"),
                                ) {
                                    Text(str(policyLabel(p)))
                                }
                            }
                        }
                    } else if (importBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("files-import-progress"))
                        Text(
                            importLabel ?: str(R.string.files_importing_plain),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("files-import-label"),
                        )
                    } else {
                        importResult?.let { TransferResultPanel(it, "files-import-result") }
                    }
                }
            },
            confirmButton = {
                when {
                    importBusy -> {
                        TextButton(
                            onClick = { importCancel.set(true) },
                            modifier = Modifier.testTag("files-import-cancel"),
                        ) {
                            Text(str(R.string.common_cancel))
                        }
                    }

                    importResult != null -> {
                        TextButton(
                            onClick = { importOpen = false },
                            modifier = Modifier.testTag("files-import-close"),
                        ) {
                            Text(str(R.string.files_close))
                        }
                    }

                    else -> {
                        TextButton(
                            onClick = {
                                if (importMode == ImportMode.FILE) {
                                    importFilePicker.launch(arrayOf("*/*"))
                                } else {
                                    importFolderPicker.launch(null)
                                }
                            },
                            modifier = Modifier.testTag("files-import-confirm"),
                        ) {
                            Text(str(R.string.files_import_choose_and_import))
                        }
                    }
                }
            },
            dismissButton = {
                if (!importBusy) {
                    TextButton(onClick = { importOpen = false }, modifier = Modifier.testTag("files-import-dismiss")) {
                        Text(str(R.string.files_close))
                    }
                }
            },
            modifier = Modifier.testTag("files-import-dialog"),
        )
    }

    // ── HXA-058: the 导出 dialog (目标 / 冲突策略 / 进度 / 取消 / 最终结果 + verified) ───────
    if (exportOpen) {
        val exportFileEntry = exportFile
        AlertDialog(
            onDismissRequest = {
                if (!exportBusy) {
                    exportOpen = false
                    exportFile = null
                }
            },
            title = { Text(str(R.string.files_export_title, exportFileEntry?.name ?: "")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!exportBusy && exportResult == null) {
                        Text(
                            str(
                                R.string.files_export_source_line,
                                exportFileEntry?.relativePath.orEmpty(),
                                fileInfo?.let { str(R.string.files_export_source_size, formatSize(it.sizeBytes)) }
                                    ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("files-export-source"),
                        )
                        Text(str(R.string.files_export_source_label), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { exportMode = ExportMode.NEW_DOC },
                                modifier = Modifier.testTag("files-export-newdoc"),
                            ) {
                                Text(
                                    if (exportMode == ExportMode.NEW_DOC) {
                                        str(R.string.files_export_newdoc_selected)
                                    } else {
                                        str(R.string.files_export_newdoc)
                                    },
                                )
                            }
                            TextButton(
                                onClick = { exportMode = ExportMode.TREE },
                                modifier = Modifier.testTag("files-export-tree"),
                            ) {
                                Text(
                                    if (exportMode == ExportMode.TREE) {
                                        str(R.string.files_export_tree_selected)
                                    } else {
                                        str(R.string.files_export_tree)
                                    },
                                )
                            }
                        }
                        when (exportMode) {
                            ExportMode.NEW_DOC -> {
                                Text(
                                    str(R.string.files_export_newdoc_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            ExportMode.TREE -> {
                                if (exportSources.isEmpty()) {
                                    Text(
                                        str(R.string.files_export_sources_empty_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("files-export-sources-empty"),
                                    )
                                }
                                exportSources.forEach { source ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier =
                                            Modifier.fillMaxWidth().testTag(
                                                "files-export-scope-${source.scopeId}",
                                            ),
                                    ) {
                                        Text(
                                            if (exportScopeId ==
                                                source.scopeId
                                            ) {
                                                "● ${source.displayName}"
                                            } else {
                                                source.displayName
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        TextButton(
                                            onClick = { exportScopeId = source.scopeId },
                                            modifier = Modifier.testTag("files-export-scope-pick-${source.scopeId}"),
                                        ) {
                                            Text(str(R.string.files_select))
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = exportParent,
                                    onValueChange = { exportParent = it },
                                    label = { Text(str(R.string.files_export_parent_field)) },
                                    singleLine = true,
                                    modifier = Modifier.testTag("files-export-parent"),
                                )
                                Text(
                                    str(R.string.files_conflict_policy),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ConflictPolicy.entries.forEach { p ->
                                        TextButton(
                                            onClick = { exportPolicy = p },
                                            modifier = Modifier.testTag("files-export-policy-${p.name}"),
                                        ) {
                                            Text(str(policyLabel(p)))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (exportBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("files-export-progress"))
                        Text(
                            exportLabel ?: str(R.string.files_exporting_plain),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("files-export-label"),
                        )
                    } else {
                        exportResult?.let { TransferResultPanel(it, "files-export-result") }
                    }
                }
            },
            confirmButton = {
                when {
                    exportBusy -> {
                        TextButton(
                            onClick = { exportCancel.set(true) },
                            modifier = Modifier.testTag("files-export-cancel"),
                        ) {
                            Text(str(R.string.common_cancel))
                        }
                    }

                    exportResult != null -> {
                        TextButton(
                            onClick = {
                                exportOpen = false
                                exportFile = null
                            },
                            modifier = Modifier.testTag("files-export-close"),
                        ) {
                            Text(str(R.string.files_close))
                        }
                    }

                    else -> {
                        TextButton(
                            onClick = {
                                when (exportMode) {
                                    ExportMode.NEW_DOC -> {
                                        val mime = fileInfo?.mimeType?.ifEmpty { null } ?: "application/octet-stream"
                                        exportDocPicker.launch(mime)
                                    }

                                    ExportMode.TREE -> {
                                        val scopeId = exportScopeId
                                        val file = exportFile
                                        if (scopeId == null) {
                                            status = str(R.string.files_export_scope_required)
                                            return@TextButton
                                        }
                                        if (file == null) {
                                            exportOpen = false
                                            return@TextButton
                                        }
                                        runExport(
                                            file.relativePath,
                                            ExportTarget.TreeDestination(scopeId, exportParent.trim()),
                                            exportPolicy,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.testTag("files-export-confirm"),
                        ) {
                            Text(str(R.string.files_export_choose_and_export))
                        }
                    }
                }
            },
            dismissButton = {
                if (!exportBusy) {
                    TextButton(
                        onClick = {
                            exportOpen = false
                            exportFile = null
                        },
                        modifier = Modifier.testTag("files-export-dismiss"),
                    ) {
                        Text(str(R.string.files_close))
                    }
                }
            },
            modifier = Modifier.testTag("files-export-dialog"),
        )
    }

    // HXA-057: the SAF tree scope management panel (可见的重新授权 / 移除入口). SAF scopes are
    // read-only here; adding or revoking re-verifies through the service (a grant only appears if
    // the resolver can actually resolve it right now).
    if (safPanelOpen) {
        AlertDialog(
            onDismissRequest = { safPanelOpen = false },
            title = { Text(str(R.string.files_saf_panel_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        str(R.string.files_saf_panel_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (safSources.isEmpty()) {
                        Text(
                            str(R.string.files_saf_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("files-saf-empty"),
                        )
                    }
                    safSources.forEach { source ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().testTag("files-saf-source-${source.scopeId}"),
                        ) {
                            Column {
                                Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    source.scopeId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            safTree.revoke(source.scopeId)
                                            sources = fileManager.sources()
                                        }
                                        status = str(R.string.files_saf_removed, source.displayName)
                                    }
                                },
                                modifier = Modifier.testTag("files-saf-remove-${source.scopeId}"),
                            ) {
                                Text(str(R.string.files_remove))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { treePicker.launch(null) },
                    modifier = Modifier.testTag("files-saf-add"),
                ) {
                    Text(str(R.string.files_add_reauthorize))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { safPanelOpen = false },
                    modifier = Modifier.testTag("files-saf-close"),
                ) {
                    Text(str(R.string.files_close))
                }
            },
            modifier = Modifier.testTag("files-saf-dialog"),
        )
    }
}

/** The destination + policy for a batch copy/move (a single file is a one-item batch). */
private data class CopyMoveTarget(
    val move: Boolean,
    val sourceRels: List<String>,
)

/** The two file-list rendering modes (HXA-046 列表和网格视图). */
private enum class ViewMode {
    LIST,
    GRID,
}

/** A breadcrumb segment: the root plus each path component (tappable to navigate up). */
@Composable
@Suppress("FunctionName")
private fun BreadcrumbCrumb(
    label: String,
    isRoot: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag(if (isRoot) "files-breadcrumb-root" else "files-breadcrumb-crumb-$label"),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (!isRoot) Text(">", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@Suppress("FunctionName")
private fun SortButton(
    label: String,
    key: SortKey,
    current: SortKey,
    onPick: (SortKey) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (current == key) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        modifier =
            Modifier
                .testTag("files-sort-${key.name}")
                .clickable { onPick(key) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
@Suppress("FunctionName")
private fun FileRow(
    entry: FileEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("files-entry-${entry.name}")
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("files-select-${entry.name}"),
        )
        Text(if (entry.isDirectory) "📁 " else "📄 ", style = MaterialTheme.typography.bodyLarge)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sizeOrType =
                if (entry.isDirectory) {
                    stringResource(R.string.files_folder_type)
                } else {
                    formatSize(entry.sizeBytes)
                }
            val metaLabel = "$sizeOrType  ·  ${formatTime(entry.mtimeEpochMillis)}"
            Text(
                metaLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun GridFileItem(
    entry: FileEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("files-entry-${entry.name}")
                .clickable(onClick = onClick),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("files-select-${entry.name}"),
        )
        Text(if (entry.isDirectory) "📁" else "📄", style = MaterialTheme.typography.headlineSmall)
        Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
@Suppress("FunctionName")
private fun TrashRow(
    entry: TrashEntryView,
    canMutate: Boolean,
    onRestore: (TrashEntryView) -> Unit,
    onPurge: (TrashEntryView) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag("files-trash-entry-${entry.entryName}"),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.originalRelativePath,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canMutate) {
            TextButton(
                onClick = { onRestore(entry) },
                modifier = Modifier.testTag("files-trash-restore-${entry.entryName}"),
            ) {
                Text(stringResource(R.string.files_restore))
            }
            TextButton(
                onClick = { onPurge(entry) },
                modifier = Modifier.testTag("files-trash-purge-${entry.entryName}"),
            ) {
                Text(stringResource(R.string.files_purge_button))
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/** How many partial-failure lines the screen renders before collapsing to "… 等 N 项". */
private const val MAX_FAILURE_DETAIL_LINES = 10

private fun formatTime(millis: Long): String = if (millis <= 0L) "—" else timeFormat.format(Date(millis))

private fun formatSize(bytes: Long): String =
    when {
        bytes < 0L -> "—"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

/** HXA-069: the conflict-policy option label as a STABLE string-resource id (resolved by the UI). */
private fun policyLabel(policy: ConflictPolicy): Int =
    when (policy) {
        ConflictPolicy.ASK -> R.string.files_ask
        ConflictPolicy.SKIP -> R.string.files_skip
        ConflictPolicy.RENAME -> R.string.files_rename
        ConflictPolicy.OVERWRITE -> R.string.files_overwrite
    }

/** HXA-069: one batched item's outcome as a STABLE string-resource id (resolved by the UI). */
private fun BatchItem.outcomeLabel(): Int =
    when (outcome) {
        BatchItem.Outcome.SUCCEEDED -> R.string.files_batch_succeeded
        BatchItem.Outcome.RENAMED -> R.string.files_batch_renamed
        BatchItem.Outcome.SKIPPED -> R.string.files_batch_skipped
        BatchItem.Outcome.FAILED -> R.string.files_batch_failed
    }

/** HXA-058: the import source shape (the two picker actions). */
private enum class ImportMode {
    FILE,
    FOLDER,
}

/** HXA-058: the export destination shape (picker document vs authorized SAF tree). */
private enum class ExportMode {
    NEW_DOC,
    TREE,
}

/**
 * HXA-058: the screen-level transfer status line, as a STABLE string-resource id + its positional
 * args (HXA-069: never locale text outside composables — the UI resolves it via stringResource).
 */
private fun transferSummary(
    verb: String,
    result: TransferResult,
): Pair<Int, Array<Any>> =
    if (result.problems.isEmpty()) {
        R.string.files_transfer_complete_all to arrayOf(verb, result.completed.size)
    } else {
        R.string.files_transfer_complete_partial to arrayOf(verb, result.completed.size, result.problems.size)
    }
