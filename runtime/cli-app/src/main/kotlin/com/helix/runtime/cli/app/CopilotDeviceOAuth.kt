package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class CopilotDeviceAttempt(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMillis: Long,
    val intervalMillis: Long,
)

internal sealed interface CopilotDevicePoll {
    data class Authorized(
        val githubToken: String,
    ) : CopilotDevicePoll

    data class Pending(
        val nextIntervalMillis: Long,
    ) : CopilotDevicePoll

    data class Rejected(
        val code: String,
    ) : CopilotDevicePoll
}

internal object CopilotDeviceProtocol {
    // Public identifier accepted by ADR-0026 for personal sideload experiments only.
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val DEVICE_TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"
    const val SCOPE = "read:user"
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val MIN_INTERVAL_MILLIS = 5_000L

    fun decodeAttempt(
        bytes: ByteArray,
        nowEpochMillis: Long,
    ): CopilotDeviceAttempt {
        val value = decodeObject(bytes)
        val expiresIn = value.getValue("expires_in").jsonPrimitive.longOrNull
        val interval = value["interval"]?.jsonPrimitive?.longOrNull ?: 5L
        require(expiresIn != null && expiresIn in 1..900) { "invalid device expiry" }
        require(interval in 1..60) { "invalid polling interval" }
        val uri = value.getValue("verification_uri").jsonPrimitive.content
        require(uri == "https://github.com/login/device") { "unexpected verification URI" }
        return CopilotDeviceAttempt(
            requiredString(value, "device_code"),
            requiredString(value, "user_code"),
            uri,
            Math.addExact(nowEpochMillis, Math.multiplyExact(expiresIn, 1_000L)),
            maxOf(MIN_INTERVAL_MILLIS, interval * 1_000L),
        )
    }

    fun decodePoll(
        bytes: ByteArray,
        currentIntervalMillis: Long,
    ): CopilotDevicePoll {
        val value = decodeObject(bytes)
        value["access_token"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
            return CopilotDevicePoll.Authorized(it)
        }
        val code = requiredString(value, "error")
        return when (code) {
            "authorization_pending" -> CopilotDevicePoll.Pending(currentIntervalMillis)
            "slow_down" -> CopilotDevicePoll.Pending(Math.addExact(currentIntervalMillis, 5_000L))
            "access_denied", "expired_token" -> CopilotDevicePoll.Rejected(code)
            else -> CopilotDevicePoll.Rejected("protocol_error")
        }
    }

    fun decodeCopilotSession(
        bytes: ByteArray,
        githubToken: String,
        nowEpochMillis: Long,
    ): CliSubscriptionSession {
        val value = decodeObject(bytes)
        val token = requiredString(value, "token")
        val expiresAtSeconds = value["expires_at"]?.jsonPrimitive?.longOrNull
        val expiry =
            if (expiresAtSeconds != null && expiresAtSeconds > nowEpochMillis / 1_000L) {
                Math.multiplyExact(expiresAtSeconds, 1_000L)
            } else {
                nowEpochMillis + 25 * 60_000L
            }
        return CliSubscriptionSession(token, githubToken, null, expiry)
    }

    private fun decodeObject(bytes: ByteArray) =
        bytes
            .also { require(it.size in 2..MAX_RESPONSE_BYTES) { "OAuth response size is invalid" } }
            .toString(Charsets.UTF_8)
            .let(Json::parseToJsonElement)
            .jsonObject

    private fun requiredString(
        value: kotlinx.serialization.json.JsonObject,
        name: String,
    ): String =
        value
            .getValue(name)
            .jsonPrimitive.content
            .also { require(it.isNotBlank() && it.length <= 8_192) }
}

internal class CopilotOAuthEndpointException(
    val httpCode: Int,
) : IllegalStateException("Copilot OAuth endpoint rejected the request (HTTP $httpCode)")

internal interface CopilotDeviceTransport {
    fun requestDeviceCode(): CopilotDeviceAttempt

    fun poll(
        attempt: CopilotDeviceAttempt,
        intervalMillis: Long,
    ): CopilotDevicePoll

