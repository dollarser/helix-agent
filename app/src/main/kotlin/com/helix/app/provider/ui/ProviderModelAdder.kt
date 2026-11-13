package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.ui.shared.R as SharedR

/** One model-entry interaction for initial setup and every existing service. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ProviderModelAdder(
    draft: ProviderModelsEditorState,
    models: List<String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    canAdd: (String) -> Boolean = { true },
    inputModifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!draft.addingModel) {
                TextButton(
                    onClick = { draft.addingModel = true },
                    enabled = enabled,
                    modifier = Modifier.testTag("provider-model-add-entry"),
                ) { Text(stringResource(R.string.provider_model_add_entry)) }
            } else {
                ProviderModelInput(
                    value = draft.manual,
                    onValueChange = {
                        draft.manual = it
                        draft.error = false
                    },
                    models = models,
                    enabled = enabled,
                    isError = draft.error,
                    modifier = inputModifier.testTag("provider-model-manual"),
                )
                OutlinedTextField(
                    value = draft.manualDisplayName,
                    onValueChange = { draft.manualDisplayName = it.take(256) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.provider_model_display_name)) },
                    placeholder = { Text(draft.manual) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("provider-model-display-name"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { draft.addManual() },
                        enabled = enabled && draft.manual.isNotBlank() && canAdd(draft.manual.trim()),
                        modifier = Modifier.testTag("provider-models-add"),
                    ) { Text(stringResource(R.string.provider_models_add)) }
                    TextButton(
                        onClick = {
                            draft.addingModel = false
                            draft.manual = ""
                            draft.manualDisplayName = ""
                            draft.error = false
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("provider-model-add-cancel"),
                    ) { Text(stringResource(SharedR.string.common_cancel)) }
                }
                if (draft.error) Text(stringResource(R.string.provider_model_selection_invalid))
            }
        }
    }
}
