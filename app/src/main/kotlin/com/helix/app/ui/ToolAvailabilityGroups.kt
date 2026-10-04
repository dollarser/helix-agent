package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.nav_expanded else R.string.nav_collapsed)
    OutlinedButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { stateDescription = state },
    ) { Text("${if (expanded) "▴" else "▾"} $title") }
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp)) { content() }
    }
}
