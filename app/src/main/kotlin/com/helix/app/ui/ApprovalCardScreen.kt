package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalCardUi
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.approval.CodeExecutionUi

/**
 * The approval card (roadmap HXA-036): the full authorization summary of one exact,
 * one-time action, rendered in the session timeline as the fourth message type (doc 01
 * FR-CHAT-003).
 *
 * Action surface (ADR-0005 / doc 02 section 8.1): EXACTLY the two buttons from
 * [ApprovalCardUi.ACTIONS] — "本次批准" / "拒绝". There is no "模型帮我批准", no
 * "此后全部允许" and no permanent-allow; the card renders from [ApprovalCardUi.ACTIONS]
 * so a drift is a compile/test failure, not a silent copy change. A bounded Policy rule,
 * when present, is displayed with its own "有界 Policy 规则" label and expiry — never as a
 * general approval credential.
 */
@Composable
@Suppress("FunctionName")
fun ApprovalCard(
    card: ApprovalCardUi,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("approval-card-${card.approvalId}"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(
                    R.string.approval_title,
                    localizedString(card.sourceRes, card.sourceArgs),
                    stringResource(card.targetRes),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            ApprovalCardFields(card)
            ApprovalCardActions(card, onApprove, onDeny)
        }
    }
}

/** The card's mandated display fields (roadmap HXA-036 field list, in order). */
@Composable
@Suppress("FunctionName")
private fun ApprovalCardFields(card: ApprovalCardUi) {
    FieldLine(stringResource(R.string.approval_source), localizedString(card.sourceRes, card.sourceArgs))
    FieldLine(stringResource(R.string.approval_target), stringResource(card.targetRes))
    FieldLine(
        stringResource(R.string.approval_scope),
        // [ApprovalCardUi.scope] is the STABLE scope ref; the UI localizes the "unscoped" ref (HXA-069).
        if (card.scope == "unscoped") stringResource(R.string.approval_scope_unscoped) else card.scope,
    )
    FieldLine(
        stringResource(R.string.approval_arguments),
        card.arguments,
        tag = "approval-card-args",
    )
    FieldLine(
        stringResource(R.string.approval_risk),
        // The uplift template interpolates the two level labels (string-resource IDs, HXA-069).
        localizedString(card.riskRes, card.riskArgs.map { stringResource(it) }),
        tag = "approval-card-risk",
    )
    FieldLine("Safety Profile", stringResource(ApprovalUiMapper.profileLabel(card.profile)))
    FieldLine(
        "Provider/MCP",
        card.providerMcpId ?: stringResource(R.string.approval_builtin_no_provider_mcp),
    )
    FieldLine(
        stringResource(R.string.approval_network_origin),
        card.networkOrigin ?: stringResource(ApprovalCardUi.NO_EGRESS),
    )
    FieldLine(
        stringResource(R.string.approval_data_residence),
        card.residence ?: stringResource(ApprovalCardUi.NO_EGRESS),
    )
    FieldLine(stringResource(R.string.approval_data_category), stringResource(card.dataCategoryRes))
    card.boundedRule?.let { rule ->
        Text(
            stringResource(R.string.approval_bounded_rule, localizedString(rule.displayRes, rule.displayArgs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("approval-card-rule"),
        )
    }
    card.codeOrCommand?.let { code ->
        FieldLine(stringResource(R.string.approval_code_command), code, tag = "approval-card-code")
    }
    card.codeExecution?.let { CodeExecutionBlock(it) }
    FieldLine(stringResource(R.string.approval_expected_impact), card.expectedImpact)
    FieldLine(stringResource(R.string.approval_verifier), stringResource(card.verifierRes))
    Text(
        card.confirmationDetail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The card's action surface: while [ApprovalCardState.PENDING] the EXACTLY two buttons from
 * [ApprovalCardUi.ACTIONS] ("本次批准" / "拒绝"); afterwards the stable state label.
 */
@Composable
@Suppress("FunctionName")
private fun ApprovalCardActions(
    card: ApprovalCardUi,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    when (card.state) {
        ApprovalCardState.PENDING -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.testTag("approval-approve-${card.approvalId}"),
                ) { Text(stringResource(ApprovalCardUi.ACTIONS[0])) }
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.testTag("approval-deny-${card.approvalId}"),
                ) { Text(stringResource(ApprovalCardUi.ACTIONS[1])) }
            }
            Text(
                stringResource(R.string.approval_no_permanent_allow),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("approval-card-no-permanent-allow"),
            )
        }

        else -> {
            val stateLabel = stringResource(ApprovalUiMapper.stateLabel(card.state))
            Text(
                card.terminalDetail?.let { detail ->
                    stringResource(R.string.approval_state_with_detail, stateLabel, detail)
                } ?: stateLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("approval-card-state-${card.approvalId}"),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun FieldLine(
    label: String,
    value: String,
    tag: String? = null,
) {
    Text(
        stringResource(R.string.approval_field_line, label, value),
        style = MaterialTheme.typography.bodyMedium,
        modifier = tag?.let { Modifier.testTag(it) } ?: Modifier,
    )
}

/**
 * The code-execution section of a code tool's approval card (HXA-053; doc 03 §5): the FULL
 * code as a copyable/searchable monospace block, the input source + size (not the body), the
 * fixed "联网：否" line, the applied limits, and the code SHA-256 short digest.
 */
@Composable
@Suppress("FunctionName")
private fun CodeExecutionBlock(execution: CodeExecutionUi) {
    Text(
        execution.code,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("approval-card-code-block"),
    )
    FieldLine(
        stringResource(R.string.approval_input_source),
        localizedString(execution.inputSourceRes, execution.inputSourceArgs),
        tag = "approval-card-input-source",
    )
    FieldLine(
        stringResource(R.string.approval_online),
        if (execution.online) {
            stringResource(R.string.approval_yes)
        } else {
            stringResource(R.string.approval_no)
        },
        tag = "approval-card-online",
    )
    FieldLine(
        stringResource(R.string.approval_execution_limits),
        localizedString(execution.limitsRes, execution.limitsArgs),
        tag = "approval-card-limits",
    )
    FieldLine(
        stringResource(R.string.approval_code_sha256),
        execution.codeSha256Short,
        tag = "approval-card-code-hash",
    )
}
