package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Suppress("TooGenericExceptionCaught") // UI failure preserves the durable input; cancellation propagates.
private suspend fun <T> preservingCancellation(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        Result.failure(failure)
    }

/** Only undelivered inputs occupy composer space. Every edit/action uses the displayed revision. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun SessionInputQueuePanel(
    service: ChatService,
    sessionId: String,
    refreshKey: Any? = null,
    activeTurnId: String? = null,
) {
    var records by remember(sessionId) { mutableStateOf<List<SessionInputRecord>>(emptyList()) }
    var previews by remember(sessionId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editing by remember(sessionId) { mutableStateOf<SessionInputRecord?>(null) }
    var editText by remember(sessionId) { mutableStateOf("") }
    var notice by remember(sessionId) { mutableStateOf<Int?>(null) }
    var loadGeneration by remember(sessionId) { mutableStateOf(0) }
    val busy = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    suspend fun refreshQueue() {
        val generation = ++loadGeneration
        val result =
            preservingCancellation {
                val pending =
                    service.sessionInputDeliveryStatus(sessionId).await().filter {
                        it.state == SessionInputState.PENDING || it.state == SessionInputState.NEEDS_ATTENTION
                    }
                pending to
                    pending.associate {
                        it.inputId to
                            service
                                .readSessionInput(it.inputId)
                                .await()
                                .orEmpty()
                                .take(240)
                    }
            }
        if (generation != loadGeneration) return
        result
            .onSuccess { (pending, text) ->
                records = pending
                previews = text
                if (notice == R.string.session_input_load_failed) notice = null
            }.onFailure { notice = R.string.session_input_load_failed }
    }

    fun act(
        record: SessionInputRecord,
        action: suspend () -> Boolean,
    ) {
        if (busy[record.inputId] == true) return
        busy[record.inputId] = true
        notice = null
        scope.launch {
            try {
                preservingCancellation(action)
                    .onSuccess { accepted ->
                        if (!accepted) notice = R.string.session_input_revision_stale
                    }.onFailure { notice = R.string.session_input_action_failed }
                refreshQueue()
            } finally {
                busy[record.inputId] = false
            }
        }
    }

    LaunchedEffect(sessionId, refreshKey) { refreshQueue() }
    LaunchedEffect(service, sessionId) {
        service.observeSessionInputDelivery(sessionId).distinctUntilChanged().collect { refreshQueue() }
    }

    if (records.isNotEmpty() || notice != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("session-input-queue-panel")) {
            SessionInputQueuePreview(
                records,
                previews,
                busy,
                activeTurnId,
                actions =
                    SessionInputQueueActions(
                        edit = { record ->
                            act(record) {
                                val text = checkNotNull(service.readSessionInput(record.inputId).await())
                                editing = record
                                editText = text
                                true
                            }
                        },
                        delete = { record ->
                            act(record) { service.withdrawSessionInput(record.inputId, record.revision).await() }
                        },
                        send = { record, turn ->
                            act(record) {
                                sendQueueInput(service, record, turn)
                            }
                        },
                    ),
            )
            notice?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { refreshQueue() } }) { Text(stringResource(R.string.chat_retry)) }
            }
        }
    }
    editing?.let { record ->
        SessionInputQueueEditor(
            inputId = record.inputId,
            text = editText,
            saving = busy[record.inputId] == true,
            notice = notice,
            onChange = { editText = it },
            onDismiss = { editing = null },
            onSave = {
                val submitted = editText
                act(record) {
                    service.editSessionInput(record.inputId, record.revision, submitted).await().also {
                        if (it) editing = null
                    }
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun SessionInputQueueEditor(
    inputId: String,
    text: String,
    saving: Boolean,
    notice: Int?,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.session_input_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = onChange,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("session-input-edit-$inputId"),
                )
                notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onSave) { Text(stringResource(R.string.session_input_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private suspend fun sendQueueInput(
    service: ChatService,
    record: SessionInputRecord,
    turn: String?,
): Boolean =
    if (turn != null && record.state == SessionInputState.PENDING) {
        service.sendQueuedInputNow(record.inputId, record.revision, turn).await()
    } else {
        when (service.resumeSessionInput(record.inputId, record.revision).await()) {
            is ChatSubmissionOutcome.Accepted, is ChatSubmissionOutcome.Enqueued,
            ChatSubmissionOutcome.PendingConfirmation,
            -> true

            is ChatSubmissionOutcome.Rejected -> false
        }
    }
