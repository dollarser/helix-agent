package com.helix.app.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.plugin.PluginComponentState

internal fun componentStateLabel(state: PluginComponentState): Int =
    when (state) {
        PluginComponentState.AVAILABLE -> R.string.connector_session_ready
        PluginComponentState.NEEDS_CONFIGURATION -> R.string.connector_session_setup
        PluginComponentState.NOT_CONNECTED -> R.string.plugin_state_disconnected
        PluginComponentState.HOST_UNAVAILABLE -> R.string.plugin_state_host_missing
        PluginComponentState.NOT_SELECTED -> R.string.plugin_state_not_selected
        PluginComponentState.DISABLED -> R.string.connector_inactive
        PluginComponentState.UNSUPPORTED -> R.string.plugin_state_unsupported
        PluginComponentState.METADATA_ONLY -> R.string.plugin_state_metadata
        PluginComponentState.REQUEST_UNAVAILABLE -> R.string.plugin_state_request_unavailable
        PluginComponentState.RELOAD_REQUIRED -> R.string.plugin_state_reload
    }

/** Only renders service facts; opening details never repairs or reconnects a component. */
@Composable
@Suppress("FunctionName")
internal fun PluginReadinessDetails(state: PluginReadinessSnapshot) {
    var expanded by remember(state.pluginId) { mutableStateOf(false) }
    Text(stringResource(R.string.plugin_effect_next_request), style = MaterialTheme.typography.bodySmall)
    TextButton({ expanded = !expanded }, Modifier.testTag("plugin-readiness-${state.pluginId}")) {
        Text(stringResource(if (expanded) R.string.tool_details_hide else R.string.tool_details_show))
    }
    if (expanded) {
        Text(stringResource(R.string.plugin_revision, state.revision))
        state.components.forEach { component ->
            Text("${component.kind.name}: ${stringResource(componentStateLabel(component.state))}")
            if (component.requestState(state.selected, state.enabled) == PluginComponentState.REQUEST_UNAVAILABLE) {
                Text(stringResource(R.string.plugin_state_request_unavailable))
            }
            Text(
                stringResource(
                    R.string.plugin_saved_loaded,
                    stringResource(if (component.saved) R.string.plugin_fact_yes else R.string.plugin_fact_no),
                    stringResource(if (component.loaded) R.string.plugin_fact_yes else R.string.plugin_fact_no),
                ),
            )
            Text(component.ownerId, style = MaterialTheme.typography.bodySmall)
            component.target?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            component.accountReference?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
