package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.helix.app.chat.ChatService
import com.helix.app.chat.GoalSummaryUi
import com.helix.app.runcontrol.GoalBudgetDefaults
import com.helix.app.runcontrol.RunControlStore

/** Default policy and explicit parked-Goal extensions live in settings, never the composer. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun GoalSettingsSection(
    store: RunControlStore,
    service: ChatService? = null,
) {
    val config by store.flow.collectAsStateWithLifecycle()
    val screen = service?.screen?.collectAsStateWithLifecycle()?.value
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<GoalSummaryUi?>(null) }
    var rows by remember { mutableStateOf<List<GoalSummaryUi>>(emptyList()) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(service, revision, screen?.openSessionId, screen?.isSending) {
        rows =
            service?.goalSummaries().orEmpty()
    }
    Column(Modifier.testTag("settings-goal-budgets"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.goal_settings_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.goal_settings_note), style = MaterialTheme.typography.bodySmall)
        val budgets = config.goalBudgets
        Text(
            stringResource(
                R.string.goal_defaults_summary,
                budgets.maxModelCalls,
                budgets.maxToolCalls,
                budgets.maxTotalTokens,
                budgets.maxDurationMillis / 60_000,
                budgets.maxWakeDurationMillis / 60_000,
            ),
        )
        SettingsActions {
            OutlinedButton({
                selected = null
                open = true
            }, Modifier.testTag("goal-defaults-edit")) {
                Text(stringResource(R.string.goal_edit_budgets))
            }
            OutlinedButton(
                { store.setGoalBudgets(GoalBudgetDefaults.VALUE) },
                Modifier.testTag("goal-defaults-reset"),
            ) {
                Text(stringResource(R.string.goal_defaults_reset))
            }
        }
        if (service != null) {
            rows
                .filter {
                    it.status.state in
                        setOf(
                            "READY",
                            "RUNNING",
                            "PAUSED",
                            "INPUT_REQUIRED",
                            "BLOCKED",
                        )
                }.forEach { row ->
                    ExpandableSummary(
                        row.objective,
                        MaterialTheme.typography.bodyMedium,
                        "settings-goal-${row.id}",
                        collapsedLines = 2,
                    )
                    OutlinedButton({
                        selected = row
                        open = true
                    }, Modifier.testTag("goal-edit-budgets-${row.id}"), enabled = row.canEditBudgets) {
                        Text(stringResource(R.string.goal_edit_budgets))
                    }
                    GoalReminderControls(service, row) { revision++ }
                }
        }
    }
    if (open) {
        GoalEditor(selected?.budgets ?: config.goalBudgets, { open = false }) { budgets ->
            val row = selected
            if (row == null) {
                store.setGoalBudgets(budgets)
            } else {
                check(requireNotNull(service).updateGoalBudgets(row.id, budgets, row.revision))
            }
            open = false
            revision++
        }
    }
}
