package com.helix.app.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        "documents" -> "Documents"
        else -> record.name
    }

internal data class PluginManagementActions(
    val use: (() -> Unit)?,
    val settings: () -> Unit,
    val changed: () -> Unit,
    val permissions: () -> Unit,
    val configurationShown: () -> Unit,
)

/** One presentation for all installations; source only controls applicable management actions. */
@Composable
@Suppress("FunctionName")
internal fun PluginManagementCard(
    record: InstalledPlugin,
    service: PluginService,
    actions: PluginManagementActions,
    configurationRequested: Boolean,
    configuration: @Composable () -> Unit,
    details: @Composable () -> Unit,
    installedContents: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    var section by rememberSaveable(record.id) { mutableStateOf(0) }
    var nativeDescription by remember(record) { mutableStateOf("") }
    LaunchedEffect(configurationRequested) {
        if (configurationRequested) {
            expanded = true
            section = 0
            actions.configurationShown()
        }
    }
    LaunchedEffect(record, service) {
        nativeDescription =
            record.native?.let { withContext(Dispatchers.IO) { service.bundledDescription(it.pluginId) } }.orEmpty()
    }
    Card(Modifier.fillMaxWidth().testTag("plugin-card-${record.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PluginManagementHeader(record, nativeDescription) { expanded = !expanded }
            if (expanded) {
                actions.use?.let {
                    TextButton(
                        it,
                        Modifier.testTag("plugin-use-${record.id}"),
                    ) { Text(stringResource(R.string.extensions_use)) }
                }
                PluginManagementTabs(record.id, section) { section = it }
                when (section) {
                    0 -> {
                        PluginConfiguration(record, service, actions)
                        configuration()
                    }

                    1 -> {
                        Text(
                            stringResource(R.string.plugin_guidance_loading),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        record.native?.let { PluginBundledContents(service, it.pluginId) } ?: installedContents()
                    }

                    else -> {
                        record.versionLabel?.let { Text(stringResource(R.string.plugin_version, it)) }
                        Text("${record.source} · ${record.hash.take(12)}", style = MaterialTheme.typography.bodySmall)
                        PluginCompatibilityDetails(record.componentDiagnostics)
                        details()
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun PluginManagementTabs(
    id: String,
    section: Int,
    onSelect: (Int) -> Unit,
) {
    SecondaryTabRow(selectedTabIndex = section) {
        listOf(R.string.plugin_configuration, R.string.plugin_guidance_tools, R.string.plugin_details)
            .forEachIndexed { index, label ->
                Tab(
                    selected = section == index,
                    onClick = { onSelect(index) },
                    text = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("plugin-tab-$index-$id"),
                )
            }
    }
}

@Composable
@Suppress("FunctionName")
private fun PluginManagementHeader(
    record: InstalledPlugin,
    nativeDescription: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick,
        Modifier.testTag(record.native?.let { "bundled-plugin-${it.pluginId}" } ?: "extension-manage-${record.id}"),
    ) {
        Text(
            if (record.native?.pluginId ==
                "documents"
            ) {
                stringResource(R.string.document_plugin_name)
            } else {
                pluginDisplayName(record)
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Text(
        stringResource(
            if (record.native !=
                null
            ) {
                R.string.plugin_origin_bundled
            } else {
                R.string.plugin_origin_installed
            },
        ),
    )
    Text(
        when (record.native?.pluginId) {
            "mobile-use" -> stringResource(R.string.bundled_mobile_use_description)
            "browser" -> stringResource(R.string.plugin_browser_description)
            "documents" -> stringResource(R.string.plugin_documents_description)
            "chat-reply" -> stringResource(R.string.plugin_chat_reply_description)
            null -> stringResource(R.string.plugin_components_summary, record.skills.size, record.endpoints.size)
            else -> nativeDescription
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
@Suppress("FunctionName")
private fun PluginConfiguration(
    record: InstalledPlugin,
    service: PluginService,
    actions: PluginManagementActions,
) {
    PluginAvailabilityControls(record, service, actions.changed) { error ->
        if (error == PluginSetupReason.MOBILE_BACKEND) actions.permissions() else actions.settings()
    }
    if (record.native?.pluginId == "mobile-use") {
        TextButton(actions.settings, Modifier.testTag("mobile-use-plugin-settings")) {
            Text(stringResource(R.string.bundled_mobile_use_settings))
        }
    } else if (record.endpoints.isEmpty()) {
        Text(stringResource(R.string.plugin_no_configuration))
    }
}
