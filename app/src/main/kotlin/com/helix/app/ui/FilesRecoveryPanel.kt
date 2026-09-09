package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.helix.app.R
import com.helix.app.files.FileTransferRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit recovery, including on the file home screen. No startup storage mutation. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun FilesRecoveryPanel(actions: FilesScreenActions) {
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<FileTransferRecovery>()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun load() {
        actions.scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { actions.fileManager.pendingTransfers() } }
            result.fold({ entries = it }, { message = it.message })
            busy = false
        }
    }
    TextButton(
        onClick = {
            open = true
            message = null
            load()
        },
        enabled = !actions.state.batchBusy,
        modifier = Modifier.testTag("files-recovery-open"),
    ) { Text(actions.str(R.string.files_recovery_title)) }
    if (!open) return
    AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text(actions.str(R.string.files_recovery_title)) },
        text = {
            Column {
                Text(actions.str(R.string.files_recovery_explanation))
                message?.let { Text(it, modifier = Modifier.testTag("files-recovery-result")) }
                if (busy) Text(actions.str(R.string.egress_loading))
                if (!busy && entries.isEmpty()) Text(actions.str(R.string.files_recovery_empty))
                LazyColumn {
                    items(entries, key = { it.id }) { entry ->
                        Column {
                            Text(entry.source)
                            Text(entry.destination)
                            TextButton(
                                enabled = !busy,
                                modifier = Modifier.testTag("files-recovery-${entry.id}"),
                                onClick = {
                                    actions.scope.launch {
                                        busy = true
                                        val result =
                                            withContext(Dispatchers.IO) {
                                                runCatching { actions.fileManager.recoverTransfer(entry.id) }
                                            }
                                        message =
                                            result.fold(
                                                { published ->
                                                    actions.str(
                                                        if (published) {
                                                            R.string.files_recovery_published
                                                        } else {
                                                            R.string.files_recovery_retry
                                                        },
                                                    )
                                                },
                                                { it.message ?: actions.str(R.string.files_read_directory_error) },
                                            )
                                        actions.state.reloadTick++
                                        busy = false
                                        load()
                                    }
                                },
                            ) { Text(actions.str(R.string.files_recovery_action)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }, enabled = !busy) { Text(actions.str(R.string.files_close)) }
        },
    )
}
