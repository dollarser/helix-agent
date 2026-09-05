package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class GrokDeviceAttempt(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresAtEpochMillis: Long,
    val intervalMillis: Long,
)

internal sealed interface GrokDevicePoll {
    data class Authorized(
        val session: CliSubscriptionSession,
        val tier: Int,
    ) : GrokDevicePoll

    data class Pending(
        val nextIntervalMillis: Long,
    ) : GrokDevicePoll

    data class Rejected(
        val code: String,
    ) : GrokDevicePoll
}

internal object GrokDeviceProtocol {
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
    const val TOKEN_URL = "https://auth.x.ai/oauth2/token"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    fun decodeAttempt(
        bytes: ByteArray,
        nowEpochMillis: Long,
    ): GrokDeviceAttempt {
        val value = decodeObject(bytes)
        val expiresIn = value["expires_in"]?.jsonPrimitive?.longOrNull
        val interval = value["interval"]?.jsonPrimitive?.longOrNull ?: 5L
        require(expiresIn != null && expiresIn in 1..1_800) { "invalid device expiry" }
        require(interval in 1..60) { "invalid polling interval" }
        val userCode = requiredString(value, "user_code")
        require(userCode.all { it.isLetterOrDigit() || it == '-' }) { "invalid user code" }
        val verificationUri = requiredString(value, "verification_uri").also(::requireXaiHttps)
        val complete = value["verification_uri_complete"]?.jsonPrimitive?.content?.also(::requireXaiHttps)
        return GrokDeviceAttempt(
            requiredString(value, "device_code"),
            userCode,
            verificationUri,
            complete,
            Math.addExact(nowEpochMillis, Math.multiplyExact(expiresIn, 1_000L)),
            maxOf(5_000L, Math.multiplyExact(interval, 1_000L)),
        )
    }

