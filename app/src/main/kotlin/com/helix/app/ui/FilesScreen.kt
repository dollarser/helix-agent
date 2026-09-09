package com.helix.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.helix.app.FeatureFiles
import com.helix.app.R
import com.helix.app.files.ExportTarget
import com.helix.app.files.FileManagerService
import com.helix.feature.files.SafTreeScopeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
fun FilesScreen(
    fileManager: FileManagerService,
    safTree: SafTreeScopeService,
    featureFiles: FeatureFiles,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val state = remember(fileManager) { FilesScreenState(fileManager) }
    val openSharedStorage = rememberSharedStorageNavigation(state, fileManager)
    val actions = FilesScreenActions(state, fileManager, safTree, featureFiles, scope, context, resources)
    BackHandler(enabled = !state.homeOpen) { state.goBack() }
    with(actions) {
        with(state) {
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
            FilesDirectoryEffects(state, actions)
            FilesPreviewEffects(state, actions)
            FilesScopeEffects(state, actions)
            FilesRecoverableLayout(state, actions, openSharedStorage)
            state.FilesPreviewDialog(actions)
            FilesMutationDialogs(state, actions)
            FilesImportDialog(
                state,
                actions,
                { importFilePicker.launch(arrayOf("*/*")) },
                { importFolderPicker.launch(null) },
            )
            FilesExportDialog(state, actions) { exportDocPicker.launch(it) }
            FilesSafDialog(state, actions) { treePicker.launch(null) }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun FilesRecoverableLayout(
    state: FilesScreenState,
    actions: FilesScreenActions,
    openSharedStorage: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        FilesRecoveryPanel(actions)
        Box(Modifier.weight(1f)) { FilesScreenLayout(state, actions, openSharedStorage) }
    }
}
