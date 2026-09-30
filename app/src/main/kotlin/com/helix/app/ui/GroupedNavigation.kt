package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ShellDestination

/** Conversation-first drawer plus the two-level global work/configure/settings navigation. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
internal fun GroupedNavigation(
    destinations: List<ShellDestination>,
    currentRoute: String,
    conversation: ConversationDrawerState,
    onCurrentConversation: () -> Unit,
    onNewConversation: () -> Unit,
    onAllConversations: () -> Unit,
    footer: @Composable () -> Unit = {},
    onNavigate: (ShellDestination) -> Unit,
) {
    val navigationDestinations =
        destinations
            .filter { it != ShellDestination.Sessions }
            .sortedBy { if (it == ShellDestination.Models) 0 else 1 }
    val initialExpanded =
        remember(navigationDestinations, currentRoute) {
            navigationDestinations
                .groupBy { it.navigationGroup() }
                .filter { (_, entries) -> entries.any { it.route == currentRoute } }
                .keys
        }
    var expandedGroups by remember(currentRoute) { mutableStateOf(initialExpanded) }

    Column(Modifier.fillMaxHeight()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("navigation-groups")) {
            Text("Helix", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_new_conversation)) },
                selected = false,
                onClick = onNewConversation,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("drawer-new-conversation"),
            )
            conversation.currentSessionId?.let {
                Text(
                    stringResource(R.string.drawer_current_conversation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                )
                NavigationDrawerItem(
                    label = {
                        Text(
                            conversation.currentTitle.ifBlank { stringResource(R.string.chat_new_session) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    selected = currentRoute == ShellDestination.Sessions.route,
                    onClick = onCurrentConversation,
                    modifier =
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .testTag("drawer-current-conversation"),
                )
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_all_conversations)) },
                selected = currentRoute == CONVERSATION_HISTORY_ROUTE,
                onClick = onAllConversations,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("drawer-all-conversations"),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            navigationDestinations.groupBy { it.navigationGroup() }.forEach { (group, entries) ->
                if (entries.size == 1) {
                    val destination = entries.first()
                    NavigationDrawerItem(
                        label = { Text(stringResource(destination.titleRes)) },
                        selected = destination.route == currentRoute,
                        onClick = { onNavigate(destination) },
                        modifier =
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .testTag("navigation-${destination.route}"),
                    )
                } else {
                    val isExpanded = group in expandedGroups
                    val hasSelectedChild = entries.any { it.route == currentRoute }
                    NavigationDrawerItem(
                        label = { Text(stringResource(group)) },
                        selected = !isExpanded && hasSelectedChild,
                        badge = { Text(if (isExpanded) "▴" else "▾") },
                        onClick = {
                            expandedGroups =
                                if (isExpanded) {
                                    expandedGroups - group
                                } else {
                                    expandedGroups + group
                                }
                        },
                        modifier =
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .testTag(navigationGroupTag(group)),
                    )
                    if (isExpanded) {
                        entries.forEach { destination ->
                            NavigationDrawerItem(
                                label = { Text(stringResource(destination.titleRes)) },
                                selected = destination.route == currentRoute,
                                onClick = { onNavigate(destination) },
                                modifier =
                                    Modifier
                                        .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
                                        .testTag("navigation-${destination.route}"),
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        footer()
    }
}

internal fun navigationGroupTag(groupResId: Int): String =
    when (groupResId) {
        R.string.nav_group_conversations -> "navigation-group-conversations"
        R.string.nav_group_work -> "navigation-group-work"
        R.string.nav_group_configure -> "navigation-group-configure"
        R.string.nav_group_settings -> "navigation-group-settings"
        else -> "navigation-group-$groupResId"
    }

private fun ShellDestination.navigationGroup(): Int =
    when (this) {
        ShellDestination.Sessions -> R.string.nav_group_conversations

        ShellDestination.Tasks, ShellDestination.Artifacts, ShellDestination.Git,
        ShellDestination.Files, ShellDestination.Browser,
        -> R.string.nav_group_work

        ShellDestination.Models -> R.string.nav_models

        ShellDestination.Terminal -> R.string.nav_terminal

        ShellDestination.Extensions, ShellDestination.Setup -> R.string.nav_group_configure

        ShellDestination.Settings -> R.string.nav_group_settings
    }
