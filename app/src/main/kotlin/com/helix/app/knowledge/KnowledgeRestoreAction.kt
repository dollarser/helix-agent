package com.helix.app.knowledge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.ui.WorkspaceTreePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A one-time user command. Canceling either dialog/picker never starts an import. */
@Composable
@Suppress("LongMethod", "TooGenericExceptionCaught") // OS result and concise UI failure projection.
internal fun knowledgeRestoreAction(service: KnowledgeService): () -> Unit {
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(WorkspaceTreePicker()) { selection ->
            if (selection != null) {
                scope.launch {
                    busy = true
                    show = true
                    result = null
                    try {
                        service.restoreLibrary(selection)
                        result = R.string.knowledge_restore_complete
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: SecurityException) {
                        result = R.string.knowledge_restore_access_failed
                    } catch (_: Exception) {
                        result = R.string.knowledge_restore_failed
                    } finally {
                        busy = false
                    }
                }
            }
        }
    if (show) {
        AlertDialog(
            onDismissRequest = { if (!busy) show = false },
            modifier = Modifier.testTag("knowledge-restore-dialog"),
            title = { Text(stringResource(R.string.knowledge_restore_library)) },
            text = {
                val message =
                    if (busy) {
                        R.string.knowledge_restore_busy
                    } else {
                        result ?: R.string.knowledge_restore_description
                    }
                Text(stringResource(message))
            },
            confirmButton = {
                if (!busy && result != R.string.knowledge_restore_complete) {
                    TextButton({
                        show = false
                        picker.launch(null)
                    }, Modifier.testTag("knowledge-restore-select")) {
                        Text(stringResource(R.string.knowledge_restore_select))
                    }
                }
            },
            dismissButton = {
                TextButton({ show = false }, enabled = !busy, modifier = Modifier.testTag("knowledge-restore-close")) {
                    Text(stringResource(R.string.knowledge_close))
                }
            },
        )
    }
    return {
        result = null
        show = true
    }
}
