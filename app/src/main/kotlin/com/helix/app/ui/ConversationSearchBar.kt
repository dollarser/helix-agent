package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ConversationSearchBar(
    query: String,
    matchCount: Int,
    currentMatchIndex: Int,
    onQueryChange: (String) -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("chat-search-bar"),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_files_search),
                contentDescription = stringResource(R.string.chat_search),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConversationSearchInputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearchAction = onNextMatch,
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                ConversationSearchCounter(matchCount = matchCount, currentMatchIndex = currentMatchIndex)
            }
            ConversationSearchActions(
                matchCount = matchCount,
                onPrevMatch = onPrevMatch,
                onNextMatch = onNextMatch,
                onClose = onClose,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ConversationSearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.testTag("chat-search-input"),
        textStyle =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
        decorationBox = { innerTextField ->
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_search_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            innerTextField()
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun ConversationSearchCounter(
    matchCount: Int,
    currentMatchIndex: Int,
) {
    val counterText =
        if (matchCount > 0) {
            stringResource(R.string.chat_search_count, currentMatchIndex + 1, matchCount)
        } else {
            stringResource(R.string.chat_search_no_matches)
        }
    val textColor =
        if (matchCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Text(
        text = counterText,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier = Modifier.padding(horizontal = 4.dp).testTag("chat-search-match-count"),
    )
}

@Composable
@Suppress("FunctionName")
private fun ConversationSearchActions(
    matchCount: Int,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClose: () -> Unit,
) {
    IconButton(
        onClick = onPrevMatch,
        enabled = matchCount > 0,
        modifier = Modifier.size(36.dp).testTag("chat-search-prev"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_expand_summary),
            contentDescription = stringResource(R.string.chat_search_prev),
            modifier = Modifier.size(18.dp).rotate(180f),
            tint =
                if (matchCount > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
        )
    }
    IconButton(
        onClick = onNextMatch,
        enabled = matchCount > 0,
        modifier = Modifier.size(36.dp).testTag("chat-search-next"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_expand_summary),
            contentDescription = stringResource(R.string.chat_search_next),
            modifier = Modifier.size(18.dp),
            tint =
                if (matchCount > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
        )
    }
    IconButton(
        onClick = onClose,
        modifier = Modifier.size(36.dp).testTag("chat-search-close"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.chat_search_close),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
