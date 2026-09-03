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
import androidx.compose.ui.unit.dp
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
                "工具审批：${card.source} / ${card.target}",
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
    FieldLine("来源", card.source)
    FieldLine("目标", card.target)
    FieldLine("作用域", card.scope)
    FieldLine("参数", card.arguments, tag = "approval-card-args")
    FieldLine("风险", card.risk, tag = "approval-card-risk")
    FieldLine("Safety Profile", ApprovalUiMapper.profileLabel(card.profile))
    FieldLine(
        "Provider/MCP",
        card.providerMcpId ?: "内置（无 Provider/MCP）",
    )
    FieldLine(
        "网络 origin",
        card.networkOrigin ?: ApprovalCardUi.NO_EGRESS,
    )
    FieldLine("数据驻留", card.residence ?: ApprovalCardUi.NO_EGRESS)
    FieldLine("数据类别", card.dataCategory)
    card.boundedRule?.let { rule ->
        Text(
            "有界 Policy 规则（非通用批准凭证）：${rule.display}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("approval-card-rule"),
        )
    }
    card.codeOrCommand?.let { code ->
        FieldLine("代码/命令", code, tag = "approval-card-code")
    }
    card.codeExecution?.let { CodeExecutionBlock(it) }
    FieldLine("预期影响", card.expectedImpact)
    FieldLine("校验器（verifier）", card.verifier)
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
                ) { Text(ApprovalCardUi.ACTIONS[0]) }
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.testTag("approval-deny-${card.approvalId}"),
                ) { Text(ApprovalCardUi.ACTIONS[1]) }
            }
            Text(
                "仅对本次动作生效。本版本不提供“模型帮我批准”或“此后全部允许”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("approval-card-no-permanent-allow"),
            )
        }

        else -> {
            Text(
                "${ApprovalUiMapper.stateLabel(card.state)}" +
                    (card.terminalDetail?.let { "：$it" } ?: ""),
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
        "$label：$value",
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
    FieldLine("输入来源", execution.inputSource, tag = "approval-card-input-source")
    FieldLine("联网", if (execution.online) "是" else "否", tag = "approval-card-online")
    FieldLine("执行限制", execution.limits, tag = "approval-card-limits")
    FieldLine("代码摘要 SHA-256", execution.codeSha256Short, tag = "approval-card-code-hash")
}
