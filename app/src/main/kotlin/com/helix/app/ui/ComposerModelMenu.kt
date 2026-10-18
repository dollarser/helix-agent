package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderSetupStep
import com.helix.app.ui.IndicatedLazyColumn
import com.helix.core.model.ProviderProvisioningKind

private fun matchesModelQuery(
    row: ProviderRowUi,
    model: String,
    query: String,
): Boolean = listOf(model, row.modelLabel(model), row.displayName).any { it.contains(query, ignoreCase = true) }

private fun ProviderRowUi.needsModelSetup(query: String): Boolean =
    ProviderSetupStep.forRow(this) != ProviderSetupStep.READY &&
        (displayName.contains(query, ignoreCase = true) || knownModels.any { it.contains(query, ignoreCase = true) })

@Composable
@Suppress("FunctionName")
private fun ConversationModelItem(
    row: ProviderRowUi,
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
                Text(
                    if (row.bundled) stringResource(R.string.builtin_model_title) else row.displayName,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (!row.chatSelectable) {
                    Text(
                        stringResource(
                            if (row.bundled) {
                                com.helix.app.provider.BuiltInModel
                                    .errorLabel(row.builtInCatalog?.failure?.name)
                                    ?: R.string.builtin_model_unverified
                            } else {
                                R.string.provider_models_connection_required
                            },
                        ),
                    )
                }
                row.modelGenerations[model]?.failure?.let {
                    Text(
                        stringResource(
                            (
                                if (row.bundled) {
                                    com.helix.app.provider.BuiltInModel
                                        .errorLabel(it.name)
                                } else {
                                    null
                                }
                            )
                                ?: com.helix.app.provider.ConnectionTestMapping
                                    .codeLabel(it),
                        ),
                    )
                }
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
internal fun ComposerModelMenu(
    providers: List<ProviderRowUi>,
    providerId: String?,
    model: String?,
    enabled: Boolean,
    onSelect: (String, String) -> Unit,
    onManageModels: (() -> Unit)? = null,
    openRequest: Int = 0,
    sourceGroups: List<ProviderProvisioningKind> =
        listOf(
            ProviderProvisioningKind.USER_CONFIGURED,
            ProviderProvisioningKind.ON_DEVICE_ASSET,
        ),
    onConfigureSource: ((ProviderProvisioningKind) -> Unit)? = null,
    onRefreshModels: (suspend (Boolean) -> Unit)? = null,
    reasoningContent: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var refreshRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var handledRefresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val currentProvider = providers.firstOrNull { it.id == providerId }
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
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    LaunchedEffect(openRequest) {
        if (openRequest > 0 && enabled) {
            query = ""
            expanded = true
        }
    }
    Box {
        TextButton(
            {
                query = ""
                expanded = true
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
        if (expanded) {
            AlertDialog(
                onDismissRequest = { expanded = false },
                title = { Text(stringResource(R.string.chat_select_model)) },
                text = {
                    IndicatedLazyColumn(Modifier.heightIn(max = 520.dp).testTag("chat-model-list")) {
                        item {
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
                                        enabled = !refreshing,
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
                                    Text(
                                        stringResource(R.string.builtin_model_catalog_notice),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
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
                            val current = row.id == providerId && candidate == model
                            ConversationModelItem(
                                row,
                                candidate,
                                current,
                                enabled,
                                reasoning =
                                    if (current) {
                                        {
                                            androidx.compose.runtime.key(providerId, model) {
                                                reasoningContent?.invoke()
                                            }
                                        }
                                    } else {
                                        null
                                    },
                            ) {
                                expanded = false
                                if (!current) onSelect(row.id, candidate)
                            }
                        }
                        items(pendingProviders, key = { "setup:${it.id}" }) { row ->
                            val navigateToSource =
                                onConfigureSource?.let { configure ->
                                    { configure(row.provisioning) }
                                } ?: onManageModels
                            ModelSetupRow(
                                row,
                                navigateToSource?.let { navigate ->
                                    {
                                        expanded = false
                                        navigate()
                                    }
                                },
                            )
                        }
                        item {
                            if (onConfigureSource != null || onManageModels != null) {
                                ModelSourceActions(sourceGroups) { source ->
                                    expanded = false
                                    if (onConfigureSource != null) {
                                        onConfigureSource(source)
                                    } else {
                                        onManageModels?.invoke()
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { expanded = false },
                        modifier = Modifier.testTag("chat-model-picker-close"),
                    ) { Text(stringResource(R.string.chat_details_close)) }
                },
            )
        }
    }
}
