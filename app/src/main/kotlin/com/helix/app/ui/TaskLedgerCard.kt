package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.todo.LedgerItemUi

/** Read-only task progress. Presentation never changes the model-owned ledger. */
@Composable
@Suppress("FunctionName")
internal fun TaskLedgerCard(
    items: List<LedgerItemUi>,
    sessionId: String? = null,
) {
    if (items.isEmpty()) return
    var details by remember(sessionId) { mutableStateOf(false) }
    val blocked = items.count { it.state == "blocked" }
    TextButton(
        onClick = { details = true },
        modifier = Modifier.fillMaxWidth().testTag("chat-ledger"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.todo_progress_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("chat-ledger-title"),
            )
            Text(
                stringResource(R.string.chat_ledger_summary, items.count { it.state == "done" }, items.size, blocked),
                color =
                    if (blocked >
                        0
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("›")
    }
    if (details) {
        ConversationSheet(stringResource(R.string.todo_progress_title), "chat-ledger-details", { details = false }) {
            items.forEachIndexed { index, item -> LedgerRow(item, index) }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun LedgerRow(
    item: LedgerItemUi,
    index: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("chat-ledger-item-${item.state}-$index"),
    ) {
        Text(ledgerSymbol(item.state))
        Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(ledgerStateRes(item.state)),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (item.state ==
                    "blocked"
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

private fun ledgerSymbol(state: String): String =
    when (state) {
        "done" -> "✓"
        "in_progress" -> "→"
        "blocked" -> "⊘"
        else -> "○"
    }

private fun ledgerStateRes(state: String): Int =
    when (state) {
        "in_progress" -> R.string.todo_state_in_progress
        "done" -> R.string.todo_state_done
        "blocked" -> R.string.todo_state_blocked
        else -> R.string.todo_state_todo
    }
