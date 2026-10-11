package com.helix.app.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.plugin.PluginSetupReason

internal fun matchesPluginQuery(
    record: InstalledPlugin,
    query: String,
    contents: List<String> = emptyList(),
): Boolean {
    val term = query.trim()
    return listOf(pluginDisplayName(record), record.native?.pluginId.orEmpty())
        .plus(contents)
        .plus(record.skills.map { it.name })
        .plus(record.endpoints.map { it.endpoint.name })
        .any { it.contains(term, ignoreCase = true) }
}

internal fun pluginDisplayName(record: InstalledPlugin): String =
    when (record.native?.pluginId) {
        "mobile-use" -> "Mobile Use"
        "browser" -> "Browser"
        else -> record.name
    }

/** One presentation for all installations; source only controls applicable management actions. */
@Composable
@Suppress("FunctionName")
internal fun PluginManagementCard(
    record: InstalledPlugin,
    service: PluginService,
    onUse: (() -> Unit)?,
    onPluginSettings: () -> Unit,
    onChanged: () -> Unit,
    onSystemPermissions: () -> Unit = onPluginSettings,
    installedContents: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("plugin-card-${record.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                { expanded = !expanded },
                Modifier.testTag(
                    record.native?.let { "bundled-plugin-${it.pluginId}" } ?: "extension-manage-${record.id}",
                ),
            ) { Text(pluginDisplayName(record), style = MaterialTheme.typography.titleMedium) }
            Text(
                stringResource(
                    if (record.native != null) R.string.plugin_origin_bundled else R.string.plugin_origin_installed,
                ),
            )
            Text(
                if (record.native?.pluginId == "mobile-use") {
                    stringResource(R.string.bundled_mobile_use_description)
                } else {
                    stringResource(R.string.plugin_components_summary, record.skills.size, record.endpoints.size)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                PluginAvailabilityControls(record, service, onChanged) { error ->
                    if (error == PluginSetupReason.MOBILE_BACKEND) onSystemPermissions() else onPluginSettings()
                }
                if (record.native?.pluginId == "mobile-use") {
                    TextButton(onPluginSettings, Modifier.testTag("mobile-use-plugin-settings")) {
                        Text(stringResource(R.string.bundled_mobile_use_settings))
                    }
                }
                onUse?.let {
                    TextButton(
                        it,
                        Modifier.testTag("plugin-use-${record.id}"),
                    ) { Text(stringResource(R.string.extensions_use)) }
                }
                record.native?.let { PluginBundledContents(service, it.pluginId) } ?: installedContents()
            }
        }
    }
}
