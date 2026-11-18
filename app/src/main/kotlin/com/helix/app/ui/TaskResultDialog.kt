package com.helix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.helix.feature.conversation.presentation.InterruptionNoticeLine
import com.helix.feature.conversation.presentation.MessageUi
import com.helix.ui.shared.MarkdownText
import com.helix.ui.shared.indicatedVerticalScroll
import kotlinx.coroutines.CancellationException
import com.helix.ui.shared.R as SharedR

@Composable
@Suppress("FunctionName", "SwallowedException", "TooGenericExceptionCaught")
internal fun TaskResultDialog(
    load: suspend (String) -> List<MessageUi>,
    collect: (String) -> Unit,
    turnId: String,
    allowCollect: Boolean = true,
    onDismiss: () -> Unit,
) {
    var result by remember(turnId) { mutableStateOf<List<MessageUi>?>(null) }
    var failed by remember(turnId) { mutableStateOf(false) }
    var retry by remember(turnId) { mutableStateOf(0) }
    var loaded by remember(turnId) { mutableStateOf(false) }
    var loading by remember(turnId) { mutableStateOf(true) }
    LaunchedEffect(turnId, retry) {
        loading = true
        try {
            result = load(turnId)
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
        loaded = true
        loading = false
    }
    BackHandler(enabled = !loaded, onBack = onDismiss)
    if (!loaded) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_task_result)) },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 440.dp).indicatedVerticalScroll(rememberScrollState())) {
                    if (failed) {
                        Text(stringResource(R.string.background_task_result_missing))
                        TextButton({ retry++ }, enabled = !loading, modifier = Modifier.testTag("task-result-retry")) {
                            Text(stringResource(SharedR.string.chat_retry))
                        }
                    }
                    result?.forEach { message ->
                        MarkdownText(message.content)
                        message.interruptionNotice?.let { InterruptionNoticeLine(it, turnId) }
                    }
                    if (result?.isEmpty() == true) Text(stringResource(R.string.background_task_result_empty))
                }
            }
        },
        confirmButton = {
            if (allowCollect) {
                TextButton(
                    enabled = result != null && !failed && !loading,
                    modifier = Modifier.testTag("task-collect-$turnId"),
                    onClick = {
                        collect(turnId)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.background_task_collect)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.goal_close)) } },
    )
}
