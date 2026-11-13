package com.helix.app.provider.ui

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
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
import com.helix.app.provider.ManagedProviderAccountResult
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.app.ui.LocalModelDialog
import com.helix.app.ui.modelSourceDescription
import com.helix.app.ui.modelSourceEmpty
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProvisioningKind
import com.helix.feature.conversation.application.ChatService
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderConfig
import com.helix.ui.shared.ModelSourceTabs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Provider management UI (HXA-028; the task's “设置 → Provider” section).
 *
 * The UI dispatches intents to [ProviderService] and observes its [rows]
 * StateFlow — it never holds a network Job, never touches DAOs/OkHttp and
 * reads saved keys only for explicit, short-lived display in the connection editor.
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
    var discoveryJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var modelBusy by remember { mutableStateOf(false) }
    var modelContext by remember { mutableStateOf<String?>(null) }
    var discoveryMessage by remember { mutableStateOf<Int?>(null) }
    var deleteFailure by remember { mutableStateOf(false) }
    var localModelOpen by remember { mutableStateOf(false) }
    var templatePickerOpen by remember { mutableStateOf(false) }
    val formOwner = requireNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    val editor =
        remember(formOwner) {
            androidx.lifecycle.ViewModelProvider(formOwner)[ProviderEditorState::class.java]
        }
    var form by editor::form
    var contextTarget by remember { mutableStateOf<Pair<ProviderRowUi, String>?>(null) }
    var accountRow by remember { mutableStateOf<ProviderRowUi?>(null) }
    var modelsRow by remember { mutableStateOf<ProviderRowUi?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var detectingId by remember { mutableStateOf<String?>(null) }
    var connectionResults by remember { mutableStateOf<Map<String, ProviderCheckFeedback<ProbeOutcome>>>(emptyMap()) }
    var modelFailures by remember { mutableStateOf<Map<String, ProviderCheckFeedback<String>>>(emptyMap()) }
    val saving = editor.saving

    fun checkModel(
        row: ProviderRowUi,
        model: String,
        capabilities: Boolean,
    ) {
        val baseline = row.configuration ?: return
        if (testingId != null || model !in row.conversationModels) return
        testingId = row.id
        detectingId = row.id
        modelFailures = modelFailures - row.id
        scope.launch {
            try {
                val result =
                    if (capabilities) {
                        providerService.runCapabilityTest(row.id, model, expectedConfig = baseline)
                    } else {
                        providerService.runConnectionTest(
                            row.id,
                            model,
                            verifyGeneration = true,
                            expectedConfig = baseline,
                        )
                    }
                if (result is ProbeOutcome.Failed) {
                    modelFailures = modelFailures + (row.id to ProviderCheckFeedback(baseline, row.accountState, model))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                modelFailures = modelFailures + (row.id to ProviderCheckFeedback(baseline, row.accountState, model))
            } finally {
                testingId = null
                detectingId = null
            }
        }
    }
    accountRow?.let { ProviderAccountDialog(it, providerService) { accountRow = null } }

    if (localModelOpen) {
        providerService.localModels?.let {
            LocalModelDialog(
                providerService = providerService,
            ) { localModelOpen = false }
        }
    }
    modelsRow?.let { row ->
        ProviderModelsDialog(row, providerService, onDismiss = { modelsRow = null })
    }
    Column(Modifier.fillMaxWidth()) {
        ModelSourceTabs(providerService.sourceGroups, group) { group = it }
        if (group != ProviderProvisioningKind.USER_CONFIGURED) {
            Text(
                stringResource(
                    if (group == ProviderProvisioningKind.MANAGED_ACCOUNT) {
                        R.string.provider_subscription_experimental_notice
                    } else {
                        modelSourceDescription(group)
                    },
                ),
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (group == ProviderProvisioningKind.USER_CONFIGURED) {
            FilledTonalButton(onClick = {
                templatePickerOpen = true
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("provider-add")) {
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
        if (rows.none { !it.bundled && it.provisioning == group }) {
            Text(
                stringResource(modelSourceEmpty(group)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.filter { !it.bundled && it.provisioning == group }.forEach { row ->
            ProviderRow(
                row = row,
                testing = testingId == row.id,
                actions =
                    ProviderRowActions(
                        onManageModels = { modelsRow = row },
                        onContext = { model -> contextTarget = row to model },
                        onTestModel = { model -> checkModel(row, model, false) },
                        onTest = {
                            val baseline = row.configuration
                            if (testingId == null && baseline != null) {
                                testingId = row.id
                                scope.launch {
                                    try {
                                        val result =
                                            providerService.runConnectionTest(
                                                row.id,
                                                expectedConfig = baseline,
                                            )
                                        connectionResults = connectionResults +
                                            (row.id to ProviderCheckFeedback(baseline, row.accountState, result))
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (e: Exception) {
                                        connectionResults = connectionResults +
                                            (
                                                row.id to
                                                    ProviderCheckFeedback(
                                                        baseline,
                                                        row.accountState,
                                                        com.helix.provider.api.ProbeOutcome.Failed(
                                                            1,
                                                            com.helix.core.model.ModelErrorCode.PROTOCOL,
                                                            "CONNECTION_CHECK_FAILED",
                                                            false,
                                                        ),
                                                    )
                                            )
                                        Log.w(TAG, "connection test for ${row.id} did not run", e)
                                    } finally {
                                        testingId = null
                                    }
                                }
                            }
                        },
                        onDetectCapabilities = { model -> checkModel(row, model, true) },
                        onEdit = { modelOverride ->
                            // storedConfig is a Room read: it runs on the service's IO
                            // scope, never on this (UI) thread. HXA-059: a backend model
                            // id selected from the row's "后端可用模型" section prefills
                            // the form's model field — the form opens for EDITING, it
                            // is never auto-saved.
                            scope.launch {
                                try {
                                    val config = providerService.storedConfig(row.id)
                                    discovery = row.backendModels.orEmpty().filterNot(row::conversationProhibited)
                                    discoveryMessage = null
                                    editor.row = row
                                    editor.models = ProviderModelsEditorState(row.modelSelection)
                                    editor.saveSession =
                                        providerService.beginDraftSave(row.id, config, row.modelSelection)
                                    form = editingProviderForm(row, config, modelOverride)
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
                        onManageAccount = { accountRow = row },
                    ),
                accountUnavailable = false,
                detectingCapabilities = detectingId == row.id,
                connectionOutcome =
                    connectionResults[row.id]
                        ?.takeIf {
                            it.matches(row)
                        }?.value,
                modelOperationError =
                    modelFailures[row.id]
                        ?.takeIf {
                            it.matches(row)
                        }?.value,
            )
        }
    }

    contextTarget?.let { (row, model) ->
        val live = rows.firstOrNull { it.id == row.id }
        if (live != null && model in live.conversationModels &&
            com.helix.app.provider
                .sameProviderEditableConfig(live.configuration, row.configuration)
        ) {
            ProviderContextDialog(live, providerService, initialModel = model, fixedModel = true) {
                contextTarget = null
            }
        }
    }

    if (templatePickerOpen) {
        TemplatePickerDialog(
            onSelect = { template ->
                templatePickerOpen = false
                discovery = emptyList()
                discoveryMessage = null
                editor.row = null
                editor.models =
                    ProviderModelsEditorState(
                        com.helix.app.provider
                            .ProviderModelSelection(),
                    ).apply {
                        addingModel = true
                    }
                editor.saveSession = providerService.beginDraftSave()
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
        val models = requireNotNull(editor.models)
        val session = requireNotNull(editor.saveSession)
        val liveRow = currentForm.providerId?.let { id -> rows.firstOrNull { it.id == id } }

        fun runModelAction(action: suspend (ProviderConfig) -> ProbeOutcome?) {
            val baseline = currentForm.savedConfig ?: return
            val busy = modelBusy || saving || discovering
            if (!currentForm.connectionMatchesSaved() || busy) return
            val target = ProviderCheckFeedback(baseline, liveRow?.accountState, Unit)

            fun showFailure() {
                val latest = providerService.rows.value.firstOrNull { it.id == baseline.id }
                if (editor.saveSession === session && latest != null && target.matches(latest)) {
                    form = form?.copy(error = SaveResult.Rejected(R.string.provider_models_operation_failed))
                }
            }
            modelBusy = true
            form = currentForm.copy(error = null)
            scope.launch {
                try {
                    if (action(baseline) is ProbeOutcome.Failed) showFailure()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showFailure()
                } finally {
                    modelBusy = false
                }
            }
        }
        modelContext?.let { model ->
            if (liveRow != null && currentForm.connectionMatchesSaved() &&
                com.helix.app.provider
                    .sameProviderEditableConfig(liveRow.configuration, currentForm.savedConfig)
            ) {
                ProviderContextDialog(
                    liveRow,
                    providerService,
                    initialModel = model,
                    independentSave = true,
                    fixedModel = true,
                ) {
                    modelContext =
                        null
                }
            }
        }
        ProviderFormDialog(
            form = currentForm,
            saving = saving,
            models = models,
            modelRow = liveRow,
            modelBusy = modelBusy,
            onTest = { model ->
                runModelAction { baseline ->
                    providerService.runConnectionTest(
                        baseline.id,
                        model,
                        verifyGeneration = true,
                        expectedConfig = baseline,
                    )
                }
            },
            onProbe = { model ->
                runModelAction { baseline ->
                    providerService.runCapabilityTest(baseline.id, model, expectedConfig = baseline)
                }
            },
            onDeclareVision = { enabled ->
                runModelAction { baseline ->
                    providerService.declareVisionCapability(baseline.id, enabled, expectedConfig = baseline)
                    null
                }
            },
            onContext = { if (currentForm.connectionMatchesSaved()) modelContext = it },
            loadStoredKey = providerService::revealUserConfiguredApiKey,
            onField = { updated ->
                if (saving || modelBusy) return@ProviderFormDialog
                if (updated.catalogIdentity() != currentForm.catalogIdentity()) {
                    discovery = emptyList()
                    discoveryMessage = null
                }
                form = updated.copy(error = null)
            },
            discoveryState = ProviderFormDiscovery(discovery, discoveryMessage, discovering),
            onDiscover = {
                if (!discovering) {
                    val target = currentForm
                    discovering = true
                    discoveryJob =
                        scope.launch {
                            try {
                                val result = discoverProviderForm(target, providerService)
                                if (editor.saveSession === session && form == target) {
                                    discovery = result.models
                                    discoveryMessage = result.message
                                }
                            } finally {
                                if (editor.saveSession === session) discovering = false
                            }
                        }
                }
            },
            onDismiss = {
                if (!saving) {
                    discoveryJob?.cancel()
                    discoveryJob = null
                    discovering = false
                    modelContext = null
                    editor.close()
                    modelsRow = null
                }
            },
            onSave = {
                editor.save(currentForm) {
                    attemptSave(it, models, session, providerService).also { result ->
                        if (result == SaveResult.Saved) {
                            android.widget.Toast
                                .makeText(
                                    context,
                                    R.string.provider_settings_saved,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                }
            },
        )
    }
}

private const val TAG = "HelixProviderUi"

private data class ProviderCheckFeedback<T>(
    val config: ProviderConfig,
    val account: com.helix.app.provider.ManagedAccountSnapshot?,
    val value: T,
) {
    fun matches(row: ProviderRowUi): Boolean =
        com.helix.app.provider
            .sameProviderEditableConfig(row.configuration, config) && row.accountState == account
}
