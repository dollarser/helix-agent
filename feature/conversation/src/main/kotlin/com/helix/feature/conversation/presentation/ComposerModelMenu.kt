package com.helix.feature.conversation.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ReasoningEffort
import com.helix.feature.conversation.R
import com.helix.feature.conversation.presentation.ComposerReasoningMenu
import com.helix.feature.conversation.presentation.ModelPickerEdit
import com.helix.feature.conversation.presentation.SessionModelSelectionResult
import com.helix.feature.conversation.presentation.modelPickerIdentity
import com.helix.ui.shared.IndicatedLazyColumn
import com.helix.ui.shared.ModelSourceActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.helix.feature.conversation.R as ConversationR
import com.helix.ui.shared.R as SharedR

private fun matchesModelQuery(
    row: ConversationModelSourceUi,
    model: String,
    query: String,
): Boolean = listOf(model, row.modelLabel(model), row.displayName).any { it.contains(query, ignoreCase = true) }

private fun ConversationModelSourceUi.needsModelSetup(query: String): Boolean =
    setupStep != ConversationModelSetupStep.READY &&
        (displayName.contains(query, ignoreCase = true) || knownModels.any { it.contains(query, ignoreCase = true) })

@Composable
// Android localized format resources accept varargs; these short display labels need the exact arguments.
@Suppress("FunctionName", "SpreadOperator")
private fun ConversationModelItem(
    row: ConversationModelSourceUi,
    model: String,
    current: Boolean,
    enabled: Boolean,
    reasoning: (@Composable () -> Unit)? = null,
    onSelect: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text((if (current) "✓ " else "") + row.modelLabel(model))
                val subtitle =
                    row.displayLabel?.let { stringResource(it.resourceId, *it.arguments.toTypedArray()) }
                        ?: row.displayName
                Text(subtitle, style = MaterialTheme.typography.labelSmall)
                row.unavailable
                    ?.takeUnless { row.chatSelectable }
                    ?.label
                    ?.let { ModelCatalogLabel(it) }
                row
                    .choice(model)
                    ?.generationFailure
                    ?.label
                    ?.let { ModelCatalogLabel(it) }
            }
        },
        trailingIcon = reasoning,
        onClick = { if (enabled && row.modelSelectable(model)) onSelect() },
        enabled = enabled && row.modelSelectable(model),
        modifier = Modifier.semantics { selected = current }.testTag("chat-model-${row.id}-$model"),
    )
}

