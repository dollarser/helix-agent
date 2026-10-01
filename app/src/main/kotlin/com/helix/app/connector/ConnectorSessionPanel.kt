package com.helix.app.connector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun statusLabel(row: PluginSessionRow): Int =
    when {
        !row.available -> R.string.connector_session_unavailable
        !row.enabled -> R.string.connector_inactive
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

/** UI sees only application-service projections; component repair remains in Extensions. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught") // failures are visible; cancellation propagates
fun ConnectorSessionPanel(
    service: PluginService,
    sessionId: String,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rows by remember(sessionId) { mutableStateOf<List<PluginSessionRow>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId, revision) {
        try {
            rows = withContext(Dispatchers.IO) { service.sessionRows(sessionId) }
            failed = false
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    val update: (PluginSessionRow, Boolean, Boolean) -> Unit = { row, enabled, default ->
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (default) {
                        service.catalog.setDefault(row.id, enabled)
                    } else {
                        service.catalog.select(sessionId, row.id, enabled)
                    }
                }
                failed = false
                revision++
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_session_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("connector-session-panel")) {
                Text(stringResource(R.string.connector_session_hint))
                if (rows.isEmpty()) Text(stringResource(R.string.connector_session_empty))
                rows.forEach { row ->
                    Row {
                        Checkbox(
                            checked = row.selected,
                            onCheckedChange = { update(row, it, false) },
                            enabled = !busy && (row.available || row.selected),
                            modifier = Modifier.testTag("connector-session-${row.id}"),
                        )
                        Column { PluginSessionState(row) }
                    }
                    if (row.available) {
                        Row {
                            Checkbox(
                                checked = row.defaultSelected,
                                onCheckedChange = { update(row, it, true) },
                                enabled = !busy,
                                modifier = Modifier.testTag("connector-default-${row.id}"),
                            )
                            Text(stringResource(R.string.connector_session_default))
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.connector_session_failed))
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
