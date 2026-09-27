package com.helix.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun WorkspaceCleanupDialog(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    val target = state.cleanupTarget ?: return

    fun dismiss() {
        if (!state.cleanupBusy) state.cleanupTarget = null
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(actions.str(R.string.files_workspace_cleanup)) },
        text = {
            Text(
                actions.str(R.string.files_workspace_cleanup_body, target.displayName),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        dismissButton = {
            TextButton(::dismiss, enabled = !state.cleanupBusy) { Text(actions.str(R.string.files_close)) }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    state.cleanupBusy = true
                    actions.scope.launch {
                        try {
                            withContext(Dispatchers.IO) { actions.fileManager.cleanupWorkspace(target.scopeId) }
                            state.status = actions.str(R.string.files_workspace_cleanup_done)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            state.status = actions.str(R.string.files_workspace_cleanup_failed)
                        } finally {
                            state.cleanupBusy = false
                            state.cleanupTarget = null
                            state.reloadTick++
                        }
                    }
                },
                enabled = !state.cleanupBusy,
                modifier = Modifier.testTag("files-workspace-cleanup-confirm"),
            ) { Text(actions.str(R.string.files_confirm)) }
        },
    )
}