@Composable
// The search/selection render tree stays in one picker, also opened by the no-model reminder.
@Suppress("FunctionName", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun ComposerModelMenu(
    providers: List<ConversationModelSourceUi>,
    providerId: String?,
    model: String?,
    enabled: Boolean,
    onSave: suspend (String, String, ReasoningEffort) -> SessionModelSelectionResult,
    openRequest: Int = 0,
    sourceGroups: List<ProviderProvisioningKind> =
        listOf(
            ProviderProvisioningKind.USER_CONFIGURED,
            ProviderProvisioningKind.ON_DEVICE_ASSET,
        ),
    onConfigureSource: ((ProviderProvisioningKind) -> Unit)? = null,
    onRefreshModels: (suspend (Boolean) -> Unit)? = null,
    reasoning: ReasoningEffort = ReasoningEffort.OFF,
    sessionKey: String? = null,
    entry: (@Composable (() -> Unit) -> Unit)? = null,
    accountEntry: @Composable (() -> Unit) -> Unit = {},
    onDetectReasoning: (suspend (String, String) -> List<ReasoningEffort>)? = null,
) {
    val identity = modelPickerIdentity(sessionKey, providerId, model, reasoning)
    var edit by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = ModelPickerEdit.Saver) {
        mutableStateOf(ModelPickerEdit(identity, false, providerId, model, reasoning))
    }
    val expanded = edit.expanded && edit.identity == identity
    var saving by remember { mutableStateOf(false) }
    var saveGeneration by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var saveFailure by remember { mutableStateOf<Int?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun open() {
        saveGeneration++
        saving = false
        edit = ModelPickerEdit(identity, true, providerId, model, reasoning)
        saveFailure = null
    }

    fun dismiss() {
        if (!saving) edit = edit.copy(expanded = false)
    }
    var query by remember { mutableStateOf("") }
    var refreshRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var handledRefresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val currentProvider = providers.firstOrNull { it.id == providerId }
    val pendingProvider = providers.firstOrNull { it.id == edit.providerId }
    val sameModel = edit.providerId == providerId && edit.model == model
    val selectable =
        edit.providerId != null && edit.model != null &&
            (
                (
                    sameModel && pendingProvider?.conversationEligible(requireNotNull(edit.model)) == true &&
                        (pendingProvider.bundled != true || pendingProvider.chatSelectable)
                ) ||
                    pendingProvider?.modelSelectable(requireNotNull(edit.model)) == true
            )
    val visibleProviders = providers.filter { it.visibleInModelPicker || (it.bundled && it.id == providerId) }
    val currentLabel = model?.let { currentProvider?.modelLabel(it) ?: it }
    val entries =
        visibleProviders
            .flatMap { row -> row.conversationModels.map { row to it } }
            .filter { (row, candidate) -> matchesModelQuery(row, candidate, query) }
    val pendingProviders = visibleProviders.filter { !it.bundled && it.needsModelSetup(query) }
    LaunchedEffect(expanded, refreshRequest) {
        if (expanded && onRefreshModels != null) {
            refreshing = true
            try {
                val force = refreshRequest != handledRefresh
                handledRefresh = refreshRequest
                onRefreshModels(force)
            } finally {
                refreshing = false
            }
        }
    }
    LaunchedEffect(identity, enabled) {
        if (edit.identity != identity || !enabled) {
            saveGeneration++
            saving = false
            saveFailure = null
            edit = ModelPickerEdit(identity, false, providerId, model, reasoning)
        }
    }
    LaunchedEffect(openRequest) {
        if (openRequest > 0 && enabled) {
            query = ""
            open()
        }
    }
    Box {
        if (entry != null) {
            entry {
                if (enabled) {
                    query = ""
                    open()
                }
            }
        } else {
            TextButton(
                {
                    query = ""
                    open()
                },
                enabled = enabled,
                modifier = Modifier.defaultMinSize(minWidth = 1.dp).testTag("chat-model-menu"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    "${currentLabel ?: stringResource(R.string.chat_select_model)} ▾",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expanded) {
            AlertDialog(
                onDismissRequest = ::dismiss,
                title = { Text(stringResource(R.string.chat_select_model)) },
                text = {
                    IndicatedLazyColumn(Modifier.heightIn(max = 520.dp).testTag("chat-model-list")) {
                        item {
                            accountEntry { refreshRequest++ }
                            OutlinedTextField(
                                query,
                                { query = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.provider_models_search)) },
                                modifier = Modifier.fillMaxWidth().testTag("chat-model-search"),
                            )
                        }
                        if (onRefreshModels != null) {
                            item {
                                Column {
                                    TextButton(
                                        onClick = { refreshRequest++ },
                                        enabled = !refreshing && !saving,
                                        modifier = Modifier.testTag("chat-model-refresh"),
                                    ) {
                                        Text(
                                            stringResource(
                                                if (refreshing) {
                                                    R.string.provider_discovering_models
                                                } else {
                                                    R.string.provider_models_refresh
                                                },
                                            ),
                                        )
                                    }
                                    providers
                                        .firstOrNull {
                                            it.bundled
                                        }?.catalogFeedback
                                        ?.label
                                        ?.let {
                                            ModelCatalogLabel(
                                                it,
                                            )
                                        }
                                }
                            }
                        }
                        if (model != null && model !in currentProvider?.conversationModels.orEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.provider_models_current_hidden, currentLabel.orEmpty()),
                                    modifier = Modifier.testTag("chat-model-hidden-current"),
                                )
                            }
                        }
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.provider_models_picker_empty),
                                    modifier = Modifier.testTag("chat-model-empty"),
                                )
                            }
                        }
                        items(entries, key = { (row, candidate) ->
                            "${row.id.length}:${row.id}$candidate"
                        }) { (row, candidate) ->
                            val current = row.id == edit.providerId && candidate == edit.model
                            ConversationModelItem(
                                row,
                                candidate,
                                current,
                                enabled && !saving,
                                reasoning =
                                    if (current) {
                                        {
                                            androidx.compose.runtime.key(row.id, candidate) {
                                                ComposerReasoningMenu(
                                                    edit.reasoning,
                                                    enabled && !saving,
                                                    {
                                                        edit = edit.copy(reasoning = it)
                                                        saveFailure = null
                                                    },
                                                    efforts = row.reasoningOptionsFor(candidate),
                                                    onDetect =
                                                        onDetectReasoning?.let { detect ->
                                                            { detect(row.id, candidate) }
                                                        },
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                            ) {
                                edit = edit.select(row.id, candidate)
                                saveFailure = null
                            }
                        }
                        items(pendingProviders, key = { "setup:${it.id}" }) { row ->
                            ConversationModelSetupRow(
                                row,
                                onConfigureSource?.let { configure ->
                                    {
                                        if (!saving) {
                                            dismiss()
                                            configure(row.provisioning)
                                        }
                                    }
                                },
                            )
                        }
                        item {
                            if (onConfigureSource != null) {
                                ModelSourceActions(sourceGroups) { source ->
                                    if (!saving) {
                                        dismiss()
                                        onConfigureSource(source)
                                    }
                                }
                            }
                        }
                        edit.model?.let { pendingProvider?.choice(it)?.reasoningNotice }?.let { notice ->
                            item { ModelCatalogLabel(notice) }
                        }
                        if (edit.model != null && !selectable) {
                            item {
                                Text(
                                    stringResource(ConversationR.string.model_selection_model_unavailable),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("chat-model-pending-unavailable"),
                                )
                            }
                        }
                        saveFailure?.let { failure ->
                            item {
                                Text(
                                    stringResource(failure),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("chat-model-save-error"),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = edit
                            val generation = ++saveGeneration
                            saving = true
                            scope.launch {
                                try {
                                    val result =
                                        onSave(
                                            requireNotNull(target.providerId),
                                            requireNotNull(target.model),
                                            target.reasoning,
                                        )
                                    if (saveGeneration == generation && edit.identity == target.identity) {
                                        if (result == SessionModelSelectionResult.APPLIED) {
                                            edit = edit.copy(expanded = false)
                                        } else {
                                            saveFailure = result.messageRes
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (saveGeneration == generation && edit.identity == target.identity) {
                                        saveFailure = SessionModelSelectionResult.FAILED.messageRes
                                    }
                                } finally {
                                    if (saveGeneration == generation) saving = false
                                }
                            }
                        },
                        enabled = enabled && !saving && selectable && edit.identity == identity,
                        modifier = Modifier.testTag("chat-model-picker-save"),
                    ) { Text(stringResource(SharedR.string.common_save)) }
                },
                dismissButton = {
                    TextButton(::dismiss, enabled = !saving, modifier = Modifier.testTag("chat-model-picker-cancel")) {
                        Text(stringResource(SharedR.string.common_cancel))
                    }
                },
            )
        }
    }
}

@Composable
// Preserve Android resource formatting for the provider-owned label descriptor.
@Suppress("FunctionName", "SpreadOperator")
private fun ModelCatalogLabel(label: ConversationModelLabel) {
    Text(stringResource(label.resourceId, *label.arguments.toTypedArray()), style = MaterialTheme.typography.bodySmall)
}

@Composable
@Suppress("FunctionName")
private fun ConversationModelSetupRow(
    row: ConversationModelSourceUi,
    onConfigure: (() -> Unit)?,
) {
    Column(Modifier.padding(vertical = 8.dp).testTag("chat-model-setup-${row.id}")) {
        Text(
            stringResource(R.string.model_picker_needs_setup, row.displayName),
            style = MaterialTheme.typography.titleSmall,
        )
        row.setupLabel?.let { ModelCatalogLabel(it) }
        if (onConfigure != null) {
            TextButton(onConfigure, Modifier.testTag("chat-model-setup-open-${row.id}")) {
                Text(stringResource(R.string.model_picker_setup_action))
            }
        }
    }
}
