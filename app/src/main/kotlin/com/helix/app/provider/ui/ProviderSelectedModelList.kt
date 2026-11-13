package com.helix.app.provider.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi

/** Persisted choices stay visible; verification details are disclosed per model. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ProviderSelectedModelList(
    row: ProviderRowUi,
    busy: Boolean,
    actions: ProviderRowActions,
) {
    var expanded by rememberSaveable(row.id, row.provisioning) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("provider-selected-models-${row.id}")) {
        Text(
            if (row.conversationModels.isEmpty()) {
                stringResource(R.string.provider_models_empty_short)
            } else {
                stringResource(R.string.provider_models_selected_count, row.conversationModels.size)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.conversationModels.forEachIndexed { index, model ->
            androidx.compose.runtime.key(row.id, model) {
                val open = expanded == model
                Column(
                    Modifier.fillMaxWidth().testTag("provider-selected-model-$model"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("provider-selected-model-toggle-$model")
                            .clickable(role = Role.Button) { expanded = if (open) null else model }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.modelLabel(model), style = MaterialTheme.typography.titleSmall)
                            if (row.modelLabel(model) != model) {
                                Text(
                                    model,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (row.modelGenerations[model]?.failure != null ||
                                row.modelVerifications[model]?.failure != null
                            ) {
                                Text(
                                    stringResource(R.string.provider_model_verification_issue),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Icon(
                            painterResource(R.drawable.ic_drawer_chevron),
                            stringResource(
                                if (open) R.string.provider_model_collapse else R.string.provider_models_details,
                            ),
                            Modifier.rotate(if (open) 270f else 90f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (open) {
                        ModelVerificationSummary(row, model)
                        ProviderModelVerificationActions(
                            row,
                            model,
                            busy,
                            true,
                            !row.conversationProhibited(model),
                            onTest = { actions.onTestModel(model) },
                            onProbe = { actions.onDetectCapabilities(model) },
                            onContext = { actions.onContext(model) },
                        )
                    }
                }
                if (index <
                    row.conversationModels.lastIndex
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
