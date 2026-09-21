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
import com.helix.app.R
import com.helix.app.mcp.oauth.McpOAuthResult
import com.helix.app.ui.rememberImportActionState
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.skills.connector.ConnectorPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
// Shared import action state preserves cancellation.
@Suppress("FunctionName", "LongMethod", "ThrowsCount", "CyclomaticComplexMethod")
fun ConnectorSection(service: ConnectorService) {
    val action = rememberImportActionState()
    var jsonDraft by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ConnectorPackage?>(null) }
    var records by remember { mutableStateOf<List<InstalledConnector>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(revision) {
        loadFailed = false
        try {
            records = withContext(Dispatchers.IO) { service.list() }
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
        Text(stringResource(R.string.connector_intro))
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) },
            enabled = !action.busy,
            modifier = Modifier.testTag("connector-import"),
        ) {
            Text(stringResource(R.string.connector_import))
        }
        OutlinedButton(
            enabled = !action.busy,
            modifier = Modifier.testTag("connector-paste"),
            onClick = { showPaste = !showPaste },
        ) { Text(stringResource(R.string.connector_paste)) }
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
        preview?.let { bundle ->
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
                        withContext(Dispatchers.IO) { service.install(bundle) }
                        preview = null
                        jsonDraft = ""
                        showPaste = false
                        revision++
                    }
                },
                modifier = Modifier.testTag("connector-install"),
            ) { Text(stringResource(R.string.connector_install)) }
            OutlinedButton(
                onClick = { preview = null },
                enabled = !action.busy,
            ) { Text(stringResource(R.string.common_cancel)) }
        }
        records.forEach { record ->
            HorizontalDivider()
            Text(record.name, style = MaterialTheme.typography.titleSmall)
            Text("${record.source} · ${record.hash.take(12)}", style = MaterialTheme.typography.bodySmall)
            record.diagnostics.forEach { Text(diagnosticText(it), style = MaterialTheme.typography.bodySmall) }
            record.endpoints.forEach { endpoint -> EndpointRow(service, endpoint) }
            record.skills.forEach { key ->
                var enabled by remember(key, revision) { mutableStateOf(service.skillEnabled(key)) }
                Row {
                    Checkbox(checked = enabled, enabled = !action.busy, onCheckedChange = { value ->
                        action.launch {
                            withContext(Dispatchers.IO) { service.setSkillEnabled(key, value) }
                            enabled = value
                        }
                    })
                    Text("Skill: ${key.name}")
                }
            }
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

private enum class EndpointAuthMode { BEARER, OAUTH }

@Composable
// Independent UI event callbacks preserve cancellation.
@Suppress("FunctionName", "LongMethod", "ThrowsCount", "CyclomaticComplexMethod")
private fun EndpointRow(
    service: ConnectorService,
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
            } else if (result is McpOAuthResult.Failure) {
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
                onClick = { authMode = EndpointAuthMode.BEARER },
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
            endpoint.endpoint.url
                .lowercase()
                .contains("github.com")
        if (authMode == EndpointAuthMode.BEARER) {
            val bearerLabel =
                if (isGitHub) "Personal Access Token (PAT)" else stringResource(R.string.connector_bearer)
            OutlinedTextField(
                value = bearer,
                onValueChange = { bearer = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(bearerLabel) },
            )
            if (isGitHub) {
                Text(
                    "GitHub PAT (Classic: ghp_... 或 Fine-grained: github_pat_...)，可在 github.com/settings/tokens 生成",
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
    service: ConnectorService,
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
        endpoint.endpoint.url
            .lowercase()
            .contains("slack.com")
    val isGitHub =
        endpoint.endpoint.url
            .lowercase()
            .contains("github.com")
    val defaultClientId =
        when {
            isSlack -> "12095777350999.12106402447285"
            isGitHub -> "Ov23li9qRQNManazs4Er"
            else -> ""
        }
    val defaultScope =
        when {
            isSlack -> "channels:read,users:read,chat:write"
            isGitHub -> "repo,read:user"
            else -> ""
        }
    val defaultRedirect = "helix://oauth/callback"
    var clientId by remember(endpoint.id) { mutableStateOf(defaultClientId) }
    var scopeText by remember(endpoint.id) { mutableStateOf(defaultScope) }
    var redirectUri by remember(endpoint.id) { mutableStateOf(defaultRedirect) }
    var connecting by remember(endpoint.id) { mutableStateOf(false) }
    var deviceCodeResp by remember(endpoint.id) {
        mutableStateOf<com.helix.extensions.mcp.oauth.McpDeviceCodeResponse?>(null)
    }
    var devicePolling by remember(endpoint.id) { mutableStateOf(false) }
    var pollingJob by remember(endpoint.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

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
                            withContext(Dispatchers.IO) {
                                service.revokeOAuth(endpoint, clientId)
                            }
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
            singleLine = true,
            label = { Text(stringResource(R.string.connector_oauth_client_id)) },
        )
        OutlinedTextField(
            value = scopeText,
            onValueChange = { scopeText = it },
            singleLine = true,
            label = { Text(stringResource(R.string.connector_oauth_scope)) },
        )
        if (!isGitHub) {
            OutlinedTextField(
                value = redirectUri,
                onValueChange = { redirectUri = it },
                singleLine = true,
                label = { Text("Redirect URI") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isGitHub) {
                OutlinedButton(
                    enabled = !busy && !devicePolling && clientId.isNotBlank(),
                    onClick = {
                        scope.launch {
                            onError(null)
                            devicePolling = true
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
                                            clientId = clientId,
                                            resp = resp,
                                            onSuccess = { tokens ->
                                                withContext(Dispatchers.IO) {
                                                    service.completeDeviceAuth(endpoint, tokens)
                                                }
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
                    Text("Device Flow (免Secret)")
                }
            }
            OutlinedButton(
                enabled = !busy && clientId.isNotBlank() && (isGitHub || redirectUri.isNotBlank()),
                onClick = {
                    scope.launch {
                        connecting = true
                        onError(null)
                        try {
                            val prepared =
                                withContext(Dispatchers.IO) {
                                    service.prepareOAuth(endpoint, clientId, scopeText, redirectUri)
                                }
                            val intent =
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(prepared.authUri)).apply {
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
                Text(if (isGitHub) "Web OAuth" else stringResource(R.string.connector_oauth_connect))
            }
        }
        if (connecting) {
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
        Text("GitHub 设备验证码 (Device Code):", style = MaterialTheme.typography.bodyMedium)
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
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(resp.verificationUri)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(intent)
                },
            ) {
                Text("复制验证码并打开 GitHub")
            }
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        }
        if (devicePolling) {
            Text("正在等待在 GitHub 页面上授权...", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun pollDeviceTokenUntilFinished(
    service: ConnectorService,
    endpoint: InstalledEndpoint,
    clientId: String,
    resp: com.helix.extensions.mcp.oauth.McpDeviceCodeResponse,
    onSuccess: suspend (com.helix.extensions.mcp.oauth.McpOAuthTokens) -> Unit,
    onError: (String?) -> Unit,
) {
    var interval = resp.intervalSeconds.coerceAtLeast(5L)
    val expireAt = System.currentTimeMillis() + resp.expiresInSeconds * 1000L
    var finished = false
    while (!finished && System.currentTimeMillis() < expireAt) {
        kotlinx.coroutines.delay(interval * 1000L)
        try {
            val pollResult =
                withContext(Dispatchers.IO) {
                    service.pollDeviceTokenOnce(endpoint, clientId, resp.deviceCode)
                }
            when (pollResult) {
                is com.helix.extensions.mcp.oauth.McpDevicePollResult.Success -> {
                    onSuccess(pollResult.tokens)
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
}
