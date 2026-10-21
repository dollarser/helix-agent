package com.helix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
        R.string.nav_group_work,
        R.string.nav_group_tools,
        R.string.nav_group_settings,
    )

/** Conversation-first drawer with a fixed account header and fixed settings/profile footer. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
internal fun GroupedNavigation(
    destinations: List<ShellDestination>,
    currentRoute: String,
    conversation: ConversationDrawerState,
    onCurrentConversation: () -> Unit,
    onNewConversation: () -> Unit,
    onAllConversations: () -> Unit,
    header: @Composable () -> Unit = {},
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
                    entries.map { it.workDrawerEntry() }
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
        header()
        Column(Modifier.weight(1f).indicatedVerticalScroll(rememberScrollState()).testTag("navigation-groups")) {
            OutlinedButton(
                onClick = onNewConversation,
                shape = RoundedCornerShape(16.dp),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ).testTag("drawer-new-conversation"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_drawer_new), null, Modifier.size(20.dp))
                    Text(stringResource(R.string.drawer_new_conversation))
                }
            }
            Text(
                stringResource(R.string.nav_group_conversations),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 8.dp),
            )
            conversation.currentSessionId?.let {
                DrawerRow(
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
                    iconRes = R.drawable.ic_drawer_message,
                    selected = currentRoute == ShellDestination.Sessions.route,
                    onClick = onCurrentConversation,
                    modifier =
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .testTag("drawer-current-conversation"),
                )
            }
            DrawerRow(
                label = { Text(stringResource(R.string.drawer_all_conversations)) },
                iconRes = R.drawable.ic_drawer_history,
                badge = { DrawerChevron() },
                selected = currentRoute == CONVERSATION_HISTORY_ROUTE,
                onClick = onAllConversations,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("drawer-all-conversations"),
            )
            HorizontalDivider(Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
            groups.filterKeys { it != R.string.nav_group_settings }.forEach { (group, entries) ->
                if (group in
                    setOf(R.string.nav_projects, R.string.nav_models, R.string.nav_extensions)
                ) {
                    val destination = entries.first()
                    DrawerRow(
                        label = { Text(stringResource(destination.titleRes)) },
                        iconRes = drawerIcon(destination.route),
                        badge = { DrawerDestinationChevron(destination.route) },
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
                    DrawerRow(
                        label = { Text(stringResource(group)) },
                        iconRes =
                            if (group ==
                                R.string.nav_group_work
                            ) {
                                R.drawable.ic_drawer_work
                            } else {
                                R.drawable.ic_drawer_tools
                            },
                        selected = !isExpanded && hasSelectedChild,
                        badge = { DrawerChevron(expanded = isExpanded) },
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
                            DrawerRow(
                                label = { DrawerEntryLabel(destination) },
                                iconRes = drawerIcon(destination.route),
                                selected = destination.matches(currentRoute),
                                onClick = { onNavigate(destination.route) },
                                modifier =
                                    Modifier
                                        .padding(start = 46.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
                                        .testTag("navigation-${destination.route}"),
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        DrawerSettingsNavigation(currentRoute, R.string.nav_group_settings in groups, onNavigate)
        footer()
    }
}

private fun ShellDestination.workDrawerEntry() =
    DrawerEntry(
        route,
        titleRes,
        when (this) {
            ShellDestination.Tasks -> R.string.nav_tasks_description
            ShellDestination.Knowledge -> R.string.knowledge_nav_description
            ShellDestination.Reminders -> R.string.reminder_nav
            else -> R.string.nav_artifacts_description
        },
    )

@Composable
@Suppress("FunctionName")
internal fun DrawerEntryLabel(entry: DrawerEntry) {
    Column {
        Text(stringResource(entry.titleRes))
        entry.descriptionRes?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

        ShellDestination.Tasks, ShellDestination.Artifacts, ShellDestination.Git, ShellDestination.Knowledge,
        ShellDestination.Reminders,
        -> R.string.nav_group_work

        ShellDestination.Projects -> R.string.nav_projects

        ShellDestination.Models -> R.string.nav_models

        ShellDestination.Terminal, ShellDestination.Files, ShellDestination.Browser -> R.string.nav_group_tools

        ShellDestination.Extensions -> R.string.nav_extensions

        ShellDestination.Setup -> R.string.nav_group_settings

        ShellDestination.Settings -> R.string.nav_group_settings
    }

@Composable
@Suppress("FunctionName")
private fun DrawerDestinationChevron(route: String) {
    if (route == "models") DrawerChevron()
}
