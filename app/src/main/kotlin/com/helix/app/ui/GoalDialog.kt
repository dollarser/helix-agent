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
    busy: Boolean = false,
    selectedGoalId: String? = null,
    onDeleteGoal: (suspend (String) -> Unit)? = null,
) {
    var rows by remember { mutableStateOf<List<GoalSummaryUi>>(emptyList()) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val controlsBusy = busy || checking
    var continueError by remember { mutableStateOf(false) }
    var evidenceGoal by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GoalSummaryUi?>(null) }
    LaunchedEffect(revision, busy, selectedGoalId) {
        rows = service.goalSummaries().filter { selectedGoalId == null || it.id == selectedGoalId }
    }
    if (evidenceGoal != null) {
        GoalCriteriaDialog(service, requireNotNull(evidenceGoal)) {
            evidenceGoal = null
            revision++
        }
    } else if (creating || editing != null) {
        GoalEditor(
            initial = editing,
            prompt = prompt,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { objective, criteria, budgets ->
                val row = editing
                if (row == null) {
                    service.createGoal(objective, criteria, budgets)
                } else {
                    check(service.updateGoalBudgets(row.id, budgets))
                }
                creating = false
                editing = null
                revision++
            },
        )
    } else {
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
                        Text(stringResource(R.string.goal_criteria_progress, row.satisfiedCriteria, row.criteria.size))
                        GoalCriterionDescriptions(row.criteria)
                        TextButton(
                            onClick = { evidenceGoal = row.id },
                            enabled = !controlsBusy,
                            modifier = Modifier.testTag("goal-criteria-${row.id}"),
                        ) {
                            Text(stringResource(R.string.goal_criterion_manage))
                        }
                        TextButton(
                            enabled = row.canContinue && !controlsBusy,
                            onClick = {
                                scope.launch {
                                    checking = true
                                    continueError = false
                                    try {
                                        if (service.completeGoalFromEvidence(row.id)) {
                                            revision++
                                            heading.bringIntoView()
                                        } else {
                                            service.continueGoal(row.id, prompt.ifBlank { row.objective })
                                            onContinued()
                                            onDismiss()
                                        }
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
                        TextButton(
                            enabled = row.canEditBudgets,
                            onClick = { editing = row },
                            modifier = Modifier.testTag("goal-edit-budgets-${row.id}"),
                        ) {
                            Text(stringResource(R.string.goal_edit_budgets))
                        }
                        GoalReminderControls(service, row) { revision++ }
                        onDeleteGoal?.let { delete ->
                            GoalDeletionControls(row, delete) { revision++ }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { creating = true },
                    modifier = Modifier.testTag("goal-create"),
                ) { Text(stringResource(R.string.goal_create)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("goal-close")) {
                    Text(stringResource(R.string.goal_close))
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException") // Validation/state races use a fixed localized error.
internal fun GoalEditor(
    initial: GoalSummaryUi?,
    prompt: String,
    onDismiss: () -> Unit,
    onSave: suspend (String, List<String>, GoalBudgets) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var objective by remember { mutableStateOf(initial?.objective ?: prompt) }
    var criteria by remember { mutableStateOf(initial?.criteria?.joinToString("\n") ?: "") }
    val initialBudget = initial?.budgets ?: GoalBudgets(32, 64, 100_000, 600_000, 300_000, 0)
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
    val criterionList = criteria.lines().map(String::trim).filter(String::isNotEmpty)
    val valid = validGoalDescription(objective, criterionList)
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
        title = { Text(stringResource(if (initial == null) R.string.goal_create else R.string.goal_edit_budgets)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    objective,
                    { objective = it },
                    enabled = initial == null && !saving,
                    label = { Text(stringResource(R.string.goal_objective)) },
                    modifier = Modifier.testTag("goal-objective"),
                )
                OutlinedTextField(
                    criteria,
                    { criteria = it },
                    enabled = initial == null && !saving,
                    label = { Text(stringResource(R.string.goal_criteria)) },
                    modifier = Modifier.testTag("goal-criteria"),
                )
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
            TextButton(enabled = valid && budgets != null && !saving, onClick = {
                scope.launch {
                    saving = true
                    failed = false
                    try {
                        reportGoalSaveFailure({ failed = true }) {
                            onSave(objective.trim(), criterionList, requireNotNull(budgets))
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

private fun validGoalDescription(
    objective: String,
    criteria: List<String>,
): Boolean = objective.trim().length in 1..1024 && criteria.size in 1..32 && criteria.all { it.length <= 1024 }

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
        "INPUT_REQUIRED" -> R.string.goal_state_input
        "COMPLETED" -> R.string.goal_state_completed
        "FAILED" -> R.string.goal_state_failed
        else -> R.string.goal_state_cancelled
    }

private fun goalPauseLabel(outcome: String?): Int? =
    when {
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
