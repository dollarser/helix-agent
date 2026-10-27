package com.helix.app.reminder

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.core.model.ReminderRepeat
import com.helix.core.storage.reminder.LocalReminder
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
@Suppress("FunctionName", "LongMethod") // One list/detail screen; actions stay in the service.
fun ReminderScreen(
    service: ReminderService,
    providers: com.helix.app.provider.ProviderService? = null,
    scheduled: com.helix.app.scheduled.ScheduledAgentService? = null,
    onOpenSession: (String) -> Unit = {},
) {
    val rows by service.items.collectAsStateWithLifecycle(emptyList())
    val request by service.openRequest.collectAsStateWithLifecycle()
    val failed by service.failure.collectAsStateWithLifecycle()
    val owner = requireNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    val page = remember(owner) { androidx.lifecycle.ViewModelProvider(owner)[ReminderPageState::class.java] }
    var selected by page::selected
    var editing by page::editing
    var interpreting by page::interpreting
    var scheduling by page::scheduling
    val departure = com.helix.app.ui.LocalNavigationDeparture.current
    val requestedRow = rows.firstOrNull { it.id == request }
    LaunchedEffect(request, requestedRow?.id) {
        val id = request ?: return@LaunchedEffect
        val row = requestedRow ?: return@LaunchedEffect
        val open = {
            selected = row
            editing = true
            interpreting = false
            scheduling = false
            service.openRequest.compareAndSet(id, null)
            Unit
        }
        if (departure == null) {
            open()
        } else {
            departure.request(
                { service.openRequest.value == id },
                cancelled = { service.openRequest.compareAndSet(id, null) },
                navigate = open,
            )
        }
    }
    if (scheduling && scheduled != null) {
        com.helix.app.scheduled
            .ScheduledAgentScreen(scheduled, { scheduling = false }, onOpenSession)
    } else if (interpreting) {
        ReminderInterpretScreen(providers, { interpreting = false }) { proposal ->
            selected = proposal
            interpreting = false
            editing = true
        }
    } else if (editing) {
        ReminderEditor(service, selected) { editing = false }
    } else {
        LazyColumn(
            Modifier.padding(16.dp).testTag("reminder-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.reminder_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.reminder_limits))
                if (failed) Text(stringResource(R.string.reminder_error))
                Button(onClick = {
                    selected = null
                    editing = true
                }, Modifier.testTag("reminder-new")) {
                    Text(stringResource(R.string.reminder_new))
                }
                TextButton(onClick = { interpreting = true }, Modifier.testTag("reminder-natural-language")) {
                    Text(stringResource(R.string.reminder_nl_entry))
                }
                if (scheduled !=
                    null
                ) {
                    TextButton(onClick = { scheduling = true }, Modifier.testTag("scheduled-entry")) {
                        Text(stringResource(R.string.scheduled_title))
                    }
                }
            }
            items(rows, key = { it.id }) { row ->
                TextButton(onClick = {
                    selected = row
                    editing = true
                }, Modifier.testTag("reminder-${row.id}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(row.title)
                        Text(reminderTimeLabel(row))
                        Text(reminderStatusLabel(row.state) + " · " + reminderStatusLabel(row.scheduling))
                        if (row.lastOutcome.isNotBlank()) Text(reminderStatusLabel(row.lastOutcome))
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
// Preserve the editor draft on service failure.
internal fun ReminderEditor(service: ReminderService, original: LocalReminder?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draftId =
        androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
            original?.id?.takeIf { it.isNotBlank() } ?: java.util.UUID
                .randomUUID()
                .toString()
        }
    var title by androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
        mutableStateOf(original?.title.orEmpty())
    }
    var body by androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
        mutableStateOf(original?.body.orEmpty())
    }
    var enabled by androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
        mutableStateOf(original?.localTime?.isNotEmpty() == true)
    }
    var local by androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
        mutableStateOf(
            original?.localTime?.takeIf { it.isNotBlank() }
                ?: LocalDateTime
                    .now()
                    .plusHours(1)
                    .withSecond(0)
                    .withNano(0)
                    .toString(),
        )
    }
    var zone by androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
        mutableStateOf(original?.zone ?: ZoneId.systemDefault().id)
    }
    var repeat by androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
        mutableStateOf(original?.repeat ?: "ONCE")
    }
    var exact by androidx.compose.runtime.saveable.rememberSaveable(original?.id) {
        mutableStateOf(original?.exact ?: false)
    }
    var error by remember { mutableStateOf(false) }
    var saveAttempted by remember(original?.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<com.helix.core.storage.reminder.ReminderEvent>()) }
    val platform = remember { ReminderPlatform(context) }
    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            service.recoverAsync()
        }
    val preview =
        remember(local, zone) {
            try {
                LocalDateTime.parse(local.replace(' ', 'T')).atZone(ZoneId.of(zone)).toString()
            } catch (
                _: java.time.DateTimeException,
            ) {
                null
            }
        }
    LaunchedEffect(original?.id) {
        if (original != null &&
            original.id.isNotBlank()
        ) {
            history = service.events(original.id)
        }
    }
    // Leaving an editor never saves or arms an unconfirmed reminder.
    BackHandler(enabled = !busy, onBack = onBack)
    val fields = listOf(title, body, enabled.toString(), local, zone, repeat, exact.toString())
    val initialFields =
        androidx.compose.runtime.saveable
            .rememberSaveable(original?.id) { fields }
    com.helix.app.ui
        .UnsavedNavigationGuard(fields != initialFields, busy, onBack) {
            title = initialFields[0]
            body = initialFields[1]
            enabled = initialFields[2].toBooleanStrict()
            local = initialFields[3]
            zone = initialFields[4]
            repeat = initialFields[5]
            exact = initialFields[6].toBooleanStrict()
        }

    LazyColumn(Modifier.padding(16.dp).testTag("reminder-editor"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.reminder_back_discard)) } }
        if (original != null && original.id.isEmpty()) {
            item {
                Text(
                    stringResource(if (saveAttempted) R.string.reminder_nl_save_check else R.string.reminder_nl_draft),
                    Modifier.testTag("reminder-nl-draft"),
                )
            }
        }
        item {
            OutlinedTextField(
                title,
                { title = it.take(160) },
                Modifier.fillMaxWidth().testTag("reminder-title"),
                label = { Text(stringResource(R.string.reminder_name)) },
            )
        }
        item {
            OutlinedTextField(
                body,
                { body = it.take(20_000) },
                Modifier.fillMaxWidth().testTag("reminder-body"),
                label = { Text(stringResource(R.string.reminder_body)) },
            )
        }
        item {
            Row {
                Checkbox(enabled, { enabled = it }, Modifier.testTag("reminder-timed"))
                Text(stringResource(R.string.reminder_timed))
            }
        }
        if (enabled) {
            item {
                OutlinedTextField(
                    local,
                    { local = it },
                    Modifier.fillMaxWidth().testTag("reminder-local"),
                    label = { Text(stringResource(R.string.reminder_date)) },
                )
            }
            item {
                Row {
                    TextButton(onClick = {
                        val date =
                            runCatching { LocalDateTime.parse(local.replace(' ', 'T')) }
                                .getOrDefault(LocalDateTime.now().plusHours(1))
                        android.app
                            .DatePickerDialog(context, { _, year, month, day ->
                                local =
                                    date
                                        .withDayOfMonth(1)
                                        .withYear(year)
                                        .withMonth(month + 1)
                                        .withDayOfMonth(day)
                                        .toString()
                            }, date.year, date.monthValue - 1, date.dayOfMonth)
                            .show()
                    }) { Text(stringResource(R.string.reminder_pick_date)) }
                    TextButton(onClick = {
                        val date =
                            runCatching { LocalDateTime.parse(local.replace(' ', 'T')) }
                                .getOrDefault(LocalDateTime.now().plusHours(1))
                        android.app
                            .TimePickerDialog(context, { _, hour, minute ->
                                local =
                                    date
                                        .withHour(hour)
                                        .withMinute(minute)
                                        .withSecond(0)
                                        .withNano(0)
                                        .toString()
                            }, date.hour, date.minute, true)
                            .show()
                    }) { Text(stringResource(R.string.reminder_pick_time)) }
                }
            }
            item {
                OutlinedTextField(
                    zone,
                    { zone = it },
                    Modifier.fillMaxWidth().testTag("reminder-zone"),
                    label = { Text(stringResource(R.string.reminder_zone)) },
                )
            }
            items(ReminderRepeat.entries) { value ->
                TextButton(onClick = { repeat = value.name }, Modifier.testTag("reminder-repeat-${value.name}")) {
                    Text((if (repeat == value.name) "✓ " else "") + reminderStatusLabel(value.name))
                }
            }
            item { Text(stringResource(R.string.reminder_repeat_help)) }
            item {
                Row {
                    Checkbox(exact, { exact = it })
                    Text(stringResource(R.string.reminder_exact))
                }
            }
            item {
                Text(stringResource(R.string.reminder_review, preview ?: "?", reminderStatusLabel(repeat)))
            }
            item {
                Text(stringResource(R.string.reminder_permission_help))
                if (!platform.notificationsAllowed()) {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >=
                            33
                        ) {
                            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }) { Text(stringResource(R.string.reminder_notification_settings)) }
                }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }) { Text(stringResource(R.string.reminder_open_settings)) }
            }
            item {
                if (exact && !platform.exactAllowed()) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    }) { Text(stringResource(R.string.reminder_alarm_settings)) }
                }
            }
        }
        item {
            if (error) Text(stringResource(R.string.reminder_error))
            Button(
                onClick = {
                    if (busy) return@Button
                    saveAttempted = true
                    busy = true
                    scope.launch {
                        try {
                            val draft =
                                LocalReminder(
                                    draftId,
                                    title.trim(),
                                    body,
                                    if (enabled) LocalDateTime.parse(local.replace(' ', 'T')).toString() else "",
                                    zone,
                                    if (enabled) repeat else "ONCE",
                                    exact,
                                    null,
                                    revision = original?.revision ?: 1,
                                )
                            service.save(draft)
                            onBack()
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && title.isNotBlank() && (!enabled || preview != null),
                modifier = Modifier.testTag("reminder-save"),
            ) { Text(stringResource(R.string.reminder_save)) }
            if (original != null && original.id.isNotBlank() && original.state == "ACTIVE") {
                Row {
                    listOf(false, true).forEach { cancel ->
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        service.finish(original.id, cancel)
                                        onBack()
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        error = true
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier =
                                Modifier.testTag(
                                    if (cancel) "reminder-cancel" else "reminder-complete",
                                ),
                        ) {
                            Text(stringResource(if (cancel) R.string.reminder_cancel else R.string.reminder_complete))
                        }
                    }
                }
            }
        }
        items(history) { event ->
            Text(
                java.time.Instant
                    .ofEpochMilli(event.dueAt)
                    .atZone(ZoneId.of(original?.zone ?: ZoneId.systemDefault().id))
                    .toString() +
                    " · " + reminderStatusLabel(event.outcome),
            )
        }
    }
}

