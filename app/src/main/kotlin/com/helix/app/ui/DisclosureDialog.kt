package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.EgressDisclosure

/**
 * The pre-send egress disclosure dialog (doc 10 section 2.6; ADR-0005): rendered
 * from the auditable [EgressDisclosure.EgressSummary] — provider, protocol,
 * canonical origin, residence, data categories, scope, and — since HXA-049
 * (ADR-0014 §5) — every staged attachment's 名称 / 类型 / 大小, in the same order
 * the content sources list the files.
 *
 * M2 honesty rule: NEITHER profile offers a permanent-allow option
 * ([EgressDisclosure.PERMANENT_ALLOW_OFFERED_IN_M2] is false); the dialog says
 * so explicitly instead of faking a “已门控” state (the Advanced bounded,
 * revocable rules arrive with the HXA-033 rule engine).
 */
@Composable
@Suppress("FunctionName", "LongMethod")
fun DisclosureDialog(
    summary: EgressDisclosure.EgressSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // stringResource is @Composable, so resolve the per-category labels in a `for` loop (composable
    // scope) — a `joinToString`/`map` transform lambda is NOT composable and would not compile.
    val separator = stringResource(R.string.common_list_separator)
    val categoryLabels = mutableListOf<String>()
    for (category in summary.categories) {
        categoryLabels.add(stringResource(category.labelRes))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.disclosure_provider,
                        summary.providerName,
                        UiLabels.protocolLabel(summary.protocol),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.disclosure_destination, UiLabels.displayOrigin(summary.origin)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(
                        R.string.disclosure_residence,
                        stringResource(UiLabels.residenceLabelRes(summary.residence)),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(
                        R.string.disclosure_categories,
                        categoryLabels.joinToString(separator),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // ADR-0014 §5: 出网文件逐条展示（名称/类型/大小，内容顺序；纯文本发送为空）。
                AttachmentDisclosureLines(summary.attachments)
                Text(
                    stringResource(R.string.disclosure_scope, stringResource(summary.scope)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (!EgressDisclosure.PERMANENT_ALLOW_OFFERED_IN_M2) {
                Text(
                    text = stringResource(R.string.disclosure_no_permanent),
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
                Text(stringResource(R.string.common_send))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("egress-dismiss"),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        modifier = Modifier.testTag("egress-disclosure-dialog"),
    )
}

/**
 * ADR-0014 §5: 逐条列出出网附件（名称/类型/大小，内容顺序）。纯文本发送的 summary 为空列表，
 * 此块不渲染任何行。
 */
@Composable
@Suppress("FunctionName")
private fun AttachmentDisclosureLines(attachments: List<EgressDisclosure.EgressAttachment>) {
    attachments.forEach { attachment ->
        Text(
            stringResource(
                R.string.disclosure_attachment,
                attachment.fileName,
                localizedString(attachment.kindRes, attachment.kindArgs),
                UiLabels.formatBytes(attachment.sizeBytes),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
