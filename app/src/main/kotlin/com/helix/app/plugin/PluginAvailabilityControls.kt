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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.plugin.PluginSetupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual management calls an application service; no model Turn or direct DAO access. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught") // UI boundary reports failure; cancellation propagates.
internal fun PluginAvailabilityControls(
    record: InstalledPlugin,
    service: PluginService,
    onChanged: () -> Unit,
    onConfigurationRequired: (PluginSetupReason) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var busy by remember(record.id) { mutableStateOf(false) }
    var failure by remember(record.id) { mutableStateOf(false) }
    var failureMessage by remember(record.id) { mutableIntStateOf(R.string.plugin_settings_read_failed) }
    var setupError by remember(record.id) { mutableStateOf<PluginSetupReason?>(null) }
    var defaultSelected by remember(record) { mutableStateOf(false) }
    var readiness by remember(record) { mutableStateOf<PluginReadinessSnapshot?>(null) }
    LaunchedEffect(record) {
        try {
            defaultSelected = withContext(Dispatchers.IO) { service.catalog.defaultSelected(record.id) }
            readiness = withContext(Dispatchers.IO) { service.componentReadiness(record) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failureMessage =
                if (com.helix.app.automation.AutomationModule
                        .isConfigurationResetRequired(error)
                ) {
                    R.string.old_state_reopen
                } else {
                    R.string.plugin_settings_read_failed
                }
            com.helix.app.diagnostics.OperationFailureLog.record(
                com.helix.app.diagnostics.OperationFailureLog.Stage.PLUGIN_READINESS,
                error,
            )
            failure = true
        }
    }
    PluginReadinessSummary(record, readiness)

    fun perform(action: () -> Unit) {
        scope.launch {
            busy = true
            failure = false
            failureMessage = R.string.mobile_use_settings_failed
            try {
                withContext(Dispatchers.IO) { action() }
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PluginSelectionUnavailable) {
                setupError = error.reason
                failure = setupError == null
                onChanged()
            } catch (_: Exception) {
                failure = true
                onChanged()
            } finally {
                busy = false
            }
        }
    }
    if (record.sessionScoped) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connector_session_default), Modifier.weight(1f))
            Switch(defaultSelected, { value ->
                perform {
                    service.setDefaultFromUser(record.id, value)
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
    if (failure) Text(stringResource(failureMessage), color = MaterialTheme.colorScheme.error)
    setupError?.let { error ->
        PluginSetupRequiredDialog(
            error,
            onContinue = {
                setupError = null
                onConfigurationRequired(error)
            },
            onDismiss = { setupError = null },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun PluginReadinessSummary(
    record: InstalledPlugin,
    readiness: PluginReadinessSnapshot?,
) {
    readiness?.takeIf { record.native == null }?.let { state ->
        Text(stringResource(R.string.extensions_component_status, state.ready, state.components.size))
        if (state.hasSkippedComponents) Text(stringResource(R.string.plugin_skipped_components))
    }
    readiness?.let { PluginReadinessDetails(it) }
}
