package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.runcontrol.RunControlStore
import com.helix.app.runcontrol.TurnBudgetBounds
import com.helix.core.model.TurnBudgets

/** Bounded, profile-independent Turn budget editor (HXA-099). */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun RunControlSettingsSection(store: RunControlStore) {
    val config by store.flow.collectAsStateWithLifecycle()
    var steps by remember(config.budgets) { mutableStateOf(config.budgets.maxSteps.toString()) }
    var calls by remember(config.budgets) { mutableStateOf(config.budgets.maxModelCalls.toString()) }
    var input by remember(config.budgets) { mutableStateOf(config.budgets.maxInputTokens.toString()) }
    var output by remember(config.budgets) { mutableStateOf(config.budgets.maxOutputTokens.toString()) }
    var total by remember(config.budgets) { mutableStateOf(config.budgets.maxTotalTokens.toString()) }
    var invalid by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var automaticInput by remember(config.budgets) {
        mutableStateOf(config.budgets.maxInputTokens == TurnBudgetBounds.MAX_INPUT_TOKENS)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("settings-turn-budgets")) {
        Text(stringResource(R.string.settings_turn_budgets_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_turn_budgets_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.budget_total_help))
        BudgetField(total, {
            total = it
            saved = false
        }, R.string.settings_budget_total, "budget-total")
        if (config.budgets != TurnBudgetBounds.DEFAULT) Text(stringResource(R.string.budget_custom_active))
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("budget-advanced")) {
            Text(stringResource(R.string.budget_advanced))
        }
        if (advanced) {
            Row {
                Switch(checked = automaticInput, onCheckedChange = {
                    automaticInput = it
                    saved = false
                }, modifier = Modifier.testTag("budget-auto-input"))
                Text(stringResource(R.string.budget_auto_input))
            }
            Text(stringResource(R.string.budget_input_help))
            if (!automaticInput) {
                BudgetField(input, {
                    input = it
                    saved = false
                }, R.string.settings_budget_input, "budget-input")
            }
            BudgetField(output, {
                output = it
                saved = false
            }, R.string.settings_budget_output, "budget-output")
            BudgetField(steps, {
                steps = it
                saved = false
            }, R.string.settings_budget_steps, "budget-steps")
            BudgetField(calls, {
                calls = it
                saved = false
            }, R.string.settings_budget_calls, "budget-calls")
            Text(stringResource(R.string.budget_calls_help))
        }
        TextButton(
            onClick = {
                val defaults = TurnBudgetBounds.DEFAULT
                store.setBudgets(defaults)
                steps = defaults.maxSteps.toString()
                calls = defaults.maxModelCalls.toString()
                input = defaults.maxInputTokens.toString()
                output = defaults.maxOutputTokens.toString()
                total = defaults.maxTotalTokens.toString()
                automaticInput = true
                invalid = false
                saved = true
            },
            modifier = Modifier.testTag("budget-recommended"),
        ) { Text(stringResource(R.string.budget_recommended)) }
        if (invalid) {
            Text(
                stringResource(R.string.settings_turn_budgets_invalid),
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = {
                invalid =
                    try {
                        store.setBudgets(
                            TurnBudgets(
                                steps.toInt(),
                                calls.toInt(),
                                if (automaticInput) TurnBudgetBounds.MAX_INPUT_TOKENS else input.toLong(),
                                output.toLong(),
                                total.toLong(),
                            ),
                        )
                        saved = true
                        false
                    } catch (_: IllegalArgumentException) {
                        true
                    }
            },
            modifier = Modifier.testTag("budget-save"),
        ) { Text(stringResource(R.string.common_save)) }
        if (saved && !invalid) Text(stringResource(R.string.budget_saved), modifier = Modifier.testTag("budget-saved"))
    }
}

@Composable
@Suppress("FunctionName")
private fun BudgetField(
    value: String,
    update: (String) -> Unit,
    label: Int,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate -> if (candidate.all(Char::isDigit)) update(candidate) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
