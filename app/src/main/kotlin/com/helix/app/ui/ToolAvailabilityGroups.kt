package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R

/** Expansion is presentation state only; tool changes remain with the caller's authorized service. */
@Composable
@Suppress("FunctionName")
internal fun <T> ToolAvailabilityGroups(
    tools: List<T>,
    name: (T) -> String,
    content: @Composable (T) -> Unit,
) {
    ToolAvailabilityDisclosure(stringResource(R.string.settings_perm_tools_label), "settings-perm-tools") {
        Text(stringResource(R.string.settings_perm_tools_note))
        tools.groupBy { name(it).substringBefore('.') }.toSortedMap().forEach { (group, entries) ->
            androidx.compose.runtime.key(group) {
                ToolAvailabilityDisclosure("$group (${entries.size})", "settings-perm-group-$group") {
                    entries.sortedBy(name).forEach { tool -> content(tool) }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ToolAvailabilityDisclosure(
    title: String,
    tag: String,
    content: @Composable () -> Unit,
) {
    SettingsDisclosure(title, tag, content = content)
}
