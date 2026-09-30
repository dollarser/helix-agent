package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.helix.app.R
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderRowUi
import com.helix.provider.api.CapabilitySource

@Composable
@Suppress("FunctionName")
private fun ModelVerificationSummary(
    row: ProviderRowUi,
    model: String,
) {
    val generation = row.modelGenerations[model]
    Text(
        stringResource(
            when {
                generation == null -> R.string.provider_model_generation_unknown
                generation.failure != null -> R.string.provider_model_generation_failed
                else -> R.string.provider_model_generation_passed
            },
        ),
    )
    val failure = generation?.failure ?: row.modelVerifications[model]?.failure
    val caps = row.capabilitiesForModel(model)
    when {
        failure != null -> {
            Text(stringResource(ConnectionTestMapping.codeLabel(failure)))
        }

        caps == null || caps.source == CapabilitySource.CONNECTION_ONLY -> {
            Text(stringResource(R.string.provider_capabilities_unverified))
        }

        else -> {
            Text(
                stringResource(
                    R.string.provider_models_capabilities,
                    if (caps.toolCalls) "✓" else "—",
                    if (caps.vision) "✓" else "—",
                ),
            )
        }
    }
    val window = row.modelMetadata[model]?.contextWindow ?: caps?.maxContextTokens
    Text(
        stringResource(
            R.string.context_server_window,
            window?.toString() ?: stringResource(R.string.chat_context_unknown),
        ),
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod") // Narrow UI intents, not infrastructure.
internal fun ProviderModelChoiceRow(
    row: ProviderRowUi,
    model: String,
    selection: ProviderModelSelection,
    busy: Boolean,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onDefault: () -> Unit,
    onExpand: () -> Unit,
    onContext: () -> Unit,
    onTest: () -> Unit,
    onProbe: () -> Unit,
    onMove: () -> Unit,
    onUse: (() -> Unit)?,
) {
    val label = row.modelLabel(model)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                model in selection.models,
                onToggle,
                enabled = !busy,
                modifier = Modifier.testTag("provider-model-choice-$model").semantics { contentDescription = label },
            )
            Column(Modifier.weight(1f)) {
                Text(label)
                if (label != model) Text(model.take(12), style = MaterialTheme.typography.labelSmall)
                if (row.backendModels != null && model !in row.backendModels) {
                    Text(
                        stringResource(R.string.provider_models_not_listed),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            val defaultLabel = stringResource(R.string.provider_models_set_default)
            RadioButton(
                selection.defaultModel == model,
                onDefault,
                enabled = !busy,
                modifier =
                    Modifier.testTag("provider-model-default-$model").semantics {
                        contentDescription =
                            "$defaultLabel: $label"
                    },
            )
        }
        TextButton(onClick = onExpand, modifier = Modifier.testTag("provider-model-details-$model")) {
            Text(stringResource(R.string.provider_models_details))
        }
        if (expanded) {
            ModelVerificationSummary(row, model)
            TextButton(onClick = onContext, enabled = !busy) { Text(stringResource(R.string.chat_context_title)) }
            Row {
                TextButton(
                    onClick = onTest,
                    enabled = !busy,
                ) { Text(stringResource(R.string.provider_model_generation_test)) }
                TextButton(onClick = onProbe, enabled = !busy && row.chatSelectable) {
                    Text(stringResource(R.string.provider_capabilities_test))
                }
            }
            if (model in selection.models) {
                TextButton(onClick = onMove, enabled = !busy) {
                    Text(stringResource(R.string.provider_models_move_first))
                }
            }
            onUse?.let {
                TextButton(
                    onClick = it,
                    enabled = !busy,
                    modifier = Modifier.testTag("provider-model-use-$model"),
                ) { Text(stringResource(R.string.provider_models_save_use)) }
            }
        }
    }
}
