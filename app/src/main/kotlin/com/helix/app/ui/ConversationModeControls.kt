package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode

@Composable
@Suppress("FunctionName")
internal fun ModeControlSection(
    config: RunControlConfig,
    turnActive: Boolean,
    onChatTools: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("chat-mode-control"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(modeExplanation(config.mode)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("chat-mode-explanation"),
        )
        if (config.mode == AgentMode.CHAT) {
            TextButton(
                onClick = { onChatTools(!config.chatToolsEnabled) },
                enabled = !turnActive,
                modifier = Modifier.testTag("chat-tools-toggle"),
            ) {
                Text(
                    stringResource(
                        if (config.chatToolsEnabled) R.string.chat_tools_disable else R.string.chat_tools_enable,
                    ),
                )
            }
        }
    }
}

private fun modeExplanation(mode: AgentMode): Int =
    when (mode) {
        AgentMode.CHAT -> R.string.chat_mode_chat_explanation
        AgentMode.PLAN -> R.string.chat_mode_plan_explanation
        AgentMode.ACT -> R.string.chat_mode_act_explanation
        AgentMode.GOAL -> R.string.chat_mode_goal_explanation
    }

internal fun modeLabel(mode: AgentMode): Int =
    when (mode) {
        AgentMode.CHAT -> R.string.chat_mode_label_chat
        AgentMode.PLAN -> R.string.chat_mode_label_plan
        AgentMode.ACT -> R.string.chat_mode_label_act
        AgentMode.GOAL -> R.string.chat_mode_label_goal
    }
