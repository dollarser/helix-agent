package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.approval.PreferenceScopeChoice
import com.helix.app.approval.ToolApprovalSettingsModel
import com.helix.app.approval.ToolApprovalSettingsState
import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The standing tool-approval preferences section (HXA-201, ADR-0052): search by tool name or
 * provider, the effective state WITH its provenance (unset / allow / ask / deny — an invalidated
 * allow visibly needs re-confirm, unset is never shown as allowed), and scope-specific
 * set / restore-default actions. The UI talks ONLY to [ToolApprovalSettingsModel] (which talks
 * to the single write service and the live read seam the Dispatcher re-resolves) — never the
 * DAO. The selected context uses the runtime preference resolver; Policy still runs separately.
 */
@Composable
@Suppress("FunctionName")
internal fun ToolApprovalSettingsSection(model: ToolApprovalSettingsModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<ToolApprovalSettingsModel.Row>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    val revision by model.changes.collectAsStateWithLifecycle()
    var selectedKey by rememberSaveable { mutableStateOf(PreferenceScopeChoice.GLOBAL.key) }
    var choices by remember { mutableStateOf(listOf(PreferenceScopeChoice.GLOBAL)) }
    val selected = choices.firstOrNull { it.key == selectedKey } ?: PreferenceScopeChoice.GLOBAL

    LaunchedEffect(query, tick, selectedKey, revision) {
        withContext(Dispatchers.IO) { model.scopeChoices() }.let { choices = it }
        val context = choices.firstOrNull { it.key == selectedKey } ?: PreferenceScopeChoice.GLOBAL
        rows = withContext(Dispatchers.IO) { model.rows(query, context) }
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
        PreferenceScopePicker(choices, selected) {
            selectedKey = it.key
            rows = emptyList()
        }
        Text(stringResource(R.string.preference_scope_explanation))
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.settings_tool_approval_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.filter { it.selection.key == selected.key }.forEach { row ->
            androidx.compose.runtime.key(row.sourceRef, row.toolName, row.selection.key) {
                ToolApprovalSettingsRow(model = model, row = row, onAction = { tick++ })
            }
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
            PreferenceSources(row)
        }
        ToolApprovalTriStateButtons(model = model, row = row, onAction = onAction)
    }
}

/**
 * The per-tool actions: store ALLOW / ASK / DENY in the selected scope, or restore the default by
 * removing only that scope record. Each is a deliberate, separate user action — a one-call approval
 * never writes a standing preference. A FlowRow keeps all four reachable on narrow viewports and
 * at large font scales (HXA-201 small-screen / large-font device matrix).
 */
@Composable
@Suppress("FunctionName")
private fun ToolApprovalTriStateButtons(
    model: ToolApprovalSettingsModel,
    row: ToolApprovalSettingsModel.Row,
    onAction: () -> Unit,
) {
    // The writes are suspend and hop to the IO dispatcher themselves (Room's main-thread
    // guard): the click handler only launches, it never does storage I/O on the main thread.
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    notice?.let { Text(stringResource(it), Modifier.testTag("preference-save-notice-${row.toolName}")) }

    fun save(write: suspend () -> ToolApprovalSettingsModel.Row) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                notice = savePreferenceNotice(write)
                onAction()
            } finally {
                busy = false
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                save { model.setPreference(row, ToolApprovalPreference.ALLOW) }
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-allow"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_allow)) }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                save { model.setPreference(row, ToolApprovalPreference.ASK) }
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-ask"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_ask)) }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                save { model.setPreference(row, ToolApprovalPreference.DENY) }
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-deny"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_deny)) }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                save { model.restoreDefault(row) }
            },
            modifier = Modifier.testTag("tool-approval-${row.toolName}-reset"),
        ) { Text(stringResource(R.string.settings_tool_approval_action_reset)) }
    }
}

/** The user-facing copy of each effective state (the provenance is part of the copy). */
internal fun stateResOf(state: ToolApprovalSettingsState): Int =
    when (state) {
        ToolApprovalSettingsState.UNSET -> R.string.settings_tool_approval_state_unset
        ToolApprovalSettingsState.ALLOW -> R.string.settings_tool_approval_state_allow
        ToolApprovalSettingsState.ASK -> R.string.settings_tool_approval_state_ask
        ToolApprovalSettingsState.ASK_INVALIDATED -> R.string.settings_tool_approval_state_ask_invalidated
        ToolApprovalSettingsState.ASK_NEW_DEFAULT -> R.string.settings_tool_approval_state_ask_new_default
        ToolApprovalSettingsState.DENY -> R.string.settings_tool_approval_state_deny
    }
