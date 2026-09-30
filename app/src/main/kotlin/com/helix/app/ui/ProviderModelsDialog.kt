package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.ProviderProvisioningKind
import com.helix.provider.api.ModelCatalogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** All provisioning types share preferences; authentication, downloads and execution stay in their own services. */
@Composable
// Dialog state and explicit user operations; no automatic network work.
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun ProviderModelsDialog(
    row: ProviderRowUi,
    service: ProviderService,
    onDismiss: () -> Unit,
) {
    val rows by service.rows.collectAsStateWithLifecycle()
    val live = rows.firstOrNull { it.id == row.id }
    val current = live ?: row
    var baseline by remember(row.id) { mutableStateOf(row.modelSelection) }
    var selection by remember(row.id) { mutableStateOf(row.modelSelection) }
    var query by remember(row.id) { mutableStateOf("") }
    var onlySelected by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var contextModel by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var catalogUnsupported by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun perform(action: suspend () -> Unit) {
        if (busy || live == null) return
        busy = true
        error = false
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
            }
        }
    }

    suspend fun save() {
        val value = selection.copy(configured = true)
        service.saveModelSelection(row.id, value, baseline)
        baseline = value
        selection = value
    }
    contextModel?.let { model ->
        ProviderContextDialog(current, service, initialModel = model) { contextModel = null }
    }
    val candidates =
        (selection.models + current.knownModels + selection.customModels).distinct().filter {
            (!onlySelected || it in selection.models) &&
                (it.contains(query, ignoreCase = true) || current.modelLabel(it).contains(query, ignoreCase = true))
        }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.provider_models_manage_title, current.displayName)) },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("provider-model-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(stringResource(R.string.provider_models_selection_help))
                    Text(stringResource(R.string.provider_model_probe_cost))
                    Text(stringResource(R.string.provider_models_selected_count, selection.models.size))
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.provider_models_search)) },
                        modifier = Modifier.fillMaxWidth().testTag("provider-model-search"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(onlySelected, { onlySelected = it })
                        Text(stringResource(R.string.provider_models_only_selected))
                    }
                    Row {
                        TextButton(
                            onClick = {
                                selection =
                                    selection.copy(models = emptyList(), configured = true)
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("provider-model-clear"),
                        ) {
                            Text(stringResource(R.string.provider_models_clear))
                        }
                        if (current.provisioning != ProviderProvisioningKind.ON_DEVICE_ASSET) {
                            TextButton(onClick = {
                                perform {
                                    when (service.refreshModelCatalog(row.id)) {
                                        is ModelCatalogResult.Listed -> catalogUnsupported = false
                                        is ModelCatalogResult.Failed -> error = true
                                        else -> catalogUnsupported = true
                                    }
                                }
                            }, enabled = !busy) { Text(stringResource(R.string.provider_models_refresh)) }
                        }
                    }
                    if (current.managedExternally ||
                        catalogUnsupported
                    ) {
                        Text(stringResource(R.string.provider_models_catalog_scope))
                    }
                    if (current.provisioning == ProviderProvisioningKind.ON_DEVICE_ASSET) {
                        Text(stringResource(R.string.provider_models_local_help))
                    }
                    if (selection.models.isEmpty()) Text(stringResource(R.string.provider_models_empty))
                    if (live == null || error) Text(stringResource(R.string.provider_models_operation_failed))
                    if (live?.modelSelection != baseline) {
                        Text(stringResource(R.string.provider_models_stale))
                        TextButton(
                            onClick = {
                                live?.let {
                                    baseline = it.modelSelection
                                    selection = it.modelSelection
                                }
                                error =
                                    false
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.provider_models_reload)) }
                    }
                }
                items(candidates, key = { it }) { model ->
                    ProviderModelChoiceRow(
                        current,
                        model,
                        selection,
                        busy,
                        expanded = expanded == model,
                        onToggle = { selection = selection.toggle(model, it) },
                        onExpand = { expanded = if (expanded == model) null else model },
                        onContext = { contextModel = model },
                        onTest = { perform { service.runConnectionTest(row.id, model, verifyGeneration = true) } },
                        onProbe = { perform { service.runCapabilityTest(row.id, model) } },
                        onMove = { selection = selection.moveFirst(model) },
                    )
                }
                if (current.provisioning != ProviderProvisioningKind.ON_DEVICE_ASSET) {
                    item {
                        Column {
                            OutlinedTextField(
                                manual,
                                { manual = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.provider_models_manual_id)) },
                                modifier = Modifier.fillMaxWidth().testTag("provider-model-manual"),
                            )
                            TextButton(onClick = {
                                try {
                                    selection = selection.addCustom(manual.trim())
                                    manual = ""
                                    error = false
                                } catch (
                                    _: IllegalArgumentException,
                                ) {
                                    error = true
                                }
                            }, enabled = !busy && manual.isNotBlank()) {
                                Text(
                                    stringResource(R.string.provider_models_add),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    perform {
                        save()
                        onDismiss()
                    }
                },
                enabled = !busy && live != null,
                modifier = Modifier.testTag("provider-models-save"),
            ) { Text(stringResource(R.string.provider_models_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.chat_details_close)) }
        },
    )
}
