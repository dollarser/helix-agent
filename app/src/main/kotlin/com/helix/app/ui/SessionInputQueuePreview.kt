package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState

/** Pending messages stay visible above the editor, with a bounded scroll area on small screens. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun SessionInputQueuePreview(
    records: List<SessionInputRecord>,
    previews: Map<String, String>,
    busy: Map<String, Boolean>,
    activeTurnId: String?,
    onSendNow: (SessionInputRecord, String) -> Unit,
) {
    if (records.isEmpty()) return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.session_input_queue_heading, records.size),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(stringResource(R.string.session_input_queue_hint), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max = 168.dp).indicatedVerticalScroll(rememberScrollState())) {
                records.forEach { record ->
                    SessionInputPendingRow(
                        record,
                        previews[record.inputId],
                        busy[record.inputId] == true,
                        activeTurnId,
                    ) {
                        onSendNow(record, it)
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SessionInputPendingRow(
    record: SessionInputRecord,
    preview: String?,
    busy: Boolean,
    activeTurnId: String?,
    onSendNow: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag("session-input-preview-${record.inputId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                preview.orEmpty().ifBlank { stringResource(R.string.session_input_attachment_message) },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (record.attachments.isNotEmpty()) {
                Text(
                    stringResource(R.string.session_input_attachment_count, record.attachments.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (record.state == SessionInputState.NEEDS_ATTENTION) {
                Text(
                    stringResource(R.string.session_input_needs_attention),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (record.delivery == SessionInputDelivery.STEER) {
                Text(stringResource(R.string.session_input_sending_current), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (record.state == SessionInputState.PENDING && record.delivery == SessionInputDelivery.QUEUE &&
            activeTurnId != null
        ) {
            TextButton(
                onClick = { onSendNow(activeTurnId) },
                enabled = !busy,
                modifier = Modifier.testTag("session-input-send-now-${record.inputId}"),
            ) { Text(stringResource(R.string.session_input_send_now)) }
        }
    }
}
