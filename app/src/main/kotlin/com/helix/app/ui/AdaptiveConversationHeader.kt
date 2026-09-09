package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Keep the conversation usable when persistent details would consume a small viewport. */
@Composable
@Suppress("FunctionName", "LongMethod") // The adaptive header keeps one Compose layout scope.
internal fun AdaptiveConversationHeader(
    summary: String,
    onBack: () -> Unit,
    onNew: () -> Unit = {},
    onTasks: () -> Unit = {},
    onNavigation: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chat-header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onNavigation?.let { navigate ->
                IconButton(navigate, modifier = Modifier.size(48.dp).testTag("open-navigation")) {
                    NavigationMenuIcon()
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = onRename != null, onClickLabel = stringResource(R.string.chat_rename)) {
                        onRename?.invoke()
                    }.padding(horizontal = 4.dp)
                    .testTag("chat-title"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    summary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                TextButton(onTasks, modifier = Modifier.testTag("background-tasks-open")) {
                    Text(stringResource(R.string.background_tasks))
                }
            }
            IconButton(onBack, modifier = Modifier.size(48.dp).testTag("chat-back")) {
                Icon(painterResource(R.drawable.ic_chat_sessions), stringResource(R.string.chat_back_to_sessions))
            }
            IconButton(onNew, modifier = Modifier.size(48.dp).testTag("chat-new-session")) {
                Icon(painterResource(R.drawable.ic_chat_new), stringResource(R.string.chat_new_session))
            }
            IconButton({ details = true }, modifier = Modifier.size(48.dp).testTag("chat-conversation-details")) {
                Icon(painterResource(R.drawable.ic_chat_more), stringResource(R.string.chat_conversation_details))
            }
        }
        if (details) {
            AlertDialog(
                onDismissRequest = { details = false },
                title = { Text(stringResource(R.string.chat_conversation_details)) },
                text = {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        if (compact) {
                            TextButton(
                                {
                                    details = false
                                    onTasks()
                                },
                                modifier = Modifier.testTag("background-tasks-open"),
                            ) {
                                Text(stringResource(R.string.background_tasks))
                            }
                        }
                        content()
                    }
                },
                confirmButton = {
                    TextButton({ details = false }, modifier = Modifier.testTag("chat-conversation-details-close")) {
                        Text(stringResource(R.string.chat_details_close))
                    }
                },
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun NavigationMenuIcon() {
    val label = stringResource(R.string.chat_app_navigation)
    Text(
        text = "☰",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
    )
}
