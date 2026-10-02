package com.helix.app.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.IndicatedLazyColumn
import com.helix.tools.automation.AutomationApplication
import com.helix.tools.automation.AutomationApplicationChoices
import com.helix.tools.automation.AutomationApplicationFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Selection is a draft until Confirm. No catalog load, search or dismiss mutates the live grant. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
internal fun AutomationApplicationPicker(
    initialSelection: Set<String>,
    loadApplications: () -> List<AutomationApplication>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    permittedPackages: Set<String>? = null,
    singleSelection: Boolean = false,
) {
    var selection by rememberSaveable { mutableStateOf(initialSelection.toList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AutomationApplicationFilter.ALL) }
    var refresh by remember { mutableStateOf(0) }
    var catalog by remember { mutableStateOf<List<AutomationApplication>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        loading = true
        failed = false
        try {
            catalog = withContext(Dispatchers.IO) { loadApplications() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            failed = true
        } finally {
            loading = false
        }
    }
    val selected = selection.toSet()
    val visible =
        remember(catalog, selected, query, filter, permittedPackages) {
            AutomationApplicationChoices.visible(catalog, selected, query, filter, permittedPackages)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("automation-app-picker"),
        title = { Text(stringResource(R.string.automation_choose_apps)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.automation_app_search)) },
                    modifier = Modifier.fillMaxWidth().testTag("automation-app-search"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AutomationApplicationFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(stringResource(option.labelResource())) },
                            modifier = Modifier.testTag("automation-app-filter-${option.name}"),
                        )
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("automation-app-loading"))
                IndicatedLazyColumn(Modifier.weight(1f, fill = false).testTag("automation-app-list")) {
                    item(key = "picker-summary") {
                        Text(stringResource(R.string.automation_app_selection_count, selected.size))
                        Text(
                            stringResource(R.string.automation_app_scope_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (failed) {
                            Text(
                                stringResource(R.string.automation_app_load_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(
                            onClick = { refresh++ },
                            enabled = !loading,
                            modifier = Modifier.testTag("automation-app-refresh"),
                        ) { Text(stringResource(R.string.automation_app_refresh)) }
                    }
                    if (!loading && !failed && visible.isEmpty()) {
                        item { Text(stringResource(R.string.automation_app_empty)) }
                    }
                    items(visible, key = { it.packageName }) { app ->
                        ApplicationChoiceRow(app, app.packageName in selected) {
                            selection = AutomationApplicationChoices.toggle(selected, app, singleSelection).toList()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                // A catalog refresh failure must not prevent confirming already known selections.
                enabled = !singleSelection || selected.size == 1,
                modifier = Modifier.testTag("automation-app-confirm"),
            ) { Text(stringResource(R.string.automation_app_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("automation-app-cancel")) {
                Text(stringResource(R.string.automation_app_cancel))
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun ApplicationChoiceRow(
    app: AutomationApplication,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("automation-app-${app.packageName}")
                .toggleable(
                    selected,
                    enabled = app.available || selected,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = app.available || selected)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            val category =
                when {
                    !app.available -> R.string.automation_app_unavailable
                    app.system -> R.string.automation_app_system
                    else -> R.string.automation_app_user
                }
            Text(stringResource(category), style = MaterialTheme.typography.labelSmall)
            if (app.available && !app.launchable) {
                Text(stringResource(R.string.automation_app_no_launcher), style = MaterialTheme.typography.bodySmall)
            }
            if (app.available && !app.enabled) {
                Text(stringResource(R.string.automation_app_disabled), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun AutomationApplicationFilter.labelResource(): Int =
    when (this) {
        AutomationApplicationFilter.ALL -> R.string.automation_app_all
        AutomationApplicationFilter.USER -> R.string.automation_app_user
        AutomationApplicationFilter.SYSTEM -> R.string.automation_app_system
        AutomationApplicationFilter.SELECTED -> R.string.automation_app_selected
    }
