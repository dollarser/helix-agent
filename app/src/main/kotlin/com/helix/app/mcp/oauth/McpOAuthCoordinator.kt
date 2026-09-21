package com.helix.app.mcp.oauth

import android.net.Uri
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.extensions.mcp.oauth.McpOAuthAuthRequest
import com.helix.extensions.mcp.oauth.McpOAuthClient
import com.helix.extensions.mcp.oauth.McpOAuthDiscovery
import com.helix.extensions.mcp.oauth.McpOAuthPkce
import com.helix.extensions.mcp.oauth.McpOAuthRevocationResult
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import com.helix.extensions.mcp.oauth.McpOAuthTokens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

sealed class McpOAuthResult {
    data class Success(
        val serverId: String,
        val tokens: McpOAuthTokens,
    ) : McpOAuthResult()

    data class Failure(
        val message: String,
        val errorCode: String? = null,
    ) : McpOAuthResult()
}

data class McpOAuthPreparedAuth(
    val authUri: String,
    val state: String,
    val attemptId: String,
)

class McpOAuthCoordinator(
    private val secretStore: SecretStore,
    private val attemptStore: McpOAuthAttemptStore,
    private val oauthClient: McpOAuthClient,
) {
    private val _events = MutableSharedFlow<McpOAuthResult>(extraBufferCapacity = 16)
    val events: SharedFlow<McpOAuthResult> = _events.asSharedFlow()

    fun hasToken(serverId: String): Boolean = secretStore.contains(SecretAlias(tokenAlias(serverId)))

    suspend fun discoverMetadata(endpointUrl: String): McpOAuthServerMetadata {
        val normalized = NormalizedEndpoint.parse(endpointUrl)
        return McpOAuthDiscovery(oauthClient.endpointGate).discover(normalized)
    }

    /**
     * Prepares an OAuth authorization attempt: generates PKCE and state,
     * persists attempt, and constructs the browser URL.
     */
    fun prepareAuthorization(
        serverId: String,
        clientId: String,
        metadata: McpOAuthServerMetadata,
        scope: String,
        redirectUri: String = DEFAULT_REDIRECT_URI,
        extraParams: Map<String, String> = emptyMap(),
    ): McpOAuthPreparedAuth {
        val pkce = McpOAuthPkce.generate()
        val state = McpOAuthPkce.generateState()
        val attemptId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val attempt =
            McpOAuthAttempt(
                attemptId = attemptId,
                serverId = serverId,
                issuer = metadata.issuer,
                tokenEndpoint = metadata.tokenEndpoint,
                clientId = clientId,
                redirectUri = redirectUri,
                codeVerifier = pkce.verifier,
                state = state,
                scope = scope,
                createdAtMs = now,
                expiresAtMs = now + McpOAuthAttempt.DEFAULT_TTL_MS,
            )
        attemptStore.saveAttempt(attempt)

        val authUri =
            oauthClient.buildAuthorizationUri(
                McpOAuthAuthRequest(
                    authorizationEndpoint = metadata.authorizationEndpoint,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    scope = scope,
                    state = state,
                    codeChallenge = pkce.challenge,
                    codeChallengeMethod = McpOAuthPkce.METHOD_S256,
                    extraParams = extraParams,
                ),
            )

        return McpOAuthPreparedAuth(
            authUri = authUri,
            state = state,
            attemptId = attemptId,
        )
    }

    /**
     * Completes OAuth callback from browser. Consumes attempt atomically (one-time).
     * Exchanges code for tokens and saves them into SecretStore.
     */
    suspend fun handleCallback(uri: Uri): McpOAuthResult = handleCallback(uri.toString())

    suspend fun handleCallback(callbackUrl: String): McpOAuthResult {
        val outcome = executeCallback(callbackUrl)
        _events.tryEmit(outcome)
        return outcome
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun executeCallback(callbackUrl: String): McpOAuthResult {
        val queryParams = parseQueryParams(callbackUrl)
        val state =
            queryParams["state"]
                ?: return McpOAuthResult.Failure("Missing OAuth state parameter in callback")

        // Atomic one-time consumption from store (prevents replay)
        val attempt =
            attemptStore.consumeAttempt(state)
                ?: return McpOAuthResult.Failure("OAuth session invalid, already consumed, or expired")

        val error = queryParams["error"]
        if (error != null) {
            val errorDesc = queryParams["error_description"] ?: error
            return McpOAuthResult.Failure(errorDesc, error)
        }

        val code =
            queryParams["code"]
                ?: return McpOAuthResult.Failure("Missing authorization code in callback")

        return try {
            val tokens =
                oauthClient.exchangeCode(
                    tokenEndpoint = attempt.tokenEndpoint,
                    clientId = attempt.clientId,
                    redirectUri = attempt.redirectUri,
                    code = code,
                    codeVerifier = attempt.codeVerifier,
                )

            // Save tokens to SecretStore
            val tokenAlias = SecretAlias(tokenAlias(attempt.serverId))
            secretStore.put(tokenAlias, tokens.accessToken)

            val refreshToken = tokens.refreshToken
            if (!refreshToken.isNullOrBlank()) {
                val refreshAlias = SecretAlias(refreshAlias(attempt.serverId))
                secretStore.put(refreshAlias, refreshToken)
            }

            McpOAuthResult.Success(
                serverId = attempt.serverId,
                tokens = tokens,
            )
        } catch (e: Exception) {
            McpOAuthResult.Failure("OAuth token exchange failed: ${e.message}")
        }
    }

    /**
     * Revokes token at vendor endpoint and clears local credentials.
     * Per ADR-CONNECTORS-002, reports vendor revoke status while always clearing local secrets.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun revokeAndClear(
        serverId: String,
        clientId: String,
        revocationEndpoint: String?,
    ): McpOAuthRevocationResult {
        val tokenAlias = SecretAlias(tokenAlias(serverId))
        val refreshAlias = SecretAlias(refreshAlias(serverId))

        var vendorResult = McpOAuthRevocationResult(vendorRevoked = false, statusCode = 0)
        if (!revocationEndpoint.isNullOrBlank()) {
            val token =
                try {
                    secretStore.get(tokenAlias)
                } catch (_: Exception) {
                    null
                }
            if (token != null) {
                vendorResult = oauthClient.revokeToken(revocationEndpoint, clientId, token)
            }
        }

        // Clear local secrets
        try {
            secretStore.delete(tokenAlias)
        } catch (_: Exception) {
        }
        try {
            secretStore.delete(refreshAlias)
        } catch (_: Exception) {
        }

        return vendorResult
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val queryIndex = url.indexOf('?')
        if (queryIndex == -1 || queryIndex == url.length - 1) return emptyMap()
        val query =
            url.substring(queryIndex + 1).let { q ->
                val hashIndex = q.indexOf('#')
                if (hashIndex != -1) q.substring(0, hashIndex) else q
            }
        return query.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    companion object {
        const val DEFAULT_REDIRECT_URI = "helix://oauth/mcp/callback"

        fun tokenAlias(serverId: String): String = "mcp.$serverId.oauth.token"

        fun refreshAlias(serverId: String): String = "mcp.$serverId.oauth.refresh"
    }
}
