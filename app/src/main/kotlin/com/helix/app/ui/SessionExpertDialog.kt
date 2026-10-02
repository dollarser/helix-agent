package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.ui.indicatedVerticalScroll
import kotlinx.coroutines.launch

/** Durable Session behavior guidance. It never edits permission or tool authority. */
@Composable
@Suppress("FunctionName", "LongMethod") // Compose DSL keeps the dialog state and actions together.
internal fun SessionExpertDialog(
    chatService: ChatService,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var existing by remember(sessionId) { mutableStateOf(false) }
    var name by remember(sessionId) { mutableStateOf("") }
    var instruction by remember(sessionId) { mutableStateOf("") }
    var saving by remember(sessionId) { mutableStateOf(false) }
    var failed by remember(sessionId) { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        val profile = chatService.loadSessionExpert(sessionId).await()
        existing = profile != null
        name = profile?.displayName.orEmpty()
        instruction = profile?.instruction.orEmpty()
        loaded = true
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.session_expert_title)) },
        text = {
            Column(
                Modifier.indicatedVerticalScroll(rememberScrollState()).testTag("session-expert-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.session_expert_hint))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = loaded && !saving,
                    label = { Text(stringResource(R.string.session_expert_name)) },
                    singleLine = true,
                    modifier = Modifier.testTag("session-expert-name"),
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    enabled = loaded && !saving,
                    label = { Text(stringResource(R.string.session_expert_instruction)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.testTag("session-expert-instruction"),
                )
                Text(stringResource(R.string.session_expert_authority_note))
                if (failed) Text(stringResource(R.string.session_expert_save_failed))
            }
        },
        confirmButton = {
            TextButton(
                enabled = loaded && !saving && name.isNotBlank() && instruction.isNotBlank(),
                modifier = Modifier.testTag("session-expert-save"),
                onClick = {
                    saving = true
                    failed = false
                    scope.launch {
                        val saved = chatService.saveSessionExpert(sessionId, name, instruction).await()
                        saving = false
                        if (saved) {
                            onDismiss()
                        } else {
                            failed = true
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            if (existing) {
                TextButton(
                    enabled = !saving,
                    modifier = Modifier.testTag("session-expert-clear"),
                    onClick = {
                        saving = true
                        failed = false
                        scope.launch {
                            val cleared = chatService.clearSessionExpert(sessionId).await()
                            saving = false
                            if (cleared) {
                                onDismiss()
                            } else {
                                failed = true
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.session_expert_clear))
                }
            } else {
                TextButton(enabled = !saving, onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}
