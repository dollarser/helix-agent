package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.TurnRecoveryPanelUi

/**
 * HXA-204 slice 2: one settled turn's recovery panel, rendered in the turn's error area. Every
 * line is a persisted fact from the slice-1 projection; every button has a stable identity and
 * its operation re-checks its own admission at execution time (the panel may be stale —
 * duplicate taps, late results, repairs made elsewhere).
 */
@Composable
@Suppress("FunctionName")
fun TurnRecoveryPanel(
    panel: TurnRecoveryPanelUi,
    busy: Set<String>,
    onReconnect: (String) -> Unit,
    onQueryResult: (String) -> Unit,
    onGrantPermission: (String) -> Unit,
    onContinueGoal: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("recovery-panel-${panel.turnId}")
                .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RecoveryPanelFacts(panel)
        RecoveryOperationButtons(
            panel = panel,
            busy = busy,
            onReconnect = onReconnect,
            onQueryResult = onQueryResult,
            onGrantPermission = onGrantPermission,
            onContinueGoal = onContinueGoal,
            onRetry = onRetry,
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun RecoveryPanelFacts(panel: TurnRecoveryPanelUi) {
    val turnId = panel.turnId
    val summary = panel.summary
    val reason =
        when {
            summary.blockClass == RecoveryBlockClass.RESULT_UNKNOWN -> {
                stringResource(R.string.recovery_reason_unknown) to true
            }

            panel.turnState == "CANCELLED" -> {
                stringResource(R.string.recovery_reason_cancelled) to false
            }

            else -> {
                null
            }
        }
    val rows =
        listOfNotNull(
            summary.completedActions.takeIf { it.isNotEmpty() }?.let {
                "recovery-completed-$turnId" to stringResource(R.string.recovery_completed, toolNames(it))
            },
            summary.deniedActions.takeIf { it.isNotEmpty() }?.let {
                "recovery-denied-$turnId" to stringResource(R.string.recovery_denied, toolNames(it))
            },
            summary.pendingReview.takeIf { it.isNotEmpty() }?.let {
                "recovery-pending-$turnId" to stringResource(R.string.recovery_pending, toolNames(it))
            },
            summary.artifactIds.takeIf { it.isNotEmpty() }?.let {
                "recovery-artifacts-$turnId" to stringResource(R.string.recovery_artifacts, it.size)
            },
            summary.nextOperation?.let {
                "recovery-next-$turnId" to
                    stringResource(R.string.recovery_next, OperationLabel(it, summary.budgetContinuationEligible))
            },
        )
    reason?.let { (text, isError) ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.testTag("recovery-reason-$turnId"),
        )
    }
    rows.forEach { (tag, text) ->
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun RecoveryOperationButtons(
    panel: TurnRecoveryPanelUi,
    busy: Set<String>,
    onReconnect: (String) -> Unit,
    onQueryResult: (String) -> Unit,
    onGrantPermission: (String) -> Unit,
    onContinueGoal: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        panel.summary.operations.forEach { operation ->
            RecoveryOperationButton(
                panel = panel,
                operation = operation,
                busy = "${panel.turnId}:${operation.name}" in busy,
                onAction =
                    when (operation) {
                        RecoveryOperation.RECONNECT -> onReconnect
                        RecoveryOperation.QUERY_RESULT -> onQueryResult
                        RecoveryOperation.GRANT_PERMISSION -> onGrantPermission
                        RecoveryOperation.CONTINUE_GOAL -> onContinueGoal
                        RecoveryOperation.RETRY_NEW_CALL -> onRetry
                    },
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun RecoveryOperationButton(
    panel: TurnRecoveryPanelUi,
    operation: RecoveryOperation,
    busy: Boolean,
    onAction: (String) -> Unit,
) {
    val turnId = panel.turnId
    when (operation) {
        RecoveryOperation.RECONNECT -> {
            RecoveryButton("recovery-reconnect-$turnId", R.string.recovery_btn_reconnect, busy) {
                onAction(turnId)
            }
        }

        RecoveryOperation.QUERY_RESULT -> {
            RecoveryButton("recovery-query-$turnId", R.string.recovery_btn_query, busy) { onAction(turnId) }
        }

        RecoveryOperation.GRANT_PERMISSION -> {
            RecoveryButton("recovery-grant-$turnId", R.string.recovery_btn_grant, busy) { onAction(turnId) }
        }

        // Admission already implies a bound, continuable Goal — never offer it without identity.
        RecoveryOperation.CONTINUE_GOAL -> {
            if (panel.goalId != null) {
                RecoveryButton("recovery-continue-$turnId", R.string.recovery_btn_continue, busy) {
                    onAction(turnId)
                }
            }
        }

        // Only the single admitted retry target renders this; the tag stays `chat-retry`
        // (the pre-panel button's identity) so existing tests and users find it unchanged.
        RecoveryOperation.RETRY_NEW_CALL -> {
            if (panel.retryAllowed) {
                RecoveryButton(
                    "chat-retry",
                    if (panel.summary.budgetContinuationEligible) {
                        R.string.budget_continue
                    } else {
                        R.string.chat_retry
                    },
                    busy,
                ) {
                    onAction(turnId)
                }
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun RecoveryButton(
    tag: String,
    labelRes: Int,
    busy: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = !busy, modifier = Modifier.testTag(tag)) {
        Text(stringResource(labelRes))
    }
}

@Suppress("FunctionName")
@Composable
private fun OperationLabel(
    operation: RecoveryOperation,
    budgetContinuationEligible: Boolean,
): String =
    when (operation) {
        RecoveryOperation.RECONNECT -> {
            stringResource(R.string.recovery_btn_reconnect)
        }

        RecoveryOperation.QUERY_RESULT -> {
            stringResource(R.string.recovery_btn_query)
        }

        RecoveryOperation.GRANT_PERMISSION -> {
            stringResource(R.string.recovery_btn_grant)
        }

        RecoveryOperation.CONTINUE_GOAL -> {
            stringResource(R.string.recovery_btn_continue)
        }

        RecoveryOperation.RETRY_NEW_CALL -> {
            stringResource(
                if (budgetContinuationEligible) {
                    R.string.budget_continue
                } else {
                    R.string.chat_retry
                },
            )
        }
    }

private fun toolNames(calls: List<ToolCallFact>): String = calls.joinToString(", ") { it.toolName }
