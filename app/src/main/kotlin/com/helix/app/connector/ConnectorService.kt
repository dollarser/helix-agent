package com.helix.app.connector

import android.content.Context
import android.net.Uri
import com.helix.app.mcp.McpAppService
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.mcp.oauth.McpOAuthVendor
import com.helix.extensions.mcp.oauth.mcpOAuthVendor
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.connector.ConnectorPackage
import com.helix.extensions.skills.connector.ConnectorPackageReader
import java.nio.file.Files

/** UI facade: bounded metadata and references enter Room; credentials remain in SecretStore. */
@Suppress("TooManyFunctions") // single UI facade for import, connection and component lifecycle
class ConnectorService(
    private val context: Context,
    private val storage: HelixStorage,
    private val mcp: McpAppService,
    private val importer: SkillImportService,
    private val skills: SkillRepository,
    val oauthCoordinator: com.helix.app.mcp.oauth.McpOAuthCoordinator? = null,
    val catalog: ConnectorCatalog = ConnectorCatalog(storage, context.filesDir.toPath().resolve("connectors")),
    private val installBoundary: (String) -> Unit = {},
) {
    private val snapshots = context.filesDir.toPath().resolve("skills/snapshots")
    private val reader = ConnectorPackageReader()

    fun previewJson(text: String): ConnectorPackage = reader.readJson(text.toByteArray(Charsets.UTF_8))

    fun preview(uri: Uri): ConnectorPackage {
        val bytes =
            context.contentResolver.openInputStream(uri).use { input ->
                ConnectorPackageReader.readBounded(requireNotNull(input), ConnectorPackageReader.MAX_BYTES)
            }
        if (bytes.firstOrNull() == 'P'.code.toByte() && bytes.getOrNull(1) == 'K'.code.toByte()) {
            val temporary = Files.createTempFile(context.cacheDir.toPath(), "connector-", ".zip")
            try {
                Files.write(temporary, bytes)
                return reader.readZip(temporary)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        return reader.readJson(bytes)
    }

    @Synchronized
    fun install(
        bundle: ConnectorPackage,
        identity: String = "local:${bundle.source}:${bundle.contentHash}",
        expectedRevision: Long? = null,
        cancelled: () -> Boolean = { false },
        sessionScoped: Boolean = true,
    ): InstalledConnector =
        synchronized(catalog.mutationLock) {
            try {
                skills.withSnapshotReferences {
                    ConnectorInstaller(
                        context.cacheDir.toPath(),
                        snapshots,
                        importer,
                        skills,
                        catalog,
                        installBoundary,
                    ) {
                        cleanupPending = true
                    }.install(bundle, identity, expectedRevision, cancelled, sessionScoped)
                }
            } finally {
                runCatching { cleanupRetired() }.onFailure { cleanupPending = true }
            }
        }

    fun list(): List<InstalledConnector> = catalog.list()

    fun sessionRows(sessionId: String): List<ConnectorSessionRow> {
        val selected = catalog.selected(sessionId)
        val installed = list().filter { it.sessionScoped }.associateBy { it.id }
        return (installed.keys + selected).map { id ->
            val record = installed[id]
            ConnectorSessionRow(
                id,
                record?.name ?: id,
                id in selected,
                record != null && catalog.defaultSelected(id),
                record != null,
                record != null && (record.endpoints.any(::enabled) || record.skills.any(::skillEnabled)),
            )
        }
    }

    /** Registers only on an explicit connection-test action, with a fresh local credential. */
    suspend fun test(
        endpoint: InstalledEndpoint,
        bearer: String,
    ): McpHandshakeSnapshot {
        prepareEndpoint(endpoint, bearer)
        return mcp.testConnection(endpoint.id)
    }

    val oauthRedirectUri: String get() = requireNotNull(oauthCoordinator).defaultRedirectUri

    fun hasOAuthToken(endpoint: InstalledEndpoint): Boolean = oauthCoordinator?.hasToken(endpoint.id) == true

    suspend fun prepareOAuth(
        endpoint: InstalledEndpoint,
        clientId: String,
        scope: String = "",
        redirectUri: String = oauthRedirectUri,
    ): com.helix.app.mcp.oauth.McpOAuthPreparedAuth {
        val coordinator = requireNotNull(oauthCoordinator) { "OAuth coordinator not available" }
        requireOwned(endpoint)
        disable(endpoint)
        val metadata = resolveOAuthMetadata(coordinator, endpoint.endpoint.url)
        val isSlack = mcpOAuthVendor(endpoint.endpoint.url) == McpOAuthVendor.SLACK
        val isCustomScheme = !redirectUri.startsWith("http://") && !redirectUri.startsWith("https://")
        val extraParams = mutableMapOf<String, String>()
        val paramScope: String
        if (isSlack && isCustomScheme) {
            extraParams["user_scope"] = scope
            paramScope = ""
        } else {
            paramScope = scope
        }
        return coordinator.prepareAuthorization(
            serverId = endpoint.id,
            clientId = clientId,
            metadata = metadata,
            scope = paramScope,
            redirectUri = redirectUri,
            extraParams = extraParams,
            resource = endpoint.endpoint.url,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveOAuthMetadata(
        coordinator: com.helix.app.mcp.oauth.McpOAuthCoordinator,
        endpointUrl: String,
    ): com.helix.extensions.mcp.oauth.McpOAuthServerMetadata =
        when (mcpOAuthVendor(endpointUrl)) {
            McpOAuthVendor.SLACK -> {
                com.helix.extensions.mcp.oauth.McpOAuthServerMetadata(
                    issuer = "https://slack.com",
                    authorizationEndpoint = "https://slack.com/oauth/v2/authorize",
                    tokenEndpoint = "https://slack.com/api/oauth.v2.access",
                    revocationEndpoint = "https://slack.com/api/auth.revoke",
                )
            }

            McpOAuthVendor.GITHUB -> {
                com.helix.extensions.mcp.oauth.McpOAuthServerMetadata(
                    issuer = "https://github.com",
                    authorizationEndpoint = "https://github.com/login/oauth/authorize",
                    tokenEndpoint = "https://github.com/login/oauth/access_token",
                    deviceAuthorizationEndpoint = "https://github.com/login/device/code",
                )
            }

            else -> {
                coordinator.discoverMetadata(endpointUrl)
            }
        }

    suspend fun getOAuthMetadata(endpoint: InstalledEndpoint): com.helix.extensions.mcp.oauth.McpOAuthServerMetadata {
        val coordinator = requireNotNull(oauthCoordinator) { "OAuth coordinator not available" }
        requireOwned(endpoint)
        return resolveOAuthMetadata(coordinator, endpoint.endpoint.url)
    }

    suspend fun requestDeviceCode(
        endpoint: InstalledEndpoint,
        clientId: String,
        scope: String = "",
    ): com.helix.extensions.mcp.oauth.McpDeviceCodeResponse {
        val coordinator = requireNotNull(oauthCoordinator) { "OAuth coordinator not available" }
        requireOwned(endpoint)
        val metadata = resolveOAuthMetadata(coordinator, endpoint.endpoint.url)
        disable(endpoint)
        return coordinator.requestDeviceAuth(endpoint.id, metadata, endpoint.endpoint.url, clientId, scope)
    }

    suspend fun pollDeviceTokenOnce(
        endpoint: InstalledEndpoint,
        state: String,
    ): com.helix.extensions.mcp.oauth.McpDevicePollResult {
        requireOwned(endpoint)
        return requireNotNull(oauthCoordinator).pollDeviceTokenOnce(endpoint.id, endpoint.endpoint.url, state)
    }

    fun cancelOAuth(endpoint: InstalledEndpoint) {
        requireOwned(endpoint)
        oauthCoordinator?.cancel(endpoint.id)
    }

    suspend fun testOAuth(endpoint: InstalledEndpoint): McpHandshakeSnapshot {
        requireOwned(endpoint)
        val tokenAlias =
            com.helix.app.mcp.oauth.McpOAuthCoordinator
                .tokenAlias(endpoint.id)
        val existing = storage.mcpServers.list().firstOrNull { it.id == endpoint.id }
        if (existing == null) {
            mcp.registerDisabled(
                endpoint.id,
                endpoint.endpoint.url,
                tokenAlias,
            )
        } else {
            storage.mcpServers.replaceAuthAlias(endpoint.id, tokenAlias)
        }
        mcp.disable(endpoint.id)
        return mcp.testConnection(endpoint.id)
    }

    suspend fun revokeOAuth(endpoint: InstalledEndpoint): com.helix.extensions.mcp.oauth.McpOAuthRevocationResult {
        requireOwned(endpoint)
        disable(endpoint)
        val coordinator = requireNotNull(oauthCoordinator) { "OAuth coordinator not available" }
        return coordinator.revokeAndClear(endpoint.id)
    }

    @Synchronized
    private fun prepareEndpoint(
        endpoint: InstalledEndpoint,
        bearer: String,
    ) {
        requireOwned(endpoint)
        val existing = storage.mcpServers.list().firstOrNull { it.id == endpoint.id }
        val alias = SecretAlias(endpoint.id)
        if (bearer.isNotBlank()) {
            require(
                bearer.length <= 8192 && bearer.none { it.isWhitespace() || it.code < 32 },
            ) { "CONNECTOR_INVALID_CREDENTIAL" }
            storage.secrets.put(alias, bearer)
        }
        if (existing == null) {
            mcp.registerDisabled(
                endpoint.id,
                endpoint.endpoint.url,
                alias.value.takeIf { bearer.isNotBlank() || endpoint.endpoint.needsCredential },
            )
        } else if (bearer.isNotBlank()) {
            storage.mcpServers.replaceAuthAlias(endpoint.id, alias.value)
        }
        mcp.disable(endpoint.id)
    }

    @Synchronized
    fun enable(
        endpoint: InstalledEndpoint,
        snapshot: McpHandshakeSnapshot,
        selected: Set<String>,
    ) {
        requireOwned(endpoint)
        require(snapshot.serverId.value == endpoint.id) { "CONNECTOR_WRONG_SERVER" }
        mcp.enable(snapshot, selected)
    }

    @Synchronized
    fun disable(endpoint: InstalledEndpoint) {
        requireOwned(endpoint)
        if (storage.mcpServers.list().any { it.id == endpoint.id }) mcp.disable(endpoint.id)
    }

    fun enabled(endpoint: InstalledEndpoint): Boolean = mcp.isActive(endpoint.id)

    fun skillEnabled(key: SkillKey): Boolean = skills.list().any { it.key == key && it.enabled }

    @Synchronized
    fun setSkillEnabled(
        key: SkillKey,
        enabled: Boolean,
    ) {
        require(list().any { key in it.skills }) { "CONNECTOR_UNKNOWN_SKILL" }
        skills.setEnabled(key, enabled, SkillEnablementScope.GLOBAL)
    }

    @Synchronized
    fun remove(record: InstalledConnector) =
        synchronized(catalog.mutationLock) {
            catalog.remove(record)
            cleanupRetired()
        }

    @Volatile
    var cleanupPending: Boolean = false
        private set

    /** Cleanup failure never rolls back an already published installation. */
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    fun cleanupRetired() =
        synchronized(catalog.mutationLock) {
            cleanupPending = false
            cleanupStaging()
            catalog.unusedSkills().forEach { key ->
                try {
                    skills.withSnapshotReferences {
                        // Independent imports can acquire ownership between the initial scan and this lock.
                        if (key in catalog.unusedSkills() && skills.hasSnapshot(key)) {
                            skills.removePermanentlyForPrivacy(key)
                        }
                    }
                } catch (_: Exception) {
                    cleanupPending = true
                }
            }
            catalog.retiredEndpoints().forEach { id ->
                try {
                    val cleaned =
                        mcp.cleanupIfIdle(id) {
                            if (storage.mcpServers.list().any { it.id == id }) mcp.delete(id)
                            storage.secrets.delete(SecretAlias(id))
                            oauthCoordinator?.clearLocal(id)
                        }
                    if (!cleaned) cleanupPending = true
                } catch (_: Exception) {
                    cleanupPending = true
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // failed cache cleanup is visible and retried, never rolls back publication
    private fun cleanupStaging() {
        try {
            Files.list(context.cacheDir.toPath()).use { paths ->
                paths.filter { it.fileName.toString().startsWith("connector-skills-") }.forEach { root ->
                    deleteStagingTree(root)
                }
            }
        } catch (_: Exception) {
            cleanupPending = true
        }
    }

    private fun deleteStagingTree(root: java.nio.file.Path) {
        Files.walk(root).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun requireOwned(endpoint: InstalledEndpoint) {
        require(list().any { endpoint in it.endpoints }) { "CONNECTOR_UNKNOWN_ENDPOINT" }
    }
}
