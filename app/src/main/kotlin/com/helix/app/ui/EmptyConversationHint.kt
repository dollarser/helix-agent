package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R

@Composable
@Suppress("FunctionName")
internal fun EmptyConversationHint(
    goalMode: Boolean,
    hasProvider: Boolean,
) {
    val label =
        when {
            !hasProvider -> R.string.chat_empty_unbound
            goalMode -> R.string.chat_empty_goal
            else -> R.string.chat_empty_conversation
        }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("chat-empty-hint"),
    )
}
