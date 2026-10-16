package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatImageRef
import com.helix.app.chat.ChatImageUi
import com.helix.app.chat.PendingAttachmentUi

/** One bounded-height row instead of one permanent history-height deduction per attachment. */
@Composable
@Suppress("FunctionName")
internal fun PendingAttachmentStrip(
    attachments: List<PendingAttachmentUi>,
    onRemove: (String) -> Unit,
    sessionId: String? = null,
    loadImage: suspend (ChatImageRef) -> ByteArray? = { null },
) {
    if (attachments.isEmpty()) return
    var selected by remember(sessionId) { mutableStateOf<ChatImageUi?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .indicatedHorizontalScroll(rememberScrollState())
            .testTag("chat-pending-attachments"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            key(sessionId, attachment.id) {
                if (attachment.isImage && sessionId != null) {
                    val image = ChatImageUi(ChatImageRef(sessionId, null, attachment.id), attachment.fileName)
                    Box {
                        ChatImageTile(image, loadImage) { selected = image }
                        IconButton(
                            { onRemove(attachment.id) },
                            Modifier
                                .align(Alignment.TopEnd)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    androidx.compose.foundation.shape.CircleShape,
                                ).testTag("chat-pending-remove-${attachment.id}"),
                        ) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.chat_delete_attachment))
                        }
                    }
                } else {
                    PendingFile(attachment, onRemove)
                }
            }
        }
    }
    selected?.takeIf { image -> attachments.any { it.id == image.reference.attachmentId } }?.let {
        ChatImageDialog(it, loadImage) { selected = null }
    }
}

@Composable
@Suppress("FunctionName")
private fun PendingFile(
    attachment: PendingAttachmentUi,
    onRemove: (String) -> Unit,
) {
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
