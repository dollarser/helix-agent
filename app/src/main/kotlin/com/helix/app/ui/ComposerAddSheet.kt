package com.helix.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Existing actions, grouped by purpose; the supporting labels make their scope explicit. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
internal fun ComposerAddSheet(
    messageEnabled: Boolean,
    capabilityEnabled: Boolean,
    actions: ComposerActions,
    onDismiss: () -> Unit,
    expertName: String? = null,
    pluginNames: List<String> = emptyList(),
) {
    val messageScope = stringResource(R.string.composer_scope_message)
    val sessionScope = stringResource(R.string.composer_scope_session)
    ConversationSheet(stringResource(R.string.composer_add_title), "composer-add", onDismiss) {
        Text(
            stringResource(R.string.composer_add_message_group),
            Modifier.padding(top = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConversationMenuRow(
            stringResource(R.string.composer_add_camera),
            {
                onDismiss()
                actions.onCamera()
            },
            Modifier.testTag("composer-add-camera"),
            enabled = messageEnabled,
        )
        ConversationMenuRow(
            stringResource(R.string.composer_add_photo),
            {
                onDismiss()
                actions.onPhoto()
            },
            Modifier.testTag("composer-add-photo"),
            enabled = messageEnabled,
        )
        ConversationMenuRow(
            stringResource(R.string.composer_add_file),
            {
                onDismiss()
                actions.onFile()
            },
            Modifier.testTag("composer-add-file"),
            enabled = messageEnabled,
        )
        ConversationMenuRow(
            stringResource(R.string.composer_add_reference),
            {
                onDismiss()
                actions.onReference()
            },
            Modifier.testTag("composer-add-reference"),
            supporting = messageScope,
            enabled = messageEnabled,
        )
        actions.onKnowledgeReference?.let { add ->
            ConversationMenuRow(
                stringResource(R.string.knowledge_reference_add),
                {
                    onDismiss()
                    add()
                },
                Modifier.testTag("composer-add-knowledge-reference"),
                supporting = messageScope,
                enabled = messageEnabled,
            )
        }
        Text(
            stringResource(R.string.composer_add_session_group),
            Modifier.padding(top = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConversationMenuRow(
            stringResource(R.string.expert_select),
            {
                onDismiss()
                actions.onExpert()
            },
            Modifier.testTag("composer-add-expert"),
            supporting = (expertName ?: stringResource(R.string.expert_default)) + " · " + sessionScope,
            enabled = capabilityEnabled,
        )
        ConversationMenuRow(
            stringResource(R.string.connector_session_title),
            {
                onDismiss()
                actions.onConnectors()
            },
            Modifier.testTag("composer-add-connectors"),
            supporting =
                if (pluginNames.isEmpty()) {
                    sessionScope
                } else {
                    pluginNames.joinToString() + " · " + sessionScope
                },
            enabled = capabilityEnabled,
        )
        ConversationMenuRow(
            stringResource(R.string.skill_selection_title),
            {
                onDismiss()
                actions.onSkills()
            },
            Modifier.testTag("composer-add-skills"),
            supporting = stringResource(R.string.skill_pick_hint),
            enabled = messageEnabled,
        )
    }
}
