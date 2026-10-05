package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState

/** A compact single-line preview per queued input; actions retain the displayed input identity. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun SessionInputQueuePreview(
    records: List<SessionInputRecord>,
    previews: Map<String, String>,
    busy: Map<String, Boolean>,
    activeTurnId: String?,
    actions: SessionInputQueueActions,
) {
    if (records.isEmpty()) return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .heightIn(max = 168.dp)
                .indicatedVerticalScroll(rememberScrollState()),
        ) {
            records.forEach { record ->
                SessionInputPendingRow(
                    record,
                    previews[record.inputId],
                    busy[record.inputId] == true,
                    activeTurnId,
                    actions,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun SessionInputPendingRow(
    record: SessionInputRecord,
    preview: String?,
    busy: Boolean,
    activeTurnId: String?,
    actions: SessionInputQueueActions,
) {
    Row(
        Modifier.fillMaxWidth().testTag("session-input-preview-${record.inputId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                preview
                    .orEmpty()
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .ifBlank { stringResource(R.string.session_input_attachment_message) },
                modifier = Modifier.testTag("session-input-preview-text-${record.inputId}"),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (record.state == SessionInputState.NEEDS_ATTENTION) {
                Text(
                    stringResource(R.string.session_input_needs_attention),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(
            onClick = { actions.edit(record) },
            enabled = !busy,
            modifier = Modifier.testTag("session-input-edit-action-${record.inputId}"),
        ) {
            Icon(painterResource(R.drawable.ic_chat_edit), stringResource(R.string.session_input_edit))
        }
        IconButton(
            onClick = { actions.delete(record) },
            enabled = !busy,
            modifier = Modifier.testTag("session-input-delete-${record.inputId}"),
        ) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.session_input_delete))
        }
        val canSend = record.delivery == SessionInputDelivery.QUEUE || record.state == SessionInputState.NEEDS_ATTENTION
        IconButton(
            onClick = { actions.send(record, activeTurnId) },
            enabled = !busy && canSend,
            modifier = Modifier.testTag("session-input-send-now-${record.inputId}"),
        ) {
            Icon(painterResource(R.drawable.ic_composer_send), stringResource(R.string.session_input_send_now))
        }
    }
}
