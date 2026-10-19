package com.helix.app.reminder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.provider.ConfirmedTextAnalysis
import com.helix.app.provider.ProviderService
import com.helix.core.model.ProviderResidence
import com.helix.core.storage.reminder.LocalReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
// Failure/cancellation preserves text and never calls the reminder persistence/delivery service.
internal fun ReminderInterpretScreen(
    providers: ProviderService?,
    onBack: () -> Unit,
    onProposal: (LocalReminder) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rows =
        providers
            ?.rows
            ?.collectAsStateWithLifecycle()
            ?.value
            .orEmpty()
    var text by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Pair<String, String>?>(null) }
    var choose by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PreparedReminderInterpretation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var interrupted by remember { mutableStateOf(false) }
    var clarification by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    BackHandler {
        job?.cancel()
        onBack()
    }

    fun prepare() {
        if (busy) return
        val selected = requireNotNull(target)
        val description = text
        busy = true
        failed = false
        interrupted = false
        clarification = ""
        job =
            scope.launch {
                try {
                    val interpreter = ReminderInterpreter(ConfirmedTextAnalysis(requireNotNull(providers)))
                    pending =
                        interpreter.prepare(
                            description,
                            Instant.now(),
                            ZoneId.systemDefault(),
                            selected.first,
                            selected.second,
                        )
                } catch (cancel: CancellationException) {
                    interrupted = true
                    throw cancel
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
    }
    if (choose) {
        AlertDialog(onDismissRequest = {
            choose = false
        }, title = { Text(stringResource(R.string.knowledge_choose_model)) }, text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                rows.filter { !it.managedExternally }.forEach { row ->
                    items(row.conversationModels.filter(row::modelSelectable)) { model ->
                        TextButton(onClick = {
                            target = row.id to model
                            choose = false
                        }) {
                            Text("${row.displayName} · ${row.modelLabel(model)}\n${row.origin}")
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(
                onClick = { choose = false },
            ) { Text(stringResource(R.string.knowledge_close)) }
        })
    }
    pending?.let { proposal ->
        val request = proposal.request
        AlertDialog(onDismissRequest = {
            pending = null
        }, title = { Text(stringResource(R.string.reminder_nl_confirm)) }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text("${request.destination.providerName}\n${request.destination.origin}\n${request.model}")
                    Text(
                        stringResource(
                            if (request.destination.residence ==
                                ProviderResidence.ON_DEVICE_LOCAL
                            ) {
                                R.string.knowledge_generation_local
                            } else {
                                R.string.reminder_nl_remote
                            },
                        ),
                    )
                    Text(stringResource(R.string.reminder_nl_scope))
                    Text(proposal.reference.atZone(proposal.zone).toString())
                    SelectionContainer { Text(proposal.text) }
                }
            }
        }, confirmButton = {
            TextButton(
                onClick = {
                    if (busy) return@TextButton
                    pending = null
                    busy = true
                    job =
                        scope.launch {
                            try {
                                when (val result = proposal.interpret()) {
                                    is ReminderInterpretation.Proposal -> onProposal(result.reminder)
                                    is ReminderInterpretation.Clarification -> clarification = result.question
                                }
                            } catch (cancel: CancellationException) {
                                interrupted = true
                                throw cancel
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                },
                modifier =
                    Modifier.testTag(
                        "reminder-nl-confirm",
                    ),
            ) { Text(stringResource(R.string.knowledge_confirm_send)) }
        }, dismissButton = {
            TextButton(onClick = { pending = null }) { Text(stringResource(R.string.knowledge_keep_editing)) }
        })
    }
    LazyColumn(Modifier.padding(16.dp).testTag("reminder-nl"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = {
                job?.cancel()
                onBack()
            }) { Text(stringResource(R.string.reminder_back_discard)) }
            Text(stringResource(R.string.reminder_nl_intro))
            OutlinedTextField(text, {
                text = it.take(2000)
            }, enabled = !busy, label = {
                Text(stringResource(R.string.reminder_nl_description))
            }, modifier = Modifier.fillMaxWidth().testTag("reminder-nl-description"))
            TextButton(onClick = { choose = true }, enabled = providers != null && !busy) {
                Text(target?.let { "${it.first} · ${it.second}" } ?: stringResource(R.string.knowledge_choose_model))
            }
            TextButton(
                onClick = {
                    prepare()
                },
                enabled = !busy && target != null && text.isNotBlank(),
                modifier =
                    Modifier.testTag(
                        "reminder-nl-prepare",
                    ),
            ) {
                Text(stringResource(R.string.reminder_nl_prepare))
            }
            if (busy) TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.reminder_nl_stop)) }
            if (failed) Text(stringResource(R.string.reminder_nl_failed))
            if (interrupted) Text(stringResource(R.string.reminder_nl_interrupted))
            if (clarification.isNotEmpty()) Text(stringResource(R.string.reminder_nl_clarify) + "\n" + clarification)
        }
    }
}
