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

/** Message materials, one-shot skills and the owner-requested expert/plugin shortcuts. */
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
    ConversationSheet(stringResource(R.string.composer_add_title), "composer-add", onDismiss) {
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
                enabled = messageEnabled,
            )
        }
        Text(
            stringResource(R.string.composer_add_session_group),
            Modifier.padding(top = 12.dp, bottom = 4.dp).testTag("composer-add-session-group"),
            style = MaterialTheme.typography.labelMedium,
        )
        ConversationMenuRow(
            stringResource(R.string.skill_selection_title),
            {
                onDismiss()
                actions.onSkills()
            },
            Modifier.testTag("composer-add-skills"),
            enabled = messageEnabled,
        )
        ConversationMenuRow(
            stringResource(R.string.connector_session_title),
            {
                onDismiss()
                actions.onConnectors()
            },
            Modifier.testTag("composer-add-connectors"),
            supporting = pluginNames.takeIf { it.isNotEmpty() }?.joinToString(),
            enabled = capabilityEnabled,
        )
        ConversationMenuRow(
            stringResource(R.string.expert_select),
            {
                onDismiss()
                actions.onExpert()
            },
            Modifier.testTag("composer-add-expert"),
            supporting = expertName,
            enabled = capabilityEnabled,
        )
    }
}
