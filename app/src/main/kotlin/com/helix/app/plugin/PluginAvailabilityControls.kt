package com.helix.app.plugin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual management calls an application service; no model Turn or direct DAO access. */
@Composable
@Suppress("FunctionName") // Compose UI entry point.
internal fun PluginAvailabilityControls(record: InstalledPlugin, service: PluginService, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(record.id) { mutableStateOf(false) }
    var failure by remember(record.id) { mutableStateOf(false) }
    var defaultSelected by remember(record) { mutableStateOf(false) }
    var readiness by remember(record) { mutableStateOf<PluginComponentReadiness?>(null) }
    LaunchedEffect(record) {
        try {
            defaultSelected = withContext(Dispatchers.IO) { service.catalog.defaultSelected(record.id) }
            readiness = withContext(Dispatchers.IO) { service.componentReadiness(record) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure = true
        }
    }
    readiness?.takeIf { record.native == null }?.let { state ->
        Text(stringResource(R.string.extensions_component_status, state.ready, state.total))
        if (state.hasSkippedComponents) Text(stringResource(R.string.plugin_skipped_components))
    }

    fun perform(action: () -> Unit) {
        scope.launch {
            busy = true
            failure = false
            try {
                withContext(Dispatchers.IO) { action() }
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = true
                onChanged()
            } finally {
                busy = false
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plugin_global_enabled), Modifier.weight(1f))
        Switch(
            checked = record.enabled,
            enabled = !busy,
            onCheckedChange = { value -> perform { service.setEnabled(record.id, value) } },
            modifier = Modifier.testTag("plugin-enabled-${record.id}"),
        )
    }
    if (record.native == null) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connector_session_default), Modifier.weight(1f))
            Switch(defaultSelected, { value ->
                perform {
                    service.catalog.setDefault(record.id, value)
                    defaultSelected = value
                }
            }, enabled = !busy, modifier = Modifier.testTag("connector-default-${record.id}"))
        }
    }
    if (record.native != null && record.enabled && readiness?.fullyReady == false) {
        TextButton(onClick = { perform { service.repairNative(record.id) } }, enabled = !busy) {
            Text(stringResource(R.string.plugin_native_repair))
        }
    }
    if (failure) Text(stringResource(R.string.connector_failed), color = MaterialTheme.colorScheme.error)
}
