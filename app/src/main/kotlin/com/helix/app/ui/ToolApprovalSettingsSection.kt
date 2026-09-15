package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.approval.ToolApprovalSettingsModel
import com.helix.app.approval.ToolApprovalSettingsState
import com.helix.core.model.ToolApprovalPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The standing tool-approval preferences section (HXA-201, ADR-0052): search by tool name or
 * provider, the effective state WITH its provenance (unset / allow / ask / deny — an invalidated
 * allow visibly needs re-confirm, unset is never shown as allowed), and the GLOBAL-scope
 * set / restore-default actions. The UI talks ONLY to [ToolApprovalSettingsModel] (which talks
 * to the single write service and the live read seam the Dispatcher re-resolves) — never the
 * DAO, so what is shown is exactly what the runtime enforces before a call starts.
 */
@Composable
@Suppress("FunctionName")
internal fun ToolApprovalSettingsSection(model: ToolApprovalSettingsModel) {
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<ToolApprovalSettingsModel.Row>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(query, tick) {
        rows = withContext(Dispatchers.IO) { model.rows(query) }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("settings-tool-approval"),
    ) {
        Text(stringResource(R.string.settings_tool_approval_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.settings_tool_approval_search_hint)) },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("settings-tool-approval-search"),
        )
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.settings_tool_approval_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEach { row ->
            ToolApprovalSettingsRow(model = model, row = row, onAction = { tick++ })
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ToolApprovalSettingsRow(
    model: ToolApprovalSettingsModel,
    row: ToolApprovalSettingsModel.Row,
    onAction: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("tool-approval-row-${row.toolName}"),
    ) {
        Text(row.toolName, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${row.originLabel} · v${row.version} · ${row.baseRisk.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(stateResOf(row.state)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("tool-approval-state-${row.toolName}"),
        )
        if (row.records.isNotEmpty()) {
            Text(
                row.records.joinToString { record ->
                    "${record.scope.name.lowercase()}:${record.scopeRef.orEmpty().ifBlank { "-" }}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tool-approval-scopes-${row.toolName}"),
            )
        }
        ToolApprovalTriStateButtons(model = model, row = row, onAction = onAction)
    }
}

/**
 * The per-tool actions: store ALLOW / ASK / DENY in the GLOBAL scope, or restore the default by
 * removing the GLOBAL record. Each is a deliberate, separate user action — a one-call approval
 * never writes a standing preference.
 */
@Composable
@Suppress("FunctionName")
private fun ToolApprovalTriStateButtons(
    model: ToolApprovalSettingsModel,
    row: ToolApprovalSettingsModel.Row,
    onAction: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                model.setPreference(row, ToolApprovalPreference.ALLOW)
                onAction()
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-allow"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_allow)) }
        OutlinedButton(
            onClick = {
                model.setPreference(row, ToolApprovalPreference.ASK)
                onAction()
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-ask"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_ask)) }
        OutlinedButton(
            onClick = {
                model.setPreference(row, ToolApprovalPreference.DENY)
                onAction()
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-deny"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_deny)) }
        OutlinedButton(
            onClick = {
                model.restoreDefault(row)
                onAction()
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-reset"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_reset)) }
    }
}

/** The user-facing copy of each effective state (the provenance is part of the copy). */
private fun stateResOf(state: ToolApprovalSettingsState): Int =
    when (state) {
        ToolApprovalSettingsState.UNSET -> R.string.settings_tool_approval_state_unset
        ToolApprovalSettingsState.ALLOW -> R.string.settings_tool_approval_state_allow
        ToolApprovalSettingsState.ASK -> R.string.settings_tool_approval_state_ask
        ToolApprovalSettingsState.ASK_INVALIDATED -> R.string.settings_tool_approval_state_ask_invalidated
        ToolApprovalSettingsState.ASK_NEW_DEFAULT -> R.string.settings_tool_approval_state_ask_new_default
        ToolApprovalSettingsState.DENY -> R.string.settings_tool_approval_state_deny
    }
