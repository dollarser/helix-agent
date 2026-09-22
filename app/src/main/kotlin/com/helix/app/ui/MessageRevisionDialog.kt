package com.helix.app.ui

import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    LaunchedEffect(text) {
        val current = draft ?: return@LaunchedEffect
        if (current.text == text) return@LaunchedEffect
        delay(250)
        val saved = service.saveRevisionText(current, text).await()
        if (saved == null) error = R.string.message_revision_save_failed else draft = saved
    }
    LaunchedEffect(screen.pendingDisclosure, screen.messages) {
        val current = draft ?: return@LaunchedEffect
        if (service.acceptedRevision(current)) onClose()
    }
    val current = draft ?: return
    if (screen.pendingDisclosure != null) return
    AlertDialog(
        onDismissRequest = {
            scope.launch {
                if (service.saveRevisionText(current, text).await() != null) {
                    onClose()
                } else {
                    error = R.string.message_revision_save_failed
                }
            }
        },
        title = { Text(stringResource(R.string.message_revision_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.message_revision_note))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    enabled = !sending,
                    modifier = Modifier.testTag("message-revision-input"),
                )
                Text(
                    stringResource(
                        if (text ==
                            current.text
                        ) {
                            R.string.message_revision_saved
                        } else {
                            R.string.message_revision_saving
                        },
                    ),
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
                sending = true
                scope.launch {
                    val saved = service.saveRevisionText(current, text).await()
                    if (saved == null) {
                        error = R.string.message_revision_save_failed
                    } else {
                        draft = saved
                        when (service.sendSubmission(saved).await().outcome) {
                            is ChatSubmissionOutcome.Accepted -> {
                                service.acceptedRevision(saved)
                                onClose()
                            }

                            ChatSubmissionOutcome.PendingConfirmation -> {
                                Unit
                            }

                            is ChatSubmissionOutcome.Rejected -> {
                                error = R.string.message_revision_failed
                            }
                        }
                    }
                    sending = false
                }
            }) { Text(stringResource(R.string.message_revision_send)) }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = {
                scope.launch {
                    val latest = service.loadComposerDraft(sessionId)
                    if (latest?.revisedMessageId == messageId) service.discardRevision(latest).await()
                    onClose()
                }
            }) { Text(stringResource(R.string.message_revision_discard)) }
        },
    )
}
