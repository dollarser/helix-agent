package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.GoalSummaryUi
import com.helix.core.model.GoalBudgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod") // Goal management is a single scrollable dialog surface.
internal fun GoalDialog(
    service: ChatService,
    prompt: String,
    onDismiss: () -> Unit,
    onContinued: () -> Unit,
    selectedGoalId: String? = null,
    onSettings: () -> Unit = {},
    onDeleteGoal: (suspend (String) -> Unit)? = null,
) {
    val screen by service.screen.collectAsStateWithLifecycle()
    val busy = screen.isSending
    var rows by remember { mutableStateOf<List<GoalSummaryUi>>(emptyList()) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val controlsBusy = busy || checking
    var continueError by remember { mutableStateOf(false) }
    LaunchedEffect(revision, busy, selectedGoalId) {
        rows = service.goalSummaries().filter { selectedGoalId == null || it.id == selectedGoalId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_manage)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.goal_explicit_continue))
                if (continueError) Text(stringResource(R.string.goal_continue_unavailable))
                rows.forEach { row ->
                    val heading = remember(row.id) { BringIntoViewRequester() }
                    ExpandableSummary(
                        row.objective,
                        modifier = Modifier.bringIntoViewRequester(heading),
                        style = MaterialTheme.typography.titleMedium,
                        tag = "goal-summary-${row.id}",
                        collapsedLines = 3,
                    )
                    Text(stringResource(goalStateLabel(row.status.state)), Modifier.testTag("goal-state-${row.id}"))
                    goalPauseLabel(row.status.outcome)?.let { Text(stringResource(it)) }
                    row.status.modelSummary?.let {
                        Text(stringResource(R.string.goal_model_report_label))
                        androidx.compose.foundation.text.selection
                            .SelectionContainer { Text(it) }
                    }
                    GoalBlockerControls(service, row) { revision++ }
                    Text(
                        stringResource(
                            R.string.goal_usage,
                            row.usage.modelCalls,
                            row.budgets.maxModelCalls,
                            row.usage.toolCalls,
                            row.budgets.maxToolCalls,
                            row.usage.tokens,
                            row.budgets.maxTotalTokens,
                            row.usage.millis / 1_000,
                            row.budgets.maxDurationMillis / 1_000,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.goal_wake_retry_summary,
                            row.budgets.maxWakeDurationMillis / 1_000,
                            row.budgets.maxRetries,
                        ),
                    )
                    GoalCriterionDescriptions(row.criteria)
                    TextButton(
                        enabled = row.canContinue && !controlsBusy,
                        onClick = {
                            scope.launch {
                                checking = true
                                continueError = false
                                try {
                                    service.continueGoal(row.id, prompt.ifBlank { row.objective })
                                    onContinued()
                                    onDismiss()
                                } catch (_: IllegalArgumentException) {
                                    continueError = true
                                } finally {
                                    checking = false
                                }
                            }
                        },
                        modifier =
                            Modifier.testTag(
                                "goal-continue-${row.id}",
                            ),
                    ) { Text(stringResource(R.string.goal_continue)) }
                    onDeleteGoal?.let { delete ->
                        GoalDeletionControls(row, delete) { revision++ }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onSettings()
                },
                modifier = Modifier.testTag("goal-settings"),
            ) { Text(stringResource(R.string.goal_settings_title)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("goal-close")) {
                Text(stringResource(R.string.goal_close))
            }
        },
    )
}

@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException") // Validation/state races use a fixed localized error.
internal fun GoalEditor(
    initialBudget: GoalBudgets,
    onDismiss: () -> Unit,
    onSave: suspend (GoalBudgets) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fields by remember {
        mutableStateOf(
            listOf(
                initialBudget.maxModelCalls.toString(),
                initialBudget.maxToolCalls.toString(),
                initialBudget.maxTotalTokens.toString(),
                (initialBudget.maxDurationMillis / 1_000).toString(),
                (initialBudget.maxWakeDurationMillis / 1_000).toString(),
                initialBudget.maxRetries.toString(),
            ),
        )
    }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val budgets = parseGoalBudgetFields(fields)
    val labels =
        listOf(
            R.string.goal_model_limit,
            R.string.goal_tool_limit,
            R.string.goal_token_limit,
            R.string.goal_duration_limit,
            R.string.goal_wake_limit,
            R.string.goal_retry_limit,
        )
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.goal_edit_budgets)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    OutlinedTextField(
                        fields[index],
                        { value ->
                            fields =
                                fields.toMutableList().also { it[index] = value }
                        },
                        enabled = !saving,
                        singleLine = true,
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.fillMaxWidth().testTag("goal-budget-$index"),
                    )
                }
                if (failed) {
                    Text(
                        stringResource(R.string.goal_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("goal-save-failed"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = budgets != null && !saving, onClick = {
                scope.launch {
                    saving = true
                    failed = false
                    try {
                        reportGoalSaveFailure({ failed = true }) {
                            onSave(requireNotNull(budgets))
                        }
                    } finally {
                        saving = false
                    }
                }
            }, modifier = Modifier.testTag("goal-save")) { Text(stringResource(R.string.goal_save)) }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onDismiss,
                modifier = Modifier.testTag("goal-editor-close"),
            ) { Text(stringResource(R.string.goal_close)) }
        },
    )
}

@Suppress("SwallowedException") // Validation/state errors become a localized UI message; cancellation propagates.
private suspend fun reportGoalSaveFailure(onFailure: () -> Unit, save: suspend () -> Unit) {
    try {
        save()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        onFailure()
    } catch (error: IllegalStateException) {
        onFailure()
    }
}

internal fun parseGoalBudgetFields(fields: List<String>): GoalBudgets? {
    val bounds =
        listOf(
            1..Int.MAX_VALUE.toLong(),
            1..Int.MAX_VALUE.toLong(),
            0..Long.MAX_VALUE,
            0..Long.MAX_VALUE / 1_000,
            0..Long.MAX_VALUE / 1_000,
            0..Int.MAX_VALUE.toLong(),
        )
    val values = fields.map(String::toLongOrNull)
    return if (values.size == bounds.size &&
        values.zip(bounds).all { (value, bound) -> value != null && value in bound }
    ) {
        val numbers = values.map(::requireNotNull)
        GoalBudgets(
            numbers[0].toInt(),
            numbers[1].toInt(),
            numbers[2],
            numbers[3] * 1_000,
            numbers[4] * 1_000,
            numbers[5].toInt(),
        )
    } else {
        null
    }
}

private fun goalStateLabel(state: String): Int =
    when (state) {
        "READY", "DRAFT" -> R.string.goal_state_ready
        "RUNNING" -> R.string.goal_state_running
        "PAUSED" -> R.string.goal_state_paused
        "BLOCKED" -> R.string.goal_state_blocked
        "INPUT_REQUIRED" -> R.string.goal_state_input
        "COMPLETED" -> R.string.goal_state_completed
        "FAILED" -> R.string.goal_state_failed
        else -> R.string.goal_state_cancelled
    }

private fun goalPauseLabel(outcome: String?): Int? =
    when {
        outcome == "USER_PAUSED" -> R.string.goal_user_paused
        outcome == "RUN_FINISHED" -> R.string.goal_pause_run_finished
        outcome == "INTERRUPTED" -> R.string.goal_pause_interrupted
        outcome?.startsWith("BUDGET_EXHAUSTED(") == true -> R.string.model_error_goal_budget_limit
        else -> null
    }

@Composable
@Suppress("FunctionName")
private fun GoalCriterionDescriptions(criteria: List<String>) {
    criteria.forEach { Text(it) }
}
