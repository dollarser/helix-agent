package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.files.FileManagerService
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-selected destination only. Staging never sends a message or grants tool access. */
class FileConversationHandoff(
    val chat: ChatService,
    val onOpen: (String) -> Unit,
) {
    fun attach(
        uri: String,
        expectedSessionId: String?,
        newConversation: Boolean,
    ): Boolean {
        val target = chat.attachFileToConversation(uri, expectedSessionId, newConversation)
        return target?.let {
            onOpen(it)
            true
        } ?: false
    }
}

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught") // Local failure is visible; cancellation is not failure.
internal fun FileConversationActions(
    path: FileScopePath,
    files: FileManagerService,
    handoff: FileConversationHandoff,
) {
    val context = LocalContext.current
    val chatScreen by handoff.chat.screen.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember(path) { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }

    fun attach(newConversation: Boolean) {
        val expected = handoff.chat.screen.value.openSessionId
        busy = true
        failed = false
        scope.launch {
            try {
                val uri =
                    withContext(Dispatchers.IO) {
                        val file = files.realFileFor(path.scopeId, path.relativePath)
                        FileProvider
                            .getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                                path.name,
                            ).toString()
                    }
                failed = !handoff.attach(uri, expected, newConversation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.files_chat_attachment_detail), style = MaterialTheme.typography.bodySmall)
        if (chatScreen.openSessionId != null) {
            TextButton({ attach(false) }, enabled = !busy, modifier = Modifier.testTag("files-chat-current")) {
                Text(stringResource(R.string.files_chat_current))
            }
        }
        TextButton({ attach(true) }, enabled = !busy, modifier = Modifier.testTag("files-chat-new")) {
            Text(stringResource(R.string.files_chat_new))
        }
        if (failed) Text(stringResource(R.string.files_chat_failed))
    }
}

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun FileDirectoryTask(
    state: FilesScreenState,
    handoff: FileConversationHandoff,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember(state.selectedScopeId, state.currentPath) { mutableStateOf(false) }
    // Manual phone/root browsing is not an Agent scope. The session binder revalidates
    // workspace and SAF availability; displaying a directory never widens permissions.
    if (state.currentSource.kind != com.helix.app.files.FileSourceKind.WORKSPACE &&
        state.currentSource.kind != com.helix.app.files.FileSourceKind.SAF
    ) {
        return
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        TextButton(
            {
                val path = FileScopePath(state.selectedScopeId, state.currentPath)
                val expected = handoff.chat.screen.value.openSessionId
                busy = true
                failed = false
                scope.launch {
                    try {
                        val id = handoff.chat.newDirectoryDraft(path.toModelReference(), expected)
                        if (id != null) handoff.onOpen(id) else failed = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && state.loadError == null && state.currentSource.scopeId == state.selectedScopeId,
            modifier = Modifier.testTag("files-directory-task"),
        ) {
            Text(stringResource(R.string.files_directory_task))
        }
        if (failed) Text(stringResource(R.string.chat_directory_failed))
    }
}
