package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi
import com.helix.feature.conversation.R as ConversationR

/** The same model list and adder are used before and after a service is created. */
@Suppress("LongParameterList", "LongMethod")
internal fun LazyListScope.providerFormModels(
    draft: ProviderModelsEditorState,
    row: ProviderRowUi?,
    discovery: ProviderFormDiscovery,
    busy: Boolean,
    connectionReady: Boolean,
    inputModifier: Modifier,
    onDiscover: () -> Unit,
    onContext: (String) -> Unit,
    onTest: (String) -> Unit,
    onProbe: (String) -> Unit,
    onDeclareVision: ((Boolean) -> Unit)?,
    allowDiscovery: Boolean = true,
    allowAdd: Boolean = true,
) {
    val known = (discovery.models + if (connectionReady) row?.knownModels.orEmpty() else emptyList()).distinct()
    val candidates = (draft.selection.models + draft.selection.customModels + known).distinct()
    val filtered = draft.matchingModels(candidates)
    item("heading") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.provider_settings_models), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.provider_models_selected_count, draft.selection.models.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (allowDiscovery) {
                    OutlinedButton(
                        onClick = onDiscover,
                        enabled = !busy,
                        modifier = Modifier.testTag("provider-discover-models"),
                    ) {
                        Text(
                            stringResource(
                                if (discovery.running) {
                                    ConversationR.string.provider_discovering_models
                                } else {
                                    R.string.provider_discover_models
                                },
                            ),
                        )
                    }
                }
                if (draft.selection.models.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            draft.selection =
                                draft.selection.copy(models = emptyList(), configured = true)
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("provider-model-clear"),
                    ) {
                        Text(stringResource(R.string.provider_models_clear))
                    }
                }
            }
            discovery.message?.let { Text(stringResource(it)) }
            if (candidates.size > 6) {
                OutlinedTextField(
                    draft.query,
                    { draft.query = it },
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(ConversationR.string.provider_models_search)) },
                    modifier = Modifier.fillMaxWidth().testTag("provider-model-search"),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = draft.onlySelected,
                                enabled = !busy,
                                role = Role.Checkbox,
                                onValueChange = { draft.onlySelected = it },
                            ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(draft.onlySelected, null, enabled = !busy)
                    Text(stringResource(R.string.provider_models_only_selected))
                }
            }
        }
    }
    if (allowAdd) {
        item("input") {
            ProviderModelAdder(
                draft,
                known,
                !busy,
                canAdd = { !connectionReady || row?.conversationProhibited(it) != true },
                inputModifier = inputModifier,
            )
        }
    }
    items(filtered, key = { "model-$it" }) { model ->
        Column {
            ProviderModelChoiceRow(
                row = if (connectionReady) row?.copy(modelSelection = draft.selection) else null,
                model = model,
                selection = draft.selection,
                busy = busy,
                expanded = draft.expanded == model,
                onToggle = { draft.selection = draft.selection.toggle(model, it) },
                onExpand = { draft.expanded = if (draft.expanded == model) null else model },
                onContext = { onContext(model) },
                onTest = { onTest(model) },
                onProbe = { onProbe(model) },
                onMove = { draft.selection = draft.selection.moveFirst(model) },
                connectionReady = connectionReady,
                onDeclareVision = onDeclareVision,
            )
            if (draft.expanded == model) {
                OutlinedTextField(
                    value = draft.selection.displayNames[model].orEmpty(),
                    onValueChange = { draft.selection = draft.selection.withDisplayName(model, it.take(256)) },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.provider_model_display_name)) },
                    placeholder = { Text(model) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("provider-model-name-$model"),
                )
            }
        }
    }
}
