package com.helix.app.mcp.oauth

import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.oauth.McpOAuthClient
import com.helix.extensions.mcp.oauth.McpOAuthRevocationResult
import com.helix.extensions.mcp.oauth.McpOAuthTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class OAuthBinding(
    val serverId: String,
    val resource: String,
    val issuer: String,
    val clientId: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val generation: String,
)

internal data class OAuthCredential(
    val binding: OAuthBinding,
    val tokens: McpOAuthTokens,
    val refreshing: Boolean = false,
)

/** SecretStore is the durable authority; the raw bearer alias is only a projection for MCP transport. */
@Suppress("TooManyFunctions") // Keep publication, generation and refresh under one durable credential owner.
internal class McpOAuthCredentials(
    private val secrets: SecretStore,
    private val client: McpOAuthClient,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Synchronized
    fun begin(
        serverId: String,
        generation: String,
    ) {
        secrets.put(epochAlias(serverId), generation)
    }

    @Synchronized
    fun isCurrent(binding: OAuthBinding): Boolean =
        secrets.contains(epochAlias(binding.serverId)) &&
            secrets.get(epochAlias(binding.serverId)) == binding.generation

    @Synchronized
    fun save(
        binding: OAuthBinding,
        tokens: McpOAuthTokens,
    ) {
        check(isCurrent(binding)) { "OAUTH_LOGIN_CANCELLED_OR_REPLACED" }
        write(OAuthCredential(binding, tokens))
        secrets.put(tokenAlias(binding.serverId), tokens.accessToken)
        // Remove aliases written by the old, unbound implementation.
        secrets.delete(SecretAlias(McpOAuthCoordinator.refreshAlias(binding.serverId)))
    }

    fun hasToken(serverId: String): Boolean = secrets.contains(recordAlias(serverId))

    suspend fun prepare(config: McpServerConfig) {
        val id = config.id.value
        val alias = config.bearerSecretAlias?.value ?: return
        if (!alias.endsWith(".oauth.token")) return
        check(config.bearerSecretAlias == tokenAlias(id)) { "OAUTH_SERVER_BINDING_MISMATCH" }
        locks.getOrPut(id) { Mutex() }.withLock {
            val record = read(id)
            check(record.binding.resource == config.endpoint.full) { "OAUTH_RESOURCE_CHANGED_RELOGIN_REQUIRED" }
            check(!record.refreshing) { "OAUTH_REFRESH_INTERRUPTED_RELOGIN_REQUIRED" }
            check(isCurrent(record.binding)) { "OAUTH_LOGIN_REPLACED" }
            if (record.tokens.isExpired()) {
                val refresh = requireNotNull(record.tokens.refreshToken) { "OAUTH_EXPIRED_RELOGIN_REQUIRED" }
                // Persist ambiguity before the network request. A crash/error never blindly replays rotation.
                write(record.copy(refreshing = true))
                val next = client.refreshToken(record.binding.tokenEndpoint, record.binding.clientId, refresh)
                save(record.binding, next.copy(refreshToken = next.refreshToken ?: refresh))
            } else {
                save(record.binding, record.tokens)
            }
        }
    }

    suspend fun revoke(serverId: String): McpOAuthRevocationResult =
        locks.getOrPut(serverId) { Mutex() }.withLock {
            val record = if (hasToken(serverId)) read(serverId) else null
            clear(serverId)
            val endpoint = record?.binding?.revocationEndpoint
            if (endpoint == null) {
                McpOAuthRevocationResult(false, 0)
            } else {
                val slack = endpoint == "https://slack.com/api/auth.revoke"
                val useRefresh = !slack && record.tokens.refreshToken != null
                client.revokeToken(
                    endpoint,
                    record.binding.clientId,
                    if (useRefresh) requireNotNull(record.tokens.refreshToken) else record.tokens.accessToken,
                    if (useRefresh) "refresh_token" else "access_token",
                )
            }
        }

    @Synchronized
    fun clear(serverId: String) {
        begin(serverId, UUID.randomUUID().toString())
        secrets.delete(recordAlias(serverId))
        secrets.delete(tokenAlias(serverId))
        secrets.delete(SecretAlias(McpOAuthCoordinator.refreshAlias(serverId)))
        check(!secrets.contains(recordAlias(serverId)) && !secrets.contains(tokenAlias(serverId))) {
            "OAUTH_LOCAL_CLEAR_FAILED"
        }
    }

    private fun read(serverId: String): OAuthCredential =
        OAuthCredentialCodec.decode(secrets.get(recordAlias(serverId)))

    @Synchronized
    private fun write(record: OAuthCredential) {
        check(isCurrent(record.binding)) { "OAUTH_LOGIN_CANCELLED_OR_REPLACED" }
        secrets.put(recordAlias(record.binding.serverId), OAuthCredentialCodec.encode(record))
    }

    private fun tokenAlias(id: String) = SecretAlias(McpOAuthCoordinator.tokenAlias(id))

    private fun recordAlias(id: String) = SecretAlias("mcp.$id.oauth.binding")

    private fun epochAlias(id: String) = SecretAlias("mcp.$id.oauth.epoch")
}
