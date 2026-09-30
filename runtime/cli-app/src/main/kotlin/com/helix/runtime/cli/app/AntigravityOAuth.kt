package com.helix.runtime.cli.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class AntigravityOnboardingRequired : IllegalStateException("Antigravity project unavailable")

/** OAuth client identity is supplied by the build owner, never inherited from an upstream app. */
internal object AntigravityOAuthProtocol {
    const val CALLBACK = "/oauth-callback"
    private val scopes =
        listOf(
            "openid",
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

    fun attempt(
        port: Int,
        random: SecureRandom = SecureRandom(),
        client: AntigravityClientConfig = AntigravityClientConfig.build(),
    ): CodexOAuthAttempt {
        client.requireConfigured()
        require(port in 1..65535)

        fun randomValue(size: Int): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(size).also(random::nextBytes))
        val state = randomValue(32)
        val verifier = randomValue(64)
        val challenge =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val redirect = "http://127.0.0.1:$port$CALLBACK"
        val url =
            "https://accounts.google.com/o/oauth2/v2/auth"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client_id", client.id)
                .addQueryParameter("redirect_uri", redirect)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", scopes.joinToString(" "))
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("access_type", "offline")
                .addQueryParameter("prompt", "consent")
                .build()
                .toString()
        return CodexOAuthAttempt(state, verifier, redirect, url)
    }

    fun callback(
        target: String,
        state: String,
    ): CodexCallbackResult {
        val validTarget = target.length in 1..8192 && target.startsWith("/")
        val url = if (validTarget) "http://127.0.0.1$target".toHttpUrlOrNull() else null
        val stateMatches = url?.encodedPath == CALLBACK && url.queryParameterValues("state") == listOf(state)
        val code = url?.queryParameterValues("code")?.singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 4096 }
        return when {
            !stateMatches -> CodexCallbackResult.Ignored

            url?.queryParameter(
                "error",
            ) != null || code == null -> CodexCallbackResult.Rejected("Google authorization rejected")

            else -> CodexCallbackResult.Code(code)
        }
    }

    fun session(
        tokens: JsonObject,
        now: Long,
        old: CliSubscriptionSession? = null,
    ): CliSubscriptionSession {
        val access = tokens["access_token"]?.jsonPrimitive?.also { require(it.isString) }?.contentOrNull
        val refresh =
            tokens["refresh_token"]?.jsonPrimitive?.also { require(it.isString) }?.contentOrNull
                ?: old?.refreshToken
        val seconds = tokens["expires_in"]?.jsonPrimitive?.longOrNull
        require(!access.isNullOrBlank() && !refresh.isNullOrBlank() && seconds != null && seconds in 1..604800)
        return CliSubscriptionSession(access, refresh, null, Math.addExact(now, seconds * 1000), old?.accountId)
    }

    fun project(root: JsonObject): String {
        val value = root["cloudaicompanionProject"]
        val id =
            when (value) {
                is JsonPrimitive -> value.also { require(it.isString) }.contentOrNull
                is JsonObject -> value["id"]?.jsonPrimitive?.also { require(it.isString) }?.contentOrNull
                else -> null
            }
        if (id.isNullOrBlank()) throw AntigravityOnboardingRequired()
        require(id.length <= 256 && id.none { it.isWhitespace() || it.code < 32 })
        return id
    }
}

/** No automatic onboarding, account rotation, paid-tier choice or eligibility bypass. */
internal class AntigravityAuth(
    private val http: AntigravityHttp,
    private val client: AntigravityClientConfig = AntigravityClientConfig.build(),
) : Closeable {
    fun exchange(
        attempt: CodexOAuthAttempt,
        code: String,
    ): CliSubscriptionSession {
        val tokens =
            http.token(
                clientFields() +
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to attempt.redirectUri,
                        "code_verifier" to attempt.verifier,
                    ),
            )
        val session = AntigravityOAuthProtocol.session(tokens, System.currentTimeMillis())
        val payload =
            buildJsonObject {
                put(
                    "metadata",
                    buildJsonObject {
                        put("ideType", "ANTIGRAVITY")
                        put("platform", "PLATFORM_UNSPECIFIED")
                        put("pluginType", "GEMINI")
                    },
                )
            }
        val project = AntigravityOAuthProtocol.project(http.call("loadCodeAssist", payload, session.accessToken))
        return session.copy(accountId = project)
    }

    fun current(vault: CliSubscriptionCredentialVault): CliCredentialSnapshot {
        client.requireConfigured()
        val platform = CliSubscriptionProvider.ANTIGRAVITY
        if (!vault.contains(platform)) throw AntigravityHttpException(401)
        val before = vault.snapshot(platform)
        if (before.session.expiresAtEpochMillis > System.currentTimeMillis() + 30000) return before
        val tokens =
            http.token(
                clientFields() +
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to before.session.refreshToken,
                    ),
            )
        val fresh = AntigravityOAuthProtocol.session(tokens, System.currentTimeMillis(), before.session)
        vault.renew(platform, before, fresh)
        val after = vault.snapshot(platform)
        check(before.revision == after.revision) { "Login changed during refresh" }
        return after
    }

    private fun clientFields(): Map<String, String> {
        client.requireConfigured()
        return mapOf("client_id" to client.id, "client_secret" to client.secret)
    }

    override fun close() = http.close()
}
