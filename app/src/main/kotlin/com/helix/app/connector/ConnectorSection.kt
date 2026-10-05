package com.helix.app.connector

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.helix.app.R
import com.helix.app.mcp.oauth.McpOAuthResult
import com.helix.app.plugin.InstalledEndpoint
import com.helix.app.plugin.InstalledPlugin
import com.helix.app.plugin.PluginService
import com.helix.app.ui.rememberImportActionState
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.mcp.oauth.McpOAuthVendor
import com.helix.extensions.mcp.oauth.mcpOAuthVendor
import com.helix.extensions.plugin.PluginPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
// Shared import action state preserves cancellation.
@Suppress("FunctionName", "LongMethod", "ThrowsCount", "CyclomaticComplexMethod")
fun ConnectorSection(
    service: PluginService,
    showImport: Boolean = true,
    showInstalled: Boolean = true,
    onUse: (() -> Unit)? = null,
    query: String = "",
    onInstalled: () -> Unit = {},
    onPluginSettings: () -> Unit = {},
) {
    val action = rememberImportActionState()
    var jsonDraft by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var replaceTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var preview by remember { mutableStateOf<PluginPackage?>(null) }
    var records by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var searchContents by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var revision by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf(false) }
    LaunchedEffect(service, revision) {
        loadFailed = false
        try {
            records = withContext(Dispatchers.IO) { service.list() }
            searchContents =
                withContext(Dispatchers.IO) {
                    records.associate { record ->
                        record.id to
                            record.native
                                ?.let {
                                    service.bundledSkillContents(it.pluginId).map { skill -> skill.name } +
                                        service.bundledToolDescriptions(it.pluginId).map { tool -> tool.first }
                                }.orEmpty()
                    }
                }
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                action.launch {
                    preview = null
                    preview = withContext(Dispatchers.IO) { service.preview(uri) }
                }
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.connector_title), style = MaterialTheme.typography.titleMedium)
        if (showImport) {
            Text(stringResource(R.string.connector_intro))
            OutlinedButton(
                onClick = {
                    replaceTarget = null
                    picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream"))
                },
                enabled = !action.busy,
                modifier = Modifier.testTag("connector-import"),
            ) {
                Text(stringResource(R.string.connector_import))
            }
            OutlinedButton(
                enabled = !action.busy,
                modifier = Modifier.testTag("connector-paste"),
                onClick = {
                    replaceTarget = null
                    showPaste = !showPaste
                },
            ) { Text(stringResource(R.string.connector_paste)) }
        }
        if (showPaste) {
            Text(stringResource(R.string.connector_paste_hint))
            OutlinedTextField(
                jsonDraft,
                {
                    jsonDraft = it
                    preview = null
                },
                enabled = !action.busy,
                minLines = 4,
                modifier = Modifier.testTag("connector-json"),
                label = { Text("MCP JSON") },
            )
            OutlinedButton(
                enabled = !action.busy && jsonDraft.isNotBlank(),
                modifier = Modifier.testTag("connector-json-preview"),
                onClick = {
                    action.launch {
                        preview = withContext(Dispatchers.IO) { service.previewJson(jsonDraft) }
                    }
                },
            ) { Text(stringResource(R.string.skill_creator_preview)) }
        }
        if (loadFailed || action.failed) {
            Text(stringResource(R.string.connector_failed), color = MaterialTheme.colorScheme.error)
        }
        if (service.cleanupPending) {
            Text(stringResource(R.string.connector_cleanup_pending))
            OutlinedButton(onClick = {
                action.launch {
                    withContext(Dispatchers.IO) { service.cleanupRetired() }
                    revision++
                }
            }, enabled = !action.busy) { Text(stringResource(R.string.connector_cleanup_retry)) }
        }
        preview?.let { bundle ->
            replaceTarget?.let { old ->
                Text(stringResource(R.string.connector_update_target, old.name))
                Text("${old.hash.take(12)} → ${bundle.contentHash.take(12)}")
                Text(
                    stringResource(
                        R.string.connector_update_changes,
                        old.skills.size,
                        bundle.skills.size,
                        old.endpoints.size,
                        bundle.endpoints.size,
                    ),
                )
            }
            bundle.versionLabel?.let { Text(stringResource(R.string.connector_update_version, it)) }
            Text(bundle.releaseNotes ?: stringResource(R.string.connector_update_notes_missing))
            Text(stringResource(R.string.connector_update_components, bundle.skills.size, bundle.endpoints.size))
            Text("${bundle.name} · ${bundle.source}")
            Text(bundle.contentHash, style = MaterialTheme.typography.bodySmall)
            bundle.endpoints.forEach { Text("${it.name}: ${it.url}") }
            bundle.skills.forEach { skill ->
                var expanded by remember(bundle.contentHash, skill.directory) { mutableStateOf(false) }
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text("Skill: ${skill.directory} (${skill.files.size})")
                }
                if (expanded) {
                    skill.files.toSortedMap().forEach { (path, bytes) ->
                        Text("$path · ${bytes.size} B", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        skill.files["SKILL.md"]
                            ?.toString(Charsets.UTF_8)
                            .orEmpty()
                            .take(8192),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (bundle.skills.isNotEmpty()) Text(stringResource(R.string.connector_skill_review))
            bundle.diagnostics.forEach { Text(diagnosticText(it), style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(
                enabled = !action.busy && (bundle.endpoints.isNotEmpty() || bundle.skills.isNotEmpty()),
                onClick = {
                    action.launch {
                        withContext(Dispatchers.IO) {
                            val old = replaceTarget
                            val installationContext = kotlin.coroutines.coroutineContext
                            service.install(
                                bundle,
                                old?.identity ?: "local:${bundle.source}:${bundle.contentHash}",
                                old?.revision,
                                cancelled = { !installationContext[kotlinx.coroutines.Job]!!.isActive },
                            )
                        }
                        replaceTarget = null
                        preview = null
                        jsonDraft = ""
                        showPaste = false
                        revision++
                        installed = true
                        onInstalled()
                    }
                },
                modifier = Modifier.testTag("connector-install"),
            ) { Text(stringResource(R.string.connector_install)) }
            OutlinedButton(
                onClick = {
                    preview = null
                    replaceTarget = null
                },
                enabled = !action.busy,
            ) { Text(stringResource(R.string.common_cancel)) }
        }
        if (installed && onUse != null) {
            OutlinedButton(onClick = onUse) { Text(stringResource(R.string.extensions_use)) }
        }
        val visible =
            records.filter {
                showInstalled &&
                    com.helix.app.plugin
                        .matchesPluginQuery(it, query, searchContents[it.id].orEmpty())
            }
        if (showInstalled && loaded) {
            if (visible.isEmpty() && !loadFailed) {
                Text(stringResource(if (query.isBlank()) R.string.plugins_empty else R.string.plugins_no_matches))
            }
        }
        visible.forEach { record ->
            androidx.compose.runtime.key(record.id) {
                com.helix.app.plugin.PluginManagementCard(record, service, onUse, onPluginSettings, { revision++ }) {
                    Text(stringResource(R.string.plugin_contents_skills, record.skills.size))
                    record.skills.forEach { key ->
                        var enabled by remember(key, revision) { mutableStateOf(false) }
                        LaunchedEffect(key, revision) {
                            enabled = withContext(Dispatchers.IO) { service.skillEnabled(key) }
                        }
                        Row {
                            Checkbox(checked = enabled, enabled = !action.busy, onCheckedChange = { value ->
                                action.launch {
                                    withContext(Dispatchers.IO) { service.setSkillEnabled(key, value) }
                                    enabled = value
                                }
                            })
                            var expanded by remember(key) { mutableStateOf(false) }
                            Column {
                                TextButton({ expanded = !expanded }) { Text("Skill: ${key.name}") }
                                if (expanded) PluginSkillPreview(service, key)
                            }
                        }
                    }
                    record.endpoints.forEach { endpoint -> KnownEndpointTools(service, endpoint) }
                    var technical by remember(record.id) { mutableStateOf(false) }
                    TextButton({ technical = !technical }) { Text(stringResource(R.string.plugin_connection_details)) }
                    if (technical) {
                        Text("${record.source} · ${record.hash.take(12)}", style = MaterialTheme.typography.bodySmall)
                        record.diagnostics.forEach {
                            Text(
                                diagnosticText(it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        record.endpoints.forEach { endpoint ->
                            androidx.compose.runtime.key(record.enabled) { EndpointRow(service, endpoint) }
                        }
                    }
                    if (record.native == null) {
                        OutlinedButton(enabled = !action.busy, onClick = {
                            replaceTarget = record
                            picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream"))
                        }, modifier = Modifier.testTag("connector-update-${record.id}")) {
                            Text(stringResource(R.string.connector_update_target, record.name))
                        }
                    }
                    if (record.native == null) {
                        Text(stringResource(R.string.connector_remove_note), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !action.busy, onClick = {
                            action.launch {
                                withContext(Dispatchers.IO) { service.remove(record) }
                                revision++
                            }
                        }) { Text(stringResource(R.string.connector_remove)) }
                    }
                }
            }
        }
    }
}

private enum class EndpointAuthMode { BEARER, OAUTH }

@Composable
// Independent UI event callbacks preserve cancellation.
@Suppress("FunctionName", "LongMethod", "ThrowsCount", "CyclomaticComplexMethod")
private fun EndpointRow(
    service: PluginService,
    endpoint: InstalledEndpoint,
) {
    val scope = rememberCoroutineScope()
    var authMode by remember(endpoint.id) {
        mutableStateOf(
            if (service.hasOAuthToken(endpoint)) EndpointAuthMode.OAUTH else EndpointAuthMode.BEARER,
        )
    }
    var hasOAuth by remember(endpoint.id) { mutableStateOf(service.hasOAuthToken(endpoint)) }
    var oauthError by remember(endpoint.id) { mutableStateOf<String?>(null) }
    var bearer by remember(endpoint.id) { mutableStateOf("") }
    var snapshot by remember(endpoint.id) { mutableStateOf<McpHandshakeSnapshot?>(null) }
    var selection by remember(endpoint.id) { mutableStateOf(emptySet<String>()) }
    var active by remember(endpoint.id) { mutableStateOf(service.enabled(endpoint)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(endpoint.id) {
        service.oauthCoordinator?.events?.collect { result ->
            if (result is McpOAuthResult.Success && result.serverId == endpoint.id) {
                hasOAuth = true
                oauthError = null
                busy = true
                failed = false
                snapshot = null
                selection = emptySet()
                try {
                    snapshot = withContext(Dispatchers.IO) { service.testOAuth(endpoint) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failed = true
                } finally {
                    active = service.enabled(endpoint)
                    busy = false
                }
            } else if (result is McpOAuthResult.Failure && result.serverId == endpoint.id) {
                oauthError = result.message
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${endpoint.endpoint.name}: ${endpoint.endpoint.url}")
        Text(stringResource(if (active) R.string.connector_active else R.string.connector_inactive))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = authMode != EndpointAuthMode.BEARER && !busy,
                onClick = {
                    service.cancelOAuth(endpoint)
                    service.disable(endpoint)
                    authMode = EndpointAuthMode.BEARER
                },
            ) {
                Text(stringResource(R.string.connector_auth_mode_bearer))
            }
            OutlinedButton(
                enabled = authMode != EndpointAuthMode.OAUTH && !busy,
                onClick = { authMode = EndpointAuthMode.OAUTH },
            ) {
                Text(stringResource(R.string.connector_auth_mode_oauth))
            }
        }

        val isGitHub =
            mcpOAuthVendor(endpoint.endpoint.url) == McpOAuthVendor.GITHUB
        if (authMode == EndpointAuthMode.BEARER) {
            val bearerLabel =
                if (isGitHub) {
                    stringResource(
                        R.string.connector_github_pat,
                    )
                } else {
                    stringResource(R.string.connector_bearer)
                }
            OutlinedTextField(
                value = bearer,
                onValueChange = { bearer = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(bearerLabel) },
            )
            if (isGitHub) {
                Text(
                    stringResource(R.string.connector_github_pat_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    failed = false
                    snapshot = null
                    selection = emptySet()
                    val credential = bearer
                    bearer = ""
                    try {
                        snapshot = withContext(Dispatchers.IO) { service.test(endpoint, credential) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        active = service.enabled(endpoint)
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.connector_test)) }
        } else {
            OAuthAuthSection(
                service = service,
                endpoint = endpoint,
                hasOAuth = hasOAuth,
                busy = busy,
                onHasOAuthChange = { hasOAuth = it },
                onTestOAuth = {
                    scope.launch {
                        busy = true
                        failed = false
                        snapshot = null
                        selection = emptySet()
                        try {
                            snapshot = withContext(Dispatchers.IO) { service.testOAuth(endpoint) }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            active = service.enabled(endpoint)
                            busy = false
                        }
                    }
                },
                onError = { oauthError = it },
            )
        }

        oauthError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        snapshot?.let { result ->
            var toolsExpanded by remember { mutableStateOf(false) }
            OutlinedButton(onClick = {
                selection =
                    result.metadata.tools
                        .map { it.name }
                        .toSet()
            }) { Text(stringResource(R.string.extensions_select_tools)) }
            OutlinedButton(onClick = { toolsExpanded = !toolsExpanded }) {
                Text(stringResource(R.string.extensions_tool_details))
            }
            if (toolsExpanded) {
                result.metadata.tools.forEach { tool ->
                    Row {
                        Checkbox(
                            checked = tool.name in selection,
                            enabled = !busy,
                            onCheckedChange = { checked ->
                                selection =
                                    if (checked) selection + tool.name else selection - tool.name
                            },
                        )
                        Column {
                            Text(tool.name)
                            Text(tool.schemaHash.take(12), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(enabled = !busy && selection.isNotEmpty(), onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { service.enable(endpoint, result, selection) }
                        active = service.enabled(endpoint)
                        snapshot = null
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.connector_enable)) }
        }
        if (active) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { service.disable(endpoint) }
                        active = false
                        snapshot = null
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed = true
                    }
                }
            }) { Text(stringResource(R.string.connector_disable)) }
        }
        if (failed) {
            Text(stringResource(R.string.connector_connection_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod", "ThrowsCount")
private fun OAuthAuthSection(
    service: PluginService,
    endpoint: InstalledEndpoint,
    hasOAuth: Boolean,
    busy: Boolean,
    onHasOAuthChange: (Boolean) -> Unit,
    onTestOAuth: () -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSlack =
        mcpOAuthVendor(endpoint.endpoint.url) == McpOAuthVendor.SLACK
    val isGitHub =
        mcpOAuthVendor(endpoint.endpoint.url) == McpOAuthVendor.GITHUB
    val defaultClientId = ""
    val defaultScope =
        when {
            isSlack -> "channels:read,users:read,chat:write"
            isGitHub -> "repo,read:user"
            else -> ""
        }
    val defaultRedirect = service.oauthRedirectUri
    var clientId by remember(endpoint.id, endpoint.endpoint.url) { mutableStateOf(defaultClientId) }
    var scopeText by remember(endpoint.id) { mutableStateOf(defaultScope) }
    var redirectUri by remember(endpoint.id) { mutableStateOf(defaultRedirect) }
    var connecting by remember(endpoint.id) { mutableStateOf(false) }
    var clientSetupBusy by remember(endpoint.id, endpoint.endpoint.url) { mutableStateOf(false) }
    var deviceCodeResp by remember(endpoint.id) {
        mutableStateOf<com.helix.extensions.mcp.oauth.McpDeviceCodeResponse?>(null)
    }
    var devicePolling by remember(endpoint.id) { mutableStateOf(false) }
    var pollingJob by remember(endpoint.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var preparationJob by remember(endpoint.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val revokedMessage = stringResource(R.string.connector_oauth_revoked)
    val localClearMessage = stringResource(R.string.connector_oauth_local_cleared)
    if (hasOAuth) {
        Text(
            stringResource(R.string.connector_oauth_connected),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = onTestOAuth) {
                Text(stringResource(R.string.connector_test))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        try {
                            val revoked =
                                withContext(Dispatchers.IO) {
                                    service.revokeOAuth(endpoint)
                                }
                            android.widget.Toast
                                .makeText(
                                    context,
                                    if (revoked.vendorRevoked) revokedMessage else localClearMessage,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            onHasOAuthChange(false)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            onError(e.message)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.connector_oauth_disconnect))
            }
        }
    } else if (isGitHub && deviceCodeResp != null) {
        GitHubDeviceCodeCard(
            resp = deviceCodeResp!!,
            devicePolling = devicePolling,
            onCancel = {
                service.cancelOAuth(endpoint)
                pollingJob?.cancel()
                pollingJob = null
                devicePolling = false
                deviceCodeResp = null
            },
        )
    } else {
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            enabled = !busy && !connecting && !devicePolling && !clientSetupBusy,
            singleLine = true,
            label = { Text(stringResource(R.string.connector_oauth_client_id)) },
        )
        OutlinedTextField(
            value = scopeText,
            onValueChange = { scopeText = it },
            enabled = !busy && !connecting && !devicePolling && !clientSetupBusy,
            singleLine = true,
            label = { Text(stringResource(R.string.connector_oauth_scope)) },
        )
        if (!isGitHub) {
            OutlinedTextField(
                value = redirectUri,
                onValueChange = { redirectUri = it },
                enabled = !busy && !connecting && !devicePolling && !clientSetupBusy,
                singleLine = true,
                label = { Text(stringResource(R.string.connector_oauth_redirect)) },
            )
        }
        androidx.compose.runtime.key(endpoint.id, endpoint.endpoint.url, redirectUri) {
            OAuthClientSetupSection(
                service.clientSetup,
                endpoint,
                redirectUri,
                busy || connecting || devicePolling,
                onClientId = { clientId = it },
                onBusy = { clientSetupBusy = it },
                onError = onError,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isGitHub) {
                OutlinedButton(
                    enabled = !busy && !connecting && !devicePolling && !clientSetupBusy && clientId.isNotBlank(),
                    onClick = {
                        devicePolling = true
                        preparationJob =
                            scope.launch {
                                onError(null)
                                try {
                                    val resp =
                                        withContext(Dispatchers.IO) {
                                            service.requestDeviceCode(endpoint, clientId, scopeText)
                                        }
                                    deviceCodeResp = resp
                                    pollingJob =
                                        scope.launch {
                                            pollDeviceTokenUntilFinished(
                                                service = service,
                                                endpoint = endpoint,
                                                resp = resp,
                                                onSuccess = {
                                                    deviceCodeResp = null
                                                    devicePolling = false
                                                },
                                                onError = {
                                                    onError(it)
                                                    devicePolling = false
                                                },
                                            )
                                        }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (e: Exception) {
                                    devicePolling = false
                                    onError(e.message)
                                }
                            }
                    },
                ) {
                    Text(stringResource(R.string.connector_device_flow))
                }
            }
            OutlinedButton(
                enabled =
                    !busy && !connecting && !devicePolling && !clientSetupBusy && clientId.isNotBlank() &&
                        (isGitHub || redirectUri.isNotBlank()),
                onClick = {
                    connecting = true
                    preparationJob =
                        scope.launch {
                            onError(null)
                            try {
                                val prepared =
                                    withContext(Dispatchers.IO) {
                                        service.prepareOAuth(endpoint, clientId, scopeText, redirectUri)
                                    }
                                val intent =
                                    Intent(Intent.ACTION_VIEW, prepared.authUri.toUri()).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                context.startActivity(intent)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (e: Exception) {
                                connecting = false
                                onError(e.message)
                            }
                        }
                },
            ) {
                Text(
                    if (isGitHub) {
                        stringResource(
                            R.string.connector_web_oauth,
                        )
                    } else {
                        stringResource(R.string.connector_oauth_connect)
                    },
                )
            }
        }
        if (connecting || devicePolling) {
            OutlinedButton(onClick = {
                service.cancelOAuth(endpoint)
                preparationJob?.cancel()
                preparationJob = null
                devicePolling = false
                connecting = false
            }) { Text(stringResource(R.string.common_cancel)) }
            Text(
                stringResource(R.string.connector_oauth_connecting),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun diagnosticText(code: String): String {
    val resource =
        when {
            code.startsWith("SKILL_METADATA_") -> R.string.connector_diag_metadata
            code.startsWith("SKILL_REQUIRES_") -> R.string.connector_diag_dependency
            code.startsWith("QWENWORK_") -> R.string.connector_diag_snapshot
            code.startsWith("UNRECOGNIZED_MCP_") -> R.string.connector_diag_config
            code.startsWith("AUTH_") -> R.string.connector_diag_auth
            code.startsWith("STDIO_") -> R.string.connector_diag_stdio
            code.startsWith("HOST_APP_") -> R.string.connector_diag_host
            code.startsWith("ENDPOINT_") -> R.string.connector_diag_endpoint
            code.startsWith("TOML_") -> R.string.connector_diag_toml
            else -> R.string.connector_diag_unsupported
        }
    return stringResource(resource, code)
}

@Composable
@Suppress("FunctionName")
private fun GitHubDeviceCodeCard(
    resp: com.helix.extensions.mcp.oauth.McpDeviceCodeResponse,
    devicePolling: Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.connector_device_code), style = MaterialTheme.typography.bodyMedium)
        Text(
            resp.userCode,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboardManager.setText(
                        androidx.compose.ui.text
                            .AnnotatedString(resp.userCode),
                    )
                    val intent =
                        Intent(Intent.ACTION_VIEW, resp.verificationUri.toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(intent)
                },
            ) {
                Text(stringResource(R.string.connector_device_open))
            }
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        }
        if (devicePolling) {
            Text(stringResource(R.string.connector_device_waiting), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun pollDeviceTokenUntilFinished(
    service: PluginService,
    endpoint: InstalledEndpoint,
    resp: com.helix.extensions.mcp.oauth.McpDeviceCodeResponse,
    onSuccess: suspend () -> Unit,
    onError: (String?) -> Unit,
) {
    var interval = resp.intervalSeconds.coerceAtLeast(5L)
    val expireAt = System.currentTimeMillis() + resp.expiresInSeconds * 1000L
    var finished = false
    while (!finished && System.currentTimeMillis() < expireAt) {
        kotlinx.coroutines.delay(minOf(interval * 1000L, expireAt - System.currentTimeMillis()).coerceAtLeast(0))
        if (System.currentTimeMillis() >= expireAt) break
        try {
            val pollResult =
                withContext(Dispatchers.IO) {
                    service.pollDeviceTokenOnce(endpoint, resp.attemptState)
                }
            when (pollResult) {
                is com.helix.extensions.mcp.oauth.McpDevicePollResult.Success -> {
                    onSuccess()
                    finished = true
                }

                is com.helix.extensions.mcp.oauth.McpDevicePollResult.SlowDown -> {
                    interval += 5L
                }

                is com.helix.extensions.mcp.oauth.McpDevicePollResult.Pending -> {
                    // Continue polling
                }

                is com.helix.extensions.mcp.oauth.McpDevicePollResult.Error -> {
                    onError(pollResult.message)
                    finished = true
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            onError(e.message)
            finished = true
        }
    }
    if (!finished) onError("OAUTH_DEVICE_EXPIRED")
}

@Composable
@Suppress("FunctionName")
private fun PluginSkillPreview(
    service: PluginService,
    key: com.helix.extensions.skills.SkillKey,
) {
    var content by remember(key) { mutableStateOf<String?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(service, key) {
        try {
            content = withContext(Dispatchers.IO) { service.inspectSkill(key) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    if (failed) Text(stringResource(R.string.connector_failed))
    content?.let { Text(it) }
}

@Composable
@Suppress("FunctionName")
private fun KnownEndpointTools(
    service: PluginService,
    endpoint: InstalledEndpoint,
) {
    var names by remember(endpoint.id) { mutableStateOf<List<String>?>(null) }
    var failed by remember(endpoint.id) { mutableStateOf(false) }
    LaunchedEffect(service, endpoint.id) {
        try {
            names = withContext(Dispatchers.IO) { service.knownToolNames(endpoint) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    if (failed) Text(stringResource(R.string.connector_failed))
    names?.let { tools ->
        Text(stringResource(R.string.plugin_contents_tools, tools.size))
        if (tools.isEmpty()) Text(stringResource(R.string.connector_session_setup))
        tools.forEach { Text(it) }
    }
}
