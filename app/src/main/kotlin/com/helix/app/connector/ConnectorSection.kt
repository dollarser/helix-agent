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
import com.helix.app.plugin.PluginImportError
import com.helix.app.plugin.PluginImportFailure
import com.helix.app.plugin.PluginImportResult
import com.helix.app.plugin.PluginImportReview
import com.helix.app.plugin.PluginImportTargetPicker
import com.helix.app.plugin.PluginPackagePreview
import com.helix.app.plugin.PluginService
import com.helix.app.ui.rememberImportActionState
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.mcp.oauth.McpOAuthVendor
import com.helix.extensions.mcp.oauth.mcpOAuthVendor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
// Shared import action state preserves cancellation; settings/permission destinations remain explicit UI callbacks.
@Suppress("FunctionName", "LongMethod", "ThrowsCount", "CyclomaticComplexMethod", "LongParameterList")
fun ConnectorSection(
    service: PluginService,
    showImport: Boolean = true,
    showInstalled: Boolean = true,
    onUse: ((String?) -> Unit)? = null,
    query: String = "",
    configurePluginId: String? = null,
    onConfigurationShown: () -> Unit = {},
    onInstalled: () -> Unit = {},
    onConfigureImported: ((String) -> Unit)? = null,
    onPluginSettings: () -> Unit = {},
    onSystemPermissions: () -> Unit = onPluginSettings,
) {
    val action = rememberImportActionState()
    var jsonDraft by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var replaceTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var preview by remember { mutableStateOf<PluginImportReview?>(null) }
    var importFailure by remember { mutableStateOf<PluginImportFailure?>(null) }
    var records by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var searchContents by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var revision by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf<InstalledPlugin?>(null) }
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
                                    service.bundledSkillContents(it.pluginId).map { skill -> skill.catalogEntry.name } +
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
                action.launch(onFailure = { importFailure = PluginImportFailure.from(it) }) {
                    preview = null
                    installed = null
                    preview =
                        withContext(Dispatchers.IO) {
                            PluginImportReview.capture(service.preview(uri), service.list(), replaceTarget)
                        }
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
                    action.launch(onFailure = { importFailure = PluginImportFailure.from(it) }) {
                        preview = null
                        installed = null
                        preview =
                            withContext(Dispatchers.IO) {
                                PluginImportReview.capture(service.previewJson(jsonDraft), service.list())
                            }
                    }
                },
            ) { Text(stringResource(R.string.skill_creator_preview)) }
        }
        if (loadFailed) Text(stringResource(R.string.connector_failed), color = MaterialTheme.colorScheme.error)
        if (action.failed) PluginImportError(importFailure)
        if (service.cleanupPending) {
            Text(stringResource(R.string.connector_cleanup_pending))
            OutlinedButton(onClick = {
                action.launch(onFailure = { importFailure = PluginImportFailure.from(it) }) {
                    withContext(Dispatchers.IO) { service.cleanupRetired() }
                    revision++
                }
            }, enabled = !action.busy) { Text(stringResource(R.string.connector_cleanup_retry)) }
        }
        preview?.let { review ->
            PluginImportTargetPicker(review, action.busy) { preview = it }
            PluginPackagePreview(review.bundle, review.update)
            OutlinedButton(
                enabled =
                    !action.busy && !review.needsChoice &&
                        (review.bundle.endpoints.isNotEmpty() || review.bundle.skills.isNotEmpty()),
                onClick = {
                    action.launch(onFailure = { importFailure = PluginImportFailure.from(it) }) {
                        installed =
                            withContext(Dispatchers.IO) {
                                val job = coroutineContext[kotlinx.coroutines.Job]
                                service.install(
                                    review.bundle,
                                    review.identity,
                                    review.target?.revision,
                                    cancelled = { job?.isActive == false },
                                )
                            }
                        replaceTarget = null
                        preview = null
                        jsonDraft = ""
                        showPaste = false
                        revision++
                        onInstalled()
                    }
                },
                modifier = Modifier.testTag("connector-install"),
            ) {
                Text(
                    stringResource(
                        if (review.alreadyInstalled) {
                            R.string.plugin_import_reuse
                        } else {
                            if (review.target != null) {
                                R.string.plugin_import_update
                            } else {
                                R.string.connector_install
                            }
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = {
                    preview = null
                    replaceTarget = null
                },
                enabled = !action.busy,
            ) { Text(stringResource(R.string.common_cancel)) }
        }
        installed?.let { PluginImportResult(it, onUse, onConfigureImported) }
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
                val usePlugin =
                    onUse?.let { use ->
                        { use(record.id) }
                    }
                com.helix.app.plugin.PluginManagementCard(
                    record,
                    service,
                    com.helix.app.plugin.PluginManagementActions(
                        usePlugin,
                        onPluginSettings,
                        { revision++ },
                        onSystemPermissions,
                        onConfigurationShown,
                    ),
                    configurationRequested = configurePluginId == record.id,
                    configuration = {
                        record.endpoints.forEach { endpoint ->
                            androidx.compose.runtime.key(record.enabled) {
                                EndpointRow(service, endpoint) { revision++ }
                            }
                        }
                    },
                    details = {
                        if (record.native == null) {
                            OutlinedButton(enabled = !action.busy, onClick = {
                                replaceTarget = record
                                picker.launch(
                                    arrayOf("application/zip", "application/json", "application/octet-stream"),
                                )
                            }, modifier = Modifier.testTag("connector-update-${record.id}")) {
                                Text(stringResource(R.string.connector_update_target, record.name))
                            }
                            var confirmRemoval by remember(record.id) { mutableStateOf(false) }
                            OutlinedButton(
                                enabled = !action.busy,
                                onClick = { confirmRemoval = true },
                                modifier = Modifier.testTag("plugin-remove-${record.id}"),
                            ) { Text(stringResource(R.string.connector_remove)) }
                            if (confirmRemoval) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { confirmRemoval = false },
                                    title = { Text(stringResource(R.string.plugin_remove_title, record.name)) },
                                    text = { Text(stringResource(R.string.connector_remove_note)) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirmRemoval = false
                                            action.launch(
                                                onFailure = { importFailure = PluginImportFailure.from(it) },
                                            ) {
                                                withContext(Dispatchers.IO) { service.remove(record) }
                                                revision++
                                            }
                                        }, modifier = Modifier.testTag("plugin-remove-confirm-${record.id}")) {
                                            Text(stringResource(R.string.connector_remove))
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { confirmRemoval = false }) {
                                            Text(stringResource(R.string.common_cancel))
                                        }
                                    },
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.plugin_contents_skills, record.skills.size))
                    record.skills.forEach { key ->
                        var enabled by remember(key, revision) { mutableStateOf(false) }
                        LaunchedEffect(key, revision) {
                            enabled = withContext(Dispatchers.IO) { service.skillEnabled(key) }
                        }
                        Row {
                            Checkbox(checked = enabled, enabled = !action.busy, onCheckedChange = { value ->
                                action.launch(onFailure = { importFailure = PluginImportFailure.from(it) }) {
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
                }
            }
        }
    }
}

private enum class EndpointAuthMode { BEARER, OAUTH }

@Composable
// UI boundary maps service failures to fixed, credential-free categories and preserves cancellation.
@Suppress("FunctionName", "LongMethod", "ThrowsCount", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
private fun EndpointRow(
    service: PluginService,
    endpoint: InstalledEndpoint,
    onChanged: () -> Unit,
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
    var failureMessage by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(endpoint.id) {
        service.oauthCoordinator?.events?.collect { result ->
            if (result is McpOAuthResult.Success && result.serverId == endpoint.id) {
                hasOAuth = true
                oauthError = null
                busy = true
                failureMessage = null
                snapshot = null
                selection = emptySet()
                try {
                    snapshot = withContext(Dispatchers.IO) { service.testOAuth(endpoint) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failureMessage = connectorConnectionFailureMessage(error, oauthAuthorized = true)
                } finally {
                    active = service.enabled(endpoint)
                    busy = false
                }
            } else if (result is McpOAuthResult.Failure && result.serverId == endpoint.id) {
                oauthError = result.message
            }
        }
    }

    Column(Modifier.testTag("plugin-connection-${endpoint.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    failureMessage = null
                    snapshot = null
                    selection = emptySet()
                    val credential = bearer
                    bearer = ""
                    try {
                        snapshot = withContext(Dispatchers.IO) { service.test(endpoint, credential) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        failureMessage = connectorConnectionFailureMessage(error, oauthAuthorized = false)
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
                        failureMessage = null
                        snapshot = null
                        selection = emptySet()
                        try {
                            snapshot = withContext(Dispatchers.IO) { service.testOAuth(endpoint) }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            failureMessage = connectorConnectionFailureMessage(error, oauthAuthorized = true)
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
                        onChanged()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        failureMessage =
                            connectorConnectionFailureMessage(
                                error,
                                oauthAuthorized =
                                    authMode == EndpointAuthMode.OAUTH,
                            )
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
                        onChanged()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failureMessage = R.string.connector_connection_failed
                    }
                }
            }) { Text(stringResource(R.string.connector_disable)) }
        }
        failureMessage?.let { message ->
            Text(stringResource(message), color = MaterialTheme.colorScheme.error)
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
            endpoint.endpoint.url == "https://mcp.linear.app/mcp" -> "read"
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

    var advanced by remember(endpoint.id, endpoint.endpoint.url) { mutableStateOf(false) }
    var connectRequest by remember(endpoint.id, endpoint.endpoint.url) { mutableStateOf(0) }

    fun connect(id: String) {
        connectRequest = 0
        connecting = true
        preparationJob =
            scope.launch {
                onError(null)
                try {
                    val prepared =
                        withContext(Dispatchers.IO) {
                            service.prepareOAuth(endpoint, id, scopeText, redirectUri)
                        }
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, prepared.authUri.toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    connecting = false
                    onError(e.message)
                }
            }
    }

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
        OAuthAdvancedOptions(
            enabled = !busy && !connecting && !devicePolling && !clientSetupBusy,
            expanded = advanced,
            onExpanded = { advanced = it },
        ) {
            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    connectRequest = 0
                    clientId = it
                },
                enabled = !busy && !connecting && !devicePolling && !clientSetupBusy,
                singleLine = true,
                label = { Text(stringResource(R.string.connector_oauth_client_id)) },
            )
            OutlinedTextField(
                value = scopeText,
                onValueChange = {
                    connectRequest = 0
                    scopeText = it
                },
                enabled = !busy && !connecting && !devicePolling && !clientSetupBusy,
                singleLine = true,
                label = { Text(stringResource(R.string.connector_oauth_scope)) },
            )
            if (!isGitHub) {
                OutlinedTextField(
                    value = redirectUri,
                    onValueChange = {
                        connectRequest = 0
                        redirectUri = it
                    },
                    enabled = !busy && !connecting && !devicePolling && !clientSetupBusy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.connector_oauth_redirect)) },
                )
            }
        }
        androidx.compose.runtime.key(endpoint.id, endpoint.endpoint.url, redirectUri) {
            OAuthClientSetupSection(
                service.clientSetup,
                endpoint,
                redirectUri,
                busy || connecting || devicePolling,
                onClientId = {
                    clientId = it
                    if (it.isBlank()) connectRequest = 0
                },
                onError = onError,
                connection = OAuthSetupConnection(connectRequest, advanced, { clientSetupBusy = it }) { connect(it) },
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
                    !busy && !connecting && !devicePolling && !clientSetupBusy &&
                        (isGitHub || redirectUri.isNotBlank()),
                onClick = {
                    if (clientId.isBlank()) connectRequest++ else connect(clientId)
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
                connectRequest = 0
            }) { Text(stringResource(R.string.common_cancel)) }
            Text(
                stringResource(R.string.connector_oauth_connecting),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
