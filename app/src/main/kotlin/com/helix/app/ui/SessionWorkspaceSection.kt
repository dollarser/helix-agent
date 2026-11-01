package com.helix.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded, wrapping directory details; storage access stays behind the Files application facade. */
@Composable
@Suppress("FunctionName")
internal fun SessionWorkspaceSection(
    files: FileManagerService,
    reference: String?,
    enabled: Boolean,
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
    SettingsGroup {
        Text(stringResource(R.string.chat_directory))
        if (reference == null) {
            Text(stringResource(R.string.chat_directory_none))
        } else {
            ScopedPathText(reference, source?.displayName)
        }
        WorkspaceAccessDetails(source, reference != null, loaded, access)
        Text(stringResource(R.string.workspace_binding_help))
        OutlinedButton(
            onClick = onChoose,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("session-settings-directory"),
        ) { Text(stringResource(R.string.chat_directory_choose)) }
    }
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
