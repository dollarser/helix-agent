package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName")
internal fun ProviderContextDialog(
    row: ProviderRowUi,
    service: ProviderService,
    initialModel: String = row.model,
    onDismiss: () -> Unit,
) = ProviderContextEditor(
    row,
    load = { service.contextSettings(row.id, it) },
    discover = { service.discoverContextWindow(row.id, it) },
    save = { model, settings -> service.saveContextSettings(row.id, model, settings) },
    initialModel = initialModel,
    onDismiss = onDismiss,
)

@Composable
// Failed storage/discovery stays repairable, using closed UI errors.
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "SwallowedException", "TooGenericExceptionCaught")
internal fun ProviderContextEditor(
    row: ProviderRowUi,
    load: suspend (String) -> ProviderContextSettings,
    discover: suspend (String) -> ProviderContextSettings,
    save: suspend (String, ProviderContextSettings) -> Unit,
    onDismiss: () -> Unit,
    initialModel: String = row.model,
) {
    var model by remember(row.id, initialModel) { mutableStateOf(initialModel) }
    var modelMenu by remember { mutableStateOf(false) }
    var settings by remember(row.id, model) { mutableStateOf(ProviderContextSettings()) }
    var window by remember(row.id, model) { mutableStateOf(ProviderContextSettings.DEFAULT_WINDOW.toString()) }
    var ratio by remember(row.id, model) { mutableStateOf("80") }
    var automaticWindow by remember(row.id, model) { mutableStateOf(true) }
    var loading by remember(row.id, model) { mutableStateOf(true) }
    var loaded by remember(row.id, model) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember(row.id, model) { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var discoveryFailed by remember { mutableStateOf(false) }
    val maximumWindow =
        if (row.provisioning == com.helix.core.model.ProviderProvisioningKind.ON_DEVICE_ASSET) {
            32768L
        } else {
            ProviderContextSettings.MAX_WINDOW
        }
    val scope = rememberCoroutineScope()
    LaunchedEffect(row.id, model, retry) {
        loading = true
        discoveryFailed = false
        try {
            if (!loaded) {
                val stored = load(model)
                settings = stored
                automaticWindow = stored.manualWindow == null
                window = (stored.manualWindow ?: stored.window).toString()
                ratio = stored.triggerPercent.toString()
                loaded = true
            }
            settings = settings.copy(serverWindow = discover(model).serverWindow)
            if (automaticWindow) window = settings.window.toString()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            // Keep server bodies and credentials out of presentation errors.
            discoveryFailed = true
        } finally {
            loading = false
        }
    }
    val parsedWindow = window.toLongOrNull()
    val parsedRatio = ratio.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_context_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box {
                    TextButton(
                        { modelMenu = true },
                        Modifier.testTag("provider-context-model"),
                        enabled = !saving,
                    ) { Text("${row.modelLabel(model)} ▾") }
                    DropdownMenu(modelMenu, { modelMenu = false }) {
                        (row.knownModels + model).distinct().forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(row.modelLabel(candidate)) },
                                modifier = Modifier.testTag("provider-context-choice-$candidate"),
                                onClick = {
                                    model = candidate
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
                Text(
                    settings.serverWindow?.let { stringResource(R.string.context_server_window, it.toString()) }
                        ?: stringResource(R.string.context_server_window_unreported),
                )
                if (automaticWindow && settings.serverWindow == null) {
                    Text(stringResource(R.string.context_window_fallback, settings.window))
                }
                Row {
                    Checkbox(
                        automaticWindow,
                        { automaticWindow = it },
                        enabled = !loading && loaded && !saving,
                        modifier = Modifier.testTag("provider-auto-window"),
                    )
                    Text(stringResource(R.string.context_auto_window))
                }
                OutlinedTextField(
                    window,
                    { window = it },
                    enabled = !automaticWindow && !loading && loaded && !saving,
                    label = { Text(stringResource(R.string.context_window_tokens)) },
                    modifier = Modifier.testTag("provider-context-window"),
                    singleLine = true,
                )
                Row {
                    Checkbox(
                        settings.autoCompact,
                        { settings = settings.copy(autoCompact = it) },
                        enabled = !loading && loaded && !saving,
                        modifier = Modifier.testTag("provider-auto-compact"),
                    )
                    Text(stringResource(R.string.context_auto_compact))
                }
                OutlinedTextField(
                    ratio,
                    { ratio = it },
                    enabled = !loading && loaded && !saving,
                    label = { Text(stringResource(R.string.context_trigger_percent)) },
                    modifier = Modifier.testTag("provider-context-threshold"),
                    singleLine = true,
                )
                Text(stringResource(R.string.context_settings_help))
                if (discoveryFailed) {
                    Text(
                        stringResource(R.string.provider_context_load_failed),
                        Modifier.testTag("provider-context-load-error"),
                    )
                    TextButton(
                        { retry++ },
                        modifier = Modifier.testTag("provider-context-retry"),
                        enabled = !loading && !saving,
                    ) {
                        Text(stringResource(R.string.chat_retry))
                    }
                }
                if (saveFailed) {
                    Text(
                        stringResource(R.string.provider_save_failed),
                        Modifier.testTag("provider-context-save-error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    if (saving) return@TextButton
                    val targetModel = model
                    val targetSettings =
                        settings.copy(
                            manualWindow = if (automaticWindow) null else parsedWindow,
                            triggerPercent = requireNotNull(parsedRatio),
                        )
                    saving = true
                    saveFailed = false
                    scope.launch {
                        try {
                            save(targetModel, targetSettings)
                            onDismiss()
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled =
                    !loading && loaded && !saving && parsedRatio in 10..95 &&
                        (
                            automaticWindow ||
                                parsedWindow in ProviderContextSettings.MIN_WINDOW..maximumWindow
                        ),
                modifier = Modifier.testTag("provider-context-save"),
            ) { Text(stringResource(if (saving) R.string.provider_save_saving else R.string.context_save)) }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("provider-context-close")) {
                Text(stringResource(R.string.chat_context_close))
            }
        },
    )
}
