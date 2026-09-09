package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.MessageUi

@Composable
@Suppress("FunctionName", "SwallowedException")
internal fun TaskResultDialog(
    service: ChatService,
    turnId: String,
    onDismiss: () -> Unit,
) {
    var result by remember(turnId) { mutableStateOf<List<MessageUi>?>(null) }
    var failed by remember(turnId) { mutableStateOf(false) }
    LaunchedEffect(turnId) {
        try {
            result = service.taskResult(turnId)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_task_result)) },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (failed) Text(stringResource(R.string.background_task_result_missing))
                    result?.forEach { MarkdownText(it.content) }
                    if (result?.isEmpty() == true) Text(stringResource(R.string.background_task_result_empty))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = result != null && !failed,
                modifier = Modifier.testTag("task-collect-$turnId"),
                onClick = {
                    service.collectTaskResult(turnId)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.background_task_collect)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.goal_close)) } },
    )
}
