package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ShellDestination

/** Two-level hierarchical navigation drawer: level 1 groups expand to level 2 destinations. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun GroupedNavigation(
    destinations: List<ShellDestination>,
    currentRoute: String,
    onNavigate: (ShellDestination) -> Unit,
) {
    val initialExpanded =
        remember(destinations, currentRoute) {
            destinations
                .groupBy { it.navigationGroup() }
                .filter { (_, entries) -> entries.any { it.route == currentRoute } }
                .keys
        }
    var expandedGroups by remember(currentRoute) { mutableStateOf(initialExpanded) }

    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).testTag("navigation-groups")) {
        Text("Helix", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
        destinations.groupBy { it.navigationGroup() }.forEach { (group, entries) ->
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
}

internal fun navigationGroupTag(groupResId: Int): String =
    when (groupResId) {
        R.string.nav_group_conversations -> "navigation-group-conversations"
        R.string.nav_group_work -> "navigation-group-work"
        R.string.nav_group_extensions -> "navigation-group-extensions"
        R.string.nav_group_settings -> "navigation-group-settings"
        else -> "navigation-group-$groupResId"
    }

private fun ShellDestination.navigationGroup(): Int =
    when (this) {
        ShellDestination.Sessions -> R.string.nav_group_conversations

        ShellDestination.Tasks, ShellDestination.Artifacts, ShellDestination.Git,
        ShellDestination.Files, ShellDestination.Browser, ShellDestination.Terminal,
        -> R.string.nav_group_work

        ShellDestination.Extensions -> R.string.nav_group_extensions

        ShellDestination.Capabilities, ShellDestination.Readiness, ShellDestination.Permissions,
        ShellDestination.Settings, ShellDestination.Audit,
        -> R.string.nav_group_settings
    }
