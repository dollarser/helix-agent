package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
    var details by remember(row.callId) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                .padding(8.dp)
                .testTag("tool-row-${row.callId}"),
    ) {
        ToolTimelineSummary(row, details) { details = !details }
        if (!details && row.resultSummary != null) {
            ToolResultInlinePreview(row.resultSummary, { details = true }, row.callId)
        }
        if (details) {
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
        }
        CommandDetailEntry(row, intents)
        if (row.prootRecoveryAvailable) {
            ProotRecoveryActions(row, intents.onInspectProot, intents.onRecoverProot, intents.onRetryProotAck)
        }
        row.card?.takeIf { details || it.state == com.helix.app.approval.ApprovalCardState.PENDING }?.let { card ->
            PendingApprovalCard(card, intents)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ToolTimelineSummary(
    row: com.helix.app.chat.ToolTimelineRow,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ToolTimelineHeading(row)
            Text(
                ToolPurpose.text(row.toolName, row.requestSummary),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggle, modifier = Modifier.testTag("tool-details-${row.callId}")) {
            Icon(
                painterResource(R.drawable.ic_expand_summary),
                stringResource(if (expanded) R.string.tool_details_hide else R.string.tool_details_show),
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ToolTimelineHeading(row: com.helix.app.chat.ToolTimelineRow) {
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
}

/** The live approval card wired to the conversation intents. */
@Composable
@Suppress("FunctionName")
private fun PendingApprovalCard(
    card: com.helix.app.approval.ApprovalCardUi,
    intents: ConversationIntents,
) {
    ApprovalCard(
        card = card,
        onApprove = { intents.onApproveApproval(card.approvalId) },
        onDeny = { intents.onDenyApproval(card.approvalId) },
    )
}

/**
 * HXA-194: the command details entry from the tool row — a read-only navigation, visible
 * for every state of a command call (a running command shows status only on the page).
 */
@Composable
@Suppress("FunctionName")
private fun CommandDetailEntry(
    row: com.helix.app.chat.ToolTimelineRow,
    intents: ConversationIntents,
) {
    if (row.toolName in com.helix.app.proot.COMMAND_TOOL_NAMES) {
        TextButton(
            onClick = { intents.onOpenCommandDetail(row.turnId, row.callId) },
            modifier = Modifier.testTag("command-detail-${row.callId}"),
        ) { Text(stringResource(R.string.chat_tool_command_detail)) }
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

@Composable
@Suppress("FunctionName")
private fun ToolResultInlinePreview(
    summary: String,
    onExpand: () -> Unit,
    callId: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onExpand)
                .testTag("tool-inline-preview-$callId"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = summary.lineSequence().take(3).joinToString("\n"),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_summary),
                contentDescription = stringResource(R.string.tool_preview_expand),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
