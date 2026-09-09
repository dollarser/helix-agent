package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.Dispatchers
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
    onChoose: (String?) -> Unit,
) {
    var sources by remember { mutableStateOf(emptyList<FileSource>()) }
    var path by remember { mutableStateOf<FileScopePath?>(null) }
    var entries by remember { mutableStateOf(emptyList<FileManagerService.FileEntry>()) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        ready = false
        failed = false
        try {
            val target = path
            if (target == null) {
                sources = withContext(Dispatchers.IO) { files.sources() }
            } else {
                entries = withContext(Dispatchers.IO) { files.list(target.scopeId, target.relativePath) }
            }
            ready = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true // scoped service failure; no raw paths or provider exceptions in UI
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_directory)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val current = path
                if (current == null) {
                    sources.forEach { source ->
                        TextButton({ path = FileScopePath(source.scopeId, "") }) { Text(source.displayName) }
                    }
                } else {
                    Text(current.relativePath.ifEmpty { current.scopeId })
                    TextButton({
                        path =
                            if (current.relativePath.isEmpty()) {
                                null
                            } else {
                                FileScopePath(current.scopeId, current.relativePath.substringBeforeLast('/', ""))
                            }
                    }) { Text(stringResource(R.string.chat_directory_parent)) }
                    if (ready) {
                        entries.filter { it.isDirectory }.forEach { entry ->
                            TextButton({ path = FileScopePath(current.scopeId, entry.relativePath) }) {
                                Text(entry.name)
                            }
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.chat_directory_failed))
                TextButton({ onChoose(null) }) { Text(stringResource(R.string.chat_directory_none)) }
            }
        },
        confirmButton = {
            TextButton({ onChoose(path?.toModelReference()) }, enabled = path != null && ready) {
                Text(stringResource(R.string.chat_directory_choose))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
