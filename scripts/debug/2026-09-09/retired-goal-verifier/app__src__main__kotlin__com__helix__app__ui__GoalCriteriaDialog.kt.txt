package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import com.helix.app.goal.GoalCriteriaSnapshot
import com.helix.core.agent.Criterion
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException")
internal fun GoalCriteriaDialog(
    service: ChatService,
    goalId: String,
    onDismiss: () -> Unit,
) {
    var snapshot by remember(goalId) { mutableStateOf<GoalCriteriaSnapshot?>(null) }
    var selected by remember(goalId) { mutableStateOf<Criterion?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(goalId, revision) {
        try {
            snapshot = service.goalCriteria(goalId)
            failed = false
        } catch (_: IllegalArgumentException) {
            failed = true
        }
    }
    val editing = selected
    if (viewing != null) {
        GoalEvidenceDialog(service, goalId, requireNotNull(viewing), snapshot?.editable == true) {
            viewing = null
            revision++
        }
    } else if (editing != null) {
        GoalCriterionBindingEditor(editing, { selected = null }) { description, binding ->
            service.bindGoalCriterion(goalId, editing, description, binding)
            selected = null
            revision++
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.goal_criterion_manage)) },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (failed) Text(stringResource(R.string.goal_criterion_error), Modifier.testTag("criterion-error"))
                    snapshot?.criteria?.forEach { criterion ->
                        Text(criterion.description, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(criterionMethodLabel(criterion.binding?.method)))
                        criterion.binding
                            ?.argument
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { Text(it) }
                        Text(
                            stringResource(
                                when {
                                    criterion.pendingReview != null -> R.string.goal_criterion_pending
                                    criterion.isSatisfied -> R.string.goal_criterion_recorded
                                    else -> R.string.goal_criterion_unverified
                                },
                            ),
                        )
                        CriterionFailureNotice(criterion, snapshot)
                        TextButton(
                            onClick = { viewing = criterion.id },
                            modifier = Modifier.testTag("goal-evidence-${criterion.id}"),
                        ) {
                            Text(stringResource(R.string.goal_evidence_view))
                        }
                        TextButton(
                            onClick = { selected = criterion },
                            enabled = snapshot?.editable == true,
                            modifier = Modifier.testTag("goal-bind-${criterion.id}"),
                        ) {
                            Text(stringResource(R.string.goal_criterion_edit))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("criteria-close")) {
                    Text(stringResource(R.string.goal_close))
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException")
internal fun GoalCriterionBindingEditor(
    initial: Criterion,
    onDismiss: () -> Unit,
    onSave: suspend (String, CriterionVerificationBinding?) -> Unit,
) {
    var description by remember(initial) { mutableStateOf(initial.description) }
    var method by remember(initial) { mutableStateOf(initial.binding?.method) }
    var argument by remember(initial) { mutableStateOf(initial.binding?.argument ?: "") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val binding = parseCriterionBinding(method, argument)
    val valid = validCriterionForm(description, method, binding)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.goal_criterion_edit)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    description,
                    { description = it },
                    enabled = !saving,
                    label = { Text(stringResource(R.string.goal_criteria)) },
                    modifier = Modifier.testTag("criterion-description"),
                )
                (listOf(null) + CriterionVerificationMethod.entries).forEach { option ->
                    TextButton(
                        onClick = {
                            method = option
                            argument = ""
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("criterion-method-${option?.name ?: "NONE"}"),
                    ) {
                        Text((if (method == option) "✓ " else "") + stringResource(criterionMethodLabel(option)))
                    }
                }
                if (method != null && method != CriterionVerificationMethod.MANUAL_REVIEW) {
                    OutlinedTextField(
                        argument,
                        { argument = it },
                        enabled = !saving,
                        label = { Text(stringResource(R.string.goal_criterion_argument)) },
                        modifier = Modifier.testTag("criterion-argument"),
                    )
                }
                Text(stringResource(R.string.goal_criterion_binding_hint))
                if (method == CriterionVerificationMethod.LOCAL_TOOL_SUCCESS) {
                    Text(stringResource(R.string.goal_criterion_tool_limit))
                }
                if (failed) Text(stringResource(R.string.goal_criterion_error), Modifier.testTag("criterion-error"))
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !saving, modifier = Modifier.testTag("criterion-save"), onClick = {
                scope.launch {
                    saving = true
                    failed = false
                    try {
                        onSave(description, binding)
                    } catch (
                        _: IllegalArgumentException,
                    ) {
                        failed = true
                    } finally {
                        saving = false
                    }
                }
            }) { Text(stringResource(R.string.goal_criterion_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.goal_close)) }
        },
    )
}

@Suppress("SwallowedException") // Invalid form values disable Save; no domain error details escape.
private fun parseCriterionBinding(
    method: CriterionVerificationMethod?,
    argument: String,
): CriterionVerificationBinding? =
    try {
        method?.let {
            CriterionVerificationBinding(
                it,
                if (it ==
                    CriterionVerificationMethod.MANUAL_REVIEW
                ) {
                    ""
                } else {
                    argument
                },
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    }

private fun criterionMethodLabel(method: CriterionVerificationMethod?): Int =
    when (method) {
        null -> R.string.goal_criterion_unbound
        CriterionVerificationMethod.ARTIFACT_SHA256 -> R.string.goal_criterion_sha
        CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS -> R.string.goal_criterion_literal
        CriterionVerificationMethod.LOCAL_TOOL_SUCCESS -> R.string.goal_criterion_tool
        CriterionVerificationMethod.MANUAL_REVIEW -> R.string.goal_criterion_manual
    }

private fun validCriterionForm(
    description: String,
    method: CriterionVerificationMethod?,
    binding: CriterionVerificationBinding?,
): Boolean =
    description.isNotBlank() && description.length <= Criterion.MAX_DESCRIPTION_LENGTH &&
        (method == null || binding != null)

@Composable
@Suppress("FunctionName")
private fun CriterionFailureNotice(
    criterion: Criterion,
    snapshot: GoalCriteriaSnapshot?,
) {
    val failure = snapshot?.failures?.get(criterion.id)
    if (!criterion.isSatisfied && criterion.pendingReview == null && failure != null) {
        Text(stringResource(goalEvidenceFailureLabel(failure)), Modifier.testTag("criterion-invalid-${criterion.id}"))
    }
}
