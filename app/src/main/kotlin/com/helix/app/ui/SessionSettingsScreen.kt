package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
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
import com.helix.app.provider.ProviderService
import com.helix.app.provider.discoverReasoningOptions
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.model.ProviderProvisioningKind
import kotlinx.coroutines.launch

/** Single authority for configuration that belongs to the currently open Conversation. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun SessionSettingsScreen(
    chatService: ChatService,
    accountEntry: @Composable (() -> Unit) -> Unit = {},
    skills: com.helix.extensions.skills.SkillRepository? = null,
    projects: com.helix.app.projects.ProjectService? = null,
    onProject: (String) -> Unit = {},
    providerService: ProviderService,
    permissionEdit: SessionPermissionEditService,
    files: com.helix.app.files.FileManagerService,
    onExtensions: () -> Unit,
    onConfigureModelSource: ((ProviderProvisioningKind) -> Unit)? = null,
) {
    val screen by chatService.screen.collectAsStateWithLifecycle()
    val runControl by chatService.runControl.collectAsStateWithLifecycle()
    val providers by providerService.rows.collectAsStateWithLifecycle()
    val sessionId = screen.openSessionId
    val scope = rememberCoroutineScope()
    var preparing by remember(sessionId) { mutableStateOf(false) }
    var prepareFailure by remember(sessionId) { mutableStateOf<String?>(null) }
    val operationFailed = stringResource(R.string.common_operation_failed)
    val withDurableSession: (() -> Unit) -> Unit = { action ->
        val id = sessionId
        if (id != null && !preparing && chatService.screen.value.openSessionId == id) {
            if (!screen.isDraft) {
                prepareFailure = null
                action()
            } else {
                preparing = true
                prepareFailure = null
                scope.launch {
                    try {
                        if (chatService.materializeDraftSession(id) == id &&
                            chatService.screen.value.openSessionId == id
                        ) {
                            action()
                        } else {
                            prepareFailure = chatService.screen.value.blockedReason ?: operationFailed
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        prepareFailure = operationFailed
                    } finally {
                        preparing = false
                    }
                }
            }
        }
    }
    var skillsOpen by remember(sessionId) { mutableStateOf(false) }
    if (skillsOpen && skills != null && sessionId != null) {
        SkillSessionPanel(skills, sessionId, onExtensions, { skillsOpen = false })
    }
    val projectRows =
        projects
            ?.projects
            ?.collectAsStateWithLifecycle(emptyList())
            ?.value
            .orEmpty()
    val currentProject = projectRows.firstOrNull { it.id == screen.projectId }
    val projectDirectory =
        currentProject?.let {
            com.helix.core.workspace
                .FileScopePath(it.workspaceId, it.relativePath)
                .toModelReference()
        }
    var directoryOpen by remember(sessionId) { mutableStateOf(false) }
    var permissionOpen by remember(sessionId) { mutableStateOf(false) }
    var modeOpen by remember(sessionId) { mutableStateOf(false) }
    val (summary, summaryFailed) =
        rememberSessionSettingsSummary(
            sessionId,
            screen.isDraft,
            skillsOpen || permissionOpen,
            permissionEdit,
            skills,
        )
    val unavailable =
        stringResource(if (summaryFailed) R.string.common_operation_failed else R.string.composer_permission_loading)

    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-session-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup {
            SettingsEntryRow(
                stringResource(R.string.session_settings_mode),
                R.drawable.ic_drawer_message,
                { modeOpen = true },
                Modifier.testTag("session-settings-mode"),
                stringResource(modeLabel(runControl.mode)),
            )
            ComposerModelMenu(
                accountEntry = accountEntry,
                providers = providers,
                providerId = screen.selectedModel?.providerId,
                model = screen.selectedModel?.model,
                enabled = !screen.isSending && screen.pendingDisclosure == null,
                onSave = { provider, model, reasoning ->
                    chatService
                        .requestSessionModelSelection(
                            sessionId,
                            provider,
                            model,
                            reasoning,
                            screen.modelSelectionSnapshot,
                        ).await()
                },
                entry = { open ->
                    SettingsEntryRow(
                        stringResource(R.string.session_settings_model),
                        R.drawable.ic_drawer_models,
                        open,
                        Modifier.testTag("chat-model-menu"),
                        screen.modelBindingIssue?.let { stringResource(it.messageResource) }
                            ?: screen.selectedModel?.model ?: stringResource(R.string.chat_select_model),
                        enabled = !screen.isSending && screen.pendingDisclosure == null,
                    )
                },
                sourceGroups = providerService.sourceGroups,
                onConfigureSource = onConfigureModelSource,
                onRefreshModels = providerService.builtInModels::refresh,
                reasoning = runControl.reasoning,
                sessionKey = sessionId,
                onDetectReasoning = providerService::discoverReasoningOptions,
            )
        }

        SettingsGroup {
            Text(stringResource(R.string.session_settings_workspace), style = MaterialTheme.typography.titleMedium)
            projects?.let {
                com.helix.app.projects.ui.ConversationProjectContext(
                    it,
                    files,
                    chatService,
                    screen,
                    onProject,
                )
            }

            SessionWorkspaceSection(
                files,
                screen.directoryRef,
                sessionId != null && !screen.isSending,
                sessionId = sessionId,
                projectDirectory = projectDirectory,
                projectName = currentProject?.name,
            ) {
                directoryOpen =
                    true
            }
        }

        SettingsGroup {
            Text(stringResource(R.string.session_settings_extensions), style = MaterialTheme.typography.titleMedium)
            SettingsEntryRow(
                stringResource(R.string.composer_permission_title),
                R.drawable.ic_permission_shield,
                { withDurableSession { permissionOpen = true } },
                Modifier.testTag("session-settings-permissions"),
                summary?.permission?.let { stringResource(it.labelRes()) } ?: unavailable,
                enabled = sessionId != null && !preparing,
            )
            if (skills != null) {
                SettingsEntryRow(
                    stringResource(R.string.session_skills_title),
                    R.drawable.ic_drawer_book,
                    { withDurableSession { skillsOpen = true } },
                    Modifier.testTag("session-settings-skills"),
                    summary?.let { stringResource(R.string.session_settings_selected_count, it.skills) } ?: unavailable,
                    enabled = sessionId != null && !preparing,
                )
            }
            prepareFailure?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("session-settings-error"))
            }
        }
    }
    if (modeOpen) {
        SessionModeDialog(chatService, sessionId, runControl, screen.isSending) { modeOpen = false }
    }
    if (permissionOpen && sessionId != null && !screen.isDraft) {
        ConversationSheet(stringResource(R.string.composer_permission_title), "session-permissions", {
            permissionOpen =
                false
        }) {
            SessionPermissionSessionSection(permissionEdit, chatService)
        }
    }

    if (directoryOpen && sessionId != null) {
        SessionDirectoryDialog(
            files,
            { directoryOpen = false },
            initialReference = screen.directoryRef,
            projectDirectory = projectDirectory,
        ) { reference ->
            chatService.setSessionDirectory(reference, sessionId).await()
        }
    }
}
