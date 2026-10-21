package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** A fixed entry opens the same settings authorities without displacing conversation navigation. */
@Composable
@Suppress("FunctionName")
internal fun DrawerSettingsNavigation(
    currentRoute: String,
    enabled: Boolean,
    onNavigate: (String) -> Unit,
) {
    if (!enabled) return
    var open by rememberSaveable { mutableStateOf(false) }
    DrawerRow(
        label = { Text(stringResource(R.string.nav_group_settings)) },
        iconRes = R.drawable.ic_drawer_settings,
        badge = { DrawerChevron() },
        selected = settingsDrawerEntries.any { it.matches(currentRoute) },
        onClick = { open = true },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("navigation-group-settings"),
    )
    if (open) {
        ConversationSheet(stringResource(R.string.nav_group_settings), "drawer-settings", { open = false }) {
            Column {
                settingsDrawerEntries.forEach { entry ->
                    DrawerRow(
                        label = { DrawerEntryLabel(entry) },
                        iconRes = drawerIcon(entry.route),
                        selected = entry.matches(currentRoute),
                        onClick = {
                            open = false
                            onNavigate(entry.route)
                        },
                        modifier = Modifier.testTag("navigation-${entry.route}"),
                    )
                }
            }
        }
    }
}
