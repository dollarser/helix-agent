package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helix.app.R
import com.helix.app.chat.ChatContextUsage

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ContextWindowIndicator(
    usage: ChatContextUsage,
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    val title = stringResource(R.string.chat_context_title)
    val label = usage.percentage?.let { (if (usage.estimatedAfterCompaction) "≈" else "") + "$it%" } ?: "?"
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
    val labelWidth = rememberTextMeasurer().measure(label, labelStyle, maxLines = 1).size.width
    val ringSize = with(LocalDensity.current) { (labelWidth.toDp() + 8.dp).coerceAtLeast(28.dp) }
    Box {
        IconButton(
            { detailsOpen = true },
            Modifier.size(maxOf(48.dp, ringSize)).testTag("chat-context-window").semantics {
                contentDescription = "$title: $label"
            },
        ) {
            Box(Modifier.size(ringSize).testTag("chat-context-ring"), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { usage.fraction ?: 0f },
                    modifier = Modifier.size(ringSize),
                    strokeWidth = 2.dp,
                    color =
                        if ((usage.fraction ?: 0f) >=
                            NEAR_FULL
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    label,
                    style = labelStyle,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
    if (detailsOpen) {
        val unknown = stringResource(R.string.chat_context_unknown)
        val usageLabel =
            if (usage.estimatedAfterCompaction) {
                R.string.context_compacted_usage
            } else {
                R.string.chat_context_usage
            }
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text(title) },
            text = {
                Text(
                    stringResource(
                        usageLabel,
                        usage.inputTokens?.toString() ?: unknown,
                        usage.windowTokens?.toString() ?: unknown,
                    ) +
                        "\n\n" +
                        stringResource(R.string.chat_context_explanation),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            dismissButton = {
                TextButton({
                    detailsOpen = false
                    onCompact()
                }, enabled = canCompact, modifier = Modifier.testTag("context-compact-now")) {
                    Text(stringResource(R.string.context_compact_now))
                }
            },
            confirmButton = {
                TextButton(
                    { detailsOpen = false },
                ) { Text(stringResource(R.string.chat_context_close)) }
            },
        )
    }
}

private const val NEAR_FULL = 0.85f
