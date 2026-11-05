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
        PluginComponentState.AVAILABLE -> R.string.plugin_component_ready
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

/** Read-only component facts belong to the plugin Details tab. */
@Composable
@Suppress("FunctionName")
internal fun PluginReadinessDetails(
    state: PluginReadinessSnapshot,
    packageHash: String? = null,
) {
    state.components.forEach { PluginComponentStatus(it, state) }
    PluginTechnicalDetails(state, packageHash)
}

@Composable
@Suppress("FunctionName")
private fun PluginTechnicalDetails(
    state: PluginReadinessSnapshot,
    packageHash: String?,
) {
    var expanded by remember(state.pluginId, state.revision) { mutableStateOf(false) }
    TextButton({ expanded = !expanded }, Modifier.testTag("plugin-technical-${state.pluginId}")) {
        Text(stringResource(R.string.plugin_technical_details))
    }
    if (!expanded) return
    packageHash?.let {
        Text(stringResource(R.string.plugin_package_hash, it), style = MaterialTheme.typography.bodySmall)
    }
    state.components.forEach { PluginComponentDiagnostic(it) }
}

@Composable
@Suppress("FunctionName")
private fun PluginComponentStatus(
    component: PluginComponentFact,
    state: PluginReadinessSnapshot,
) {
    val name =
        component.displayName?.takeIf(String::isNotBlank)
            ?: stringResource(componentKindLabel(component.kind))
    val status = if (state.enabled) component.state else PluginComponentState.DISABLED
    Text("$name · ${stringResource(componentStateLabel(status))}")
    if (component.requestState(state.selected, state.enabled) == PluginComponentState.REQUEST_UNAVAILABLE) {
        Text(stringResource(R.string.plugin_state_request_unavailable))
    }
    if (component.kind == com.helix.extensions.plugin.PluginComponentKind.MCP) {
        component.target?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
@Suppress("FunctionName")
private fun PluginComponentDiagnostic(component: PluginComponentFact) {
    Text(
        stringResource(R.string.plugin_component_identifier, component.ownerId),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(
            R.string.plugin_saved_loaded,
            stringResource(if (component.saved) R.string.plugin_fact_yes else R.string.plugin_fact_no),
            stringResource(if (component.loaded) R.string.plugin_fact_yes else R.string.plugin_fact_no),
        ),
    )
    if (component.kind != com.helix.extensions.plugin.PluginComponentKind.MCP) {
        component.target?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun componentKindLabel(kind: com.helix.extensions.plugin.PluginComponentKind): Int =
    when (kind) {
        com.helix.extensions.plugin.PluginComponentKind.SKILL -> R.string.plugin_component_skill
        com.helix.extensions.plugin.PluginComponentKind.MCP -> R.string.plugin_component_connection
        com.helix.extensions.plugin.PluginComponentKind.NATIVE -> R.string.plugin_component_native
        com.helix.extensions.plugin.PluginComponentKind.BROWSER -> R.string.plugin_component_browser
        else -> R.string.plugin_component_other
    }
