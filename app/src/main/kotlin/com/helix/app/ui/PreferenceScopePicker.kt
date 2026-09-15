package com.helix.app.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.approval.PreferenceScopeChoice
import com.helix.app.approval.ToolApprovalSettingsModel
import com.helix.core.model.ToolApprovalPreferenceScope

@Composable
@Suppress("FunctionName")
internal fun PreferenceScopePicker(
    choices: List<PreferenceScopeChoice>,
    selected: PreferenceScopeChoice,
    select: (PreferenceScopeChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("preference-scope-picker")) {
            Text(scopeLabel(selected))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(scopeLabel(choice)) },
                    onClick = {
                        select(choice)
                        open = false
                    },
                    modifier = Modifier.testTag("preference-scope-${choice.key}"),
                )
            }
        }
    }
}

@Composable
private fun scopeLabel(choice: PreferenceScopeChoice): String =
    when (choice.scope) {
        ToolApprovalPreferenceScope.GLOBAL -> {
            stringResource(R.string.preference_scope_global)
        }

        ToolApprovalPreferenceScope.SESSION -> {
            stringResource(R.string.preference_scope_session, choice.label)
        }

        ToolApprovalPreferenceScope.WORKSPACE -> {
            stringResource(
                R.string.preference_scope_workspace,
                choice.label.ifBlank { stringResource(R.string.preference_default_workspace) },
            )
        }
    }

@Composable
@Suppress("FunctionName")
internal fun PreferenceSources(row: ToolApprovalSettingsModel.Row) {
    row.records.forEach { rule ->
        val label =
            if (rule.scope == row.selection.scope && rule.scopeRef.orEmpty() == row.selection.ref) {
                scopeLabel(row.selection)
            } else {
                when (rule.scope) {
                    ToolApprovalPreferenceScope.GLOBAL -> stringResource(R.string.preference_scope_global)
                    ToolApprovalPreferenceScope.WORKSPACE -> stringResource(R.string.preference_inherited_workspace)
                    ToolApprovalPreferenceScope.SESSION -> stringResource(R.string.preference_inherited_session)
                }
            }
        val value =
            stringResource(
                when (rule.preference) {
                    com.helix.core.model.ToolApprovalPreference.ALLOW -> R.string.settings_tool_approval_action_allow
                    com.helix.core.model.ToolApprovalPreference.ASK -> R.string.settings_tool_approval_action_ask
                    com.helix.core.model.ToolApprovalPreference.DENY -> R.string.settings_tool_approval_action_deny
                },
            )
        Text(
            stringResource(R.string.preference_rule_source, label, value),
            Modifier.testTag("tool-approval-scopes-${row.toolName}"),
        )
    }
}
