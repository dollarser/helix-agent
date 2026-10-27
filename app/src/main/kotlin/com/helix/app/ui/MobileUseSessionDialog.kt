package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.connector.MOBILE_USE_PLUGIN_ID
import com.helix.app.connector.MobileUseSessionState
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSetupReason
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod")
internal fun MobileUseSessionDialog(
    service: PluginService,
    sessionId: String,
    requestId: String,
    isCurrent: () -> Boolean,
    onPermissions: () -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        remember(requestId) {
            MobileUseSessionState(
                { service.sessionRows(sessionId, MOBILE_USE_PLUGIN_ID).singleOrNull() },
                { enabled -> service.selectFromUser(sessionId, MOBILE_USE_PLUGIN_ID, enabled) },
                isCurrent,
            )
        }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    LaunchedEffect(state, revision) { state.refresh() }
    val reason = state.setupReason ?: state.row?.setupReason
    val unavailable = !state.loading && !state.failed && state.row?.available != true
    val scope = rememberCoroutineScope()
    val callbacks = MobileUseDialogCallbacks(onPermissions, onConfigure, onDismiss)
    val confirm = { confirmMobileUseDialog(state, reason, unavailable, scope, callbacks, isCurrent) }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.mobile_use_session_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        mobileUseDialogMessage(state, reason, unavailable),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                confirm,
                enabled = !state.loading && !state.busy && isCurrent(),
                modifier = Modifier.testTag("mobile-use-dialog-confirm"),
            ) {
                Text(
                    stringResource(
                        mobileUseDialogConfirmLabel(state, reason, unavailable),
                    ),
                )
            }
        },
        dismissButton = {
            if (!state.active && !unavailable) {
                TextButton(
                    { if (!state.busy) onDismiss() },
                    enabled = !state.busy,
                ) { Text(stringResource(R.string.common_cancel)) }
            }
        },
        modifier = Modifier.testTag("mobile-use-dialog"),
    )
}

private fun mobileUseDialogMessage(
    state: MobileUseSessionState,
    reason: PluginSetupReason?,
    unavailable: Boolean,
): Int =
    when {
        state.loading -> R.string.tasks_commands_loading
        state.failed -> R.string.connector_session_failed
        unavailable -> R.string.plugin_setup_unsupported
        state.active -> R.string.mobile_use_session_enabled
        reason == PluginSetupReason.MOBILE_BACKEND -> R.string.plugin_setup_mobile_backend
        reason == PluginSetupReason.MOBILE_SCOPE -> R.string.mobile_use_configuration_required
        else -> R.string.mobile_use_session_hint
    }

private fun mobileUseDialogConfirmLabel(
    state: MobileUseSessionState,
    reason: PluginSetupReason?,
    unavailable: Boolean,
): Int =
    when {
        state.active || unavailable -> R.string.chat_details_close
        state.failed -> R.string.chat_retry
        reason == PluginSetupReason.MOBILE_BACKEND -> R.string.mobile_use_go_authorize
        reason == PluginSetupReason.MOBILE_SCOPE -> R.string.plugin_setup_continue
        else -> R.string.mobile_use_session_enable
    }

private data class MobileUseDialogCallbacks(
    val permissions: () -> Unit,
    val configure: () -> Unit,
    val dismiss: () -> Unit,
)

private fun confirmMobileUseDialog(
    state: MobileUseSessionState,
    reason: PluginSetupReason?,
    unavailable: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    callbacks: MobileUseDialogCallbacks,
    isCurrent: () -> Boolean,
) {
    when {
        state.active || unavailable -> {
            callbacks.dismiss()
        }

        state.failed -> {
            scope.launch { state.refresh() }
        }

        reason == PluginSetupReason.MOBILE_BACKEND -> {
            callbacks.permissions()
        }

        reason == PluginSetupReason.MOBILE_SCOPE -> {
            callbacks.configure()
        }

        else -> {
            scope.launch {
                state.choose(true)
                if (state.active && isCurrent()) callbacks.dismiss()
            }
        }
    }
}
