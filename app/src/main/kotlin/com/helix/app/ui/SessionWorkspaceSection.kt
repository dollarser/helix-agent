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
    LaunchedEffect(reference) {
        source =
            try {
                val scope = reference?.let { FileScopePath.fromModelReference(it).scopeId }
                withContext(Dispatchers.IO) { files.sources().firstOrNull { it.scopeId == scope } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        loaded = true
    }
    SettingsGroup {
        Text(stringResource(R.string.chat_directory))
        Text(reference ?: stringResource(R.string.chat_directory_none))
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
        if (reference != null && loaded) {
            val status =
                when {
                    source?.available != true -> R.string.workspace_unavailable
                    source?.supportsMutation == true -> R.string.workspace_operation_checks
                    else -> R.string.workspace_read_only
                }
            Text(stringResource(status))
        }
        Text(stringResource(R.string.workspace_binding_help))
        OutlinedButton(
            onClick = onChoose,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("session-settings-directory"),
        ) { Text(stringResource(R.string.chat_directory_choose)) }
    }
}
