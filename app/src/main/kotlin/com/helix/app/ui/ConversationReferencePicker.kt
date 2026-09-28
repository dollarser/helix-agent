package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.SessionRowUi

/** Selects provenance only. Reference bytes are frozen later at explicit submission. */
@Composable
@Suppress("FunctionName")
internal fun ConversationReferencePicker(
    sessions: List<SessionRowUi>,
    currentSessionId: String,
    onSelect: (SessionRowUi) -> Unit,
    onDismiss: () -> Unit,
    onMemory: (() -> Unit)? = null,
) {
    val candidates = sessions.filter { it.id != currentSessionId }
    ConversationSheet(
        stringResource(R.string.conversation_reference_title),
        "conversation-reference",
        onDismiss,
    ) {
        onMemory?.let { action ->
            TextButton(
                action,
                modifier = Modifier.testTag("reference-memory"),
            ) { Text(stringResource(R.string.memory_title)) }
        }
        Text(stringResource(R.string.conversation_reference_hint))
        if (candidates.isEmpty()) {
            Text(stringResource(R.string.conversation_reference_empty))
        } else {
            candidates.forEach { row ->
                TextButton(
                    onClick = { onSelect(row) },
                    modifier = Modifier.testTag("conversation-reference-${row.id}"),
                ) {
                    Text(row.title)
                }
            }
        }
    }
}
