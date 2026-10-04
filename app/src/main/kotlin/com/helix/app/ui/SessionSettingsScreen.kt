package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.helix.app.approval.SessionPermissionEditService
import com.helix.app.chat.ChatService
import com.helix.app.plugin.PluginService
import com.helix.app.provider.ProviderService
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.model.ProviderProvisioningKind
import kotlinx.coroutines.launch

/** Single authority for configuration that belongs to the currently open Conversation. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun SessionSettingsScreen(
    chatService: ChatService,
    projects: com.helix.app.projects.ProjectService? = null,
    onProject: (String) -> Unit = {},
    providerService: ProviderService,
    permissionEdit: SessionPermissionEditService,
    connectors: PluginService,
    files: com.helix.app.files.FileManagerService,
    onModels: () -> Unit,
    onExtensions: () -> Unit,
    onConfigureModelSource: ((ProviderProvisioningKind) -> Unit)? = null,
) {
    val screen by chatService.screen.collectAsStateWithLifecycle()
    val runControl by chatService.runControl.collectAsStateWithLifecycle()
    val providers by providerService.rows.collectAsStateWithLifecycle()
    val sessionId = screen.openSessionId
    val scope = rememberCoroutineScope()
    val withDurableSession: (() -> Unit) -> Unit = { action ->
        val id = sessionId
        if (id == null) {
            Unit
        } else if (!screen.isDraft) {
            action()
        } else {
            scope.launch {
                if (chatService.materializeDraftSession(id) == id) action()
            }
        }
    }
    var connectorsOpen by remember(sessionId) { mutableStateOf(false) }
    var expertOpen by remember(sessionId) { mutableStateOf(false) }
    var directoryOpen by remember(sessionId) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-session-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup {
            Text(stringResource(R.string.session_settings_mode))
            Text(runControl.mode.name)
            Text(stringResource(R.string.chat_command_mode_hint))
            Text(stringResource(R.string.session_settings_model))
            ComposerModelMenu(
                providers = providers,
                providerId = screen.badge?.providerId,
                model = screen.badge?.model,
                enabled = !screen.isSending && screen.pendingDisclosure == null,
                onSelect = chatService::selectSessionModel,
                onManageModels = onModels,
                sourceGroups = providerService.sourceGroups,
                onConfigureSource = onConfigureModelSource,
            )
            OutlinedButton(
                onClick = onModels,
                modifier = Modifier.fillMaxWidth().testTag("session-settings-manage-models"),
            ) {
                Text(stringResource(R.string.session_settings_manage_models))
            }
        }

        if (!screen.isDraft) projects?.let { SessionProjectSection(it, sessionId, onProject) }

        SessionWorkspaceSection(files, screen.directoryRef, sessionId != null) { directoryOpen = true }

        if (screen.isDraft) {
            SettingsGroup {
                Text(stringResource(R.string.settings_perm_session_label))
                OutlinedButton(
                    onClick = { withDurableSession {} },
                    enabled = sessionId != null,
                    modifier = Modifier.fillMaxWidth().testTag("session-settings-materialize-permissions"),
                ) {
                    Text(stringResource(R.string.session_settings_configure_permissions))
                }
            }
        } else {
            SessionPermissionSessionSection(permissionEdit, chatService)
        }

        SettingsGroup {
            OutlinedButton(
                onClick = { withDurableSession { expertOpen = true } },
                enabled = sessionId != null,
                modifier = Modifier.fillMaxWidth().testTag("session-settings-expert"),
            ) {
                Text(stringResource(R.string.session_expert_title))
            }
            OutlinedButton(
                onClick = { withDurableSession { connectorsOpen = true } },
                enabled = sessionId != null,
                modifier = Modifier.fillMaxWidth().testTag("session-settings-connectors"),
            ) {
                Text(stringResource(R.string.connector_session_title))
            }
        }
    }

    if (directoryOpen && sessionId != null) {
        SessionDirectoryDialog(files, { directoryOpen = false }) { reference ->
            chatService.setSessionDirectory(reference, sessionId).await()
        }
    }
    if (connectorsOpen && sessionId != null) {
        com.helix.app.connector.ConnectorSessionPanel(connectors, sessionId, onExtensions) {
            connectorsOpen = false
        }
    }
    if (expertOpen && sessionId != null) {
        SessionExpertDialog(chatService, sessionId) { expertOpen = false }
    }
}