    fun decodePoll(
        bytes: ByteArray,
        currentIntervalMillis: Long,
        nowEpochMillis: Long,
    ): GrokDevicePoll {
        val value = decodeObject(bytes)
        value["access_token"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { access ->
            val tier = decodeTier(access) ?: return GrokDevicePoll.Rejected("unknown_tier")
            if (tier !in setOf(1, 3, 4, 5, 6, 7)) return GrokDevicePoll.Rejected("ineligible_tier")
            val refresh =
                value["refresh_token"]
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf(String::isNotBlank) ?: return GrokDevicePoll.Rejected("missing_refresh_token")
            val expiresIn = value["expires_in"]?.jsonPrimitive?.longOrNull
            if (expiresIn == null || expiresIn <= 0) return GrokDevicePoll.Rejected("invalid_expiry")
            return GrokDevicePoll.Authorized(
                CliSubscriptionSession(
                    access,
                    refresh,
                    null,
                    Math.addExact(nowEpochMillis, Math.multiplyExact(expiresIn, 1_000L)),
                ),
                tier,
            )
        }
        return when (val code = requiredString(value, "error")) {
            "authorization_pending" -> GrokDevicePoll.Pending(currentIntervalMillis)
            "slow_down" -> GrokDevicePoll.Pending(Math.addExact(currentIntervalMillis, 5_000L))
            "access_denied", "expired_token" -> GrokDevicePoll.Rejected(code)
            else -> GrokDevicePoll.Rejected("protocol_error")
        }
    }

    fun decodeRefresh(
        bytes: ByteArray,
        previous: CliSubscriptionSession,
        nowEpochMillis: Long,
    ): CliSubscriptionSession {
        val value = decodeObject(bytes)
        val access = requiredString(value, "access_token")
        val tier = decodeTier(access)
        require(tier in setOf(1, 3, 4, 5, 6, 7)) { "refreshed token has no eligible tier" }
        val refresh =
            value["refresh_token"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: previous.refreshToken
        val expiresIn = value["expires_in"]?.jsonPrimitive?.longOrNull
        require(expiresIn != null && expiresIn > 0) { "invalid refreshed expiry" }
        return CliSubscriptionSession(access, refresh, null, nowEpochMillis + expiresIn * 1_000L)
    }

    private fun decodeTier(token: String): Int? =
        runCatching {
            val payload = token.split('.').also { require(it.size >= 2 && it[1].length <= MAX_RESPONSE_BYTES) }[1]
            val decoded = Base64.getUrlDecoder().decode(payload)
            require(decoded.size <= MAX_RESPONSE_BYTES)
            Json
                .parseToJsonElement(decoded.decodeToString())
                .jsonObject["tier"]
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull()
        }.getOrNull()

    private fun requireXaiHttps(value: String) {
        val url = value.toHttpUrlOrNull()
        require(url != null && url.isHttps && (url.host == "x.ai" || url.host.endsWith(".x.ai"))) {
            "unexpected verification URI"
        }
    }

    private fun decodeObject(bytes: ByteArray) =
        bytes
            .also {
                require(it.size in 2..MAX_RESPONSE_BYTES) { "OAuth response size is invalid" }
            }.decodeToString()
            .let(Json::parseToJsonElement)
            .jsonObject

    private fun requiredString(
        value: kotlinx.serialization.json.JsonObject,
        name: String,
    ) = value
        .getValue(name)
        .jsonPrimitive.content
        .also { require(it.isNotBlank() && it.length <= 8_192) }
}

internal class GrokOAuthEndpointException(
    val httpCode: Int,
    val oauthCode: String?,
) : IllegalStateException("Grok OAuth endpoint rejected the request (HTTP $httpCode)") {
    val permanentlyInvalid = oauthCode == "invalid_grant"
}

internal interface GrokDeviceTransport {
    fun requestDeviceCode(): GrokDeviceAttempt

    fun poll(
        attempt: GrokDeviceAttempt,
        intervalMillis: Long,
    ): GrokDevicePoll

    fun refresh(session: CliSubscriptionSession): CliSubscriptionSession
}

internal class OkHttpGrokDeviceTransport(
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : GrokDeviceTransport,
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

    override fun requestDeviceCode(): GrokDeviceAttempt =
        execute(
            Request
                .Builder()
                .url(GrokDeviceProtocol.DEVICE_CODE_URL)
                .header("Accept", "application/json")
                .header("x-grok-client-surface", "ui")
                .post(
                    FormBody
                        .Builder()
                        .add("client_id", GrokDeviceProtocol.CLIENT_ID)
                        .add("scope", GrokDeviceProtocol.SCOPE)
                        .add("referrer", "helix-sideload")
                        .build(),
                ).build(),
        ).let { GrokDeviceProtocol.decodeAttempt(it, clock()) }

    override fun poll(
        attempt: GrokDeviceAttempt,
        intervalMillis: Long,
    ): GrokDevicePoll =
        executeAllowOAuthError(
            Request
                .Builder()
                .url(GrokDeviceProtocol.TOKEN_URL)
                .header("Accept", "application/json")
                .header("x-grok-client-surface", "ui")
                .post(
                    FormBody
                        .Builder()
                        .add("grant_type", GrokDeviceProtocol.DEVICE_GRANT)
                        .add("device_code", attempt.deviceCode)
                        .add("client_id", GrokDeviceProtocol.CLIENT_ID)
                        .build(),
                ).build(),
        ).let { GrokDeviceProtocol.decodePoll(it, intervalMillis, clock()) }

    override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession =
        execute(
            Request
                .Builder()
                .url(GrokDeviceProtocol.TOKEN_URL)
                .header("Accept", "application/json")
                .post(
                    FormBody
                        .Builder()
                        .add("grant_type", "refresh_token")
                        .add("client_id", GrokDeviceProtocol.CLIENT_ID)
                        .add("refresh_token", session.refreshToken)
                        .build(),
                ).build(),
        ).let { GrokDeviceProtocol.decodeRefresh(it, session, clock()) }

    override fun close() = client.dispatcher.cancelAll()

    private fun execute(request: Request): ByteArray =
        client.newCall(request).execute().use { response ->
            val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
            if (!response.isSuccessful) throw endpointError(response.code, bytes)
            bytes
        }

    private fun executeAllowOAuthError(request: Request): ByteArray =
        client.newCall(request).execute().use { response ->
            val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
            if (!response.isSuccessful && response.code !in 400..499) throw endpointError(response.code, bytes)
            bytes
        }

    private fun endpointError(
        code: Int,
        bytes: ByteArray,
    ): GrokOAuthEndpointException {
        val oauthCode =
            runCatching {
                Json
                    .parseToJsonElement(bytes.decodeToString())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
        return GrokOAuthEndpointException(code, oauthCode)
    }
}

internal class GrokLoginController(
    private val vault: CliSubscriptionCredentialVault,
    private val transport: GrokDeviceTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    fun start() = transport.requestDeviceCode()

    fun finish(
        attempt: GrokDeviceAttempt,
        cancellation: DeviceLoginCancellation,
    ): Int {
        var interval = attempt.intervalMillis
        while (true) {
            cancellation.check()
            check(clock() < attempt.expiresAtEpochMillis) { "login expired" }
            sleep(interval)
            cancellation.check()
            val result =
                try {
                    transport.poll(attempt, interval)
                } catch (_: IOException) {
                    continue
                }
            when (result) {
                is GrokDevicePoll.Authorized -> {
                    cancellation.check()
                    vault.save(CliSubscriptionProvider.GROK, result.session)
                    return result.tier
                }

                is GrokDevicePoll.Pending -> {
                    interval = result.nextIntervalMillis
                }

                is GrokDevicePoll.Rejected -> {
                    error("device authorization rejected: ${result.code}")
                }
            }
        }
    }

    fun refresh() {
        try {
            vault.save(CliSubscriptionProvider.GROK, transport.refresh(vault.load(CliSubscriptionProvider.GROK)))
        } catch (error: GrokOAuthEndpointException) {
            if (error.permanentlyInvalid) vault.logout(CliSubscriptionProvider.GROK)
            throw error
        } catch (error: IllegalArgumentException) {
            vault.logout(CliSubscriptionProvider.GROK)
            throw error
        }
    }

    fun logout() = vault.logout(CliSubscriptionProvider.GROK)
}
