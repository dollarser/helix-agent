package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class ClaudeOAuthAttempt(
    val state: String,
    val verifier: String,
    val redirectUri: String,
    val authorizeUrl: String,
)

internal data class ClaudeExchangeResult(
    val session: CliSubscriptionSession,
    val subscriptionType: String?,
)

internal object ClaudeOAuthProtocol {
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
    const val TOKEN_URL = "https://claude.ai/v1/oauth/token"
    const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
    const val CALLBACK_PATH = "/callback"
    const val SCOPE =
        "org:create_api_key user:profile user:inference user:sessions:claude_code " +
            "user:mcp_servers user:file_upload"
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    fun createAttempt(
        port: Int,
        random: SecureRandom = SecureRandom(),
    ): ClaudeOAuthAttempt {
        require(port in 1..65535)
        val state = randomToken(random, 32)
        val verifier = randomToken(random, 64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
        val redirect = "http://localhost:$port$CALLBACK_PATH"
        val query =
            FormBody
                .Builder()
                .add("code", "true")
                .add("client_id", CLIENT_ID)
                .add("response_type", "code")
                .add("redirect_uri", redirect)
                .add("scope", SCOPE)
                .add("code_challenge", challenge)
                .add("code_challenge_method", "S256")
                .add("state", state)
                .build()
                .let { body ->
                    (0 until body.size).joinToString("&") { "${body.encodedName(it)}=${body.encodedValue(it)}" }
                }
        return ClaudeOAuthAttempt(state, verifier, redirect, "$AUTHORIZE_URL?$query")
    }

    fun parseCallback(
        rawTarget: String,
        expectedState: String,
    ): CodexCallbackResult {
        if (rawTarget.length !in 1..8 * 1024) return CodexCallbackResult.Rejected("callback too large")
        val url = "http://localhost$rawTarget".toHttpUrlOrNull()
        return when {
            url == null -> CodexCallbackResult.Rejected("invalid callback")
            url.encodedPath != CALLBACK_PATH -> CodexCallbackResult.Ignored
            else -> decodeCallbackParameters(url, expectedState)
        }
    }

    private fun decodeCallbackParameters(
        url: okhttp3.HttpUrl,
        expectedState: String,
    ): CodexCallbackResult {
        val error = url.queryParameter("error_description") ?: url.queryParameter("error")
        return when {
            error != null -> {
                CodexCallbackResult.Rejected(error.take(256))
            }

            url.queryParameter("state") != expectedState -> {
                CodexCallbackResult.Ignored
            }

            else -> {
                val code = url.queryParameter("code")
                if (code.isNullOrBlank() || code.length > 4096) {
                    CodexCallbackResult.Rejected("missing or invalid authorization code")
                } else {
                    CodexCallbackResult.Code(code)
                }
            }
        }
    }

    fun decodeSession(
        bytes: ByteArray,
        nowEpochMillis: Long,
        fallback: CliSubscriptionSession? = null,
    ): CliSubscriptionSession {
        require(bytes.size in 2..MAX_RESPONSE_BYTES) { "token response size is invalid" }
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val access = value["access_token"]?.jsonPrimitive?.content
        require(!access.isNullOrBlank()) { "token response has no access token" }
        val refresh = value["refresh_token"]?.jsonPrimitive?.content ?: fallback?.refreshToken
        require(!refresh.isNullOrBlank()) { "token response has no refresh token" }
        val seconds = value["expires_in"]?.jsonPrimitive?.longOrNull
        require(seconds != null && seconds > 0) { "token response has no usable expiry" }
        return CliSubscriptionSession(
            access,
            refresh,
            null,
            Math.addExact(nowEpochMillis, Math.multiplyExact(seconds, 1000)),
        )
    }

    fun decodeSubscriptionType(bytes: ByteArray): String? {
        require(bytes.size in 2..MAX_RESPONSE_BYTES) { "profile response size is invalid" }
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val account = value["account"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return sequenceOf(value["subscriptionType"], value["subscription_type"], account?.get("subscription_type"))
            .mapNotNull { runCatching { it?.jsonPrimitive?.content }.getOrNull() }
            .firstOrNull { it.isNotBlank() }
    }

    fun isClaudeCodeEligible(subscriptionType: String?): Boolean =
        subscriptionType?.lowercase(Locale.ROOT) in setOf("pro", "max", "team", "enterprise")

    private fun randomToken(
        random: SecureRandom,
        bytes: Int,
    ) = ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal interface ClaudeOAuthTransport {
    fun exchange(
        attempt: ClaudeOAuthAttempt,
        code: String,
    ): ClaudeExchangeResult

    fun refresh(session: CliSubscriptionSession): CliSubscriptionSession
}

internal class ClaudeOAuthEndpointException(
    val httpCode: Int,
    val oauthCode: String?,
) : IllegalStateException("Claude OAuth endpoint rejected the request (HTTP $httpCode)") {
    val permanentlyInvalid = oauthCode in setOf("invalid_grant", "invalid_token")
}

internal class ClaudeEligibilityException(
    val subscriptionType: String?,
) : IllegalStateException("Claude Code subscription eligibility was not verified")

internal class OkHttpClaudeOAuthTransport(
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ClaudeOAuthTransport,
    Closeable {
    private val client =
        client
            .newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    override fun exchange(
        attempt: ClaudeOAuthAttempt,
        code: String,
    ): ClaudeExchangeResult {
        val body =
            buildJsonObject {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", attempt.redirectUri)
                put("client_id", ClaudeOAuthProtocol.CLIENT_ID)
                put("code_verifier", attempt.verifier)
                put("state", attempt.state)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val session =
            executeToken(
                Request
                    .Builder()
                    .url(ClaudeOAuthProtocol.TOKEN_URL)
                    .post(body)
                    .build(),
                null,
            )
        val profileRequest =
            Request
                .Builder()
                .url(ClaudeOAuthProtocol.PROFILE_URL)
                .header("Authorization", "Bearer ${session.accessToken}")
                .get()
                .build()
        val subscriptionType =
            client.newCall(profileRequest).execute().use { response ->
                val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
                if (!response.isSuccessful) null else ClaudeOAuthProtocol.decodeSubscriptionType(bytes)
            }
        return ClaudeExchangeResult(session, subscriptionType)
    }

    override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession {
        val body =
            buildJsonObject {
                put("grant_type", "refresh_token")
                put("refresh_token", session.refreshToken)
                put("client_id", ClaudeOAuthProtocol.CLIENT_ID)
                put("scope", ClaudeOAuthProtocol.SCOPE)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        return executeToken(
            Request
                .Builder()
                .url(ClaudeOAuthProtocol.TOKEN_URL)
                .post(body)
                .build(),
            session,
        )
    }

    override fun close() = client.dispatcher.cancelAll()

    private fun executeToken(
        request: Request,
        fallback: CliSubscriptionSession?,
    ): CliSubscriptionSession =
        client.newCall(request).execute().use { response ->
            val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
            if (!response.isSuccessful) {
                val oauthCode =
                    runCatching {
                        Json
                            .parseToJsonElement(bytes.decodeToString())
                            .jsonObject["error"]
                            ?.jsonPrimitive
                            ?.content
                    }.getOrNull()
                throw ClaudeOAuthEndpointException(response.code, oauthCode)
            }
            ClaudeOAuthProtocol.decodeSession(bytes, clock(), fallback)
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal class ClaudeLoginController(
    private val vault: CliSubscriptionCredentialVault,
    private val transport: ClaudeOAuthTransport,
) {
    fun complete(
        attempt: ClaudeOAuthAttempt,
        code: String,
    ): String {
        val result = transport.exchange(attempt, code)
        if (!ClaudeOAuthProtocol.isClaudeCodeEligible(result.subscriptionType)) {
            vault.logout(CliSubscriptionProvider.CLAUDE)
            throw ClaudeEligibilityException(result.subscriptionType)
        }
        vault.save(CliSubscriptionProvider.CLAUDE, result.session)
        return result.subscriptionType!!
    }

    fun refresh() {
        try {
            vault.save(CliSubscriptionProvider.CLAUDE, transport.refresh(vault.load(CliSubscriptionProvider.CLAUDE)))
        } catch (error: ClaudeOAuthEndpointException) {
            if (error.permanentlyInvalid) vault.logout(CliSubscriptionProvider.CLAUDE)
            throw error
        }
    }

    fun logout() = vault.logout(CliSubscriptionProvider.CLAUDE)
}
