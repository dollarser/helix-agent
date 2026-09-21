package com.helix.app.mcp.oauth

import android.net.Uri
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.storage.SecretStore
import com.helix.extensions.mcp.McpServerConfig
import com.helix.extensions.mcp.oauth.McpOAuthAuthRequest
import com.helix.extensions.mcp.oauth.McpOAuthClient
import com.helix.extensions.mcp.oauth.McpOAuthDiscovery
import com.helix.extensions.mcp.oauth.McpOAuthPkce
import com.helix.extensions.mcp.oauth.McpOAuthRevocationResult
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import com.helix.extensions.mcp.oauth.McpOAuthTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.URI
import java.util.UUID

sealed class McpOAuthResult {
    data class Success(
        val serverId: String,
    ) : McpOAuthResult()

    data class Failure(
        val message: String,
        val errorCode: String? = null,
        val serverId: String? = null,
    ) : McpOAuthResult()
}

data class McpOAuthPreparedAuth(
    val authUri: String,
    val state: String,
    val attemptId: String,
)

@Suppress("TooManyFunctions")
class McpOAuthCoordinator(
    secretStore: SecretStore,
    private val attemptStore: McpOAuthAttemptStore,
    private val oauthClient: McpOAuthClient,
    private val redirectScheme: String = "helix",
) {
    private val credentials = McpOAuthCredentials(secretStore, oauthClient)
    private val mutableEvents = MutableSharedFlow<McpOAuthResult>(extraBufferCapacity = 16)
    val events: SharedFlow<McpOAuthResult> = mutableEvents.asSharedFlow()

    val defaultRedirectUri: String get() = "$redirectScheme://oauth/mcp/callback"

    fun hasToken(serverId: String): Boolean = credentials.hasToken(serverId)

    suspend fun prepareCredential(config: McpServerConfig) = credentials.prepare(config)

    suspend fun discoverMetadata(endpointUrl: String): McpOAuthServerMetadata =
        McpOAuthDiscovery(oauthClient.endpointGate).discover(NormalizedEndpoint.parse(endpointUrl))

    fun prepareAuthorization(
        serverId: String,
        clientId: String,
        metadata: McpOAuthServerMetadata,
        scope: String,
        redirectUri: String = defaultRedirectUri,
        extraParams: Map<String, String> = emptyMap(),
        resource: String = metadata.issuer,
    ): McpOAuthPreparedAuth {
        val redirect = URI(redirectUri)
        require(redirect.rawQuery == null && redirect.rawFragment == null && redirect.rawUserInfo == null)
        require(
            redirect.scheme == redirectScheme && redirect.host == "oauth" && redirect.port == -1 &&
                redirect.path in setOf("/mcp/callback", "/callback"),
        ) { "Unsupported OAuth redirect" }
        require(metadata.supportsS256())
        val pkce = McpOAuthPkce.generate()
        val attempt = newAttempt(serverId, clientId, metadata, scope, redirectUri, resource, pkce.verifier)
        val authUri =
            oauthClient.buildAuthorizationUri(
                McpOAuthAuthRequest(
                    metadata.authorizationEndpoint,
                    clientId,
                    redirectUri,
                    scope,
                    attempt.state,
                    pkce.challenge,
                    McpOAuthPkce.METHOD_S256,
                    extraParams + ("resource" to attempt.resource),
                ),
            )
        return McpOAuthPreparedAuth(authUri, attempt.state, attempt.attemptId)
    }

    suspend fun handleCallback(uri: Uri): McpOAuthResult = handleCallback(uri.toString())

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun handleCallback(callbackUrl: String): McpOAuthResult {
        var serverId: String? = null
        val result =
            try {
                val callback = McpOAuthCallback.parse(callbackUrl)
                val state = callback.parameters["state"] ?: return McpOAuthResult.Failure("OAUTH_CALLBACK_INVALID")
                val pending =
                    attemptStore.peekAttempt(state)
                        ?: return McpOAuthResult.Failure("OAuth session invalid, already consumed, or expired")
                serverId = pending.serverId
                require(callback.matches(pending)) { "OAuth callback binding mismatch" }
                require(credentials.isCurrent(pending.binding()))
                val attempt = requireNotNull(attemptStore.consumeAttempt(state))
                if (callback.parameters.containsKey("error")) {
                    McpOAuthResult.Failure("OAUTH_AUTHORIZATION_DENIED", serverId = serverId)
                } else {
                    val code = requireNotNull(callback.parameters["code"])
                    val tokens =
                        oauthClient.exchangeCode(
                            attempt.tokenEndpoint,
                            attempt.clientId,
                            attempt.redirectUri,
                            code,
                            attempt.codeVerifier,
                            attempt.resource,
                        )
                    currentCoroutineContext().ensureActive()
                    credentials.save(attempt.binding(), tokens)
                    McpOAuthResult.Success(attempt.serverId)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                McpOAuthResult.Failure("OAUTH_LOGIN_FAILED_RETRY_REQUIRED", serverId = serverId)
            }
        mutableEvents.tryEmit(result)
        return result
    }

    suspend fun requestDeviceAuth(
        serverId: String,
        metadata: McpOAuthServerMetadata,
        resource: String,
        clientId: String,
        scope: String,
    ): com.helix.extensions.mcp.oauth.McpDeviceCodeResponse {
        val response =
            oauthClient.requestDeviceCode(
                requireNotNull(metadata.deviceAuthorizationEndpoint),
                clientId,
                scope,
            )
        val attempt =
            newAttempt(
                serverId,
                clientId,
                metadata,
                scope,
                "device",
                resource,
                response.deviceCode,
                response.expiresInSeconds * 1000,
            )
        return response.copy(attemptState = attempt.state)
    }

    suspend fun pollDeviceTokenOnce(
        serverId: String,
        resource: String,
        state: String,
    ): com.helix.extensions.mcp.oauth.McpDevicePollResult {
        val attempt = requireNotNull(attemptStore.peekAttempt(state)) { "OAUTH_DEVICE_EXPIRED" }
        require(attempt.serverId == serverId && attempt.resource == NormalizedEndpoint.parse(resource).full)
        require(credentials.isCurrent(attempt.binding()))
        val result =
            oauthClient.pollDeviceTokenOnce(
                attempt.tokenEndpoint,
                attempt.clientId,
                attemptStore.verifier(state),
            )
        if (result is com.helix.extensions.mcp.oauth.McpDevicePollResult.Success) {
            currentCoroutineContext().ensureActive()
            requireNotNull(attemptStore.consumeAttempt(state))
            credentials.save(attempt.binding(), result.tokens)
            mutableEvents.tryEmit(McpOAuthResult.Success(serverId))
        }
        return result
    }

    fun cancel(serverId: String) {
        attemptStore.cancelServer(serverId)
        credentials.begin(serverId, UUID.randomUUID().toString())
    }

    fun clearLocal(serverId: String) {
        attemptStore.cancelServer(serverId)
        credentials.clear(serverId)
    }

    suspend fun revokeAndClear(serverId: String): McpOAuthRevocationResult {
        attemptStore.cancelServer(serverId)
        return credentials.revoke(serverId)
    }

    @Suppress("LongParameterList") // The full issuer/resource/client/redirect binding is persisted together.
    private fun newAttempt(
        serverId: String,
        clientId: String,
        metadata: McpOAuthServerMetadata,
        scope: String,
        redirect: String,
        resource: String,
        verifier: String,
        ttl: Long = McpOAuthAttempt.DEFAULT_TTL_MS,
    ): McpOAuthAttempt {
        val now = System.currentTimeMillis()
        val attempt =
            McpOAuthAttempt(
                UUID.randomUUID().toString(),
                serverId,
                metadata.issuer,
                metadata.tokenEndpoint,
                clientId,
                redirect,
                scope,
                McpOAuthPkce.generateState(),
                verifier,
                now,
                now + ttl,
                NormalizedEndpoint.parse(resource).full,
                metadata.revocationEndpoint,
            )
        attemptStore.cleanupExpired()
        attemptStore.cancelServer(serverId)
        credentials.begin(serverId, attempt.attemptId)
        attemptStore.saveAttempt(attempt)
        return attempt
    }

    private fun McpOAuthAttempt.binding() =
        OAuthBinding(
            serverId,
            resource,
            issuer,
            clientId,
            tokenEndpoint,
            revocationEndpoint,
            attemptId,
        )

    companion object {
        const val DEFAULT_REDIRECT_URI = "helix://oauth/mcp/callback"

        fun tokenAlias(serverId: String): String = "mcp.$serverId.oauth.token"

        fun refreshAlias(serverId: String): String = "mcp.$serverId.oauth.refresh"
    }
}
