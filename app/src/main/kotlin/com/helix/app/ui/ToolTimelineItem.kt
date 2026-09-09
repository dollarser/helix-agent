package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/**
 * One tool-timeline row (roadmap HXA-036): the tool REQUEST + RESULT, and — while the
 * approval card is live — the full [ApprovalCard] confirmation surface. The four timeline
 * message types (model text, tool request, tool result, approval card) are visually
 * distinct here (doc 01 FR-CHAT-003).
 */

@Composable
@Suppress("FunctionName")
internal fun ToolTimelineItem(
    row: com.helix.app.chat.ToolTimelineRow,
    intents: ConversationIntents,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .testTag("tool-row-${row.callId}"),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.chat_tool_row, row.toolName),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                row.stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tool-row-state-${row.callId}"),
            )
        }
        ExpandableSummary(
            stringResource(R.string.chat_tool_request, row.requestSummary),
            style = MaterialTheme.typography.bodySmall,
            tag = "tool-row-args-${row.callId}",
            collapsedLines = 3,
        )
        row.resultSummary?.let { summary ->
            ExpandableSummary(
                stringResource(R.string.chat_tool_result, summary),
                style = MaterialTheme.typography.bodySmall,
                tag = "tool-row-result-${row.callId}",
                collapsedLines = 5,
            )
        }
        if (row.prootRecoveryAvailable) {
            ProotRecoveryActions(row, intents.onInspectProot, intents.onRecoverProot, intents.onRetryProotAck)
        }
        row.card?.let { card ->
            ApprovalCard(
                card = card,
                onApprove = { intents.onApproveApproval(card.approvalId) },
                onDeny = { intents.onDenyApproval(card.approvalId) },
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun ProotRecoveryActions(
    row: com.helix.app.chat.ToolTimelineRow,
    action: (String, String, Boolean) -> Unit,
    recover: (String, String) -> Unit = { _, _ -> },
    retryAcknowledgement: (String, String) -> Unit = { _, _ -> },
) {
    if (row.prootRecoveredOutput == null) row.prootRecoveryReport?.let { Text(stringResource(it.labelRes)) }
    TextButton(
        enabled = !row.prootRecoveryBusy,
        onClick = { recover(row.turnId, row.callId) },
        modifier = Modifier.testTag("proot-result-${row.callId}"),
    ) { Text(stringResource(R.string.proot_recovery_view)) }
    if (row.prootResultUnavailable) Text(stringResource(R.string.proot_recovery_result_unavailable))
    row.prootRecoveredOutput?.let {
        ProotResultPanel(it, row.callId)
        ProotAcknowledgementActions(row, it.acknowledged, retryAcknowledgement)
    }
    TextButton(
        enabled = !row.prootRecoveryBusy,
        onClick = { action(row.turnId, row.callId, false) },
        modifier = Modifier.testTag("proot-query-${row.callId}"),
    ) { Text(stringResource(R.string.proot_recovery_query)) }
    if (row.prootRecoveryReport?.canStop == true) {
        TextButton(
            enabled = !row.prootRecoveryBusy,
            onClick = { action(row.turnId, row.callId, true) },
            modifier = Modifier.testTag("proot-stop-${row.callId}"),
        ) { Text(stringResource(R.string.proot_recovery_stop)) }
    }
}
