package com.helix.app.knowledge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.core.storage.knowledge.KnowledgeStore

/** Explicit recovery; dismissing the dialog does not publish, delete or overwrite anything. */
@Composable
@Suppress("FunctionName")
internal fun KnowledgePendingSaves(
    entries: List<KnowledgeStore.PendingSave>,
    failed: Boolean,
    busy: Boolean,
    onRecover: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("knowledge-pending-dialog"),
        title = { Text(stringResource(R.string.knowledge_pending_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.knowledge_pending_message))
                entries.forEach { entry ->
                    Text(entry.title)
                    Text(entry.body.take(400))
                    TextButton(
                        { onRecover(entry.token) },
                        enabled = !busy,
                        modifier = Modifier.testTag("knowledge-recover-save"),
                    ) {
                        Text(stringResource(R.string.knowledge_pending_recover))
                    }
                }
                if (failed) Text(stringResource(R.string.knowledge_storage_unavailable))
            }
        },
        confirmButton = {
            TextButton(
                onDismiss,
                enabled = !busy,
                modifier = Modifier.testTag("knowledge-pending-close"),
            ) { Text(stringResource(R.string.knowledge_pending_close)) }
        },
    )
}
