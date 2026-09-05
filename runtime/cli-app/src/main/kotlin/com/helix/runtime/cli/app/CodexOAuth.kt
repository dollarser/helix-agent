package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.Closeable
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class CodexOAuthAttempt(
    val state: String,
    val verifier: String,
    val redirectUri: String,
    val authorizeUrl: String,
)

internal sealed interface CodexCallbackResult {
    data class Code(
        val value: String,
    ) : CodexCallbackResult

    data class Rejected(
        val reason: String,
    ) : CodexCallbackResult

    data object Ignored : CodexCallbackResult
}

internal object CodexOAuthProtocol {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    const val CALLBACK_PATH = "/auth/callback"
    const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    private const val MAX_CALLBACK_CHARS = 8 * 1024
    private const val MAX_TOKEN_RESPONSE_BYTES = 64 * 1024

    fun createAttempt(
        port: Int,
        random: SecureRandom = SecureRandom(),
    ): CodexOAuthAttempt {
        require(port in 1..65535)
        val state = randomToken(random, 32)
        val verifier = randomToken(random, 64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
        val redirect = "http://localhost:$port$CALLBACK_PATH"
        val query =
            FormBody
                .Builder()
                .add("response_type", "code")
                .add("client_id", CLIENT_ID)
                .add("redirect_uri", redirect)
                .add("scope", SCOPE)
                .add("code_challenge", challenge)
                .add("code_challenge_method", "S256")
                .add("state", state)
                .add("id_token_add_organizations", "true")
                .add("codex_cli_simplified_flow", "true")
                .add("originator", "codex_cli_rs")
                .build()
                .let { body ->
                    (0 until body.size).joinToString("&") { "${body.encodedName(it)}=${body.encodedValue(it)}" }
                }
        return CodexOAuthAttempt(state, verifier, redirect, "$AUTHORIZE_URL?$query")
    }

    @Suppress("ReturnCount") // Each malformed OAuth callback class has a distinct fail-closed result.
    fun parseCallback(
        rawTarget: String,
        expectedState: String,
    ): CodexCallbackResult {
        if (rawTarget.length !in 1..MAX_CALLBACK_CHARS) return CodexCallbackResult.Rejected("callback too large")
        val url =
            "http://localhost$rawTarget".toHttpUrlOrNull()
                ?: return CodexCallbackResult.Rejected("invalid callback")
        if (url.encodedPath != CALLBACK_PATH) return CodexCallbackResult.Ignored
        url.queryParameter("error_description")?.let { return CodexCallbackResult.Rejected(it.take(256)) }
        url.queryParameter("error")?.let { return CodexCallbackResult.Rejected(it.take(256)) }
        if (url.queryParameter("state") != expectedState) return CodexCallbackResult.Ignored
        val code = url.queryParameter("code")
        return if (code.isNullOrBlank() || code.length > 4096) {
            CodexCallbackResult.Rejected("missing or invalid authorization code")
        } else {
            CodexCallbackResult.Code(code)
        }
    }

    fun decodeSession(
        bytes: ByteArray,
        nowEpochMillis: Long,
        fallback: CliSubscriptionSession? = null,
    ): CliSubscriptionSession {
        require(bytes.size in 2..MAX_TOKEN_RESPONSE_BYTES) { "token response size is invalid" }
        val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val access = value.requiredString("access_token")
        val refresh = value["refresh_token"]?.jsonPrimitive?.content ?: fallback?.refreshToken
        require(!refresh.isNullOrBlank()) { "token response has no refresh token" }
        val idToken = value["id_token"]?.jsonPrimitive?.content ?: fallback?.idToken
        val expiresIn = value["expires_in"]?.jsonPrimitive?.longOrNull
        val expiry =
            when {
                expiresIn != null && expiresIn > 0 -> Math.addExact(nowEpochMillis, Math.multiplyExact(expiresIn, 1000))
                else -> jwtPayload(access)["exp"]?.jsonPrimitive?.longOrNull?.let { Math.multiplyExact(it, 1000) }
            }
        require(expiry != null && expiry > nowEpochMillis) { "token response has no usable expiry" }
        val accountId =
            idToken
                ?.let(::jwtPayload)
                ?.get("https://api.openai.com/auth")
                ?.jsonObject
                ?.get("chatgpt_account_id")
                ?.jsonPrimitive
                ?.content ?: fallback?.accountId
        require(!accountId.isNullOrBlank()) { "token response has no ChatGPT account id" }
        return CliSubscriptionSession(access, refresh, idToken, expiry, accountId)
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.content?.also { require(it.isNotBlank()) { "$name is blank" } }
            ?: throw IllegalArgumentException("token response has no $name")

    private fun jwtPayload(token: String): JsonObject {
        val parts = token.split('.')
        require(parts.size >= 2 && parts[1].length <= MAX_TOKEN_RESPONSE_BYTES) { "JWT shape is invalid" }
        val decoded = Base64.getUrlDecoder().decode(parts[1])
        require(decoded.size <= MAX_TOKEN_RESPONSE_BYTES) { "JWT payload is too large" }
        return Json.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject
    }

    private fun randomToken(
        random: SecureRandom,
        bytes: Int,
    ): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal interface CodexOAuthTransport {
    fun exchange(
        attempt: CodexOAuthAttempt,
        code: String,
    ): CliSubscriptionSession

    fun refresh(session: CliSubscriptionSession): CliSubscriptionSession
}

internal class CodexOAuthEndpointException(
    val httpCode: Int,
    val oauthCode: String?,
) : IllegalStateException("token endpoint rejected the request (HTTP $httpCode)") {
    val permanentlyInvalid: Boolean = oauthCode in PERMANENT_REFRESH_CODES

    private companion object {
        val PERMANENT_REFRESH_CODES =
            setOf("refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated", "invalid_grant")
    }
}

internal class OkHttpCodexOAuthTransport(
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CodexOAuthTransport,
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

    fun preflight() {
        val request =
            Request
                .Builder()
                .url(CodexOAuthProtocol.TOKEN_URL)
                .post(FormBody.Builder().add("grant_type", "helix_connectivity_probe").build())
                .build()
        client.newCall(request).execute().use { response ->
            require(response.code in 400..499) { "token endpoint preflight returned an unexpected status" }
        }
    }

    override fun exchange(
        attempt: CodexOAuthAttempt,
        code: String,
    ): CliSubscriptionSession {
        val body =
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", attempt.redirectUri)
                .add("client_id", CodexOAuthProtocol.CLIENT_ID)
                .add("code_verifier", attempt.verifier)
                .build()
        return execute(
            Request
                .Builder()
                .url(CodexOAuthProtocol.TOKEN_URL)
                .post(body)
                .build(),
            null,
        )
    }

    override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession {
        val body =
            """{"client_id":"${CodexOAuthProtocol.CLIENT_ID}","grant_type":"refresh_token","refresh_token":${Json.encodeToString(
                session.refreshToken,
            )}}"""
                .toRequestBody("application/json".toMediaType())
        return execute(
            Request
                .Builder()
                .url(CodexOAuthProtocol.TOKEN_URL)
                .post(body)
                .build(),
            session,
        )
    }

    override fun close() {
        client.dispatcher.cancelAll()
    }

    private fun execute(
        request: Request,
        fallback: CliSubscriptionSession?,
    ): CliSubscriptionSession =
        client.newCall(request).execute().use { response ->
            val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
            if (!response.isSuccessful) {
                val oauthCode =
                    runCatching {
                        Json
                            .parseToJsonElement(bytes.toString(Charsets.UTF_8))
                            .jsonObject["error"]
                            ?.jsonPrimitive
                            ?.content
                    }.getOrNull()
                throw CodexOAuthEndpointException(response.code, oauthCode)
            }
            CodexOAuthProtocol.decodeSession(bytes, clock(), fallback)
        }
}

internal class BoundedDnsCache(
    private val upstream: Dns = Dns.SYSTEM,
    private val clock: () -> Long = System::currentTimeMillis,
) : Dns {
    private data class Entry(
        val addresses: List<InetAddress>,
        val expiresAtMillis: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> =
        synchronized(entries) {
            entries[hostname]?.takeIf { it.expiresAtMillis > clock() }?.addresses
        } ?: upstream.lookup(hostname).also { addresses ->
            synchronized(entries) {
                if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
                entries[hostname] = Entry(addresses.toList(), clock() + MAX_AGE_MILLIS)
            }
        }

    private companion object {
        const val MAX_ENTRIES = 8
        const val MAX_AGE_MILLIS = 5 * 60 * 1000L
    }
}

internal fun BufferedSource.readBoundedByteArray(maxBytes: Long): ByteArray {
    require(maxBytes > 0)
    request(maxBytes + 1)
    require(buffer.size <= maxBytes) { "token response is too large" }
    return readByteArray()
}

internal class CodexLoginController(
    private val vault: CliSubscriptionCredentialVault,
    private val transport: CodexOAuthTransport,
) {
    fun complete(
        attempt: CodexOAuthAttempt,
        code: String,
    ) {
        vault.save(CliSubscriptionProvider.CODEX, transport.exchange(attempt, code))
    }

    fun refresh() {
        try {
            vault.save(CliSubscriptionProvider.CODEX, transport.refresh(vault.load(CliSubscriptionProvider.CODEX)))
        } catch (error: CodexOAuthEndpointException) {
            if (error.permanentlyInvalid) vault.logout(CliSubscriptionProvider.CODEX)
            throw error
        }
    }

    fun logout() = vault.logout(CliSubscriptionProvider.CODEX)
}
