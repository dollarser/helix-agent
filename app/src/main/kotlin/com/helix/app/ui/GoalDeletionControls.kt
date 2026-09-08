package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.GoalSummaryUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "SwallowedException") // Show fixed localized errors for deletion/state races.
internal fun GoalDeletionControls(
    goal: GoalSummaryUi,
    delete: suspend (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember(goal.id) { mutableStateOf(false) }
    var deleting by remember(goal.id) { mutableStateOf(false) }
    var failed by remember(goal.id) { mutableStateOf(false) }
    TextButton(
        enabled = goal.canDelete && !deleting,
        onClick = { confirming = true },
        modifier = Modifier.testTag("goal-delete-${goal.id}"),
    ) { Text(stringResource(R.string.goal_delete)) }
    if (confirming) {
        GoalDeletionConfirmation(
            goal.objective,
            deleting,
            failed,
            onDismiss = { confirming = false },
            onConfirm = {
                scope.launch {
                    deleting = true
                    failed = false
                    try {
                        delete(goal.id)
                        confirming = false
                        onDeleted()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IllegalStateException) {
                        failed = true
                    } catch (error: IllegalArgumentException) {
                        failed = true
                    } finally {
                        deleting = false
                    }
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun GoalDeletionConfirmation(
    objective: String,
    deleting: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.goal_delete)) },
        text = {
            Column {
                Text(stringResource(R.string.goal_delete_description, objective))
                if (failed) {
                    Text(
                        stringResource(R.string.goal_delete_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("goal-delete-failed"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = onConfirm,
                modifier = Modifier.testTag("goal-delete-confirm"),
            ) { Text(stringResource(R.string.goal_delete)) }
        },
        dismissButton = {
            TextButton(
                enabled = !deleting,
                onClick = onDismiss,
                modifier = Modifier.testTag("goal-delete-cancel"),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
