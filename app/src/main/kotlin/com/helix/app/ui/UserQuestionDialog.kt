package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.app.chat.UserQuestionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun UserQuestionDialog(container: AppContainer) {
    val service = container.userQuestions ?: return
    val screen by container.chatService.screen.collectAsStateWithLifecycle()
    var questions by remember { mutableStateOf(emptyList<UserQuestionService.Question>()) }
    LaunchedEffect(screen.openSessionId, screen.toolTimeline, screen.isSending) {
        questions = screen.openSessionId?.let { service.pending(it) }.orEmpty()
    }
    val question =
        questions.firstOrNull { it.sessionId == screen.openSessionId && screen.pendingDisclosure == null } ?: return
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable(
        question.id,
        stateSaver =
            listSaver(
                save = { it.toList() },
                restore = { it.toSet() },
            ),
    ) { mutableStateOf(emptySet<String>()) }
    var custom by rememberSaveable(question.id) { mutableStateOf("") }
    var sending by remember(question.id) { mutableStateOf(false) }
    var failed by remember(question.id) { mutableStateOf(false) }

    fun dismiss() {
        if (!sending) {
            scope.launch {
                service.dismiss(question)
                questions = service.pending(question.sessionId)
            }
        }
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.user_question_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(question.text)
                question.options.forEach { option ->
                    FilterChip(
                        selected = option in selected,
                        onClick = {
                            selected = toggleQuestionChoice(selected, option, question.multiple)
                        },
                        enabled = !sending,
                        label = { Text(option) },
                    )
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { if (it.length <= 4000) custom = it },
                    enabled = !sending,
                    label = { Text(stringResource(R.string.user_question_custom)) },
                )
                if (failed) Text(stringResource(R.string.user_question_failed))
            }
        },
        confirmButton = {
            TextButton(enabled = !sending && (selected.isNotEmpty() || custom.isNotBlank()), onClick = {
                sending = true
                scope.launch {
                    try {
                        failed = !service.answer(question, selected, custom, container.chatService)
                        questions = service.pending(question.sessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IllegalArgumentException) {
                        failed = true
                    } finally {
                        sending = false
                    }
                }
            }) { Text(stringResource(R.string.user_question_send)) }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = ::dismiss) { Text(stringResource(R.string.user_question_skip)) }
        },
    )
}

private fun toggleQuestionChoice(
    selected: Set<String>,
    option: String,
    multiple: Boolean,
): Set<String> =
    when {
        option in selected -> selected - option
        multiple -> selected + option
        else -> setOf(option)
    }
