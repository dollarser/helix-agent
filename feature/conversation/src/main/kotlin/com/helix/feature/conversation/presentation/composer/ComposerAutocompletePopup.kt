package com.helix.feature.conversation.presentation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.feature.conversation.R
import com.helix.ui.shared.IndicatedLazyColumn

@Composable
@Suppress("FunctionName")
fun ComposerAutocompletePopup(
    suggestions: List<ComposerSuggestionItem>,
    onSelect: (ComposerSuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
    highlightedIndex: Int = 0,
) {
    if (suggestions.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(highlightedIndex, suggestions) {
        val target = highlightedIndex.coerceIn(suggestions.indices)
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == target }
        if (item == null || item.offset < layout.viewportStartOffset ||
            item.offset + item.size > layout.viewportEndOffset
        ) {
            listState.scrollToItem(target)
        }
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("composer-autocomplete-popup"),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        IndicatedLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
        ) {
            itemsIndexed(suggestions, key = { _, item -> item.id }) { index, item ->
                ComposerSuggestionRow(
                    item = item,
                    highlighted = index == highlightedIndex,
                    onClick = { onSelect(item) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ComposerSuggestionRow(
    item: ComposerSuggestionItem,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ).clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("composer-suggestion-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val description = commandDescription(item)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            description?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        val typeLabel =
            when (item.type) {
                ComposerSuggestionType.SLASH_COMMAND -> stringResource(R.string.chat_suggestion_type_command)
                ComposerSuggestionType.CONNECTOR -> stringResource(R.string.chat_suggestion_type_connector)
                ComposerSuggestionType.SKILL -> stringResource(R.string.chat_suggestion_type_skill)
                ComposerSuggestionType.FILE -> stringResource(R.string.chat_suggestion_type_file)
                ComposerSuggestionType.ARTIFACT -> stringResource(R.string.chat_suggestion_type_artifact)
            }
        SuggestionChip(
            onClick = onClick,
            label = { Text(typeLabel, style = MaterialTheme.typography.labelSmall) },
            colors =
                SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
        )
    }
}

@Composable
private fun commandDescription(item: ComposerSuggestionItem): String? {
    val resource =
        when (item.id) {
            "slash:chat" -> R.string.chat_command_chat_desc
            "slash:plan" -> R.string.chat_command_plan_desc
            "slash:act" -> R.string.chat_command_act_desc
            "slash:goal" -> R.string.chat_command_goal_desc
            "slash:compact" -> R.string.chat_command_compact_desc
            "slash:clear" -> R.string.chat_command_clear_desc
            "slash:help" -> R.string.chat_command_help_desc
            else -> null
        }
    return resource?.let { stringResource(it) } ?: item.detail
}
