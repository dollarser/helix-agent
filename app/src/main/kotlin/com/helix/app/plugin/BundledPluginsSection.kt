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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exposes existing catalog entries without granting device access or introducing another toggle. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun BundledPluginsSection(
    service: PluginService,
    onPluginSettings: () -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    var records by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(service, revision) {
        failed = false
        try {
            records = withContext(Dispatchers.IO) { service.list().filter { it.native != null } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    if (records.isEmpty() && !failed) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("bundled-plugins")) {
        Text(stringResource(R.string.bundled_plugins_title), style = MaterialTheme.typography.titleMedium)
        if (failed) {
            Text(stringResource(R.string.connector_failed))
            TextButton({ revision++ }) { Text(stringResource(R.string.chat_retry)) }
        }
        records.forEach { record ->
            var expanded by remember(record.id) { mutableStateOf(false) }
            Card(
                onClick = { expanded = !expanded },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("bundled-plugin-${record.native?.pluginId}"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (record.native?.pluginId == "mobile-use") "Mobile Use" else record.name)
                    if (record.native?.pluginId == "mobile-use") {
                        Text(stringResource(R.string.bundled_mobile_use_description))
                    }
                    PluginAvailabilityControls(record, service) { revision++ }
                    if (record.native?.pluginId == "mobile-use") {
                        TextButton(onPluginSettings, modifier = Modifier.testTag("mobile-use-plugin-settings")) {
                            Text(stringResource(R.string.bundled_mobile_use_settings))
                        }
                    }
                    Text(
                        stringResource(
                            if (expanded) R.string.plugin_contents_collapse else R.string.plugin_contents_expand,
                        ),
                    )
                    if (expanded) {
                        PluginBundledContents(service, requireNotNull(record.native).pluginId)
                    }
                }
            }
        }
    }
}
