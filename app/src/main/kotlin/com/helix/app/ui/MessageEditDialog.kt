package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.ChatService
import com.helix.app.chat.MessageEditResult
import com.helix.app.chat.MessageEditSnapshot
import kotlinx.coroutines.launch

/** An independent editor. The composer, attachments, submitted work and later replies remain intact. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun MessageEditDialog(
    service: ChatService,
    sessionId: String,
    messageId: String,
    screen: ChatScreenState,
    onClose: () -> Unit,
) {
    var snapshot by remember(sessionId, messageId) { mutableStateOf<MessageEditSnapshot?>(null) }
    var text by rememberSaveable(sessionId, messageId) { mutableStateOf("") }
    var initialized by rememberSaveable(sessionId, messageId) { mutableStateOf(false) }
    var version by rememberSaveable(sessionId, messageId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val busy = screen.isSending || screen.pendingDisclosure != null
    LaunchedEffect(sessionId, messageId) {
        snapshot = service.prepareMessageEdit(sessionId, messageId).await()
        val loaded = snapshot
        if (loaded == null) {
            error = R.string.message_edit_unavailable
        } else if (!initialized) {
            text = loaded.text
            version = loaded.contentVersion
            initialized = true
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(stringResource(R.string.message_edit_title)) },
        text = {
            Column {
                Text(stringResource(R.string.message_edit_note))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    enabled = snapshot != null && !saving,
                    modifier = Modifier.fillMaxWidth().testTag("message-edit-input"),
                    minLines = 3,
                    maxLines = 8,
                )
                if (busy) Text(stringResource(R.string.message_edit_busy))
                error?.let {
                    Text(
                        stringResource(it),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && !busy && snapshot != null && text.isNotBlank(),
                modifier = Modifier.testTag("message-edit-save"),
                onClick = {
                    val current = snapshot ?: return@TextButton
                    saving = true
                    scope.launch {
                        try {
                            val result = service.saveMessageEdit(current.copy(contentVersion = version), text).await()
                            val reason = messageEditError(result)
                            if (reason == null) onClose() else error = reason
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onClose,
                modifier = Modifier.testTag("message-edit-cancel"),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

internal fun messageEditError(result: MessageEditResult): Int? =
    when (result) {
        MessageEditResult.SAVED -> null
        MessageEditResult.BUSY -> R.string.message_edit_busy
        MessageEditResult.CHANGED -> R.string.message_edit_conflict
        else -> R.string.message_edit_unavailable
    }
