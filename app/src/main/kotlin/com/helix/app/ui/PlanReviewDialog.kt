package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.helix.app.plan.PlanReview
import com.helix.core.model.GoalBudgets
import com.helix.core.storage.repository.PlanLifecycleState
import kotlinx.coroutines.launch

/** Same default run budgets as the goal-creation panel (GoalDialog) — plan execution creates a goal. */
private val PLAN_EXECUTION_BUDGETS = GoalBudgets(32, 64, 100_000, 600_000, 300_000, 0)

/**
 * The plan review surface (P0-B, doc section 12): shows the structured plan artifact
 * (objective / steps / acceptance criteria / risks — never a plan paragraph) and the user's
 * decisions: execute (approve + start the executing goal), keep planning, or cancel.
 */
@Composable
@Suppress("FunctionName", "SwallowedException") // a deleted plan just closes the dialog
internal fun PlanReviewDialog(
    service: ChatService,
    planId: String,
    onDone: () -> Unit,
) {
    var review by remember { mutableStateOf<PlanReview?>(null) }
    val screen by service.screen.collectAsStateWithLifecycle()

    LaunchedEffect(planId) {
        review =
            try {
                service.reviewPlan(planId)
            } catch (e: IllegalArgumentException) {
                null
            }
    }

    val plan =
        review ?: run {
            return
        }

    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan_review_title))
                Text(
                    stringResource(R.string.plan_review_version, plan.artifact.version),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .testTag("plan-review-${plan.artifact.id.value}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlanReviewSections(plan)
                screen.blockedReason?.let { Text(it) }
                PlanReviewActions(service, planId, plan.state, onDone)
            }
        },
        confirmButton = {
            TextButton(onDone, Modifier.testTag("plan-close-$planId")) {
                Text(stringResource(R.string.plan_close))
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun PlanReviewSections(plan: PlanReview) {
    Text(stringResource(R.string.plan_review_objective), style = MaterialTheme.typography.titleSmall)
    Text(plan.artifact.objective)
    Text(stringResource(R.string.plan_review_steps), style = MaterialTheme.typography.titleSmall)
    plan.artifact.steps.forEachIndexed { index, step ->
        Text("${index + 1}. ${step.title}")
        Text(step.description, style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.plan_review_criteria), style = MaterialTheme.typography.titleSmall)
    plan.artifact.acceptanceCriteria.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    if (plan.artifact.risks.isNotEmpty()) {
        Text(stringResource(R.string.plan_review_risks), style = MaterialTheme.typography.titleSmall)
        plan.artifact.risks.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
@Suppress("FunctionName")
private fun PlanReviewActions(
    service: ChatService,
    planId: String,
    state: PlanLifecycleState,
    onDone: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    if (state != PlanLifecycleState.READY) return
    val scope = rememberCoroutineScope()
    Row {
        TextButton(
            {
                busy = true
                scope.launch {
                    val executed =
                        runCatching {
                            val binding = service.approvePlan(planId)
                            service.executeApprovedPlan(binding, PLAN_EXECUTION_BUDGETS)
                        }.getOrNull()
                    busy = false
                    if (executed != null) onDone()
                }
            },
            enabled = !busy,
            modifier = Modifier.testTag("plan-execute-$planId"),
        ) { Text(stringResource(R.string.plan_execute)) }
        TextButton(
            {
                scope.launch {
                    service.revisePlan(planId)
                    onDone()
                }
            },
            enabled = !busy,
            modifier = Modifier.testTag("plan-revise-$planId"),
        ) { Text(stringResource(R.string.plan_revise)) }
        TextButton(
            {
                scope.launch {
                    service.cancelPlan(planId)
                    onDone()
                }
            },
            enabled = !busy,
            modifier = Modifier.testTag("plan-cancel-$planId"),
        ) { Text(stringResource(R.string.plan_cancel)) }
    }
}
