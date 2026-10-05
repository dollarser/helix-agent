package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSessionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Task-first entry over existing plugin and host settings. It never grants or selects capabilities. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod", "TooGenericExceptionCaught")
internal fun PhoneTaskPreparation(
    service: PluginService,
    sessionId: String,
    onSelectTools: () -> Unit,
    onExtensions: () -> Unit,
    onPermissions: () -> Unit,
    onPrompt: (String) -> Unit,
) {
    var open by remember(sessionId) { mutableStateOf(false) }
    var row by remember(sessionId) { mutableStateOf<PluginSessionRow?>(null) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var revision by remember(sessionId) { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    LaunchedEffect(service, sessionId, open, revision) {
        try {
            row =
                withContext(Dispatchers.IO) {
                    val id = service.list().firstOrNull { it.native?.pluginId == "mobile-use" }?.id
                    service.sessionRows(sessionId).firstOrNull { it.id == id }
                }
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    if (row == null && !failed) return
    TextButton({ open = true }, modifier = Modifier.testTag("phone-task-prepare")) {
        Text(stringResource(R.string.phone_task_prepare))
    }
    if (open) {
        ConversationSheet(stringResource(R.string.phone_task_prepare), "phone-task-prepare", { open = false }) {
            Text(stringResource(phonePreparationStatus(row, failed)))
            Text(stringResource(R.string.phone_task_prepare_hint))
            TextButton({
                open = false
                onSelectTools()
            }) {
                Text(stringResource(R.string.phone_task_select))
            }
            TextButton({
                open = false
                onExtensions()
            }) {
                Text(stringResource(R.string.phone_task_configure))
            }
            TextButton({
                open = false
                onPermissions()
            }) {
                Text(stringResource(R.string.phone_task_permissions))
            }
            val prompt = stringResource(R.string.phone_task_example)
            TextButton({
                open = false
                onPrompt(prompt)
            }, modifier = Modifier.testTag("phone-task-example")) {
                Text(stringResource(R.string.phone_task_try))
            }
        }
    }
}

internal fun phonePreparationStatus(
    row: PluginSessionRow?,
    failed: Boolean,
): Int =
    when {
        failed || row == null -> R.string.phone_task_check_failed
        row.selectionError == "MOBILE_USE_NOT_CONFIGURED" -> R.string.mobile_use_configuration_required
        !row.enabled || !row.ready || row.selectionError != null -> R.string.phone_task_needs_configuration
        !row.selected -> R.string.phone_task_needs_selection
        else -> R.string.phone_task_selected
    }