@Composable
private fun reminderTimeLabel(row: LocalReminder): String =
    if (row.localTime.isBlank()) {
        stringResource(R.string.reminder_memo)
    } else {
        val at =
            row.nextAt?.let {
                java.time.Instant
                    .ofEpochMilli(it)
                    .atZone(ZoneId.of(row.zone))
            }
        (at?.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) ?: row.localTime + " " + row.zone) +
            " · " + reminderStatusLabel(row.repeat)
    }

@Composable
@Suppress("CyclomaticComplexMethod") // Localized closed status mapping, no execution branches.
internal fun reminderStatusLabel(value: String): String =
    stringResource(
        when (value) {
            "ACTIVE" -> R.string.reminder_active
            "COMPLETED" -> R.string.reminder_done
            "CANCELLED" -> R.string.reminder_cancelled
            "EXACT" -> R.string.reminder_precise
            "APPROXIMATE" -> R.string.reminder_approximate
            "NOTIFICATIONS_OFF", "BLOCKED" -> R.string.reminder_blocked
            "POSTED" -> R.string.reminder_posted
            "MISSED" -> R.string.reminder_missed
            "UNKNOWN", "POSTING" -> R.string.reminder_unknown
            "ONCE" -> R.string.reminder_once
            "DAILY" -> R.string.reminder_daily
            "WEEKLY" -> R.string.reminder_weekly
            "MONTHLY" -> R.string.reminder_monthly
            "MEMO" -> R.string.reminder_memo
            "NONE" -> R.string.reminder_no_pending
            else -> R.string.reminder_pending
        },
    )
