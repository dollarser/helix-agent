package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.SubscriptionRecoveryUi
import com.helix.app.provider.SubscriptionRecoveryStatus

@Composable
@Suppress("FunctionName")
internal fun SubscriptionRecoveryActions(
    row: SubscriptionRecoveryUi,
    action: (String, String, Boolean) -> Unit,
    recover: (String, String) -> Unit = { _, _ -> },
) {
    Column {
        Text(stringResource(R.string.subscription_recovery_title))
        if (row.output == null) row.status?.let { Text(stringResource(subscriptionStatusLabel(it))) }
        if (row.localResultAvailable || row.status == SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED) {
            TextButton(
                enabled = !row.busy,
                onClick = { recover(row.turnId, row.modelCallId) },
                modifier = Modifier.testTag("subscription-result-${row.modelCallId}"),
            ) {
                val label =
                    if (row.localResultAvailable) {
                        R.string.subscription_recovery_view_local
                    } else {
                        R.string.subscription_recovery_view
                    }
                Text(stringResource(label))
            }
        }
        if (row.outputUnavailable) Text(stringResource(R.string.subscription_recovery_result_unavailable))
        row.output?.let { SubscriptionResultPages(it, row.modelCallId) }
        TextButton(
            enabled = !row.busy,
            onClick = { action(row.turnId, row.modelCallId, false) },
            modifier = Modifier.testTag("subscription-query-${row.modelCallId}"),
        ) { Text(stringResource(R.string.subscription_recovery_query)) }
        if (row.status == SubscriptionRecoveryStatus.RUNNING) {
            TextButton(
                enabled = !row.busy,
                onClick = { action(row.turnId, row.modelCallId, true) },
                modifier = Modifier.testTag("subscription-stop-${row.modelCallId}"),
            ) { Text(stringResource(R.string.subscription_recovery_stop)) }
        }
    }
}

private fun subscriptionStatusLabel(status: SubscriptionRecoveryStatus): Int =
    when (status) {
        SubscriptionRecoveryStatus.UNKNOWN -> R.string.subscription_recovery_unknown
        SubscriptionRecoveryStatus.RUNNING -> R.string.subscription_recovery_running
        SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED -> R.string.subscription_recovery_unverified
        SubscriptionRecoveryStatus.STOPPED -> R.string.subscription_recovery_stopped
        SubscriptionRecoveryStatus.EVIDENCE_EXPIRED -> R.string.subscription_recovery_expired
        SubscriptionRecoveryStatus.ENDED -> R.string.subscription_recovery_ended
        SubscriptionRecoveryStatus.STOP_REQUESTED -> R.string.subscription_recovery_stopping
    }

@Composable
@Suppress("FunctionName")
private fun SubscriptionResultPages(
    output: com.helix.app.provider.SubscriptionRecoveredOutput,
    modelCallId: String,
) {
    var page by remember(output) { mutableIntStateOf(0) }
    Text(stringResource(R.string.subscription_recovery_result_saved))
    if (output.pages.isEmpty()) {
        Text(stringResource(R.string.subscription_recovery_no_text))
    } else {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(output.pages[page], modifier = Modifier.testTag("subscription-result-text-$modelCallId"))
        }
        if (output.pages.size > 1) {
            Text(stringResource(R.string.subscription_recovery_page, page + 1, output.pages.size))
            TextButton(enabled = page > 0, onClick = { page-- }) {
                Text(stringResource(R.string.subscription_recovery_previous))
            }
            TextButton(enabled = page < output.pages.lastIndex, onClick = { page++ }) {
                Text(stringResource(R.string.subscription_recovery_next))
            }
        }
    }
}