    fun exchange(githubToken: String): CliSubscriptionSession
}

internal class OkHttpCopilotDeviceTransport(
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CopilotDeviceTransport,
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

    override fun requestDeviceCode(): CopilotDeviceAttempt =
        execute(
            Request
                .Builder()
                .url(CopilotDeviceProtocol.DEVICE_CODE_URL)
                .header("Accept", "application/json")
                .post(
                    FormBody
                        .Builder()
                        .add("client_id", CopilotDeviceProtocol.CLIENT_ID)
                        .add("scope", CopilotDeviceProtocol.SCOPE)
                        .build(),
                ).build(),
        ).let { CopilotDeviceProtocol.decodeAttempt(it, clock()) }

    override fun poll(
        attempt: CopilotDeviceAttempt,
        intervalMillis: Long,
    ): CopilotDevicePoll =
        execute(
            Request
                .Builder()
                .url(CopilotDeviceProtocol.DEVICE_TOKEN_URL)
                .header("Accept", "application/json")
                .post(
                    FormBody
                        .Builder()
                        .add("client_id", CopilotDeviceProtocol.CLIENT_ID)
                        .add("device_code", attempt.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .build(),
                ).build(),
        ).let { CopilotDeviceProtocol.decodePoll(it, intervalMillis) }

    override fun exchange(githubToken: String): CliSubscriptionSession =
        execute(
            Request
                .Builder()
                .url(CopilotDeviceProtocol.COPILOT_TOKEN_URL)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $githubToken")
                .header("User-Agent", "GitHubCopilotChat/0.35.0")
                .header("Editor-Version", "vscode/1.107.0")
                .header("Editor-Plugin-Version", "copilot-chat/0.35.0")
                .header("Copilot-Integration-Id", "vscode-chat")
                .get()
                .build(),
        ).let { CopilotDeviceProtocol.decodeCopilotSession(it, githubToken, clock()) }

    override fun close() = client.dispatcher.cancelAll()

    private fun execute(request: Request): ByteArray =
        client.newCall(request).execute().use { response ->
            val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
            if (!response.isSuccessful) throw CopilotOAuthEndpointException(response.code)
            bytes
        }
}

internal class DeviceLoginCancellation {
    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    fun check() = check(!cancelled.get()) { "login cancelled" }
}

internal typealias CopilotLoginCancellation = DeviceLoginCancellation

internal class CopilotLoginController(
    private val vault: CliSubscriptionCredentialVault,
    private val transport: CopilotDeviceTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    fun start(): CopilotDeviceAttempt = transport.requestDeviceCode()

    fun finish(
        attempt: CopilotDeviceAttempt,
        cancellation: CopilotLoginCancellation,
    ) {
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
                    // Preserve this user-visible authorization attempt across transient network loss.
                    continue
                }
            when (result) {
                is CopilotDevicePoll.Authorized -> {
                    cancellation.check()
                    val session = exchangeWithRetry(result.githubToken, cancellation)
                    cancellation.check()
                    vault.save(CliSubscriptionProvider.COPILOT, session)
                    return
                }

                is CopilotDevicePoll.Pending -> {
                    interval = result.nextIntervalMillis
                }

                is CopilotDevicePoll.Rejected -> {
                    error("device authorization rejected: ${result.code}")
                }
            }
        }
    }

    private fun exchangeWithRetry(
        githubToken: String,
        cancellation: CopilotLoginCancellation,
    ): CliSubscriptionSession {
        var lastFailure: IOException? = null
        repeat(5) { attempt ->
            cancellation.check()
            try {
                return transport.exchange(githubToken)
            } catch (error: IOException) {
                lastFailure = error
                if (attempt < 4) sleep((attempt + 1) * 2_000L)
            }
        }
        throw requireNotNull(lastFailure)
    }

    fun refresh() {
        val previous = vault.load(CliSubscriptionProvider.COPILOT)
        val renewed = transport.exchange(previous.refreshToken)
        vault.save(CliSubscriptionProvider.COPILOT, renewed)
    }

    fun logout() = vault.logout(CliSubscriptionProvider.COPILOT)
}
