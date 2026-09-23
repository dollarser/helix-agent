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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ShellDestination

/** Group presentation only; each existing route still uses the shell's navigation callback. */
@Composable
@Suppress("FunctionName")
internal fun GroupedNavigation(
    destinations: List<ShellDestination>,
    currentRoute: String,
    onNavigate: (ShellDestination) -> Unit,
) {
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).testTag("navigation-groups")) {
        Text("Helix", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
        destinations.groupBy { it.navigationGroup() }.forEach { (group, entries) ->
            Text(
                stringResource(group),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
            entries.forEach { destination ->
                NavigationDrawerItem(
                    label = { Text(stringResource(destination.titleRes)) },
                    selected = destination.route == currentRoute,
                    onClick = { onNavigate(destination) },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("navigation-${destination.route}"),
                )
            }
        }
    }
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
