package com.helix.feature.conversation.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.core.agent.TurnInterruptionNotice
import com.helix.feature.conversation.R

/** Inline to the original message; viewing its result never retries the task. */
@Composable
@Suppress("FunctionName")
fun InterruptionNoticeLine(
    notice: TurnInterruptionNotice,
    originalTurnId: String,
    onOpenTaskResult: ((String) -> Unit)? = null,
) {
    Column(Modifier.testTag("turn-interruption-$originalTurnId")) {
        Text(
            stringResource(
                when (notice) {
                    TurnInterruptionNotice.INTERRUPTED -> R.string.turn_notice_interrupted
                    TurnInterruptionNotice.UNKNOWN_EFFECTS -> R.string.turn_notice_unknown
                    TurnInterruptionNotice.INSPECTION_UNAVAILABLE -> R.string.turn_notice_inspection_unavailable
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onOpenTaskResult != null) {
            TextButton(
                { onOpenTaskResult(originalTurnId) },
                Modifier.testTag("turn-interruption-details-$originalTurnId"),
            ) {
                Text(stringResource(R.string.turn_notice_details))
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
fun MessageInterruptionNotice(
    message: MessageUi,
    onOpenTaskResult: ((String) -> Unit)?,
) {
    message.interruptionNotice?.let { notice ->
        message.turnId?.let { original -> InterruptionNoticeLine(notice, original, onOpenTaskResult) }
    }
}
