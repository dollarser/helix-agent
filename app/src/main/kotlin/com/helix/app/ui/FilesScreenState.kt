package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helix.app.files.ConflictPolicy
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileManagerService.BatchItem
import com.helix.app.files.FileManagerService.FileEntry
import com.helix.app.files.FileManagerService.TrashEntryView
import com.helix.app.files.FileSource
import com.helix.app.files.SortKey
import com.helix.app.files.TransferResult
import com.helix.feature.files.SafTreeSource
import java.util.concurrent.atomic.AtomicBoolean

/** Composition-owned state; actions and panels share this one instance. */
internal class FilesScreenState(
    fileManager: FileManagerService,
) {
    var homeOpen by mutableStateOf(true)
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var controlsOpen by mutableStateOf(false)
    var sourcesOpen by mutableStateOf(false)

    var sources by mutableStateOf(fileManager.sources())
    var selectedScopeId by mutableStateOf(sources.first().scopeId)
    var currentPath by mutableStateOf("")
    var sortKey by mutableStateOf(SortKey.NAME)
    var viewMode by mutableStateOf(ViewMode.LIST)
    var reloadTick by mutableIntStateOf(0)
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
    var loadError by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var status by mutableStateOf<String?>(null)
    var batchFailures by mutableStateOf<List<BatchItem>>(emptyList())
    var safPanelOpen by mutableStateOf(false)
    var safSources by mutableStateOf<List<SafTreeSource>>(emptyList())
    var permanentDelete: List<String>? by mutableStateOf(null)
    var trashOpen by mutableStateOf(false)
    var trashEntries by mutableStateOf<List<TrashEntryView>>(emptyList())
    var openFile by mutableStateOf<FileEntry?>(null)
    var preview by mutableStateOf<FilePreviewState>(FilePreviewState.Loading)
    val fileInfo get() = (preview as? FilePreviewState.Ready)?.info
    var renameTarget by mutableStateOf<FileEntry?>(null)
    var copyMove by mutableStateOf<CopyMoveTarget?>(null)
    var newFolderOpen by mutableStateOf(false)
    var conflictTarget by mutableStateOf<Pair<String, String>?>(null)
    var suggestedName by mutableStateOf<String?>(null)
    var batchBusy by mutableStateOf(false)
    var batchLabel by mutableStateOf<String?>(null)
    val cancelFlag = AtomicBoolean(false)
    var importOpen by mutableStateOf(false)
    var importMode by mutableStateOf(ImportMode.FILE)
    var importPolicy by mutableStateOf(ConflictPolicy.ASK)
    var importBusy by mutableStateOf(false)
    var importLabel by mutableStateOf<String?>(null)
    var importResult by mutableStateOf<TransferResult?>(null)
    val importCancel = AtomicBoolean(false)
    var exportOpen by mutableStateOf(false)
    var exportMode by mutableStateOf(ExportMode.NEW_DOC)
    var exportScopeId by mutableStateOf<String?>(null)
    var exportParent by mutableStateOf("")
    var exportPolicy by mutableStateOf(ConflictPolicy.ASK)
    var exportBusy by mutableStateOf(false)
    var exportLabel by mutableStateOf<String?>(null)
    var exportResult by mutableStateOf<TransferResult?>(null)
    val exportCancel = AtomicBoolean(false)
    var exportSources by mutableStateOf<List<SafTreeSource>>(emptyList())
    var exportFile by mutableStateOf<FileEntry?>(null)

    fun openLocation(
        scopeId: String,
        path: String = "",
    ) {
        selectedScopeId = scopeId
        currentPath = path
        selected = emptySet()
        searchQuery = ""
        searchOpen = false
        trashOpen = false
        homeOpen = false
        sourcesOpen = false
    }

    fun goBack() {
        when {
            selected.isNotEmpty() -> {
                selected = emptySet()
            }

            searchOpen -> {
                searchOpen = false
                searchQuery = ""
            }

            trashOpen -> {
                trashOpen = false
            }

            currentPath.isNotEmpty() -> {
                currentPath = currentPath.substringBeforeLast('/', "")
            }

            else -> {
                homeOpen = true
            }
        }
    }

    val visibleEntries: List<FileEntry> get() = entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
    val currentSource: FileSource get() = sources.first { it.scopeId == selectedScopeId }
    val canMutate: Boolean get() = currentSource.supportsMutation
}
