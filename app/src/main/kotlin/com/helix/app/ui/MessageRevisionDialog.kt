package com.helix.app.ui

import androidx.compose.foundation.layout.Column
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
import com.helix.app.R
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.ui.indicatedVerticalScroll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI owns the editor only; the service owns submission, persistence and stopped-Turn settlement. */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun MessageRevisionDialog(
    service: ChatService,
    sessionId: String,
    messageId: String,
    screen: ChatScreenState,
    onClose: () -> Unit,
) {
    var draft by remember(sessionId, messageId) { mutableStateOf<ChatSubmission?>(null) }
    var text by remember(sessionId, messageId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val busy = screen.activeTurn?.state?.isTerminal == false
    LaunchedEffect(sessionId, messageId) {
        val loaded = service.prepareLatestRevision(sessionId, messageId).await()
        if (loaded == null) {
            onClose()
        } else {
            draft = loaded
            text = loaded.text
        }
    }
    LaunchedEffect(draft) {
        val snapshot = draft ?: return@LaunchedEffect
        delay(250)
        service.saveComposerDraftAsync(snapshot).await()
    }
    LaunchedEffect(draft, screen.pendingDisclosure, screen.messages, screen.activeTurn?.id) {
        val current = draft ?: return@LaunchedEffect
        withContext(NonCancellable) {
            if (service.acceptedRevision(current)) onClose()
        }
    }
    val current = draft ?: return
    if (screen.pendingDisclosure != null) return
    AlertDialog(
        onDismissRequest = {
            service.saveComposerDraftAsync(service.revisionSubmission(current, text))
            onClose()
        },
        title = { Text(stringResource(R.string.message_revision_title)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.message_revision_note))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        draft = service.revisionSubmission(current, it)
                        error = null
                    },
                    enabled = !sending,
                    modifier = Modifier.testTag("message-revision-input"),
                )
                if (busy) {
                    Text(stringResource(R.string.message_revision_busy))
                    val turnId = screen.activeTurn?.id
                    TextButton(onClick = { if (turnId != null) scope.launch { service.stopTurn(turnId) } }) {
                        Text(stringResource(R.string.message_revision_stop))
                    }
                }
                error?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !sending && !busy, modifier = Modifier.testTag("message-revision-send"), onClick = {
                val snapshot = service.revisionSubmission(current, text)
                draft = snapshot
                sending = true
                scope.launch {
                    try {
                        when (service.sendSubmission(snapshot).await().outcome) {
                            is ChatSubmissionOutcome.Accepted -> {
                                onClose()
                            }

                            ChatSubmissionOutcome.PendingConfirmation -> {
                                Unit
                            }

                            is ChatSubmissionOutcome.Rejected, is ChatSubmissionOutcome.Enqueued -> {
                                error = R.string.message_revision_failed
                            }
                        }
                    } finally {
                        sending = false
                    }
                }
            }) { Text(stringResource(R.string.message_revision_send)) }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = {
                scope.launch {
                    service.discardRevision(current).await()
                    onClose()
                }
            }) { Text(stringResource(R.string.message_revision_discard)) }
        },
    )
}
