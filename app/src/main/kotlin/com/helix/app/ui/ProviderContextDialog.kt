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
import com.helix.app.provider.ProviderContextSettings
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ProviderContextDialog(
    row: ProviderRowUi,
    service: ProviderService,
    onDismiss: () -> Unit,
) {
    var model by remember(row.id) { mutableStateOf(row.model) }
    var modelMenu by remember { mutableStateOf(false) }
    var settings by remember(row.id, model) { mutableStateOf(ProviderContextSettings()) }
    var window by remember(row.id, model) { mutableStateOf("200000") }
    var ratio by remember(row.id, model) { mutableStateOf("80") }
    var automaticWindow by remember(row.id, model) { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(row.id, model) {
        loading = true
        val stored = service.contextSettings(row.id, model)
        settings = stored
        automaticWindow = stored.manualWindow == null
        window = (stored.manualWindow ?: stored.window).toString()
        ratio = stored.triggerPercent.toString()
        try {
            settings = service.discoverContextWindow(row.id, model)
            if (automaticWindow) window = settings.window.toString()
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
                    TextButton({ modelMenu = true }, Modifier.testTag("provider-context-model")) { Text("$model ▾") }
                    DropdownMenu(modelMenu, { modelMenu = false }) {
                        (listOf(row.model) + row.backendModels.orEmpty()).distinct().forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate) },
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
                    stringResource(
                        R.string.context_server_window,
                        settings.serverWindow?.toString() ?: stringResource(R.string.chat_context_unknown),
                    ),
                )
                Row {
                    Checkbox(
                        automaticWindow,
                        { automaticWindow = it },
                        enabled = !loading,
                        modifier = Modifier.testTag("provider-auto-window"),
                    )
                    Text(stringResource(R.string.context_auto_window))
                }
                OutlinedTextField(
                    window,
                    { window = it },
                    enabled = !automaticWindow && !loading,
                    label = { Text(stringResource(R.string.context_window_tokens)) },
                    modifier = Modifier.testTag("provider-context-window"),
                    singleLine = true,
                )
                Row {
                    Checkbox(
                        settings.autoCompact,
                        { settings = settings.copy(autoCompact = it) },
                        enabled = !loading,
                        modifier = Modifier.testTag("provider-auto-compact"),
                    )
                    Text(stringResource(R.string.context_auto_compact))
                }
                OutlinedTextField(
                    ratio,
                    { ratio = it },
                    enabled = !loading,
                    label = { Text(stringResource(R.string.context_trigger_percent)) },
                    modifier = Modifier.testTag("provider-context-threshold"),
                    singleLine = true,
                )
                Text(stringResource(R.string.context_settings_help))
            }
        },
        confirmButton = {
            TextButton(
                {
                    scope.launch {
                        service.saveContextSettings(
                            row.id,
                            model,
                            settings.copy(
                                manualWindow = if (automaticWindow) null else parsedWindow,
                                triggerPercent = requireNotNull(parsedRatio),
                            ),
                        )
                        onDismiss()
                    }
                },
                enabled =
                    !loading && parsedRatio in 10..95 &&
                        (
                            automaticWindow ||
                                parsedWindow in ProviderContextSettings.MIN_WINDOW..ProviderContextSettings.MAX_WINDOW
                        ),
                modifier = Modifier.testTag("provider-context-save"),
            ) { Text(stringResource(R.string.context_save)) }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("provider-context-close")) {
                Text(stringResource(R.string.chat_context_close))
            }
        },
    )
}
