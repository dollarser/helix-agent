package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.runcontrol.RunControlStore
import com.helix.core.agent.TurnBudgetBounds

/** Bounded, profile-independent Turn budget editor (HXA-099). */
@Composable
@Suppress("FunctionName")
internal fun RunControlSettingsSection(store: RunControlStore) {
    val config by store.flow.collectAsStateWithLifecycle()
    RunControlOutputEditor(config.budgets.maxOutputTokens) { value ->
        store.setBudgets(config.budgets.copy(maxOutputTokens = value))
    }
}

@Composable
@Suppress("FunctionName")
internal fun RunControlOutputEditor(
    limit: Long,
    onSave: (Long) -> Unit,
) {
    var output by rememberSaveable(limit) { mutableStateOf(limit.toString()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("settings-turn-budgets")) {
        Text(stringResource(R.string.settings_turn_budgets_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_turn_budgets_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.budget_observation_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BudgetField(output, {
            output = it
            invalid = false
            saved = false
        }, invalid, R.string.settings_budget_output, "budget-output")
        Button(
            onClick = {
                invalid =
                    try {
                        val value = output.toLong()
                        require(value in 1..TurnBudgetBounds.MAX_OUTPUT_TOKENS)
                        onSave(value)
                        saved = true
                        false
                    } catch (_: IllegalArgumentException) {
                        true
                    }
            },
            modifier = Modifier.fillMaxWidth().testTag("budget-save"),
        ) { Text(stringResource(R.string.common_save)) }
        if (saved && !invalid) Text(stringResource(R.string.budget_saved), modifier = Modifier.testTag("budget-saved"))
    }
}

@Composable
@Suppress("FunctionName")
private fun BudgetField(
    value: String,
    update: (String) -> Unit,
    invalid: Boolean,
    label: Int,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate -> if (candidate.all(Char::isDigit)) update(candidate) },
        label = { Text(stringResource(label)) },
        isError = invalid,
        supportingText = {
            Text(
                stringResource(
                    if (invalid) R.string.settings_turn_budgets_invalid else R.string.settings_budget_output_range,
                ),
                Modifier.testTag(if (invalid) "budget-error" else "budget-range"),
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
