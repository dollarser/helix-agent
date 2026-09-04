package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R

/**
 * The "后端可用模型" section of a passed provider row (HXA-059): the model list
 * carried out of the last PASSED connection test, rendered with a filter box
 * and clickable chips. Selecting a chip PREFILLS the edit form's model field
 * (never auto-saves). The list is display-capped at [MODELS_DISPLAY_CAP]; a
 * model id is an OPAQUE string (it may contain `/`, `-` or other characters,
 * e.g. SGLang's long path ids), so it is NEVER used in a test tag — the chips
 * are tagged by display index and matched by text.
 */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun BackendModelsSection(
    models: List<String>,
    onModelSelected: (modelId: String) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val needle = filter.trim()
    val filtered =
        if (needle.isEmpty()) {
            models
        } else {
            models.filter { it.contains(needle, ignoreCase = true) }
        }
    val visible = filtered.take(MODELS_DISPLAY_CAP)
    Column(
        modifier = Modifier.fillMaxWidth().testTag("provider-models-section"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.provider_models_available_count, models.size),
            style = MaterialTheme.typography.labelMedium,
        )
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text(stringResource(R.string.provider_models_filter_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("provider-models-filter"),
        )
        if (visible.isEmpty()) {
            Text(
                stringResource(R.string.provider_models_no_match),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visible.forEachIndexed { index, modelId ->
                    OutlinedButton(
                        onClick = { onModelSelected(modelId) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("provider-model-chip-$index"),
                    ) {
                        Text(
                            modelId,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (filtered.size > MODELS_DISPLAY_CAP) {
            Text(
                stringResource(R.string.provider_models_shown_summary, filtered.size, MODELS_DISPLAY_CAP),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The UI display cap for the HXA-059 backend model list (on top of the probe's
 * 1000-entry storage bound): keeps the row card from rendering an unbounded
 * chip grid; the full list still survives the filter box.
 */
internal const val MODELS_DISPLAY_CAP = 200
