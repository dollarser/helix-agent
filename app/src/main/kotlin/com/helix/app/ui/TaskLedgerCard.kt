package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.todo.LedgerItemUi

/**
 * The conversation's Progress section (research doc section 16; HX2-07): the model's
 * working-memory ledger projected from its latest successful `todo.write` — done / in
 * progress / todo / blocked rows so a long task stays legible while the model works.
 * Read-only: the model owns the ledger; the user never edits it here.
 */
@Composable
@Suppress("FunctionName")
internal fun TaskLedgerCard(items: List<LedgerItemUi>) {
    if (items.isEmpty()) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
                .testTag("chat-ledger"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.todo_progress_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("chat-ledger-title"),
        )
        items.forEachIndexed { index, item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("chat-ledger-item-${item.state}-$index"),
            ) {
                Text(ledgerSymbol(item.state))
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(ledgerStateRes(item.state)),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (item.state == "blocked") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

/** The doc's §16 markers: ✓ done, → in progress, ○ todo, ⊘ blocked. */
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
