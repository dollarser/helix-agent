package com.helix.app.marketplace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.helix.app.plugin.PluginCompatibilityDetails
import com.helix.app.ui.rememberImportActionState
import com.helix.extensions.plugin.PluginMarketplace
import com.helix.extensions.plugin.PluginPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun MarketplaceSourcesSection(
    service: MarketplaceService,
    revision: Int,
    onChanged: () -> Unit,
) {
    val action = rememberImportActionState()
    var sources by remember { mutableStateOf<List<PluginMarketplace>>(emptyList()) }
    var preview by remember { mutableStateOf<MarketplaceSourcePreview?>(null) }
    var remove by remember { mutableStateOf<PluginMarketplace?>(null) }
    LaunchedEffect(revision) { action.launch { sources = withContext(Dispatchers.IO) { service.imports.sources() } } }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri !=
                null
            ) {
                action.launch { preview = withContext(Dispatchers.IO) { service.imports.previewSource(uri) } }
            }
        }
    OutlinedButton(
        onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) },
        enabled = !action.busy,
        modifier = Modifier.testTag("marketplace-add-source"),
    ) { Text(stringResource(R.string.plugin_market_add_source)) }
    if (action.failed) Text(stringResource(R.string.plugin_market_failed), color = MaterialTheme.colorScheme.error)
    sources.forEach { source ->
        TextButton(onClick = { remove = source }, enabled = !action.busy) {
            Text(stringResource(R.string.plugin_market_source_remove, source.name))
        }
    }
    preview?.let { selected ->
        MarketplaceSourceDialog(selected, action.busy, onDismiss = { preview = null }) {
            action.launch {
                withContext(Dispatchers.IO) {
                    val job = coroutineContext
                    service.imports.addSource(selected) { !job[kotlinx.coroutines.Job]!!.isActive }
                }
                preview = null
                onChanged()
            }
        }
    }
    remove?.let { selected ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(selected.name) },
            text = { Text(stringResource(R.string.plugin_market_remove_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    action.launch {
                        withContext(Dispatchers.IO) { service.imports.removeSource(selected) }
                        remove = null
                        onChanged()
                    }
                }, enabled = !action.busy) { Text(stringResource(R.string.plugin_market_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { remove = null }) { Text(stringResource(R.string.session_export_close)) }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun MarketplaceSourceDialog(
    preview: MarketplaceSourcePreview,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preview.catalog.name) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.plugin_market_source_hint))
                preview.catalog.entries.forEach { entry ->
                    Text("${entry.name} · ${entry.version.orEmpty()}")
                    if (entry.issue != null) Text(stringResource(R.string.plugin_market_package_needed))
                }
            }
        },
        confirmButton = {
            Button(onClick = onAdd, enabled = !busy, modifier = Modifier.testTag("marketplace-source-confirm")) {
                Text(stringResource(R.string.plugin_market_add_source))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_export_close)) } },
    )
}

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun MarketplaceCandidateDialog(
    service: MarketplaceService,
    item: MarketplaceItem,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    val action = rememberImportActionState()
    var bundle by remember(item.id) { mutableStateOf<PluginPackage?>(item.imported?.entry?.bundle) }
    var previousRevision by remember(item.id) { mutableStateOf<Long?>(null) }
    var installedVersion by remember(item.id) { mutableStateOf<String?>(null) }
    var loaded by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        action.launch {
            val installed = withContext(Dispatchers.IO) { service.findInstalled(item) }
            previousRevision = installed?.revision
            installedVersion = installed?.versionLabel
            loaded = true
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                action.launch { bundle = withContext(Dispatchers.IO) { service.imports.previewProvided(item, uri) } }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(itemName(item)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.plugin_market_source, requireNotNull(item.imported).sourceName))
                installedVersion?.let { Text(stringResource(R.string.plugin_market_installed_version, it)) }
                Text(stringResource(R.string.plugin_market_install_hint))
                if (action.failed) {
                    Text(stringResource(R.string.plugin_market_failed), color = MaterialTheme.colorScheme.error)
                }
                val selected = bundle
                if (selected == null) {
                    Text(stringResource(R.string.plugin_market_package_needed))
                    OutlinedButton(onClick = {
                        picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream"))
                    }, enabled = !action.busy) { Text(stringResource(R.string.plugin_market_choose_package)) }
                } else {
                    selected.versionLabel?.let { Text(stringResource(R.string.plugin_market_candidate_version, it)) }
                    val components =
                        stringResource(
                            R.string.plugin_market_components,
                            selected.skills.size,
                            selected.endpoints.size,
                        )
                    Text(components)
                    selected.endpoints.forEach { endpoint ->
                        Text("${endpoint.name} · ${endpoint.url}", style = MaterialTheme.typography.bodySmall)
                    }
                    PluginCompatibilityDetails(selected.componentDiagnostics)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selected = requireNotNull(bundle)
                    action.launch {
                        withContext(Dispatchers.IO) {
                            coroutineContext.ensureActive()
                            val job = coroutineContext[kotlinx.coroutines.Job]
                            service.imports.installCandidate(
                                item,
                                selected,
                                previousRevision,
                            ) { job?.isActive == false }
                        }
                        onInstalled()
                    }
                },
                enabled = loaded && !action.busy && bundle?.let(::hasSupportedComponents) == true,
                modifier = Modifier.testTag("marketplace-candidate-confirm"),
            ) {
                Text(stringResource(R.string.marketplace_action_install))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_export_close)) } },
    )
}
