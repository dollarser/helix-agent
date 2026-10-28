package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.core.storage.repository.ExpertProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Existing profile selection and application-level authoring remain explicit, separate modes. */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal fun SessionExpertDialog(
    chatService: ChatService,
    sessionId: String?,
    onManage: () -> Unit = {},
    returnToSelection: Boolean = false,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(emptyList<ExpertProfile>()) }
    var selected by remember { mutableStateOf<ExpertProfile?>(null) }
    var mode by rememberSaveable(sessionId) { mutableStateOf(if (sessionId == null) "manage" else "select") }
    var editId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(sessionId) { mutableStateOf("") }
    var instruction by rememberSaveable(sessionId) { mutableStateOf("") }
    var imported by rememberSaveable(sessionId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        profiles = chatService.savedExperts().await()
        selected = sessionId?.let { chatService.loadSessionExpert(it).await() }
        loaded = true
    }
    val act: (suspend () -> Unit) -> Unit = { action ->
        if (!busy) {
            busy = true
            failed = false
            scope.launch {
                try {
                    action()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
    LaunchedEffect(chatService, sessionId) { act { reload() } }
    val cancel: () -> Unit = {
        if (!busy) {
            failed = false
            when {
                mode == "edit" || mode == "import" -> mode = "manage"
                mode == "manage" && sessionId != null -> mode = "select"
                else -> onDismiss()
            }
        }
    }
    ConversationSheet(
        title = stringResource(if (mode == "select") R.string.expert_select else R.string.expert_library),
        tag = "session-expert",
        onDismiss = { if (!busy) onDismiss() },
        avoidIme = true,
        footer = {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(enabled = !busy, onClick = cancel, modifier = Modifier.testTag("expert-cancel")) {
                    Text(
                        stringResource(
                            when {
                                mode == "manage" && returnToSelection -> R.string.expert_return_selection
                                mode == "manage" -> R.string.chat_details_close
                                else -> R.string.common_cancel
                            },
                        ),
                    )
                }
                if (mode == "edit" || mode == "import") {
                    Button(
                        enabled = !busy && loaded,
                        onClick = {
                            act {
                                if (mode == "edit") {
                                    check(chatService.saveExpert(requireNotNull(editId), name, instruction).await())
                                } else {
                                    check(chatService.importExpert(imported, requireNotNull(editId)).await())
                                }
                                reload()
                                mode = "manage"
                                imported = ""
                            }
                        },
                        modifier =
                            Modifier.testTag(
                                "session-expert-save",
                            ),
                    ) { Text(stringResource(R.string.common_save)) }
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("session-expert-dialog"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.session_expert_authority_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!loaded && !failed) Text(stringResource(R.string.tasks_commands_loading))
            if (mode == "select") {
                Text(
                    stringResource(R.string.expert_selection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConversationMenuRow(
                    title = stringResource(R.string.expert_default),
                    onClick = {
                        act {
                            check(chatService.selectExpert(requireNotNull(sessionId), null).await())
                            onDismiss()
                        }
                    },
                    modifier = Modifier.testTag("session-expert-clear"),
                    enabled = loaded && !busy,
                    selected = if (loaded) selected == null else null,
                )
                selected?.let { current ->
                    Text(stringResource(R.string.expert_current, current.displayName))
                    if (profiles.none { it == current }) Text(stringResource(R.string.expert_snapshot_retained))
                }
            }
            if (mode == "select" || mode == "manage") {
                if (loaded && profiles.isEmpty()) Text(stringResource(R.string.expert_library_empty))
                profiles.forEach { profile ->
                    ConversationMenuRow(
                        title = profile.displayName,
                        supporting = profile.instruction,
                        selected = if (mode == "select" && loaded) selected == profile else null,
                        enabled = !busy && loaded,
                        modifier = Modifier.testTag("expert-row-${profile.id}"),
                        onClick = {
                            if (mode == "select") {
                                act {
                                    check(chatService.selectExpert(requireNotNull(sessionId), profile.id).await())
                                    onDismiss()
                                }
                            } else {
                                editId = profile.id
                                name = profile.displayName
                                instruction = profile.instruction
                                mode = "edit"
                            }
                        },
                    )
                }
                ConversationMenuRow(
                    title = stringResource(if (mode == "select") R.string.expert_manage else R.string.expert_create),
                    supporting = if (mode == "select") stringResource(R.string.expert_manage_hint) else null,
                    enabled = !busy,
                    modifier = Modifier.testTag("expert-manage"),
                    onClick = {
                        if (mode == "manage") {
                            editId =
                                java.util.UUID
                                    .randomUUID()
                                    .toString()
                            name = ""
                            instruction = ""
                            mode = "edit"
                        } else {
                            onManage()
                        }
                    },
                )
                if (mode == "manage") {
                    ConversationMenuRow(
                        stringResource(R.string.expert_import),
                        {
                            editId =
                                java.util.UUID
                                    .randomUUID()
                                    .toString()
                            mode = "import"
                        },
                        enabled = !busy,
                    )
                }
            }
            if (mode == "edit") {
                OutlinedTextField(
                    name,
                    { name = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.session_expert_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("session-expert-name"),
                )
                OutlinedTextField(
                    instruction,
                    { instruction = it },
                    enabled = !busy,
                    minLines = 4,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.session_expert_instruction)) },
                    modifier = Modifier.fillMaxWidth().testTag("session-expert-instruction"),
                )
            }
            if (mode == "import") {
                Text(stringResource(R.string.expert_import_hint))
                OutlinedTextField(
                    imported,
                    { imported = it },
                    enabled = !busy,
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("expert-import-json"),
                )
            }
            if (failed) {
                Text(
                    stringResource(R.string.session_expert_save_failed),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
                TextButton(
                    enabled = !busy,
                    onClick = { act { reload() } },
                ) { Text(stringResource(R.string.chat_retry)) }
            }
        }
    }
}
