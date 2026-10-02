package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
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
import com.helix.app.chat.PendingAttachmentUi

/** One bounded-height row instead of one permanent history-height deduction per attachment. */
@Composable
@Suppress("FunctionName")
internal fun PendingAttachmentStrip(
    attachments: List<PendingAttachmentUi>,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .indicatedHorizontalScroll(rememberScrollState())
            .testTag("chat-pending-attachments"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Row(
                Modifier.widthIn(max = 260.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        attachment.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(UiLabels.formatBytes(attachment.sizeBytes), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = { onRemove(attachment.id) },
                    modifier = Modifier.testTag("chat-pending-remove-${attachment.id}"),
                ) {
                    Text(stringResource(R.string.chat_delete_attachment))
                }
            }
        }
    }
}
