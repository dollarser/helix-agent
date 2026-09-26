package com.helix.app.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.core.model.AgentMode

/** Contextual add/configure menu. Only capabilities backed by real domain state are shown. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod") // Declarative menu rows are intentionally co-located.
internal fun ComposerAddSheet(
    mode: AgentMode,
    messageEnabled: Boolean,
    sessionConfigEnabled: Boolean,
    onMode: (AgentMode) -> Unit,
    actions: ComposerActions,
    onDismiss: () -> Unit,
) {
    ConversationSheet(
        stringResource(R.string.composer_add_title),
        "composer-add",
        onDismiss,
    ) {
        Text(stringResource(R.string.composer_add_message_group))
        TextButton(
            onClick = {
                onDismiss()
                actions.onCamera()
            },
            enabled = messageEnabled,
            modifier = Modifier.testTag("composer-add-camera"),
        ) {
            Text(stringResource(R.string.composer_add_camera))
        }
        TextButton(
            onClick = {
                onDismiss()
                actions.onPhoto()
            },
            enabled = messageEnabled,
            modifier = Modifier.testTag("composer-add-photo"),
        ) {
            Text(stringResource(R.string.composer_add_photo))
        }
        TextButton(
            onClick = {
                onDismiss()
                actions.onFile()
            },
            enabled = messageEnabled,
            modifier = Modifier.testTag("composer-add-file"),
        ) {
            Text(stringResource(R.string.composer_add_file))
        }
        TextButton(
            onClick = {
                onDismiss()
                actions.onReference()
            },
            enabled = messageEnabled,
            modifier = Modifier.testTag("composer-add-reference"),
        ) {
            Text(stringResource(R.string.composer_add_reference))
        }
        HorizontalDivider()
        Text(stringResource(R.string.composer_add_session_group))
        ComposerModeMenu(mode, sessionConfigEnabled, onMode)
        TextButton(
            onClick = {
                onDismiss()
                actions.onExpert()
            },
            enabled = sessionConfigEnabled,
            modifier = Modifier.testTag("composer-add-expert"),
        ) {
            Text(stringResource(R.string.session_expert_title))
        }
        TextButton(
            onClick = {
                onDismiss()
                actions.onSkills()
            },
            enabled = sessionConfigEnabled,
            modifier = Modifier.testTag("composer-add-skills"),
        ) {
            Text(stringResource(R.string.session_skills_title))
        }
        TextButton(
            onClick = {
                onDismiss()
                actions.onConnectors()
            },
            enabled = sessionConfigEnabled,
            modifier = Modifier.testTag("composer-add-connectors"),
        ) {
            Text(stringResource(R.string.connector_session_title))
        }
        TextButton(
            onClick = {
                onDismiss()
                actions.onSessionSettings()
            },
            modifier = Modifier.testTag("composer-add-session-settings"),
        ) {
            Text(stringResource(R.string.session_settings_title))
        }
    }
}
