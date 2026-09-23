package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName")
internal fun EmptyConversationHint(
    goalMode: Boolean,
    hasProvider: Boolean,
    onSelectPrompt: ((String) -> Unit)? = null,
) {
    val label =
        when {
            !hasProvider -> R.string.chat_empty_unbound
            goalMode -> R.string.chat_empty_goal
            else -> R.string.chat_empty_conversation
        }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("chat-empty-hint"),
        )
        if (hasProvider && onSelectPrompt != null) {
            val suggestions = emptyConversationSuggestions(goalMode)
            EmptyConversationPromptList(suggestions, onSelectPrompt)
        }
    }
}

internal fun emptyConversationSuggestions(goalMode: Boolean): List<Int> =
    if (goalMode) {
        listOf(
            R.string.chat_prompt_suggestion_goal_fix,
            R.string.chat_prompt_suggestion_goal_refactor,
        )
    } else {
        listOf(
            R.string.chat_prompt_suggestion_code_analysis,
            R.string.chat_prompt_suggestion_plan_feature,
            R.string.chat_prompt_suggestion_run_tests,
        )
    }

@Composable
@Suppress("FunctionName")
private fun EmptyConversationPromptList(
    suggestions: List<Int>,
    onSelectPrompt: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("chat-starter-prompts"),
    ) {
        suggestions.forEach { promptRes ->
            val promptText = stringResource(promptRes)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPrompt(promptText) }
                        .testTag("starter-prompt-$promptRes"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = promptText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
