package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun SessionRenameDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename)) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.chat_session_title_label)) },
                modifier = Modifier.testTag("session-title"),
            )
        },
        confirmButton = {
            TextButton(
                { onSave(value.trim()) },
                enabled = value.isNotBlank() && value.length <= 200 && '\u0000' !in value,
                modifier = Modifier.testTag("session-rename-save"),
            ) { Text(stringResource(R.string.chat_save_details)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "SwallowedException")
internal fun SessionDirectoryDialog(
    files: FileManagerService,
    onDismiss: () -> Unit,
    allowIndependent: Boolean = true,
    onChoose: suspend (String?) -> Boolean,
) {
    var sources by remember { mutableStateOf(emptyList<FileSource>()) }
    var path by remember { mutableStateOf<FileScopePath?>(null) }
    var entries by remember { mutableStateOf(emptyList<FileManagerService.FileEntry>()) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf<com.helix.app.files.WorkspaceAccessReport?>(null) }
    var alternative by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    val requestStorage = rememberWorkspaceStoragePermission { retry++ }
    WorkspaceStorageResume { retry++ }
    val scope = rememberCoroutineScope()
    val choose: (String?) -> Unit = { reference ->
        if (!saving) {
            scope.launch {
                saving = true
                try {
                    failed = !onChoose(reference)
                    if (!failed) onDismiss()
                } finally {
                    saving = false
                }
            }
        }
    }
    LaunchedEffect(path, retry) {
        ready = false
        failed = false
        access = null
        alternative = null
        truncated = false
        try {
            val location = withContext(Dispatchers.IO) { files.directoryChoices(path) }
            sources = location.sources
            entries = location.entries
            access = location.access
            alternative = location.localAlternative
            truncated = location.truncated
            ready = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true // scoped service failure; no raw paths or provider exceptions in UI
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.chat_directory)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).indicatedVerticalScroll(rememberScrollState())) {
                val current = path
                WorkspaceStorageEntry(
                    !saving,
                    sources.any {
                        com.helix.app.files.SharedStorageAccess
                            .isScope(it.scopeId)
                    },
                    requestStorage,
                )
                if (current == null) {
                    WorkspaceSourceChoices(sources, !saving) { path = it }
                } else {
                    ScopedPathText(
                        current.toModelReference(),
                        sources.firstOrNull { it.scopeId == current.scopeId }?.displayName,
                    )
                    TextButton({
                        path =
                            if (current.relativePath.isEmpty()) {
                                null
                            } else {
                                FileScopePath(current.scopeId, current.relativePath.substringBeforeLast('/', ""))
                            }
                    }, enabled = !saving) { Text(stringResource(R.string.chat_directory_parent)) }
                    WorkspaceAccessText(access, showCopyHint = true)
                    if (truncated) Text(stringResource(R.string.files_listing_partial))
                    WorkspaceLocalAlternative(alternative, !saving) { path = FileScopePath.fromModelReference(it) }
                    if (ready) {
                        entries.filter { it.isDirectory }.forEach { entry ->
                            TextButton(
                                { path = FileScopePath(current.scopeId, entry.relativePath) },
                                enabled = !saving,
                            ) {
                                Text(entry.name)
                            }
                        }
                    }
                }
                if (failed) {
                    Text(stringResource(R.string.chat_directory_failed))
                    TextButton({ retry++ }, enabled = !saving, modifier = Modifier.testTag("session-directory-retry")) {
                        Text(stringResource(R.string.chat_retry))
                    }
                }
                IndependentDirectoryOption(allowIndependent, !saving) { choose(null) }
            }
        },
        confirmButton = {
            TextButton(
                { choose(path?.toModelReference()) },
                enabled = path != null && ready && !saving,
                modifier = Modifier.testTag("session-directory-confirm"),
            ) {
                Text(stringResource(R.string.chat_directory_choose))
            }
        },
        dismissButton = {
            TextButton(onDismiss, enabled = !saving, modifier = Modifier.testTag("session-directory-cancel")) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun IndependentDirectoryOption(
    visible: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    if (visible) {
        TextButton(onChoose, enabled = enabled, modifier = Modifier.testTag("session-directory-private")) {
            Text(stringResource(R.string.chat_directory_none))
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun WorkspaceStorageEntry(
    enabled: Boolean,
    available: Boolean,
    onRequest: () -> Unit,
) {
    Text(
        stringResource(
            if (android.os.Build.VERSION.SDK_INT >=
                30
            ) {
                R.string.workspace_permission_modern
            } else {
                R.string.workspace_permission_legacy
            },
        ),
    )
    Text(
        stringResource(
            if (available) R.string.workspace_storage_ready else R.string.workspace_storage_permission_needed,
        ),
    )
    TextButton(onRequest, enabled = enabled, modifier = Modifier.testTag("workspace-storage-permission")) {
        Text(stringResource(R.string.workspace_permission_action))
    }
}

@Composable
@Suppress("FunctionName")
private fun WorkspaceLocalAlternative(
    reference: String?,
    enabled: Boolean,
    onChoose: (String) -> Unit,
) {
    if (reference != null) {
        TextButton({ onChoose(reference) }, enabled = enabled, modifier = Modifier.testTag("workspace-use-real-path")) {
            Text(stringResource(R.string.workspace_use_real_path))
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun WorkspaceStorageResume(onResume: () -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val current by androidx.compose.runtime.rememberUpdatedState(onResume)
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) current()
            }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
}

@Composable
@Suppress("FunctionName")
private fun WorkspaceSourceChoices(
    sources: List<FileSource>,
    enabled: Boolean,
    onChoose: (FileScopePath) -> Unit,
) {
    sources.forEach { source ->
        TextButton(
            { onChoose(FileScopePath(source.scopeId, "")) },
            enabled = enabled && source.available,
            modifier = Modifier.testTag("session-directory-source-${source.scopeId}"),
        ) { Text(source.displayName) }
    }
}
