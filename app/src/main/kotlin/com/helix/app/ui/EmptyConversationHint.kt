package com.helix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Suggested tasks add to the user's draft; they never silently replace unfinished input. */
internal fun appendTaskPrompt(
    draft: String,
    prompt: String,
): String = if (draft.isBlank()) prompt else "$draft\n\n$prompt"

@Composable
@Suppress("FunctionName")
internal fun EmptyConversationHint(
    goalMode: Boolean,
    hasProvider: Boolean,
    onSelectPrompt: ((String) -> Unit)? = null,
    onChooseModel: () -> Unit = {},
    preparation: @Composable () -> Unit = {},
) {
    val label =
        when {
            !hasProvider -> R.string.chat_empty_unbound
            goalMode -> R.string.chat_empty_goal
            else -> R.string.chat_empty_conversation
        }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HelixBrandMark()
        Text(
            stringResource(R.string.chat_task_welcome),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("chat-empty-hint"),
            textAlign = TextAlign.Center,
        )
        if (!hasProvider) {
            TextButton(onChooseModel, modifier = Modifier.testTag("starter-choose-model")) {
                Text(stringResource(R.string.chat_task_choose_model))
            }
        }
        preparation()
        if (onSelectPrompt != null) {
            val suggestions = emptyConversationSuggestions(goalMode)
            EmptyConversationPromptList(suggestions, onSelectPrompt)
        }
    }
}

internal fun emptyConversationSuggestions(goalMode: Boolean): List<Int> =
    if (goalMode) {
        listOf(
            R.string.chat_task_goal_research,
            R.string.chat_task_goal_files,
        )
    } else {
        listOf(
            R.string.chat_task_web,
            R.string.chat_task_document,
            R.string.chat_task_plan,
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
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("chat-starter-prompts"),
    ) {
        suggestions.forEachIndexed { index, promptRes ->
            val promptText = stringResource(promptRes)
            Surface(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPrompt(promptText) }
                        .testTag("starter-prompt-$promptRes"),
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(
                            listOf(
                                R.drawable.ic_drawer_browser,
                                R.drawable.ic_files_document,
                                R.drawable.ic_drawer_tasks,
                            )[
                                index %
                                    3,
                            ],
                        ),
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = promptText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
