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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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

/** Keep primary device/browser capabilities first; stable sorting retains the other catalog order. */
internal fun pluginSelectionPriority(row: PluginSessionRow): Int =
    when (row.id) {
        "bundled-plugin:browser" -> 0
        "bundled-plugin:mobile-use" -> 1
        else -> 2
    }

internal fun selectionIssueLabel(row: PluginSessionRow): Int? {
    if (!row.selected) return null
    return when {
        !row.available -> R.string.connector_session_unavailable
        !row.enabled -> R.string.connector_inactive
        row.setupReason == PluginSetupReason.MOBILE_CONFIGURATION -> R.string.mobile_use_configuration_error
        row.setupReason == PluginSetupReason.MOBILE_BACKEND -> R.string.mobile_use_needs_access
        else -> null
    }
}

/** Routine readiness belongs in Details; only selected capabilities needing repair stay visible. */
@Composable
@Suppress("FunctionName")
private fun PluginSessionState(row: PluginSessionRow) {
    Text(
        com.helix.app.plugin
            .pluginLabel(row.name),
        style = MaterialTheme.typography.bodyLarge,
    )
    selectionIssueLabel(row)?.let {
        Text(
            stringResource(it),
            Modifier.testTag("plugin-session-issue-${row.id}"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun PluginSessionChoice(
    row: PluginSessionRow,
    busy: Boolean,
    onSelect: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    val selectable = !busy
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = row.selected, onCheckedChange = null, enabled = selectable)
                Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PluginSessionState(row)
                    if (row.expertProvided) {
                        Text(
                            stringResource(R.string.expert_plugin_provided),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (selectionIssueLabel(row) != null) {
                Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp)) {
                    TextButton(
                        onClick = onConfigure,
                        enabled = !busy,
                        modifier = Modifier.testTag("plugin-session-configure-${row.id}"),
                    ) {
                        Text(
                            stringResource(
                                when (row.setupReason) {
                                    PluginSetupReason.MOBILE_BACKEND -> {
                                        R.string.mobile_use_go_authorize
                                    }

                                    PluginSetupReason.MOBILE_CONFIGURATION -> {
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

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught") // failures are visible; cancellation propagates
fun ConnectorSessionPanel(
    service: PluginService,
    sessionId: String,
    onConfigure: (String, PluginSetupReason?) -> Unit,
    pluginId: String? = null,
    canApply: () -> Boolean = { true },
    onDismiss: () -> Unit,
) {
    val createState = { ConnectorSessionPanelState(service, sessionId, pluginId, canApply) }
    val state =
        androidx.compose.runtime.saveable.rememberSaveable(
            sessionId,
            pluginId,
            saver = ConnectorSessionPanelState.saver(createState),
        ) { createState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.refresh(afterConfiguration = state.pendingRepair != null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (state.loaded) scope.launch { state.refresh(afterConfiguration = state.pendingRepair != null) }
    }
    PluginSessionPicker(state, onConfigure, onDismiss)
}

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun PluginSessionPicker(
    state: ConnectorSessionPanelState,
    onConfigure: (String, PluginSetupReason?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismiss = {
        state.cancelRepair()
        onDismiss()
    }
    if (!state.canPresent) return
    PluginSessionRepairPrompt(state, onConfigure)
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
                TextButton(enabled = !state.busy, onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    enabled = state.editable,
                    onClick = { scope.launch { if (state.apply()) dismiss() } },
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
            Text(stringResource(R.string.plugin_session_scope_hint), style = MaterialTheme.typography.bodySmall)
            if (state.empty) Text(stringResource(R.string.connector_session_empty))
            state.rows.sortedBy(::pluginSelectionPriority).forEach { row ->
                key(row.id) {
                    PluginSessionChoice(
                        row,
                        state.busy || state.loading || state.failed,
                        { scope.launch { state.select(row, it) } },
                    ) {
                        onConfigure(row.id, row.setupReason)
                    }
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
                    R.string.mobile_use_configuration_error
                } else {
                    R.string.connector_session_failed
                },
            ),
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun PluginSessionRepairPrompt(
    state: ConnectorSessionPanelState,
    onConfigure: (String, PluginSetupReason?) -> Unit,
) {
    state.setupRow?.let { row ->
        com.helix.app.plugin.PluginSetupRequiredDialog(
            state.setupReason ?: row.setupReason
                ?: if (!row.enabled) PluginSetupReason.DISABLED else PluginSetupReason.CONTENT,
            onContinue = {
                state.beginRepair()?.let { onConfigure(it.id, it.setupReason) }
            },
            onDismiss = state::dismissSetup,
        )
    }
}
