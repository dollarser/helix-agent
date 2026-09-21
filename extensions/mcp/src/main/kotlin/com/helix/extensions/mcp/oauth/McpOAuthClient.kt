package com.helix.extensions.mcp.oauth

import com.helix.core.model.NormalizedEndpoint
import com.helix.extensions.mcp.McpEndpointGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class McpOAuthTokens(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("expires_in")
    val expiresInSeconds: Long? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    val scope: String? = null,
    val issuedAtMs: Long = System.currentTimeMillis(),
) {
    init {
        require(accessToken.isNotBlank()) { "access_token must not be blank" }
    }

    fun isExpired(
        nowMs: Long = System.currentTimeMillis(),
        skewMs: Long = 60_000L,
    ): Boolean {
        if (expiresInSeconds == null) return false
        val expiryMs = issuedAtMs + (expiresInSeconds * 1000L)
        return nowMs + skewMs >= expiryMs
    }
}

data class McpOAuthAuthRequest(
    val authorizationEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val state: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = McpOAuthPkce.METHOD_S256,
    val extraParams: Map<String, String> = emptyMap(),
)

data class McpOAuthRevocationResult(
    val vendorRevoked: Boolean,
    val statusCode: Int,
    val message: String? = null,
)

class McpOAuthException(
    message: String,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class McpOAuthClient(
    val endpointGate: McpEndpointGate,
    private val okHttpClient: OkHttpClient = defaultHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val singleFlightLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Constructs the authorization URL to be opened in an external system browser.
     * Enforces PKCE S256 and high-entropy state.
     */
    fun buildAuthorizationUri(request: McpOAuthAuthRequest): String {
        require(request.clientId.isNotBlank()) { "clientId must not be blank" }
        require(request.redirectUri.isNotBlank()) { "redirectUri must not be blank" }
        require(request.state.isNotBlank()) { "state must not be blank" }
        require(request.codeChallenge.isNotBlank()) { "codeChallenge must not be blank" }

        val params =
            mutableListOf(
                "response_type" to "code",
                "client_id" to request.clientId,
                "redirect_uri" to request.redirectUri,
                "state" to request.state,
                "code_challenge" to request.codeChallenge,
                "code_challenge_method" to request.codeChallengeMethod,
            )
        if (request.scope.isNotBlank()) {
            params.add("scope" to request.scope)
        }
        for ((k, v) in request.extraParams) {
            params.add(k to v)
        }

        val query =
            params.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
        val delimiter = if (request.authorizationEndpoint.contains("?")) "&" else "?"
        return "${request.authorizationEndpoint}$delimiter$query"
    }

    /**
     * Exchanges the authorization code for access and refresh tokens.
     */
    suspend fun exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        redirectUri: String,
        code: String,
        codeVerifier: String,
    ): McpOAuthTokens =
        withContext(Dispatchers.IO) {
            val formBody =
                FormBody
                    .Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", clientId)
                    .add("redirect_uri", redirectUri)
                    .add("code", code)
                    .add("code_verifier", codeVerifier)
                    .build()

            executeTokenRequest(tokenEndpoint, formBody)
        }

    /**
     * Refreshes access token with single-flight deduplication.
     */
    suspend fun refreshToken(
        tokenEndpoint: String,
        clientId: String,
        refreshToken: String,
    ): McpOAuthTokens =
        withContext(Dispatchers.IO) {
            val lockKey = "$tokenEndpoint#$clientId#$refreshToken"
            val lock = singleFlightLocks.computeIfAbsent(lockKey) { Mutex() }
            lock.withLock {
                val formBody =
                    FormBody
                        .Builder()
                        .add("grant_type", "refresh_token")
                        .add("client_id", clientId)
                        .add("refresh_token", refreshToken)
                        .build()

                executeTokenRequest(tokenEndpoint, formBody)
            }
        }

    /**
     * Revokes token at vendor revocation endpoint per RFC 7009.
     * Returns true if remote vendor acknowledged revocation, false otherwise.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun revokeToken(
        revocationEndpoint: String,
        clientId: String,
        token: String,
        tokenTypeHint: String = "access_token",
    ): McpOAuthRevocationResult =
        withContext(Dispatchers.IO) {
            val normalized = NormalizedEndpoint.parse(revocationEndpoint)
            val permit = endpointGate.authorize(normalized)
            val client =
                okHttpClient
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .proxy(Proxy.NO_PROXY)
                    .dns(
                        Dns { hostname ->
                            if (hostname != permit.host) {
                                throw UnknownHostException("Unexpected host in OAuth revocation")
                            }
                            permit.pinnedAddresses(hostname)
                        },
                    ).build()

            val formBody =
                FormBody
                    .Builder()
                    .add("client_id", clientId)
                    .add("token", token)
                    .add("token_type_hint", tokenTypeHint)
                    .build()

            val request =
                Request
                    .Builder()
                    .url(revocationEndpoint)
                    .header("Accept", "application/json")
                    .post(formBody)
                    .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body.string()
                    McpOAuthRevocationResult(
                        vendorRevoked = response.isSuccessful,
                        statusCode = response.code,
                        message = bodyStr.take(MAX_ERROR_BYTES),
                    )
                }
            } catch (e: Exception) {
                McpOAuthRevocationResult(
                    vendorRevoked = false,
                    statusCode = -1,
                    message = e.message,
                )
            }
        }

    private suspend fun executeTokenRequest(
        tokenEndpoint: String,
        formBody: FormBody,
    ): McpOAuthTokens {
        val normalized = NormalizedEndpoint.parse(tokenEndpoint)
        val permit = endpointGate.authorize(normalized)
        val client =
            okHttpClient
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .proxy(Proxy.NO_PROXY)
                .dns(
                    Dns { hostname ->
                        if (hostname != permit.host) {
                            throw UnknownHostException("Unexpected host in OAuth token exchange")
                        }
                        permit.pinnedAddresses(hostname)
                    },
                ).build()

        val request =
            Request
                .Builder()
                .url(tokenEndpoint)
                .header("Accept", "application/json")
                .post(formBody)
                .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            require(body.length <= MAX_TOKEN_RESPONSE_BYTES) {
                "Token response exceeds maximum allowed bytes"
            }

            if (!response.isSuccessful) {
                val errorCode = parseErrorCode(body)
                val errorDesc = parseErrorDescription(body) ?: body.take(MAX_ERROR_BYTES)
                throw McpOAuthException(
                    message = "OAuth token request failed with HTTP ${response.code}: $errorDesc",
                    errorCode = errorCode,
                )
            }

            return parseTokens(body)
        }
    }

    private fun parseTokens(body: String): McpOAuthTokens {
        val element = json.parseToJsonElement(body).jsonObject
        val isOk = element["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        if (!isOk) {
            val error = element["error"]?.jsonPrimitive?.content ?: "OAuth token request returned not ok"
            throw McpOAuthException(message = error, errorCode = error)
        }

        val authedUser =
            try {
                element["authed_user"]?.jsonObject
            } catch (_: Exception) {
                null
            }
        val accessToken =
            element["access_token"]?.jsonPrimitive?.content
                ?: authedUser?.get("access_token")?.jsonPrimitive?.content
                ?: throw McpOAuthException("Missing access_token in token response")
        val tokenType = element["token_type"]?.jsonPrimitive?.content ?: "Bearer"
        val expiresIn = element["expires_in"]?.jsonPrimitive?.longOrNull
        val refreshToken =
            element["refresh_token"]?.jsonPrimitive?.content
                ?: authedUser?.get("refresh_token")?.jsonPrimitive?.content
        val scope =
            element["scope"]?.jsonPrimitive?.content
                ?: authedUser?.get("scope")?.jsonPrimitive?.content

        return McpOAuthTokens(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresInSeconds = expiresIn,
            refreshToken = refreshToken,
            scope = scope,
            issuedAtMs = System.currentTimeMillis(),
        )
    }

    private fun parseErrorCode(body: String): String? =
        try {
            json
                .parseToJsonElement(body)
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content
        } catch (_: Exception) {
            null
        }

    private fun parseErrorDescription(body: String): String? =
        try {
            json
                .parseToJsonElement(body)
                .jsonObject["error_description"]
                ?.jsonPrimitive
                ?.content
        } catch (_: Exception) {
            null
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val MAX_TOKEN_RESPONSE_BYTES = 65_536
        const val MAX_ERROR_BYTES = 1_024

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(15))
                .build()
    }
}
