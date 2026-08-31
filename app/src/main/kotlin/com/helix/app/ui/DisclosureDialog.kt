package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.helix.app.chat.EgressDisclosure

/**
 * The pre-send egress disclosure dialog (doc 10 section 2.6; ADR-0005): rendered
 * from the auditable [EgressDisclosure.EgressSummary] — provider, protocol,
 * canonical origin, residence, data categories, scope.
 *
 * M2 honesty rule: NEITHER profile offers a permanent-allow option
 * ([EgressDisclosure.PERMANENT_ALLOW_OFFERED_IN_M2] is false); the dialog says
 * so explicitly instead of faking a “已门控” state (the Advanced bounded,
 * revocable rules arrive with the HXA-033 rule engine).
 */
@Composable
@Suppress("FunctionName")
fun DisclosureDialog(
    summary: EgressDisclosure.EgressSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认发送本次数据") },
        text = {
            Column {
                Text(
                    "Provider：${summary.providerName}（${UiLabels.protocolLabel(summary.protocol)}）",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "目的地：${UiLabels.displayOrigin(summary.origin)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "数据驻留：${UiLabels.residenceLabel(summary.residence)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "数据类别：${summary.categories.joinToString("、") { it.label }}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text("范围：${summary.scope}", style = MaterialTheme.typography.bodyLarge)
            }
            if (!EgressDisclosure.PERMANENT_ALLOW_OFFERED_IN_M2) {
                Text(
                    text =
                        "本次确认仅对本次发送生效。本版本不提供“永久允许”选项" +
                            "（Advanced 的有限期允许规则将在后续里程碑提供）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("egress-confirm"),
            ) {
                Text("发送")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("egress-dismiss"),
            ) {
                Text("取消")
            }
        },
        modifier = Modifier.testTag("egress-disclosure-dialog"),
    )
}
