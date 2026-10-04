package com.helix.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.provider.ManagedProviderAccountResult
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProvisioningKind
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Provider management UI (HXA-028; the task's “设置 → Provider” section).
 *
 * The UI dispatches intents to [ProviderService] and observes its [rows]
 * StateFlow — it never holds a network Job, never touches DAOs/OkHttp and
 * never sees a secret (only the [ProviderRowUi.hasKey] flag, NFR-007).
 *
 * Rules rendered here (all enforced in the pure/service layer):
 * - a provider is chat-selectable ONLY after a completed connection test
 *   (“未完成连接测试不贬为已可用”);
 * - a user-configured HTTP endpoint shows a non-blocking risk warning;
 *   no separate confirmation checkbox or transport permission is required;
 * - a test failure shows the SAFE phase + code label (FR-LLM-004 / doc 02
 *   section 13) — never a raw exception message.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
fun ProviderManager(
    providerService: ProviderService,
    initialSource: ProviderProvisioningKind? = null,
) {
    val rows by providerService.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var group by rememberSaveable(initialSource) {
        mutableStateOf(
            initialSource?.takeIf { it in providerService.sourceGroups }
                ?: ProviderProvisioningKind.USER_CONFIGURED,
        )
    }
    androidx.compose.runtime.LaunchedEffect(providerService.sourceGroups) {
        if (group !in providerService.sourceGroups) group = ProviderProvisioningKind.USER_CONFIGURED
    }
    var discovery by remember { mutableStateOf<List<String>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var discoveryMessage by remember { mutableStateOf<Int?>(null) }
    var deleteFailure by remember { mutableStateOf(false) }
    var localModelOpen by remember { mutableStateOf(false) }
    var templatePickerOpen by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<ProviderForm?>(null) }
    var contextRow by remember { mutableStateOf<com.helix.app.provider.ProviderRowUi?>(null) }
    var modelsRow by remember { mutableStateOf<ProviderRowUi?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var detectingId by remember { mutableStateOf<String?>(null) }
    var capabilityResults by remember { mutableStateOf<Map<String, ProbeOutcome>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    var accountFailureId by remember { mutableStateOf<String?>(null) }

    if (localModelOpen) {
        providerService.localModels?.let {
            LocalModelDialog(
                providerService = providerService,
            ) { localModelOpen = false }
        }
    }
    modelsRow?.let { row ->
        ProviderModelsDialog(
            row,
            providerService,
            onDismiss = { modelsRow = null },
        )
    }
    Column(Modifier.fillMaxWidth()) {
        ModelSourceTabs(providerService.sourceGroups, group) { group = it }
        Text(
            stringResource(modelSourceDescription(group)),
            modifier = Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (group == ProviderProvisioningKind.USER_CONFIGURED) {
            OutlinedButton(onClick = {
                templatePickerOpen = true
            }, modifier = Modifier.fillMaxWidth().testTag("provider-add")) {
                Text(stringResource(R.string.provider_add))
            }
        }
        if (group == ProviderProvisioningKind.ON_DEVICE_ASSET && providerService.localModels != null) {
            OutlinedButton(
                onClick = { localModelOpen = true },
                modifier = Modifier.fillMaxWidth().testTag("local-model-add"),
            ) { Text(stringResource(R.string.model_source_download)) }
        }
        if (deleteFailure) Text(stringResource(R.string.local_model_delete_failed))
        if (rows.none { it.provisioning == group }) {
            Text(
                stringResource(modelSourceEmpty(group)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.filter { it.provisioning == group }.forEach { row ->
            ProviderRow(
                row = row,
                testing = testingId == row.id,
                actions =
                    ProviderRowActions(
                        onManageModels = { modelsRow = row },
                        onContext = { contextRow = row },
                        onDeclareVision = { enabled ->
                            // The user-visible manual declaration (ADR-0014): vision may come from a
                            // real probe OR this explicit mark — the UI shows 「手动声明」 afterwards.
                            scope.launch { providerService.declareVisionCapability(row.id, enabled) }
                        },
                        onTest = {
                            if (testingId == null) {
                                testingId = row.id
                                scope.launch {
                                    try {
                                        providerService.runConnectionTest(
                                            row.id,
                                            row.model,
                                        )
                                    } catch (e: Exception) {
                                        // A row that cannot even be resolved (corruption) fails
                                        // closed: the status stays 未测试, the row stays
                                        // non-selectable, and the exception is logged — never
                                        // rendered raw (doc 02 section 13).
                                        Log.w(TAG, "connection test for ${row.id} did not run", e)
                                    } finally {
                                        testingId = null
                                    }
                                }
                            }
                        },
                        onDetectCapabilities = {
                            if (testingId == null) {
                                testingId = row.id
                                detectingId = row.id
                                capabilityResults = capabilityResults - row.id
                                scope.launch {
                                    try {
                                        val result =
                                            providerService.runCapabilityTest(
                                                row.id,
                                                row.model,
                                            )
                                        capabilityResults = capabilityResults + (row.id to result)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "capability detection failed: ${e.javaClass.simpleName}")
                                        val failure =
                                            ProbeOutcome.Failed(
                                                0,
                                                ModelErrorCode.PROTOCOL,
                                                "capability detection did not complete",
                                                false,
                                            )
                                        capabilityResults = capabilityResults + (row.id to failure)
                                    } finally {
                                        testingId = null
                                        detectingId = null
                                    }
                                }
                            }
                        },
                        onEdit = { modelOverride ->
                            // storedConfig is a Room read: it runs on the service's IO
                            // scope, never on this (UI) thread. HXA-059: a backend model
                            // id selected from the row's "后端可用模型" section prefills
                            // the form's model field — the form opens for EDITING, it
                            // is never auto-saved.
                            scope.launch {
                                try {
                                    val config = providerService.storedConfig(row.id)
                                    discovery = row.backendModels.orEmpty()
                                    discoveryMessage = null
                                    form =
                                        editingProviderForm(row, config, modelOverride)
                                } catch (e: Exception) {
                                    // A row that cannot be resolved (corruption) fails
                                    // closed: the edit is not opened and the exception
                                    // is logged — never rendered raw (doc 02 section 13).
                                    Log.w(TAG, "could not load provider ${row.id} for edit", e)
                                }
                            }
                        },
                        onUnload = {
                            scope.launch {
                                try {
                                    deleteFailure = false
                                    providerService.localModels?.unload(row.model)
                                } catch (
                                    cancel: kotlinx.coroutines.CancellationException,
                                ) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    Log.w(TAG, "model resource operation failed: ${failure.javaClass.simpleName}")
                                    deleteFailure = true
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                try {
                                    deleteFailure = false
                                    providerService.delete(row.id)
                                } catch (e: Exception) {
                                    deleteFailure = true
                                    Log.w(TAG, "could not delete provider ${row.id}", e)
                                }
                            }
                        },
                        onManageAccount = {
                            scope.launch {
                                accountFailureId =
                                    if (
                                        providerService.openManagedAccount(row.id) ==
                                        ManagedProviderAccountResult.OPENED
                                    ) {
                                        null
                                    } else {
                                        row.id
                                    }
                            }
                        },
                    ),
                accountUnavailable = accountFailureId == row.id,
                detectingCapabilities = detectingId == row.id,
                capabilityOutcome = capabilityResults[row.id],
            )
        }
    }

    contextRow?.let { ProviderContextDialog(it, providerService) { contextRow = null } }

    if (templatePickerOpen) {
        TemplatePickerDialog(
            onSelect = { template ->
                templatePickerOpen = false
                discovery = emptyList()
                discoveryMessage = null
                form =
                    ProviderForm(
                        providerId = null,
                        template = template,
                        fields =
                            ProviderForm.FormFields(
                                name = template.displayName,
                                endpoint = template.defaultEndpoint?.full.orEmpty(),
                                model = "",
                                headerName = "",
                                headerValue = "",
                                apiKey = "",
                            ),
                        preservedHeaders = template.defaultHeaders,
                        hasStoredKey = false,
                        error = null,
                    )
            },
            onDismiss = { templatePickerOpen = false },
        )
    }

    val currentForm = form
    if (currentForm != null) {
        ProviderFormDialog(
            form = currentForm,
            saving = saving,
            onField = { updated ->
                if (updated.catalogIdentity() != currentForm.catalogIdentity()) {
                    discovery = emptyList()
                    discoveryMessage = null
                }
                form =
                    updated.copy(
                        error = null,
                        selectedModels =
                            if (updated.fields.endpoint ==
                                currentForm.fields.endpoint
                            ) {
                                updated.selectedModels
                            } else {
                                emptySet()
                            },
                    )
            },
            discoveryState = ProviderFormDiscovery(discovery, discoveryMessage, discovering),
            onDiscover = {
                if (!discovering) {
                    val target = currentForm
                    discovering = true
                    scope.launch {
                        try {
                            val result = discoverProviderForm(target, providerService)
                            if (form == target) {
                                discovery = result.models
                                discoveryMessage = result.message
                            }
                        } finally {
                            discovering = false
                        }
                    }
                }
            },
            onDismiss = {
                // An in-flight save keeps running (its result is dropped below
                // when it no longer matches the form); only the dialog closes.
                form = null
            },
            onSave = {
                if (saving) return@ProviderFormDialog
                val target = currentForm
                saving = true
                scope.launch {
                    try {
                        val result = attemptSave(target, providerService)
                        // Stale-result guard: the form may have changed (or been
                        // closed) while the Room/Keystore write was in flight.
                        if (form == target) {
                            form =
                                when (result) {
                                    SaveResult.Saved -> null
                                    is SaveResult.Rejected -> target.copy(error = result)
                                }
                        }
                    } finally {
                        saving = false
                    }
                }
            },
        )
    }
}

private const val TAG = "HelixProviderUi"
