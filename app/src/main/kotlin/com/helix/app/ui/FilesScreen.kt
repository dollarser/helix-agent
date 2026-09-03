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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.helix.app.FeatureFiles
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
                            val treeName = uri.lastPathSegment?.let { Uri.decode(it) } ?: "SAF 目录"
                            safTree.grant(uri.toString(), treeName).displayName
                        }
                    sources = withContext(Dispatchers.IO) { fileManager.sources() }
                    status = "已授权 SAF 来源：$name（只读）"
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
            importLabel = "正在读取来源信息…"
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
                    "导入中：$name（${formatSize(size)}）"
                } else {
                    "导入中…"
                }
            val result =
                withContext(Dispatchers.IO) {
                    fileManager.importSingleDocument(uri, policy, importCancel::get) { done, total ->
                        if (total > 0) {
                            importLabel = "导入中：${formatSize(done)} / ${formatSize(total)}"
                        } else {
                            importLabel = "导入中：${formatSize(done)}"
                        }
                    }
                }
            importBusy = false
            importLabel = null
            importResult = result
            status = transferSummary("导入", result)
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
            importLabel = "正在枚举文件夹…"
            val result =
                withContext(Dispatchers.IO) {
                    fileManager.importTree(uri, policy, importCancel::get) { done, total ->
                        importLabel = "导入中：第 ${done + 1}/$total 个文件"
                    }
                }
            importBusy = false
            importLabel = null
            importResult = result
            status = transferSummary("导入", result)
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
            exportLabel = "导出中…"
            val result =
                withContext(Dispatchers.IO) {
                    fileManager.exportDocument(fileRel, target, policy, exportCancel::get) { done, total ->
                        if (total > 0) {
                            exportLabel = "导出中：${formatSize(done)} / ${formatSize(total)}"
                        } else {
                            exportLabel = "导出中：${formatSize(done)}"
                        }
                    }
                }
            exportBusy = false
            exportLabel = null
            exportResult = result
            status = transferSummary("导出", result)
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
                    val label = uri.lastPathSegment ?: "新文档"
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
            onFailure = { loadError = it.message ?: "无法读取目录" },
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
                    status = "已重命名"
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
            batchLabel = "处理中 0/$total"
            batchFailures = emptyList()
            val result =
                withContext(Dispatchers.IO) {
                    work(
                        { done, t -> batchLabel = "处理中 $done/$t" },
                        { cancelFlag.get() },
                    )
                }
            batchLabel = null
            batchBusy = false
            status = "完成：成功 ${result.succeeded.size}，跳过/失败 ${result.failures.size}"
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
            status = "目标目录不能为空"
            return
        }
        startBatch(sourceRels.size) { progress, cancel ->
            fileManager.batchMoveOrCopy(selectedScopeId, sourceRels, destDir, policy, move, progress, cancel)
        }
    }

    fun startTrash(sourceRels: List<String>) {
        if (!canMutate) {
            status = "此来源为只读"
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
                is FileOpResult.Ok -> status = "已恢复"
                FileOpResult.Conflict -> status = "恢复失败：原路径已被占用"
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
                is FileOpResult.Ok -> status = "已永久删除"
                is FileOpResult.NotFound -> status = result.message
                is FileOpResult.Error -> status = result.message
                FileOpResult.Conflict -> status = "无法删除"
            }
            reloadTick++
        }
    }

    fun emptyTrashPanel() {
        scope.launch {
            val count = withContext(Dispatchers.IO) { fileManager.emptyTrash(selectedScopeId) }
            status = "已清空回收站（$count 项）"
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
                context.startActivity(Intent.createChooser(send, "分享"))
            } catch (e: Exception) {
                status = "无法分享：${e.message}"
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
                "成功 ${result.completed.size} 项，跳过/冲突/失败 ${result.problems.size} 项",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.reclaimedTempFiles > 0) {
                Text(
                    "已回收 ${result.reclaimedTempFiles} 个中断残留的临时文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            result.items.take(MAX_FAILURE_DETAIL_LINES).forEach { item ->
                val mark =
                    when (item.status) {
                        TransferItemStatus.COMPLETED -> "成功"
                        TransferItemStatus.CONFLICT -> "冲突"
                        TransferItemStatus.SKIPPED -> "跳过"
                        TransferItemStatus.CANCELLED -> "已取消"
                        TransferItemStatus.FAILED -> "失败"
                    }
                Text(
                    "· ${item.sourceLabel} → ${item.targetLabel}：$mark" +
                        (item.detail?.let { "（$it）" } ?: "") +
                        (if (item.verified) "（已校验）" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.items.size > MAX_FAILURE_DETAIL_LINES) {
                Text(
                    "… 等 ${result.items.size} 项",
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
            "当前来源：${currentSource.displayName}" + if (canMutate) "" else "（只读）",
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
                BreadcrumbCrumb("根目录", isRoot = true, onClick = { currentPath = "" })
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
                SortButton("名称", SortKey.NAME, sortKey, onPick = { sortKey = it })
                SortButton("时间", SortKey.TIME, sortKey, onPick = { sortKey = it })
                SortButton("大小", SortKey.SIZE, sortKey, onPick = { sortKey = it })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { viewMode = ViewMode.LIST }, modifier = Modifier.testTag("files-view-list")) {
                    Text("列表")
                }
                TextButton(onClick = { viewMode = ViewMode.GRID }, modifier = Modifier.testTag("files-view-grid")) {
                    Text("网格")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { trashOpen = true }, modifier = Modifier.testTag("files-trash-open")) {
                    Text("回收站")
                }
                if (canMutate) {
                    TextButton(onClick = { newFolderOpen = true }, modifier = Modifier.testTag("files-newfolder")) {
                        Text("新建文件夹")
                    }
                }
                // HXA-057: the visible 重新授权 / 移除 entry for SAF tree scopes.
                TextButton(onClick = { safPanelOpen = true }, modifier = Modifier.testTag("files-saf-open")) {
                    Text("SAF 来源")
                }
                // HXA-058: the 导入 entry (a single document or a folder into the Workspace).
                TextButton(
                    onClick = {
                        importResult = null
                        importOpen = true
                    },
                    modifier = Modifier.testTag("files-import-open"),
                ) {
                    Text("导入")
                }
            }
        }

        // 长操作进度/取消 + 状态 + 部分失败清单.
        if (batchBusy) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    batchLabel ?: "处理中…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("files-batch-progress"),
                )
                TextButton(onClick = { cancelFlag.set(true) }, modifier = Modifier.testTag("files-batch-cancel")) {
                    Text("取消")
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
                        "· ${item.sourceRelativePath} — ${item.outcomeLabel()}：$detail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (batchFailures.size > MAX_FAILURE_DETAIL_LINES) {
                    Text(
                        "… 等 ${batchFailures.size} 项",
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
                        Text("回收站", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { trashOpen = false }, modifier = Modifier.testTag("files-trash-back")) {
                            Text("返回")
                        }
                        if (canMutate) {
                            TextButton(
                                onClick = { emptyTrashPanel() },
                                modifier = Modifier.testTag("files-trash-empty"),
                            ) {
                                Text("清空")
                            }
                        }
                    }
                    if (trashEntries.isEmpty()) {
                        Text(
                            "回收站为空",
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
                    "（空目录）",
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
                Text("已选 ${selected.size}", style = MaterialTheme.typography.bodyMedium)
                val selectedRels = selected.toList()
                TextButton(
                    onClick = { copyMove = CopyMoveTarget(move = false, selectedRels) },
                    modifier = Modifier.testTag("files-batch-copy"),
                ) {
                    Text("复制")
                }
                TextButton(
                    onClick = { copyMove = CopyMoveTarget(move = true, selectedRels) },
                    modifier = Modifier.testTag("files-batch-move"),
                ) {
                    Text("移动")
                }
                TextButton(onClick = { startTrash(selectedRels) }, modifier = Modifier.testTag("files-batch-trash")) {
                    Text("删除")
                }
                TextButton(onClick = { selected = emptySet() }, modifier = Modifier.testTag("files-batch-clear")) {
                    Text("取消选择")
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
                                    "（无预览）",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("files-preview-none"),
                                )
                            }
                        }
                        fileInfo?.let { meta ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "大小：${formatSize(meta.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "修改时间：${formatTime(meta.mtimeEpochMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text("类型：${meta.mimeType}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "SHA-256：${meta.sha256 ?: "（文件过大，已省略）"}",
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
                                    Text("重命名")
                                }
                                TextButton(
                                    onClick = { copyMove = CopyMoveTarget(move = false, listOf(file.relativePath)) },
                                    modifier = Modifier.testTag("files-action-copy"),
                                ) {
                                    Text("复制")
                                }
                                TextButton(
                                    onClick = { copyMove = CopyMoveTarget(move = true, listOf(file.relativePath)) },
                                    modifier = Modifier.testTag("files-action-move"),
                                ) {
                                    Text("移动")
                                }
                            }
                            TextButton(
                                onClick = {
                                    openFile = null
                                    startTrash(listOf(file.relativePath))
                                },
                                modifier = Modifier.testTag("files-action-trash"),
                            ) {
                                Text("回收站")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { share(file) }, modifier = Modifier.testTag("files-action-share")) {
                                Text("分享")
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
                                    Text("导出")
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { openFile = null }, modifier = Modifier.testTag("files-preview-close")) {
                        Text("关闭")
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
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
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
                            status = "名称不能为空"
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
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }, modifier = Modifier.testTag("files-rename-cancel")) {
                    Text("取消")
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
            title = { Text(if (cm.move) "移动所选" else "复制所选") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = destDir,
                        onValueChange = { destDir = it },
                        label = { Text("目标目录（相对路径）") },
                        singleLine = true,
                        modifier = Modifier.testTag("files-dest-field"),
                    )
                    Text(
                        "冲突策略（默认询问，从不默认覆盖）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ConflictPolicy.entries.forEach { p ->
                            TextButton(
                                onClick = { policy = p },
                                modifier = Modifier.testTag("files-policy-${p.name}"),
                            ) {
                                Text(policyLabel(p))
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
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { copyMove = null }, modifier = Modifier.testTag("files-dest-cancel")) {
                    Text("取消")
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
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("文件夹名称") },
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
                            status = "名称不能为空"
                            return@TextButton
                        }
                        scope.launch {
                            val result =
                                withContext(Dispatchers.IO) {
                                    fileManager.makeDirectory(selectedScopeId, currentPath, name)
                                }
                            when (result) {
                                is FileOpResult.Ok -> {
                                    status = "已创建文件夹"
                                    reloadTick++
                                }

                                FileOpResult.Conflict -> {
                                    status = "同名文件夹已存在"
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
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { newFolderOpen = false }, modifier = Modifier.testTag("files-newfolder-cancel")) {
                    Text("取消")
                }
            },
            modifier = Modifier.testTag("files-newfolder-dialog"),
        )
    }

    conflictTarget?.let { (srcRel, dstRel) ->
        AlertDialog(
            onDismissRequest = { conflictTarget = null },
            title = { Text("目标已存在") },
            text = {
                Text(
                    "“${dstRel.substringAfterLast('/')}” 已存在。请选择处理方式（默认不覆盖）：",
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
                    Text("重命名")
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
                        Text("覆盖")
                    }
                    TextButton(
                        onClick = {
                            conflictTarget = null
                            status = "已跳过（未覆盖）"
                        },
                        modifier = Modifier.testTag("files-conflict-skip"),
                    ) {
                        Text("跳过")
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
            title = { Text("导入到 Workspace") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!importBusy && importResult == null) {
                        Text(
                            "来源：用户选择的文档 / 文件夹（复制进 Workspace input/，不创建聊天消息）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { importMode = ImportMode.FILE },
                                modifier = Modifier.testTag("files-import-file"),
                            ) {
                                Text(if (importMode == ImportMode.FILE) "● 单个文件" else "单个文件")
                            }
                            TextButton(
                                onClick = { importMode = ImportMode.FOLDER },
                                modifier = Modifier.testTag("files-import-folder"),
                            ) {
                                Text(if (importMode == ImportMode.FOLDER) "● 文件夹" else "文件夹")
                            }
                        }
                        Text(
                            "目标：Workspace input/",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("files-import-target"),
                        )
                        Text(
                            "冲突策略（默认询问，从不默认覆盖）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConflictPolicy.entries.forEach { p ->
                                TextButton(
                                    onClick = { importPolicy = p },
                                    modifier = Modifier.testTag("files-import-policy-${p.name}"),
                                ) {
                                    Text(policyLabel(p))
                                }
                            }
                        }
                    } else if (importBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("files-import-progress"))
                        Text(
                            importLabel ?: "导入中…",
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
                            Text("取消")
                        }
                    }

                    importResult != null -> {
                        TextButton(
                            onClick = { importOpen = false },
                            modifier = Modifier.testTag("files-import-close"),
                        ) {
                            Text("关闭")
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
                            Text("选择并导入")
                        }
                    }
                }
            },
            dismissButton = {
                if (!importBusy) {
                    TextButton(onClick = { importOpen = false }, modifier = Modifier.testTag("files-import-dismiss")) {
                        Text("关闭")
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
            title = { Text("导出 ${exportFileEntry?.name ?: ""}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!exportBusy && exportResult == null) {
                        Text(
                            "来源：${exportFileEntry?.relativePath.orEmpty()}${
                                fileInfo?.let { "（${formatSize(it.sizeBytes)}）" } ?: ""
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("files-export-source"),
                        )
                        Text("目标：", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { exportMode = ExportMode.NEW_DOC },
                                modifier = Modifier.testTag("files-export-newdoc"),
                            ) {
                                Text(if (exportMode == ExportMode.NEW_DOC) "● 新建文档" else "新建文档")
                            }
                            TextButton(
                                onClick = { exportMode = ExportMode.TREE },
                                modifier = Modifier.testTag("files-export-tree"),
                            ) {
                                Text(if (exportMode == ExportMode.TREE) "● 已授权 SAF 目录" else "已授权 SAF 目录")
                            }
                        }
                        when (exportMode) {
                            ExportMode.NEW_DOC -> {
                                Text(
                                    "通过系统“创建文档”对话框选择保存位置（文件名与冲突由系统对话框处理）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            ExportMode.TREE -> {
                                if (exportSources.isEmpty()) {
                                    Text(
                                        "暂无已授权 SAF 来源（在“SAF 来源”中授权；只读来源不可写，导出会 fail-closed）",
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
                                            Text("选择")
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = exportParent,
                                    onValueChange = { exportParent = it },
                                    label = { Text("子目录（相对路径，可为空 = 根目录）") },
                                    singleLine = true,
                                    modifier = Modifier.testTag("files-export-parent"),
                                )
                                Text(
                                    "冲突策略（默认询问，从不默认覆盖）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ConflictPolicy.entries.forEach { p ->
                                        TextButton(
                                            onClick = { exportPolicy = p },
                                            modifier = Modifier.testTag("files-export-policy-${p.name}"),
                                        ) {
                                            Text(policyLabel(p))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (exportBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("files-export-progress"))
                        Text(
                            exportLabel ?: "导出中…",
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
                            Text("取消")
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
                            Text("关闭")
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
                                            status = "请选择一个已授权 SAF 来源"
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
                            Text("选择并导出")
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
                        Text("关闭")
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
            title = { Text("SAF 来源（只读）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "撤销或重新授权即时生效；模型与工具只看到 scopeId 与相对路径。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (safSources.isEmpty()) {
                        Text(
                            "暂无已授权 SAF 来源。",
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
                                        status = "已移除 SAF 来源：${source.displayName}"
                                    }
                                },
                                modifier = Modifier.testTag("files-saf-remove-${source.scopeId}"),
                            ) {
                                Text("移除")
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
                    Text("添加 / 重新授权")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { safPanelOpen = false },
                    modifier = Modifier.testTag("files-saf-close"),
                ) {
                    Text("关闭")
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
            val sizeOrType = if (entry.isDirectory) "文件夹" else formatSize(entry.sizeBytes)
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
                Text("恢复")
            }
            TextButton(
                onClick = { onPurge(entry) },
                modifier = Modifier.testTag("files-trash-purge-${entry.entryName}"),
            ) {
                Text("删除")
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

private fun policyLabel(policy: ConflictPolicy): String =
    when (policy) {
        ConflictPolicy.ASK -> "询问"
        ConflictPolicy.SKIP -> "跳过"
        ConflictPolicy.RENAME -> "重命名"
        ConflictPolicy.OVERWRITE -> "覆盖"
    }

private fun BatchItem.outcomeLabel(): String =
    when (outcome) {
        BatchItem.Outcome.SUCCEEDED -> "成功"
        BatchItem.Outcome.RENAMED -> "已重命名"
        BatchItem.Outcome.SKIPPED -> "跳过"
        BatchItem.Outcome.FAILED -> "失败"
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

/** HXA-058: the screen-level transfer status line ("导入/导出完成：…"). */
private fun transferSummary(
    verb: String,
    result: TransferResult,
): String =
    if (result.problems.isEmpty()) {
        "${verb}完成：成功 ${result.completed.size} 项"
    } else {
        "${verb}完成：成功 ${result.completed.size}，跳过/冲突/失败 ${result.problems.size}"
    }
