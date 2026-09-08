package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.helix.app.R
import com.helix.core.model.TurnState

/** Read-only presentation of the active turn; it never starts or retries work. */
@Composable
@Suppress("FunctionName")
internal fun TurnProgressLabel(
    state: TurnState,
    awaitingApproval: Boolean,
) {
    val label =
        when (state) {
            TurnState.CREATED, TurnState.BUILDING_CONTEXT -> {
                R.string.chat_preparing
            }

            TurnState.WAITING_MODEL -> {
                R.string.chat_waiting_model
            }

            TurnState.RECEIVING_MODEL -> {
                R.string.chat_receiving_model
            }

            TurnState.WAITING_APPROVAL -> {
                R.string.chat_waiting_approval
            }

            TurnState.RUNNING_TOOL -> {
                if (awaitingApproval) R.string.chat_waiting_approval else R.string.chat_running_tool
            }

            TurnState.RECORDING_TOOL_RESULT -> {
                R.string.chat_recording_result
            }

            TurnState.CANCELLING -> {
                R.string.chat_cancelling
            }

            TurnState.INTERRUPTED -> {
                R.string.chat_interrupted
            }

            TurnState.CANCELLED -> {
                R.string.chat_cancelled
            }

            TurnState.COMPLETED, TurnState.FAILED -> {
                null
            }
        }
    if (label != null) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("chat-turn-progress").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
