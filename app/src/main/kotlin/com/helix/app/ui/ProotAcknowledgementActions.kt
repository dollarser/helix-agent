package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.ToolTimelineRow

@Composable
@Suppress("FunctionName")
internal fun ProotAcknowledgementActions(
    row: ToolTimelineRow,
    acknowledged: Boolean?,
    retry: (String, String) -> Unit,
) {
    val label =
        when (acknowledged) {
            true -> R.string.proot_ack_confirmed
            false -> R.string.proot_ack_pending
            null -> R.string.proot_ack_unknown
        }
    Text(stringResource(label))
    if (acknowledged != true) {
        TextButton(
            enabled = !row.prootRecoveryBusy,
            onClick = { retry(row.turnId, row.callId) },
            modifier = Modifier.testTag("proot-ack-${row.callId}"),
        ) { Text(stringResource(R.string.proot_ack_retry)) }
    }
}
