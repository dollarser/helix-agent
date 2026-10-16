package com.helix.app.plugin

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.plugin.PluginDiagnostic
import com.helix.extensions.plugin.PluginPackage

internal fun limitedPluginComponents(diagnostics: List<PluginDiagnostic>): Int =
    diagnostics.count { it.omitted || it.code in setOf("SKILL_REQUIRES_BINARY", "DESKTOP_SETUP_NOT_EXECUTED") }

@Composable
@Suppress("FunctionName")
internal fun PluginImportError(failure: PluginImportFailure?) {
    Text(
        stringResource(failure?.messageRes ?: R.string.plugin_import_failed),
        modifier = Modifier.testTag("plugin-import-error"),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
@Suppress("FunctionName")
internal fun PluginImportSupport(
    skills: Int,
    endpoints: Int,
    diagnostics: List<PluginDiagnostic>,
) {
    Text(stringResource(R.string.plugin_components_summary, skills, endpoints))
    if (endpoints > 0) Text(stringResource(R.string.plugin_import_connection_hint))
    val limited = limitedPluginComponents(diagnostics)
    if (limited > 0) {
        Text(stringResource(R.string.plugin_import_limited, limited), Modifier.testTag("plugin-import-limited"))
    }
    if (skills == 0 && endpoints == 0) Text(stringResource(R.string.plugin_import_unsupported))
}

@Composable
@Suppress("FunctionName")
internal fun PluginPackagePreview(
    bundle: PluginPackage,
    update: PluginUpdateReport? = null,
) {
    Text(bundle.name, style = MaterialTheme.typography.titleMedium)
    bundle.versionLabel?.let { Text(stringResource(R.string.plugin_version, it)) }
    PluginImportSupport(bundle.skills.size, bundle.endpoints.size, bundle.componentDiagnostics)
    if (bundle.skills.isNotEmpty()) {
        Text(
            stringResource(
                R.string.plugin_import_skill_names,
                bundle.skills.joinToString(limit = 5, truncated = "…") { it.directory },
            ),
        )
    }
    if (bundle.endpoints.isNotEmpty()) {
        Text(
            stringResource(
                R.string.plugin_import_connection_names,
                bundle.endpoints.joinToString(limit = 5, truncated = "…") { it.name },
            ),
        )
    }
    update?.let { PluginUpdatePreview(it, bundle.releaseNotes) }
    var details by remember(bundle.contentHash) { mutableStateOf(false) }
    TextButton({ details = !details }, Modifier.testTag("plugin-import-details")) {
        Text(stringResource(R.string.plugin_details))
    }
    if (details) PluginPackageDetails(bundle)
}

@Composable
@Suppress("FunctionName")
private fun PluginPackageDetails(bundle: PluginPackage) {
    Text("${bundle.source} · ${bundle.contentHash}", style = MaterialTheme.typography.bodySmall)
    bundle.endpoints.forEach { Text("${it.name}: ${it.url}", style = MaterialTheme.typography.bodySmall) }
    bundle.skills.forEach { skill ->
        var expanded by remember(bundle.contentHash, skill.directory) { mutableStateOf(false) }
        TextButton({ expanded = !expanded }) { Text(skill.directory) }
        if (expanded) {
            skill.files.toSortedMap().forEach { (path, bytes) ->
                Text("$path · ${bytes.size} B", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                skill.files["SKILL.md"]
                    ?.toString(Charsets.UTF_8)
                    .orEmpty()
                    .take(8192),
            )
        }
    }
    PluginCompatibilityDetails(bundle.componentDiagnostics, initiallyExpanded = true)
}

@Composable
@Suppress("FunctionName")
private fun PluginUpdatePreview(
    report: PluginUpdateReport,
    notes: String?,
) {
    val unspecified = stringResource(R.string.plugin_import_version_unknown)
    Text(
        stringResource(
            R.string.plugin_import_versions,
            report.oldVersion ?: unspecified,
            report.newVersion ?: unspecified,
        ),
    )
    listOf(
        R.string.plugin_import_added_skills to report.addedSkills,
        R.string.plugin_import_removed_skills to report.removedSkills,
        R.string.plugin_import_added_connections to report.addedEndpoints,
        R.string.plugin_import_removed_connections to report.removedEndpoints,
        R.string.plugin_import_changed_connections to report.changedEndpoints,
    ).forEach { (label, names) ->
        if (names.isNotEmpty()) Text(stringResource(label, names.joinToString(", ")))
    }
    if (report.requiresReconnection) Text(stringResource(R.string.plugin_import_reconnect))
    Text(notes ?: stringResource(R.string.connector_update_notes_missing))
}

@Composable
@Suppress("FunctionName")
internal fun PluginImportTargetPicker(
    review: PluginImportReview,
    busy: Boolean,
    onSelected: (PluginImportReview) -> Unit,
) {
    if (review.needsChoice) {
        Text(stringResource(R.string.plugin_import_choose_target))
        review.candidates.forEach { record ->
            OutlinedButton(
                onClick = { onSelected(review.chooseTarget(record.id)) },
                enabled = !busy,
                modifier = Modifier.testTag("plugin-import-target-${record.id}"),
            ) {
                Column {
                    Text(stringResource(R.string.connector_update_target, record.name))
                    Text("${record.versionLabel.orEmpty()} · ${record.id}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton({
            onSelected(review.chooseSeparate())
        }, enabled = !busy, modifier = Modifier.testTag("plugin-import-separate")) {
            Text(stringResource(R.string.plugin_import_separate))
        }
    } else if (review.alreadyInstalled) {
        Text(stringResource(R.string.plugin_import_already_installed))
    } else {
        review.target?.let { Text(stringResource(R.string.connector_update_target, it.name)) }
    }
}

@Composable
@Suppress("FunctionName")
internal fun PluginImportResult(
    record: InstalledPlugin,
    onUse: ((String?) -> Unit)?,
    onConfigure: ((String) -> Unit)?,
) {
    Text(stringResource(R.string.plugin_import_done, record.name), Modifier.testTag("plugin-import-done"))
    PluginImportSupport(record.skills.size, record.endpoints.size, record.componentDiagnostics)
    if (record.endpoints.isNotEmpty() && onConfigure != null) {
        OutlinedButton({ onConfigure(record.id) }, Modifier.testTag("plugin-import-configure")) {
            Text(stringResource(R.string.plugin_configuration))
        }
    }
    if (onUse != null) {
        OutlinedButton({ onUse(record.id) }, Modifier.testTag("plugin-import-use")) {
            Text(stringResource(R.string.extensions_use))
        }
    }
}
