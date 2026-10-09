package com.helix.app.automation

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.extensions.mobileuse.automation.AutomationApplicationCatalog
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import kotlinx.coroutines.launch

/** Global plugin configuration; saving never selects a conversation or grants Android permissions. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun MobileUseSettings(
    context: Context,
    store: MobileUseGrantStore,
    backendStatus: @Composable () -> Unit = {},
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    onPermissions: () -> Unit,
) {
    val state = rememberSaveable(saver = MobileUseSettingsState.saver(store)) { MobileUseSettingsState(store) }
    var picker by remember { mutableStateOf(false) }
    var pendingExit by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val applications = remember(context) { AutomationApplicationCatalog(context) }
    LaunchedEffect(store) { state.load() }
    val exit: (String) -> Unit = { destination ->
        if (!state.busy) {
            if (state.dirty) {
                pendingExit = destination
            } else if (destination == "back") {
                onBack()
            } else {
                onPermissions()
            }
        }
    }
    val finishExit: () -> Unit = {
        val destination = pendingExit
        pendingExit = null
        if (destination == "back") onBack() else onPermissions()
    }
    androidx.activity.compose.BackHandler { exit("back") }
    if (pendingExit != null) {
        MobileUseLeaveDialog(state, { pendingExit = null }, {
            state.discard()
            finishExit()
        }) {
            scope.launch { if (state.save()) finishExit() }
        }
    }
    if (picker) {
        AutomationApplicationPicker(
            initialSelection = state.packages.toSet(),
            loadApplications = applications::load,
            onConfirm = {
                state.packages = it.toList()
                picker = false
            },
            onDismiss = { picker = false },
        )
    }
    Column(
        Modifier.fillMaxSize().testTag("mobile-use-global-settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.TextButton({
            exit("back")
        }, enabled = !state.busy, modifier = Modifier.testTag("mobile-use-back")) {
            Text(stringResource(R.string.permissions_back))
        }
        Column(
            Modifier.weight(1f).indicatedVerticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.mobile_use_permissions_heading),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.mobile_use_permissions_help))
                    OutlinedButton(
                        { exit("permissions") },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("mobile-use-open-permissions"),
                    ) {
                        Text(stringResource(R.string.settings_system_permissions_title))
                    }
                    backendStatus()
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.mobile_use_scope_heading),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.mobile_use_scope_help))
                    Row {
                        Checkbox(
                            state.wholePhone,
                            {
                                state.wholePhone = it
                            },
                            enabled = state.loaded && !state.busy,
                            modifier = Modifier.testTag("automation-all-applications"),
                        )
                        Text(stringResource(R.string.automation_whole_phone_label))
                    }
                    OutlinedButton(
                        { picker = true },
                        enabled = state.loaded && !state.busy && !state.wholePhone,
                        modifier = Modifier.testTag("automation-select-apps"),
                    ) {
                        Text(stringResource(R.string.automation_app_selection_count, state.packages.size))
                    }
                    Text(stringResource(R.string.automation_image_handling))
                }
            }
            Text(stringResource(R.string.mobile_use_session_heading), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.mobile_use_global_help))
        }
        HorizontalDivider()
        MobileUseSaveStatus(state.saved, state.failed)
        MobileUseLoadStatus(state) { scope.launch { state.load() } }
        Button(
            onClick = { scope.launch { if (state.save()) onSaved() } },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().testTag("mobile-use-save-settings"),
        ) {
            Text(stringResource(if (state.busy) R.string.provider_save_saving else R.string.mobile_use_save_settings))
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun MobileUseSaveStatus(
    saved: Boolean,
    failed: Boolean,
) {
    Text(
        stringResource(if (saved) R.string.mobile_use_saved else R.string.mobile_use_save_hint),
        Modifier.testTag("mobile-use-save-status"),
    )
    if (failed) {
        Text(
            stringResource(R.string.mobile_use_settings_failed),
            Modifier.testTag("mobile-use-save-error"),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun MobileUseLeaveDialog(
    state: MobileUseSettingsState,
    onContinue: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!state.busy) onContinue() },
        title = { Text(stringResource(R.string.mobile_use_unsaved_title)) },
        text = {
            Text(
                stringResource(
                    if (state.failed) R.string.mobile_use_settings_failed else R.string.mobile_use_unsaved_help,
                ),
            )
        },
        confirmButton = {
            Button(onSave, enabled = state.canSave, modifier = Modifier.testTag("mobile-use-save-leave")) {
                Text(stringResource(R.string.context_save))
            }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(
                    onDiscard,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("mobile-use-discard"),
                ) {
                    Text(stringResource(R.string.message_revision_discard))
                }
                androidx.compose.material3.TextButton(onContinue, enabled = !state.busy) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun MobileUseLoadStatus(
    state: MobileUseSettingsState,
    retry: () -> Unit,
) {
    if (!state.loaded) {
        if (state.failed) {
            OutlinedButton(retry, Modifier.testTag("mobile-use-reload")) {
                Text(stringResource(R.string.chat_retry))
            }
        } else {
            Text(stringResource(R.string.tasks_commands_loading))
        }
    }
}
