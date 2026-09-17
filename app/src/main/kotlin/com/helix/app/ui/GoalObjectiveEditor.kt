package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
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
import com.helix.app.chat.ChatService
import com.helix.app.chat.GoalSummaryUi
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName")
internal fun GoalObjectiveEditor(
    service: ChatService,
    row: GoalSummaryUi,
    busy: Boolean,
    changed: () -> Unit,
) {
    var editing by remember(row.id) { mutableStateOf(false) }
    var text by remember(row.id, row.revision) { mutableStateOf(row.objective) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (!row.canEditObjective) return
    if (editing) {
        Column {
            OutlinedTextField(
                text,
                onValueChange = { text = it },
                enabled = !busy && !saving,
                label = { Text(stringResource(R.string.goal_edit_objective)) },
                modifier = Modifier.testTag("goal-objective-${row.id}"),
            )
            if (failed) Text(stringResource(R.string.goal_save_failed))
            TextButton(enabled = !busy && !saving && text.isNotBlank(), onClick = {
                scope.launch {
                    saving = true
                    try {
                        failed = !service.editGoalObjective(row.id, row.revision, text)
                        if (!failed) {
                            editing = false
                            changed()
                        }
                    } finally {
                        saving = false
                    }
                }
            }, modifier = Modifier.testTag("goal-objective-save-${row.id}")) {
                Text(stringResource(R.string.goal_save))
            }
        }
    } else {
        TextButton(
            onClick = { editing = true },
            enabled = !busy,
            modifier = Modifier.testTag("goal-objective-edit-${row.id}"),
        ) {
            Text(stringResource(R.string.goal_edit_objective))
        }
    }
}
