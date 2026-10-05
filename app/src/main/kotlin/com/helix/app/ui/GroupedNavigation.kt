package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ShellDestination
import com.helix.app.ui.indicatedVerticalScroll

private val drawerGroupOrder =
    listOf(
        R.string.nav_projects,
        R.string.nav_models,
        R.string.nav_extensions,
        R.string.nav_group_tools,
        R.string.nav_group_work,
        R.string.nav_group_settings,
    )

/** Conversation-first drawer with direct resource entries and expandable Work/Settings groups. */
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
    onNavigate: (String) -> Unit,
) {
    val navigationDestinations =
        destinations
            .filter { it !in setOf(ShellDestination.Sessions, ShellDestination.Git, ShellDestination.Setup) }
            .sortedBy { drawerGroupOrder.indexOf(it.navigationGroup()) }
    val groups =
        navigationDestinations.groupBy { it.navigationGroup() }.mapValues { (group, entries) ->
            when (group) {
                R.string.nav_group_settings -> {
                    settingsDrawerEntries
                }

                R.string.nav_group_work -> {
                    entries.map { DrawerEntry(it.route, it.titleRes) } + workUtilityDrawerEntries
                }

                R.string.nav_group_tools -> {
                    entries
                        .sortedBy { listOf("terminal", "files", "browser").indexOf(it.route) }
                        .map { DrawerEntry(it.route, it.titleRes) }
                }

                else -> {
                    entries.map { DrawerEntry(it.route, it.titleRes) }
                }
            }
        }
    val initialExpanded = groups.filterValues { entries -> entries.any { it.matches(currentRoute) } }.keys
    var expandedGroups by rememberSaveable { mutableStateOf(initialExpanded.toList()) }
    LaunchedEffect(currentRoute) { expandedGroups = (expandedGroups + initialExpanded).distinct() }

    Column(Modifier.fillMaxHeight()) {
        Column(Modifier.weight(1f).indicatedVerticalScroll(rememberScrollState()).testTag("navigation-groups")) {
            Text("Helix", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_new_conversation)) },
                selected = false,
                onClick = onNewConversation,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("drawer-new-conversation"),
            )
            conversation.currentSessionId?.let {
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(stringResource(R.string.drawer_current_conversation))
                            Text(
                                conversation.currentTitle.ifBlank { stringResource(R.string.chat_new_session) },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
            groups.forEach { (group, entries) ->
                if (group in
                    setOf(R.string.nav_projects, R.string.nav_models, R.string.nav_extensions)
                ) {
                    val destination = entries.first()
                    NavigationDrawerItem(
                        label = { Text(stringResource(destination.titleRes)) },
                        selected = destination.matches(currentRoute),
                        onClick = { onNavigate(destination.route) },
                        modifier =
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .testTag("navigation-${destination.route}"),
                    )
                } else {
                    val isExpanded = group in expandedGroups
                    val hasSelectedChild = entries.any { it.matches(currentRoute) }
                    val expandedLabel =
                        stringResource(if (isExpanded) R.string.nav_expanded else R.string.nav_collapsed)
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
                                .testTag(navigationGroupTag(group))
                                .semantics { stateDescription = expandedLabel },
                    )
                    if (isExpanded) {
                        entries.forEach { destination ->
                            NavigationDrawerItem(
                                label = { Text(stringResource(destination.titleRes)) },
                                selected = destination.matches(currentRoute),
                                onClick = { onNavigate(destination.route) },
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
        R.string.nav_group_tools -> "navigation-group-tools"
        R.string.nav_group_configure -> "navigation-group-configure"
        R.string.nav_group_settings -> "navigation-group-settings"
        else -> "navigation-group-$groupResId"
    }

private fun ShellDestination.navigationGroup(): Int =
    when (this) {
        ShellDestination.Sessions -> R.string.nav_group_conversations

        ShellDestination.Tasks, ShellDestination.Artifacts, ShellDestination.Git,
        -> R.string.nav_group_work

        ShellDestination.Projects -> R.string.nav_projects

        ShellDestination.Models -> R.string.nav_models

        ShellDestination.Terminal, ShellDestination.Files, ShellDestination.Browser -> R.string.nav_group_tools

        ShellDestination.Extensions -> R.string.nav_extensions

        ShellDestination.Setup -> R.string.nav_group_settings

        ShellDestination.Settings -> R.string.nav_group_settings
    }
