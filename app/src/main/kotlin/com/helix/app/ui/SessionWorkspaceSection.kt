package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded, wrapping directory details; storage access stays behind the Files application facade. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod")
internal fun SessionWorkspaceSection(
    files: FileManagerService,
    reference: String?,
    enabled: Boolean,
    sessionId: String? = null,
    projectDirectory: String? = null,
    projectName: String? = null,
    onChoose: () -> Unit,
) {
    var source by remember(reference) { mutableStateOf<FileSource?>(null) }
    var loaded by remember(reference) { mutableStateOf(false) }
    var access by remember(reference) { mutableStateOf<com.helix.app.files.WorkspaceAccessReport?>(null) }
    LaunchedEffect(reference) {
        source =
            try {
                val scope = reference?.let { FileScopePath.fromModelReference(it).scopeId }
                val observed =
                    withContext(Dispatchers.IO) {
                        val report = reference?.let(files::inspectWorkspace)
                        files.sources().firstOrNull { it.scopeId == scope } to report
                    }
                access = observed.second
                observed.first
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        loaded = true
    }
    var menu by remember(reference) { mutableStateOf(false) }
    var details by remember(reference) { mutableStateOf(false) }
    val path = reference?.let { runCatching { FileScopePath.fromModelReference(it) }.getOrNull() }
    val label = workingDirectoryLabel(reference, source, path, sessionId, projectDirectory, projectName)
    val location = path?.relativePath?.takeIf { it.isNotEmpty() }?.let { "$label / $it" } ?: label
    Column {
        SettingsEntryRow(
            stringResource(R.string.chat_directory),
            R.drawable.ic_files_folder,
            { menu = true },
            Modifier.testTag("session-settings-directory"),
            location,
            true,
            subtitleMaxLines = 2,
        )
        if (reference != null && loaded && source?.available != true) {
            Text(stringResource(R.string.workspace_unavailable), color = MaterialTheme.colorScheme.error)
        } else if (source?.supportsMutation == false) {
            Text(stringResource(R.string.workspace_read_only))
        }
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text(stringResource(R.string.chat_directory)) },
            text = {
                Column {
                    SettingsEntryRow(
                        stringResource(R.string.workspace_change_directory),
                        R.drawable.ic_files_folder,
                        {
                            menu = false
                            onChoose()
                        },
                        Modifier.testTag("session-directory-change"),
                        enabled = enabled,
                    )
                    SettingsEntryRow(
                        stringResource(R.string.workspace_location_details),
                        R.drawable.ic_location_info,
                        {
                            menu = false
                            details = true
                        },
                        Modifier.testTag("session-directory-details"),
                    )
                }
            },
            confirmButton = {
                TextButton({ menu = false }) { Text(stringResource(R.string.chat_details_close)) }
            },
        )
    }
    if (details) {
        SessionLocationDetails(reference, source, loaded, access) { details = false }
    }
}

@Composable
@Suppress("FunctionName")
private fun SessionLocationDetails(
    reference: String?,
    source: FileSource?,
    loaded: Boolean,
    access: com.helix.app.files.WorkspaceAccessReport?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_location_details)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState())) {
                reference?.let {
                    SelectionContainer { Text(it) }
                    TextButton({ clipboard.setText(AnnotatedString(it)) }) {
                        Text(stringResource(R.string.workspace_copy_location))
                    }
                }
                Text(stringResource(R.string.workspace_directory_purpose))
                Text(stringResource(R.string.workspace_internal_storage_help))
                WorkspaceAccessDetails(source, reference != null, loaded, access)
                Text(stringResource(R.string.workspace_binding_help))
            }
        },
        confirmButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.chat_details_close)) }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun WorkspaceAccessDetails(
    source: FileSource?,
    hasReference: Boolean,
    loaded: Boolean,
    access: com.helix.app.files.WorkspaceAccessReport?,
) {
    val backend = source?.workspaceBackend
    if (backend != null) {
        Text(
            stringResource(
                if (backend ==
                    "SAF"
                ) {
                    R.string.workspace_document_backend
                } else {
                    R.string.workspace_path_backend
                },
            ),
        )
    }
    if (hasReference && loaded) {
        val status =
            when {
                source?.available != true -> R.string.workspace_unavailable
                source?.supportsMutation == true -> R.string.workspace_operation_checks
                else -> R.string.workspace_read_only
            }
        Text(stringResource(status))
    }
    WorkspaceAccessText(access)
    if (access?.directPath == true) Text(stringResource(R.string.workspace_private_build_help))
}

@Composable
@Suppress("FunctionName")
internal fun WorkspaceAccessText(
    access: com.helix.app.files.WorkspaceAccessReport?,
    showCopyHint: Boolean = false,
) {
    access?.let { observed ->
        Text(
            stringResource(
                if (observed.directPath) R.string.workspace_direct_observed else R.string.workspace_saf_observed,
            ),
            modifier = Modifier.testTag("directory-access-capability"),
        )
        if (showCopyHint && !observed.directPath) Text(stringResource(R.string.workspace_cli_explicit_copy))
        Text(
            stringResource(
                if (observed.writeAdvertised) R.string.workspace_write_hint else R.string.workspace_read_only,
            ),
        )
    }
}

@Composable
private fun workingDirectoryLabel(
    reference: String?,
    source: FileSource?,
    path: FileScopePath?,
    sessionId: String?,
    projectDirectory: String?,
    projectName: String?,
): String =
    when {
        reference == projectDirectory && projectName != null -> {
            stringResource(R.string.workspace_project_directory_label, projectName)
        }

        reference == null || (sessionId != null && source?.ownerSessionId == sessionId) -> {
            stringResource(R.string.workspace_session_directory)
        }

        else -> {
            source?.displayName?.takeIf { it.isNotBlank() } ?: path?.scopeId
                ?: stringResource(R.string.workspace_session_directory)
        }
    }
