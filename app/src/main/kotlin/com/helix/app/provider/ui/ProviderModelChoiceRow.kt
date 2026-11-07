package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.helix.app.R
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderRowUi
import com.helix.provider.api.CapabilitySource
import com.helix.feature.conversation.R as ConversationR

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
        if (window == null) {
            stringResource(R.string.context_server_window_unreported)
        } else {
            stringResource(R.string.context_server_window, window.toString())
        },
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
    onExpand: () -> Unit,
    onContext: () -> Unit,
    onTest: () -> Unit,
    onProbe: () -> Unit,
    onMove: () -> Unit,
) {
    val label = row.modelLabel(model)
    val purposes = row.purposesFor(model)
    val canChoose = !row.conversationProhibited(model)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("provider-model-choice-$model")
                    .toggleable(
                        model in selection.models,
                        enabled = !busy && (canChoose || model in selection.models),
                        role = Role.Checkbox,
                        onValueChange = onToggle,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                model in selection.models,
                null,
                enabled = !busy && (canChoose || model in selection.models),
            )
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(
                    stringResource(
                        purposeLabel(purposes),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (label != model) Text(model, style = MaterialTheme.typography.labelSmall)
                if (row.backendModels != null && model !in row.backendModels) {
                    Text(
                        stringResource(R.string.provider_models_not_listed),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        TextButton(onClick = onExpand, modifier = Modifier.testTag("provider-model-details-$model")) {
            Text(stringResource(R.string.provider_models_details))
        }
        if (expanded) {
            ModelVerificationSummary(row, model)
            Column {
                TextButton(
                    onClick = onTest,
                    enabled = !busy && canChoose,
                ) { Text(stringResource(R.string.provider_model_generation_test)) }
                TextButton(onClick = onProbe, enabled = !busy && row.chatSelectable) {
                    Text(stringResource(R.string.provider_capabilities_test))
                }
            }
            TextButton(
                onClick = onContext,
                enabled = !busy,
            ) { Text(stringResource(ConversationR.string.chat_context_title)) }
            if (model in selection.models) {
                TextButton(onClick = onMove, enabled = !busy) {
                    Text(stringResource(R.string.provider_models_move_first))
                }
            }
        }
    }
}

private fun purposeLabel(purposes: Set<com.helix.provider.api.ModelPurpose>): Int =
    when {
        purposes.isEmpty() -> R.string.provider_model_purpose_unknown
        com.helix.provider.api.ModelPurpose.CHAT in purposes -> R.string.provider_model_purpose_chat
        else -> R.string.provider_model_purpose_other
    }
