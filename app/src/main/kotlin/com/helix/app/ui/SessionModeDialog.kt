package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Uses the same bound, awaited session transition as composer commands. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun SessionModeDialog(
    chat: ChatService,
    sessionId: String?,
    config: RunControlConfig,
    active: Boolean,
    onDismiss: () -> Unit,
) {
    var saving by remember(sessionId) { mutableStateOf(false) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.session_settings_mode)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState()).selectableGroup()) {
                AgentMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("session-mode-${mode.name}")
                            .selectable(
                                config.mode == mode,
                                enabled = sessionId != null && !active && !saving,
                                role = Role.RadioButton,
                            ) {
                                val id = sessionId ?: return@selectable
                                saving = true
                                failed = false
                                scope.launch {
                                    try {
                                        if (chat.setModeFromComposer(id, mode)) onDismiss() else failed = true
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        failed = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(config.mode == mode, onClick = null)
                        Text(stringResource(modeLabel(mode)))
                    }
                }
                ModeControlSection(config, active || saving) { enabled ->
                    if (chat.screen.value.openSessionId == sessionId) chat.setChatToolsEnabled(enabled)
                }
                if (failed) {
                    Text(
                        stringResource(R.string.common_operation_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.chat_details_close)) }
        },
    )
}
