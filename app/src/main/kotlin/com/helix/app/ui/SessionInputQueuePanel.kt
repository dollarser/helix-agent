package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Suppress("TooGenericExceptionCaught") // Failed UI actions retain their input and offer retry; cancellation propagates.
private suspend fun <T> preservingCancellation(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        Result.failure(failure)
    }

private suspend fun runInputAction(
    action: suspend () -> Unit,
    onFailure: () -> Unit,
    onComplete: () -> Unit,
) {
    try {
        preservingCancellation(action).onFailure { onFailure() }
    } finally {
        onComplete()
    }
}

private data class SessionInputQueueRowState(
    val record: SessionInputRecord,
    val body: String?,
    val editing: Boolean,
    val editText: String,
    val busy: Boolean,
)

private data class SessionInputQueueRowActions(
    val onExpand: () -> Unit,
    val onEdit: () -> Unit,
    val onEditText: (String) -> Unit,
    val onSaveEdit: () -> Unit,
    val onCancelEdit: () -> Unit,
    val onWithdraw: () -> Unit,
    val onResume: () -> Unit,
)

/**
 * Displays persisted inputs that have not yet been consumed by the session. Loading is tied to
 * [refreshKey], supplied by the owning screen when its observable state changes; this panel never
 * starts a polling loop. Every mutation carries the record revision so a stale panel cannot
 * withdraw, edit or resume a newer record silently.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun SessionInputQueuePanel(
    service: ChatService,
    sessionId: String,
    refreshKey: Any? = null,
) {
    var records by remember(sessionId) { mutableStateOf<List<SessionInputRecord>>(emptyList()) }
    var expanded by remember(sessionId) { mutableStateOf(false) }
    var loading by remember(sessionId) { mutableStateOf(false) }
    var loadFailed by remember(sessionId) { mutableStateOf(false) }
    var editingId by remember(sessionId) { mutableStateOf<String?>(null) }
    var editText by remember(sessionId) { mutableStateOf("") }
    var editGeneration by remember(sessionId) { mutableStateOf(0) }
    var notice by remember(sessionId) { mutableStateOf<Int?>(null) }
    var loadGeneration by remember(sessionId) { mutableStateOf(0) }
    val bodies = remember(sessionId) { mutableStateMapOf<String, String?>() }
    val busy = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    suspend fun refreshQueue() {
        val generation = loadGeneration + 1
        loadGeneration = generation
        loading = true
        loadFailed = false
        val result =
            preservingCancellation {
                service.sessionInputDeliveryStatus(sessionId).await()
            }
        if (generation != loadGeneration) return
        result
            .onSuccess { loaded ->
                val previous = records.associateBy { it.inputId }
                val current = loaded.associateBy { it.inputId }
                bodies.keys
                    .toList()
                    .filter { previous[it]?.textRef != current[it]?.textRef || it !in current }
                    .forEach(bodies::remove)
                records = loaded
            }.onFailure {
                loadFailed = true
            }
        loading = false
    }

    LaunchedEffect(sessionId, refreshKey) { refreshQueue() }

    if (records.isEmpty() && !loading && !loadFailed) return
    val pendingCount =
        records.count {
            it.state == SessionInputState.PENDING ||
                it.state == SessionInputState.NEEDS_ATTENTION
        }
    val appendedCount = records.count { it.state == SessionInputState.APPENDED }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("session-input-queue-panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().testTag("session-input-queue-toggle"),
        ) {
            if (records.isEmpty()) {
                Text(stringResource(R.string.session_input_delivery_title))
            } else {
                SessionInputDeliveryCountText(pendingCount, appendedCount)
            }
        }
        if (loading) Text(stringResource(R.string.session_input_loading))
        if (loadFailed) {
            Text(
                stringResource(R.string.session_input_load_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("session-input-queue-error"),
            )
            TextButton(onClick = { scope.launch { refreshQueue() } }) {
                Text(stringResource(R.string.session_input_refresh))
            }
        }
        notice?.let { resource ->
            Text(
                stringResource(resource),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("session-input-queue-notice"),
            )
        }
        if (expanded) {
            records.forEach { record ->
                SessionInputQueueRow(
                    state =
                        SessionInputQueueRowState(
                            record = record,
                            body = bodies[record.inputId],
                            editing = editingId == record.inputId,
                            editText = editText,
                            busy = busy[record.inputId] == true,
                        ),
                    actions =
                        SessionInputQueueRowActions(
                            onExpand = {
                                if (bodies.containsKey(record.inputId)) {
                                    bodies.remove(record.inputId)
                                } else {
                                    scope.launch {
                                        val loaded =
                                            preservingCancellation {
                                                service.readSessionInput(record.inputId).await()
                                            }.getOrNull()
                                        bodies[record.inputId] = loaded
                                    }
                                }
                            },
                            onEdit = {
                                editGeneration += 1
                                val generation = editGeneration
                                editingId = record.inputId
                                editText = bodies[record.inputId] ?: ""
                                if (!bodies.containsKey(record.inputId)) {
                                    scope.launch {
                                        val loaded =
                                            preservingCancellation {
                                                service.readSessionInput(record.inputId).await()
                                            }.getOrNull()
                                        bodies[record.inputId] = loaded
                                        val sameEditor = editingId == record.inputId && editGeneration == generation
                                        if (sameEditor && loaded != null) {
                                            editText = loaded
                                        }
                                    }
                                }
                            },
                            onEditText = {
                                editGeneration += 1
                                editText = it
                            },
                            onSaveEdit = {
                                val submittedText = editText
                                busy[record.inputId] = true
                                notice = null
                                scope.launch {
                                    runInputAction(
                                        action = {
                                            val saved =
                                                service
                                                    .editSessionInput(
                                                        record.inputId,
                                                        record.revision,
                                                        submittedText,
                                                    ).await()
                                            if (saved) {
                                                editingId = null
                                                refreshQueue()
                                            } else {
                                                notice = R.string.session_input_revision_stale
                                                refreshQueue()
                                            }
                                        },
                                        onFailure = { notice = R.string.session_input_action_failed },
                                        onComplete = { busy[record.inputId] = false },
                                    )
                                }
                            },
                            onCancelEdit = { editingId = null },
                            onWithdraw = {
                                busy[record.inputId] = true
                                notice = null
                                scope.launch {
                                    runInputAction(
                                        action = {
                                            val withdrawn =
                                                service
                                                    .withdrawSessionInput(
                                                        record.inputId,
                                                        record.revision,
                                                    ).await()
                                            if (withdrawn) {
                                                refreshQueue()
                                            } else {
                                                notice = R.string.session_input_revision_stale
                                                refreshQueue()
                                            }
                                        },
                                        onFailure = { notice = R.string.session_input_action_failed },
                                        onComplete = { busy[record.inputId] = false },
                                    )
                                }
                            },
                            onResume = {
                                busy[record.inputId] = true
                                notice = null
                                scope.launch {
                                    runInputAction(
                                        action = {
                                            val outcome =
                                                service
                                                    .resumeSessionInput(
                                                        record.inputId,
                                                        record.revision,
                                                    ).await()
                                            when (outcome) {
                                                is ChatSubmissionOutcome.Accepted,
                                                is ChatSubmissionOutcome.Enqueued,
                                                -> {
                                                    refreshQueue()
                                                }

                                                ChatSubmissionOutcome.PendingConfirmation -> {
                                                    notice = R.string.session_input_resume_not_accepted
                                                }

                                                is ChatSubmissionOutcome.Rejected -> {
                                                    notice = R.string.session_input_resume_not_accepted
                                                }
                                            }
                                        },
                                        onFailure = { notice = R.string.session_input_action_failed },
                                        onComplete = { busy[record.inputId] = false },
                                    )
                                }
                            },
                        ),
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun SessionInputDeliveryCountText(
    pendingCount: Int,
    appendedCount: Int,
) {
    Text(
        stringResource(R.string.session_input_delivery_count, pendingCount, appendedCount),
        modifier = Modifier.testTag("session-input-delivery-count"),
    )
}

@Composable
@Suppress("FunctionName")
private fun SessionInputQueueRow(
    state: SessionInputQueueRowState,
    actions: SessionInputQueueRowActions,
) {
    val record = state.record
    Column(
        modifier = Modifier.fillMaxWidth().testTag("session-input-row-${record.inputId}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SessionInputQueueRowLabels(record)
        if (state.editing) {
            SessionInputQueueRowEditor(state, actions)
        } else {
            SessionInputQueueRowPreview(state, actions)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SessionInputQueueRowLabels(record: SessionInputRecord) {
    Text(
        stringResource(
            R.string.session_input_row_summary,
            deliveryLabel(record.delivery),
            stateLabel(record.state),
        ),
        style = MaterialTheme.typography.labelMedium,
    )
    if (record.state == SessionInputState.NEEDS_ATTENTION) {
        Text(
            stringResource(R.string.session_input_needs_attention),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (record.delivery == SessionInputDelivery.STEER) {
        Text(
            stringResource(
                if (record.state == SessionInputState.NEEDS_ATTENTION) {
                    R.string.session_input_steer_expired
                } else {
                    R.string.session_input_steer_current
                },
            ),
        )
    }
    if (record.state == SessionInputState.APPENDED) {
        Text(
            stringResource(
                if (record.requestModelCallId == null) {
                    R.string.session_input_appended_history
                } else {
                    R.string.session_input_appended_request
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun SessionInputQueueRowEditor(
    state: SessionInputQueueRowState,
    actions: SessionInputQueueRowActions,
) {
    OutlinedTextField(
        value = state.editText,
        onValueChange = actions.onEditText,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().testTag("session-input-edit-${state.record.inputId}"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !state.busy, onClick = actions.onSaveEdit) {
            Text(stringResource(R.string.session_input_save))
        }
        TextButton(enabled = !state.busy, onClick = actions.onCancelEdit) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SessionInputQueueRowPreview(
    state: SessionInputQueueRowState,
    actions: SessionInputQueueRowActions,
) {
    state.body?.let { Text(it, modifier = Modifier.padding(start = 8.dp)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !state.busy, onClick = actions.onExpand) {
            Text(
                stringResource(
                    if (state.body == null) R.string.session_input_show else R.string.session_input_hide,
                ),
            )
        }
        if (state.record.state == SessionInputState.PENDING ||
            state.record.state == SessionInputState.NEEDS_ATTENTION
        ) {
            TextButton(enabled = !state.busy, onClick = actions.onEdit) {
                Text(stringResource(R.string.session_input_edit))
            }
            TextButton(enabled = !state.busy, onClick = actions.onWithdraw) {
                Text(stringResource(R.string.session_input_withdraw))
            }
            if (state.record.state == SessionInputState.NEEDS_ATTENTION) {
                TextButton(enabled = !state.busy, onClick = actions.onResume) {
                    Text(stringResource(R.string.session_input_resume))
                }
            }
        }
    }
}

@Composable
private fun deliveryLabel(delivery: SessionInputDelivery): String =
    stringResource(
        when (delivery) {
            SessionInputDelivery.QUEUE -> R.string.session_input_delivery_queue
            SessionInputDelivery.STEER -> R.string.session_input_delivery_steer
        },
    )

@Composable
private fun stateLabel(state: SessionInputState): String =
    stringResource(
        when (state) {
            SessionInputState.PENDING -> R.string.session_input_state_pending
            SessionInputState.NEEDS_ATTENTION -> R.string.session_input_state_needs_attention
            SessionInputState.APPENDED -> R.string.session_input_state_appended
            SessionInputState.WITHDRAWN -> R.string.session_input_state_withdrawn
        },
    )
