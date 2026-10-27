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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    onPermissions: () -> Unit,
) {
    val state = rememberSaveable(saver = MobileUseSettingsState.saver(store)) { MobileUseSettingsState(store) }
    var picker by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !state.saved && !state.failed) advanced = true
    }
    val scope = rememberCoroutineScope()
    val applications = remember(context) { AutomationApplicationCatalog(context) }
    LaunchedEffect(store) { state.load() }
    val exit = rememberMobileUseSettingsLeaving(state, onBack, onPermissions)
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
        Modifier.fillMaxSize().padding(16.dp).testTag("mobile-use-global-settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    OutlinedButton(
                        { exit("permissions") },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("mobile-use-open-permissions"),
                    ) {
                        Text(stringResource(R.string.settings_system_permissions_title))
                    }
                }
            }
            TextButton({ advanced = !advanced }, Modifier.testTag("mobile-use-advanced")) {
                Text(stringResource(R.string.mobile_use_advanced_configuration))
            }
            if (advanced) {
                MobileUseScopeOptions(state, onSelectApplications = { picker = true })
                MobileUseSaveStatus(state)
                Button(
                    onClick = { scope.launch { if (state.save()) onSaved() } },
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth().testTag("mobile-use-save-settings"),
                ) {
                    Text(
                        stringResource(
                            if (state.busy) R.string.provider_save_saving else R.string.mobile_use_save_settings,
                        ),
                    )
                }
            }
            MobileUseLoadStatus(state) { scope.launch { state.load() } }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun MobileUseScopeOptions(
    state: MobileUseSettingsState,
    onSelectApplications: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mobile_use_scope_heading), style = MaterialTheme.typography.titleMedium)
            Row {
                Checkbox(
                    state.wholePhone,
                    { state.wholePhone = it },
                    enabled = state.loaded && !state.busy,
                    modifier = Modifier.testTag("automation-all-applications"),
                )
                Text(stringResource(R.string.automation_whole_phone_label))
            }
            if (!state.wholePhone) {
                OutlinedButton(
                    onSelectApplications,
                    enabled = state.loaded && !state.busy,
                    modifier = Modifier.testTag("automation-select-apps"),
                ) { Text(stringResource(R.string.automation_app_selection_count, state.packages.size)) }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun MobileUseSaveStatus(state: MobileUseSettingsState) {
    Text(
        stringResource(if (state.saved) R.string.mobile_use_saved else R.string.mobile_use_save_hint),
        Modifier.testTag("mobile-use-save-status"),
    )
    if (state.failed) {
        Text(
            stringResource(if (state.requiresReset) R.string.old_state_reopen else R.string.mobile_use_settings_failed),
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
                    Text(stringResource(R.string.common_discard_edit))
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
        if (state.failed && !state.requiresReset) {
            OutlinedButton(retry, Modifier.testTag("mobile-use-reload")) {
                Text(stringResource(R.string.chat_retry))
            }
        } else if (!state.failed) {
            Text(stringResource(R.string.tasks_commands_loading))
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun rememberMobileUseSettingsLeaving(
    state: MobileUseSettingsState,
    onBack: () -> Unit,
    onPermissions: () -> Unit,
): (String) -> Unit {
    var pendingExit by rememberSaveable { mutableStateOf<String?>(null) }
    var departure by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
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
        finishMobileUseExit(destination, onBack, onPermissions, departure)
        departure = null
    }
    com.helix.app.ui.NavigationLeaveGuard { complete ->
        when {
            state.busy -> {
                complete(false)
            }

            state.dirty -> {
                departure = complete
                pendingExit = "departure"
            }

            else -> {
                complete(true)
            }
        }
    }
    androidx.activity.compose.BackHandler { exit("back") }
    if (pendingExit != null) {
        MobileUseLeaveDialog(state, {
            pendingExit = null
            departure?.invoke(false)
            departure = null
        }, {
            state.discard()
            finishExit()
        }) {
            scope.launch { if (state.save()) finishExit() }
        }
    }
    return exit
}

private fun finishMobileUseExit(
    destination: String?,
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    departure: ((Boolean) -> Unit)?,
) {
    when (destination) {
        "back" -> onBack()
        "permissions" -> onPermissions()
        "departure" -> departure?.invoke(true)
    }
}
