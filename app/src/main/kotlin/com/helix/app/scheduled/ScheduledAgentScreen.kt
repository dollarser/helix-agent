package com.helix.app.scheduled

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.core.storage.scheduled.ScheduledAgentRule
import com.helix.core.storage.scheduled.ScheduledOccurrence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Composable
@Suppress("FunctionName", "LongMethod") // Local list/editor navigation, all effects go through the service.
fun ScheduledAgentScreen(
    service: ScheduledAgentService,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val rules by service.rules.collectAsStateWithLifecycle(emptyList())
    val occurrences by service.occurrences.collectAsStateWithLifecycle(emptyList())
    val attempts by service.attempts.collectAsStateWithLifecycle(emptyList())
    val failure by service.failure.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ScheduledAgentRule?>(null) }
    var retry by remember { mutableStateOf<ScheduledOccurrence?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        while (true) {
            scheduledUiAction({ error = true }) { service.refreshAttempts() }
            delay(1000)
        }
    }
    if (editing) {
        ScheduledAgentEditor(service, selected) { editing = false }
        return
    }
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.padding(16.dp).testTag("scheduled-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Text(stringResource(R.string.scheduled_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.scheduled_limits))
            if (error || failure) Text(stringResource(R.string.scheduled_error))
            Button(onClick = {
                selected = null
                editing = true
            }, Modifier.testTag("scheduled-new")) {
                Text(stringResource(R.string.scheduled_new))
            }
        }
        items(rules, key = { "rule:${it.id}" }) { rule ->
            Text(rule.title, style = MaterialTheme.typography.titleMedium)
            Text("${rule.localTime} · ${rule.zone}")
            Text(scheduledStatus(rule.status))
            TextButton(onClick = {
                selected = rule
                editing = true
            }) { Text(stringResource(R.string.scheduled_edit)) }
            if (rule.enabled) {
                TextButton(onClick = {
                    scope.launch {
                        scheduledUiAction({ error = true }) {
                            service.pause(rule.id, rule.revision)
                        }
                    }
                }, Modifier.testTag("scheduled-pause-${rule.id}")) { Text(stringResource(R.string.scheduled_pause)) }
            }
        }
        items(occurrences, key = { "occ:${it.ruleId}:${it.revision}:${it.dueAt}" }) { occurrence ->
            val original = remember(occurrence.ruleJson) { ScheduledRuleSnapshot.decode(occurrence.ruleJson) }
            val instant = Instant.ofEpochMilli(occurrence.dueAt).atZone(ZoneId.of(original.zone))
            Text(original.title + " · " + instant)
            Text(scheduledStatus(occurrence.outcome))
            val attempt = attempts.firstOrNull { it.id == occurrence.lastAttemptId }
            TextButton(
                onClick = { onOpenSession(original.sessionId) },
            ) { Text(stringResource(R.string.scheduled_result)) }
            if (attempt?.state in ScheduledAgentService.ACTIVE || attempt?.state == "UNKNOWN") {
                TextButton(onClick = {
                    scope.launch {
                        scheduledUiAction({ error = true }) {
                            service.stop(requireNotNull(attempt).id)
                        }
                    }
                }) { Text(stringResource(R.string.scheduled_stop)) }
            }
            if (occurrence.outcome in ScheduledAgentService.REPLAYABLE) {
                TextButton(
                    onClick = { retry = occurrence },
                    modifier =
                        Modifier.testTag(
                            "scheduled-retry-${occurrence.ruleId}:${occurrence.revision}:${occurrence.dueAt}",
                        ),
                ) { Text(stringResource(R.string.scheduled_retry)) }
            }
        }
    }
    retry?.let { occurrence ->
        val original = ScheduledRuleSnapshot.decode(occurrence.ruleJson)
        val bound = ScheduledAgentScope.decode(original.scopeJson)
        AlertDialog(
            onDismissRequest = { retry = null },
            title = { Text(stringResource(R.string.scheduled_retry)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("scheduled-retry-preview")) {
                    Text(stringResource(R.string.scheduled_retry_scope))
                    Text(original.prompt)
                    ScheduledScopePreview(bound)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    retry = null
                    service.retryAsync(
                        occurrence.ruleId,
                        occurrence.revision,
                        occurrence.dueAt,
                        occurrence.lastAttemptId,
                    )
                }) { Text(stringResource(R.string.scheduled_confirm_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { retry = null }, modifier = Modifier.testTag("scheduled-retry-cancel")) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

// A stable editor identity and an immutable confirmation preview.
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun ScheduledAgentEditor(
    service: ScheduledAgentService,
    original: ScheduledAgentRule?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val id = remember(original?.id) { original?.id ?: UUID.randomUUID().toString() }
    var title by remember(id) { mutableStateOf(original?.title.orEmpty()) }
    var prompt by remember(id) { mutableStateOf(original?.prompt.orEmpty()) }
    var sessionId by remember(id) { mutableStateOf(original?.sessionId.orEmpty()) }
    var local by remember(id) { mutableStateOf(original?.localTime ?: defaultScheduledTime()) }
    var zone by remember(id) { mutableStateOf(original?.zone ?: ZoneId.systemDefault().id) }
    var repeat by remember(id) { mutableStateOf(original?.repeat ?: "ONCE") }
    var runtime by remember(id) {
        mutableStateOf(
            ScheduledRuntimeBudget.seconds(original?.runtimeBudgetMillis ?: ScheduledRuntimeBudget.DEFAULT_MILLIS),
        )
    }
    var enabled by remember(id) { mutableStateOf(original?.enabled ?: false) }
    var choices by remember { mutableStateOf(emptyList<ScheduledSessionChoice>()) }
    var prepared by remember { mutableStateOf<PreparedScheduledRule?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(service) { scheduledUiAction({ error = true }) { choices = service.sessions() } }
    BackHandler(enabled = !busy, onBack = onBack)
    LazyColumn(Modifier.padding(16.dp).testTag("scheduled-editor"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.reminder_back_discard)) }
            Text(stringResource(R.string.scheduled_limits))
            if (error) Text(stringResource(R.string.scheduled_error), Modifier.testTag("scheduled-editor-error"))
            OutlinedTextField(
                title,
                { title = it.take(160) },
                Modifier.fillMaxWidth().testTag("scheduled-name"),
                label = { Text(stringResource(R.string.reminder_name)) },
            )
            OutlinedTextField(
                prompt,
                { prompt = it.take(6000) },
                Modifier.fillMaxWidth().testTag("scheduled-prompt"),
                label = { Text(stringResource(R.string.scheduled_prompt)) },
            )
            Text(stringResource(R.string.scheduled_session))
        }
        items(choices, key = { it.id }) { choice ->
            TextButton(onClick = { sessionId = choice.id }, Modifier.testTag("scheduled-session-${choice.id}")) {
                Text((if (sessionId == choice.id) "✓ " else "") + choice.title)
            }
        }
        item {
            OutlinedTextField(
                local,
                { local = it },
                Modifier.fillMaxWidth().testTag("scheduled-local"),
                label = { Text(stringResource(R.string.reminder_date)) },
            )
            OutlinedTextField(
                zone,
                { zone = it },
                Modifier.fillMaxWidth().testTag("scheduled-zone"),
                label = { Text(stringResource(R.string.reminder_zone)) },
            )
            listOf(
                "ONCE" to R.string.reminder_once,
                "DAILY" to R.string.reminder_daily,
                "WEEKLY" to R.string.reminder_weekly,
                "MONTHLY" to R.string.reminder_monthly,
            ).forEach { (value, label) ->
                Row {
                    Checkbox(repeat == value, { repeat = value })
                    Text(stringResource(label))
                }
            }
            OutlinedTextField(
                runtime,
                { runtime = it },
                Modifier.fillMaxWidth().testTag("scheduled-runtime"),
                label = { Text(stringResource(R.string.scheduled_runtime)) },
            )
            Text(stringResource(R.string.scheduled_calendar))
            Row {
                Checkbox(enabled, { enabled = it }, Modifier.testTag("scheduled-enabled"))
                Text(stringResource(R.string.scheduled_enable))
            }
            Button(
                onClick = {
                    busy = true
                    error = false
                    scope.launch {
                        scheduledUiAction({ error = true }) {
                            val draft =
                                ScheduledAgentRule(
                                    id,
                                    title,
                                    prompt,
                                    "",
                                    "",
                                    sessionId,
                                    local.replace(' ', 'T'),
                                    zone,
                                    repeat,
                                    revision = original?.revision ?: 1,
                                    enabled = enabled,
                                )
                            prepared = service.prepare(draft, ScheduledRuntimeBudget.parseSeconds(runtime))
                        }
                        busy = false
                    }
                },
                enabled = !busy && title.isNotBlank() && prompt.isNotBlank() && sessionId.isNotBlank(),
                modifier = Modifier.testTag("scheduled-review"),
            ) { Text(stringResource(R.string.scheduled_review)) }
        }
    }
    prepared?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!busy) prepared = null },
            title = { Text(stringResource(R.string.scheduled_review)) },
            text = {
                LazyColumn {
                    item {
                        Text(preview.rule.title)
                        Text(preview.rule.prompt)
                        Text("${preview.rule.localTime} · ${preview.rule.zone}")
                        Text(
                            stringResource(
                                if (preview.rule.enabled) R.string.scheduled_enable else R.string.scheduled_disabled,
                            ),
                        )
                        ScheduledScopePreview(preview.scope)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, modifier = Modifier.testTag("scheduled-confirm"), onClick = {
                    busy = true
                    error = false
                    scope.launch {
                        scheduledUiAction({ error = true }) {
                            service.save(preview)
                            onBack()
                        }
                        prepared = null
                        busy = false
                    }
                }) { Text(stringResource(R.string.reminder_save)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { prepared = null },
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun ScheduledScopePreview(bound: ScheduledAgentScope) {
    Text(bound.sessionTitle)
    Text(
        stringResource(R.string.scheduled_runtime_review, ScheduledRuntimeBudget.seconds(bound.maxRuntimeMillis)),
        Modifier.testTag("scheduled-runtime-preview"),
    )
    Text("${bound.providerName} · ${bound.model} · ${bound.origin}", Modifier.testTag("scheduled-scope-target"))
    Text(stringResource(R.string.scheduled_disclosure, bound.workspace, bound.toolCount))
}

@Composable
private fun scheduledStatus(value: String): String =
    stringResource(
        when (value) {
            "PAUSED" -> R.string.scheduled_disabled
            "PENDING", "ENQUEUED" -> R.string.scheduled_pending
            "FINISHED" -> R.string.reminder_no_pending
            "COMPLETED" -> R.string.scheduled_completed
            "MISSED" -> R.string.reminder_missed
            "BUSY" -> R.string.scheduled_busy
            "ADMITTING", "RUNNING" -> R.string.scheduled_running
            "STOP_REQUESTED" -> R.string.scheduled_stopping
            "WAITING" -> R.string.scheduled_waiting
            "CANCELLED" -> R.string.reminder_cancelled
            "FAILED", "SCOPE_CHANGED" -> R.string.scheduled_failed
            "INTERRUPTED" -> R.string.scheduled_interrupted
            else -> R.string.scheduled_unknown
        },
    )

@Suppress("TooGenericExceptionCaught") // UI failure remains visible and never returns a success receipt.
private suspend fun scheduledUiAction(onError: () -> Unit, action: suspend () -> Unit) {
    try {
        action()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        onError()
    }
}

private fun defaultScheduledTime(): String =
    LocalDateTime
        .now()
        .plusHours(1)
        .withSecond(0)
        .withNano(0)
        .toString()
