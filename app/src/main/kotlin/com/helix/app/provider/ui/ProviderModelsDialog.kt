package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.app.provider.sameProviderEditableConfig
import com.helix.core.model.ProviderProvisioningKind
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProbeOutcome
import com.helix.ui.shared.IndicatedLazyColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.helix.ui.shared.R as SharedR

/** API and subscription model editing share the same draft, list, actions and explicit save contract. */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun ProviderModelsDialog(
    row: ProviderRowUi,
    service: ProviderService,
    onDismiss: () -> Unit,
) {
    require(!row.bundled) { "Built-in models are selected directly in the conversation" }
    val rows by service.rows.collectAsStateWithLifecycle()
    val live = rows.firstOrNull { it.id == row.id }
    val current = live ?: row
    val draft = remember(row.id) { ProviderModelsEditorState(row.modelSelection) }
    val config = remember(row.id) { row.configuration }
    val connectionReady = live != null && sameProviderEditableConfig(live.configuration, config)
    var contextModel by remember(row.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discovery by remember(row.id) { mutableStateOf(ProviderFormDiscovery()) }
    val scope = rememberCoroutineScope()

    fun <T> perform(action: suspend () -> T) {
        if (busy || !connectionReady) return
        val account = current.accountState

        fun showFailure() {
            val latest = service.rows.value.firstOrNull { it.id == row.id }
            if (latest != null && latest.accountState == account &&
                sameProviderEditableConfig(latest.configuration, config)
            ) {
                draft.error = true
            }
        }
        busy = true
        draft.error = false
        scope.launch {
            try {
                if (action() is ProbeOutcome.Failed) showFailure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure()
            } finally {
                busy = false
                discovery = discovery.copy(running = false)
            }
        }
    }
    contextModel?.let { model ->
        if (connectionReady) {
            ProviderContextDialog(current, service, initialModel = model, independentSave = true, fixedModel = true) {
                contextModel = null
            }
        }
    }
    ProviderEditorDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.provider_models_manage_title, current.displayName)) },
        text = {
            IndicatedLazyColumn(
                Modifier.fillMaxWidth().fillMaxHeight().testTag("provider-model-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                providerFormModels(
                    draft = draft,
                    row = current,
                    discovery = discovery,
                    busy = busy || !connectionReady,
                    connectionReady = connectionReady,
                    inputModifier = Modifier,
                    onDiscover = {
                        perform {
                            discovery = ProviderFormDiscovery(running = true)
                            discovery =
                                when (val result = service.refreshModelCatalog(row.id, expectedConfig = config)) {
                                    is ModelCatalogResult.Listed -> {
                                        ProviderFormDiscovery(
                                            result.models,
                                            R.string.provider_discovery_hint,
                                        )
                                    }

                                    is ModelCatalogResult.Failed -> {
                                        ProviderFormDiscovery(
                                            message = R.string.provider_discovery_failed,
                                        )
                                    }

                                    ModelCatalogResult.Unsupported -> {
                                        ProviderFormDiscovery(
                                            message = R.string.provider_directory_unsupported,
                                        )
                                    }
                                }
                        }
                    },
                    onContext = { contextModel = it },
                    onTest = { model ->
                        perform {
                            service.runConnectionTest(
                                row.id,
                                model,
                                verifyGeneration = true,
                                expectedConfig = config,
                            )
                        }
                    },
                    onProbe = { model ->
                        perform { service.runCapabilityTest(row.id, model, expectedConfig = config) }
                    },
                    onDeclareVision = null,
                    allowDiscovery = current.provisioning != ProviderProvisioningKind.ON_DEVICE_ASSET,
                    allowAdd = current.provisioning != ProviderProvisioningKind.ON_DEVICE_ASSET,
                )
                item("result") {
                    if (draft.selection.models.isEmpty()) Text(stringResource(R.string.provider_models_empty_short))
                    if (!connectionReady || draft.error) Text(stringResource(R.string.provider_models_operation_failed))
                    if (live?.modelSelection != draft.baseline) {
                        Text(stringResource(R.string.provider_models_stale))
                        TextButton(onClick = {
                            live?.let {
                                draft.baseline = it.modelSelection
                                draft.selection = it.modelSelection
                                draft.error = false
                            }
                        }, enabled = !busy) { Text(stringResource(R.string.provider_models_reload)) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    perform {
                        val value = draft.selectionForSave()
                        service.saveModelSelection(row.id, value, draft.baseline, expectedConfig = config)
                        onDismiss()
                    }
                },
                enabled = !busy && connectionReady,
                modifier = Modifier.testTag("provider-models-save"),
            ) { Text(stringResource(R.string.provider_models_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("provider-models-close")) {
                Text(stringResource(SharedR.string.common_cancel))
            }
        },
    )
}
