package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.MessagePresentation
import com.helix.app.chat.MessageUi

@Composable
@Suppress("FunctionName")
internal fun AssistantMessageBody(
    message: MessageUi,
    modifier: Modifier,
    searchQuery: String?,
    isCurrentMatch: Boolean,
) {
    val parsed = remember(message.content) { ThinkingParser.parse(message.content) }
    // Provider padding is not content and must not turn a short update into an empty disclosure.
    val body = remember(parsed.text) { parsed.text.trim() }
    val muted = message.presentation != MessagePresentation.NORMAL
    val foldable =
        message.presentation == MessagePresentation.COMPLETED_PROGRESS &&
            body.isNotEmpty() && (body.length > 240 || body.count { it == '\n' } >= 4)
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val searchReveal =
        isCurrentMatch ||
            (!searchQuery.isNullOrBlank() && message.content.contains(searchQuery, ignoreCase = true))
    val revealed = !foldable || expanded || searchReveal
    Column(Modifier.testTag("message-presentation-${message.id}-${message.presentation.name}")) {
        if (foldable && !searchReveal) ProgressDisclosure(message.id, revealed) { expanded = !revealed }
        if (revealed) {
            parsed.thinking?.let { thinking ->
                ThinkingAccordion(
                    thinking,
                    parsed.isStreaming,
                    message.id,
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            if (body.isNotEmpty()) {
                MarkdownText(body, modifier, searchQuery, muted = muted)
            }
        } else {
            Text(
                remember(body) { markdownInline(body) },
                modifier,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ProgressDisclosure(
    id: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val label = stringResource(if (expanded) R.string.nav_expanded else R.string.nav_collapsed)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = label }
            .padding(horizontal = 12.dp)
            .testTag("message-progress-toggle-$id"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_expand_summary),
            null,
            Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.message_progress_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
