package com.helix.app.connector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.ui.indicatedVerticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun statusLabel(row: PluginSessionRow): Int =
    when {
        !row.available -> R.string.connector_session_unavailable
        !row.enabled -> R.string.connector_inactive
        row.selectionError == "MOBILE_USE_NOT_CONFIGURED" -> R.string.mobile_use_configuration_required
        row.selectionError == "MOBILE_USE_BACKEND_UNAVAILABLE" -> R.string.mobile_use_needs_access
        row.selectionError != null -> R.string.connector_session_setup
        !row.ready -> R.string.connector_session_setup
        else -> R.string.connector_session_ready
    }

/** Display facts only: partial readiness never changes session selection or authorization. */
@Composable
@Suppress("FunctionName")
private fun PluginSessionState(row: PluginSessionRow) {
    Text(row.name)
    if (row.hasSkippedComponents) {
        Text(stringResource(R.string.plugin_skipped_components))
    } else if (row.readyComponents > 0 && !row.ready) {
        Text(stringResource(R.string.plugin_partial_ready, row.readyComponents, row.totalComponents))
    }
    Text(stringResource(statusLabel(row)))
}

@Composable
@Suppress("FunctionName")
internal fun PluginSessionChoice(
    row: PluginSessionRow,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    Row {
        Checkbox(
            checked = row.selected,
            onCheckedChange = { selected ->
                if (selected && row.selectionError != null) onConfigure() else onSelect(selected)
            },
            enabled = !busy && (row.selected || row.available),
            modifier = Modifier.testTag("connector-session-${row.id}"),
        )
        Column {
            PluginSessionState(row)
            if (row.selectionError != null) {
                TextButton(
                    onClick = onConfigure,
                    enabled = !busy,
                    modifier = Modifier.testTag("plugin-session-configure-${row.id}"),
                ) {
                    Text(
                        stringResource(
                            when (row.selectionError) {
                                "MOBILE_USE_BACKEND_UNAVAILABLE" -> R.string.phone_task_permissions
                                "MOBILE_USE_NOT_CONFIGURED" -> R.string.phone_task_configure
                                else -> R.string.connector_session_configure
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** UI sees only application-service projections; component repair remains in Extensions. */
private fun openPluginConfiguration(
    row: PluginSessionRow,
    error: String?,
    onConfigure: () -> Unit,
    onMobileUseConfiguration: () -> Unit,
    onSystemPermissions: () -> Unit,
) {
    when {
        error == "MOBILE_USE_BACKEND_UNAVAILABLE" -> onSystemPermissions()
        row.id == "bundled-plugin:mobile-use" -> onMobileUseConfiguration()
        else -> onConfigure()
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught") // failures are visible; cancellation propagates
fun ConnectorSessionPanel(
    service: PluginService,
    sessionId: String,
    onConfigure: () -> Unit,
    pluginId: String? = null,
    onMobileUseConfiguration: () -> Unit = onConfigure,
    onSystemPermissions: () -> Unit = onConfigure,
    onDismiss: () -> Unit,
) {
    val state = remember(service, sessionId, pluginId) { ConnectorSessionPanelState(service, sessionId, pluginId) }
    val scope = rememberCoroutineScope()
    var setupRequest by remember(state) { mutableStateOf<Pair<PluginSessionRow, String>?>(null) }
    LaunchedEffect(state) { state.refresh() }
    val configure: (PluginSessionRow, String?) -> Unit = { row, error ->
        if (error != null) {
            setupRequest = row to error
        } else {
            onDismiss()
            openPluginConfiguration(row, error, onConfigure, onMobileUseConfiguration, onSystemPermissions)
        }
    }
    val update: (PluginSessionRow, Boolean) -> Unit = { row, enabled ->
        scope.launch {
            state.select(row, enabled)
            state.selectionError?.let { configure(row, it) }
        }
    }
    val request = setupRequest
    if (request != null) {
        com.helix.app.plugin.PluginSetupRequiredDialog(
            request.second,
            onContinue = {
                setupRequest = null
                onDismiss()
                openPluginConfiguration(
                    request.first,
                    request.second,
                    onConfigure,
                    onMobileUseConfiguration,
                    onSystemPermissions,
                )
            },
            onDismiss = { setupRequest = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_session_title)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState()).testTag("connector-session-panel")) {
                if (state.title.isNotBlank()) Text(stringResource(R.string.plugin_session_name, state.title))
                Text(stringResource(R.string.connector_session_hint))
                if (state.loading) {
                    Text(
                        stringResource(R.string.tasks_commands_loading),
                        Modifier.testTag("plugin-session-loading"),
                    )
                }
                if (state.empty) {
                    Text(stringResource(R.string.connector_session_empty))
                }
                if (state.updated &&
                    !state.failed
                ) {
                    Text(
                        stringResource(R.string.plugin_session_updated),
                        Modifier.testTag("plugin-session-updated"),
                    )
                }
                state.rows.forEach { row ->
                    PluginSessionChoice(row, state.busy || state.loading, { update(row, it) }) {
                        configure(row, row.selectionError)
                    }
                }
                state.skills.forEach { skill -> SessionSkillChoice(service, sessionId, skill) }
                SessionSelectionFailure(state) { scope.launch { state.refresh() } }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onConfigure()
            }) {
                Text(stringResource(R.string.connector_session_configure))
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun SessionSelectionFailure(
    state: ConnectorSessionPanelState,
    onRetry: () -> Unit,
) {
    if (state.failed) {
        TextButton(onRetry, enabled = !state.busy && !state.loading) { Text(stringResource(R.string.chat_retry)) }
        Text(
            stringResource(
                if (state.needsConfiguration) {
                    R.string.mobile_use_configuration_required
                } else {
                    R.string.connector_session_failed
                },
            ),
        )
    }
}

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
private fun SessionSkillChoice(
    service: PluginService,
    sessionId: String,
    skill: com.helix.extensions.skills.SkillListItem,
) {
    var selected by remember(skill) { mutableStateOf(skill.enabled) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row {
        Checkbox(checked = selected, enabled = !busy, onCheckedChange = { value ->
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { service.selectSkill(skill.key, sessionId, value) }
                    selected = value
                    failed = false
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        })
        Column {
            Text(skill.key.name)
            Text(skill.description)
            if (failed) Text(stringResource(R.string.connector_session_failed))
        }
    }
}
