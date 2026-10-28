package com.helix.app.connector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import com.helix.app.ui.ConversationSheet
import kotlinx.coroutines.launch

internal fun statusLabel(row: PluginSessionRow): Int =
    when {
        !row.available -> R.string.connector_session_unavailable
        !row.enabled -> R.string.connector_inactive
        row.setupReason == PluginSetupReason.MOBILE_SCOPE -> R.string.mobile_use_configuration_required
        row.setupReason == PluginSetupReason.MOBILE_BACKEND -> R.string.mobile_use_needs_access
        row.setupReason != null -> R.string.connector_session_setup
        !row.ready -> R.string.connector_session_setup
        else -> R.string.connector_session_ready
    }

/** Display facts only: partial readiness never changes session selection or authorization. */
@Composable
@Suppress("FunctionName")
private fun PluginSessionState(row: PluginSessionRow) {
    Text(
        if (row.name == "documents") stringResource(R.string.document_plugin_name) else row.name,
        style = MaterialTheme.typography.bodyLarge,
    )
    if (row.hasSkippedComponents) {
        Text(stringResource(R.string.plugin_skipped_components))
    } else if (row.readyComponents > 0 && !row.ready) {
        Text(stringResource(R.string.plugin_partial_ready, row.readyComponents, row.totalComponents))
    }
    Text(
        stringResource(statusLabel(row)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
@Suppress("FunctionName")
internal fun PluginSessionChoice(
    row: PluginSessionRow,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    val selectable = !busy && (row.selected || (row.available && row.enabled && row.readyComponents > 0))
    val background =
        if (row.selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }
    Surface(color = background, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(row.selected, enabled = selectable, role = Role.Checkbox, onValueChange = onSelect)
                    .testTag("connector-session-${row.id}")
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Checkbox(checked = row.selected, onCheckedChange = null, enabled = selectable)
                Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PluginSessionState(row)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, bottom = 8.dp)) {
                row.readiness?.let {
                    com.helix.app.plugin
                        .PluginReadinessDetails(it, compact = true)
                }
                if (row.setupReason != null || !row.enabled) {
                    TextButton(
                        onClick = onConfigure,
                        enabled = !busy,
                        modifier = Modifier.testTag("plugin-session-configure-${row.id}"),
                    ) {
                        Text(
                            stringResource(
                                when (row.setupReason) {
                                    PluginSetupReason.MOBILE_BACKEND, PluginSetupReason.MOBILE_SCOPE -> {
                                        R.string.phone_task_configure
                                    }

                                    else -> {
                                        R.string.connector_session_configure
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** UI sees only application-service projections; component repair remains in Extensions. */
private fun openPluginConfiguration(
    row: PluginSessionRow,
    error: PluginSetupReason?,
    onConfigure: () -> Unit,
    onMobileUseConfiguration: () -> Unit,
) {
    when {
        row.id == MOBILE_USE_PLUGIN_ID || error == PluginSetupReason.MOBILE_BACKEND ||
            error == PluginSetupReason.MOBILE_SCOPE -> onMobileUseConfiguration()

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
    canApply: () -> Boolean = { true },
    onDismiss: () -> Unit,
) {
    val state =
        remember(service, sessionId, pluginId) { ConnectorSessionPanelState(service, sessionId, pluginId, canApply) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.refresh() }
    ConversationSheet(
        title = stringResource(R.string.connector_session_title),
        tag = "connector-session",
        onDismiss = { if (!state.busy) onDismiss() },
        avoidIme = true,
        footer = {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(enabled = !state.busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    enabled = state.editable,
                    onClick = { scope.launch { if (state.apply()) onDismiss() } },
                    modifier = Modifier.testTag("plugin-session-apply"),
                ) { Text(stringResource(R.string.plugin_selection_apply)) }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("connector-session-panel"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.title.isNotBlank()) Text(stringResource(R.string.plugin_session_name, state.title))
            Text(
                stringResource(R.string.connector_session_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.loading) {
                Text(stringResource(R.string.tasks_commands_loading), Modifier.testTag("plugin-session-loading"))
            }
            if (state.empty) Text(stringResource(R.string.connector_session_empty))
            state.rows.forEach { row ->
                PluginSessionChoice(row, state.busy || state.loading || state.failed, { state.select(row, it) }) {
                    if (row.id == MOBILE_USE_PLUGIN_ID ||
                        row.setupReason in setOf(PluginSetupReason.MOBILE_SCOPE, PluginSetupReason.MOBILE_BACKEND)
                    ) {
                        onDismiss()
                    }
                    openPluginConfiguration(row, row.setupReason, onConfigure, onMobileUseConfiguration)
                }
            }
            SessionSelectionFailure(state) { scope.launch { state.refresh() } }
        }
    }
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
                if (!state.loaded) {
                    R.string.plugin_selection_load_failed
                } else if (state.needsConfiguration) {
                    R.string.mobile_use_configuration_required
                } else {
                    R.string.connector_session_failed
                },
            ),
        )
    }
}
