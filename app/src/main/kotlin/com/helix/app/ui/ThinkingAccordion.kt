package com.helix.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

internal data class ParsedThinkingMessage(
    val thinking: String?,
    val text: String,
    val isStreaming: Boolean,
)

internal object ThinkingParser {
    private const val TAG_THINK_OPEN = "<think>"
    private const val TAG_THINK_CLOSE = "</think>"

    fun parse(raw: String): ParsedThinkingMessage {
        if (!raw.contains(TAG_THINK_OPEN)) {
            return ParsedThinkingMessage(null, raw, false)
        }
        val before = raw.substringBefore(TAG_THINK_OPEN)
        val afterOpen = raw.substringAfter(TAG_THINK_OPEN)
        return if (afterOpen.contains(TAG_THINK_CLOSE)) {
            val thinking = afterOpen.substringBefore(TAG_THINK_CLOSE).trim()
            val after = afterOpen.substringAfter(TAG_THINK_CLOSE)
            val combinedText = (before + after).trim()
            ParsedThinkingMessage(thinking.ifEmpty { null }, combinedText, false)
        } else {
            val thinking = afterOpen.trim()
            ParsedThinkingMessage(thinking.ifEmpty { null }, before.trim(), true)
        }
    }
}

/**
 * Collapsible Thinking Accordion benchmarking Operit/DeepSeek/Claude mobile UX.
 */
@Composable
@Suppress("FunctionName")
internal fun ThinkingAccordion(
    thinking: String,
    isStreaming: Boolean,
    messageId: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(messageId) { mutableStateOf(isStreaming) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("thinking-accordion-$messageId"),
    ) {
        ThinkingHeader(
            isStreaming = isStreaming,
            expanded = expanded,
            messageId = messageId,
            onToggle = { expanded = !expanded },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ThinkingContent(thinking = thinking, messageId = messageId)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ThinkingHeader(
    isStreaming: Boolean,
    expanded: Boolean,
    messageId: String,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .testTag("thinking-header-$messageId"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chat_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text =
                        if (isStreaming) {
                            stringResource(R.string.chat_thinking_in_progress)
                        } else {
                            stringResource(R.string.chat_thought_process)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_expand_summary),
                contentDescription =
                    stringResource(
                        if (expanded) R.string.tool_details_hide else R.string.tool_details_show,
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .size(16.dp)
                        .rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ThinkingContent(
    thinking: String,
    messageId: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 8.dp, end = 4.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                ).padding(10.dp)
                .testTag("thinking-content-$messageId"),
    ) {
        Text(
            text = thinking,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
