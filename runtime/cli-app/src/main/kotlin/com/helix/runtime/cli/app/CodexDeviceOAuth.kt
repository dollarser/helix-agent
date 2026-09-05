package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class CodexDeviceAttempt(
    val deviceAuthId: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalMillis: Long,
    val expiresAtEpochMillis: Long,
)

internal data class CodexDeviceAuthorization(
    val code: String,
    val verifier: String,
)

internal sealed interface CodexDevicePoll {
    data object Pending : CodexDevicePoll

    data class Authorized(
        val authorization: CodexDeviceAuthorization,
    ) : CodexDevicePoll
}

internal class CodexDeviceEndpointException(
    val stage: String,
    val httpCode: Int,
) : IllegalStateException("Codex device $stage endpoint returned HTTP $httpCode")

internal class CodexDeviceLoginException(
    val reason: String,
) : IllegalStateException("Codex device login stopped: $reason")

internal class CodexDeviceNetworkException(
    val stage: String,
    cause: IOException,
) : IOException("Codex device $stage network failure", cause)

internal object CodexDeviceProtocol {
    const val USER_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    const val POLL_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
    const val VERIFICATION_URL = "https://auth.openai.com/codex/device"
    const val REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"
    const val EXPIRES_MILLIS = 15 * 60 * 1_000L
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    fun decodeAttempt(
        bytes: ByteArray,
        nowEpochMillis: Long,
    ): CodexDeviceAttempt {
        val value = decodeObject(bytes)
        val intervalSeconds =
            value
                .getValue("interval")
                .jsonPrimitive.content
                .trim()
                .toLongOrNull()
        require(intervalSeconds != null && intervalSeconds in 1..60) { "invalid device interval" }
        val userCode = requiredString(value, "user_code")
        require(userCode.length <= 64 && userCode.all { it.isLetterOrDigit() || it == '-' }) { "invalid user code" }
        return CodexDeviceAttempt(
            requiredString(value, "device_auth_id"),
            userCode,
            VERIFICATION_URL,
            Math.multiplyExact(intervalSeconds, 1_000L),
            Math.addExact(nowEpochMillis, EXPIRES_MILLIS),
        )
    }

    fun decodePoll(bytes: ByteArray): CodexDevicePoll {
        val value = decodeObject(bytes)
        val code = requiredString(value, "authorization_code")
        val verifier = requiredString(value, "code_verifier")
        val challenge = requiredString(value, "code_challenge")
        val expected =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
            )
        require(MessageDigest.isEqual(expected.encodeToByteArray(), challenge.encodeToByteArray())) {
            "device PKCE challenge does not match verifier"
        }
        return CodexDevicePoll.Authorized(CodexDeviceAuthorization(code, verifier))
    }

    private fun decodeObject(bytes: ByteArray) =
        bytes
            .also { require(it.size in 2..MAX_RESPONSE_BYTES) { "device response size is invalid" } }
            .decodeToString()
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

internal interface CodexDeviceTransport {
    fun requestDeviceCode(): CodexDeviceAttempt

    fun poll(attempt: CodexDeviceAttempt): CodexDevicePoll

    fun exchange(
        attempt: CodexDeviceAttempt,
        authorization: CodexDeviceAuthorization,
    ): CliSubscriptionSession
}

internal class OkHttpCodexDeviceTransport(
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CodexDeviceTransport,
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

    override fun requestDeviceCode(): CodexDeviceAttempt {
        val body = buildJsonObject { put("client_id", CodexOAuthProtocol.CLIENT_ID) }.toString()
        val bytes = network("request") { execute("request", CodexDeviceProtocol.USER_CODE_URL, body, setOf(200)) }
        return CodexDeviceProtocol.decodeAttempt(bytes, clock())
    }

    override fun poll(attempt: CodexDeviceAttempt): CodexDevicePoll {
        val body =
            buildJsonObject {
                put("device_auth_id", attempt.deviceAuthId)
                put("user_code", attempt.userCode)
            }.toString()
        val result = network("poll") { executePoll(body) }
        return when (result.first) {
            200 -> CodexDeviceProtocol.decodePoll(result.second)
            403, 404 -> CodexDevicePoll.Pending
            else -> throw CodexDeviceEndpointException("poll", result.first)
        }
    }

    override fun exchange(
        attempt: CodexDeviceAttempt,
        authorization: CodexDeviceAuthorization,
    ): CliSubscriptionSession {
        val body =
            okhttp3.FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("code", authorization.code)
                .add("redirect_uri", CodexDeviceProtocol.REDIRECT_URI)
                .add("client_id", CodexOAuthProtocol.CLIENT_ID)
                .add("code_verifier", authorization.verifier)
                .build()
        return network("exchange") {
            client
                .newCall(
                    Request
                        .Builder()
                        .url(CodexOAuthProtocol.TOKEN_URL)
                        .post(body)
                        .build(),
                ).execute()
                .use { response ->
                    val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
                    if (!response.isSuccessful) throw CodexOAuthEndpointException(response.code, oauthCode(bytes))
                    CodexOAuthProtocol.decodeSession(bytes, clock())
                }
        }
    }

    override fun close() = client.dispatcher.cancelAll()

    private fun execute(
        stage: String,
        url: String,
        body: String,
        successCodes: Set<Int>,
    ): ByteArray {
        val result = executeWithStatus(url, body)
        if (result.first !in successCodes) throw CodexDeviceEndpointException(stage, result.first)
        return result.second
    }

    private fun executeWithStatus(
        url: String,
        body: String,
    ): Pair<Int, ByteArray> =
        client
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
            .use { response -> response.code to response.body.source().readBoundedByteArray(64 * 1024L) }

    private fun executePoll(body: String): Pair<Int, ByteArray> =
        client
            .newCall(
                Request
                    .Builder()
                    .url(CodexDeviceProtocol.POLL_URL)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
            .use { response ->
                // The official Codex client treats 403/404 as pending from the status alone.
                // Reading those bodies can block on an otherwise valid long-poll response.
                if (response.code == 403 || response.code == 404) {
                    response.code to byteArrayOf()
                } else {
                    response.code to response.body.source().readBoundedJsonObjectByteArray(64 * 1024L)
                }
            }

    private fun oauthCode(bytes: ByteArray): String? =
        runCatching {
            Json
                .parseToJsonElement(bytes.decodeToString())
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

    private inline fun <T> network(
        stage: String,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (error: IOException) {
            throw CodexDeviceNetworkException(stage, error)
        }
}

internal fun BufferedSource.readBoundedJsonObjectByteArray(maxBytes: Long): ByteArray {
    require(maxBytes > 0)
    val received = Buffer()
    while (received.size <= maxBytes) {
        val remaining = maxBytes + 1 - received.size
        val count = read(received, minOf(8 * 1024L, remaining))
        require(received.size <= maxBytes) { "device response is too large" }
        val bytes = received.snapshot().toByteArray()
        if (
            bytes.isNotEmpty() &&
            runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.isSuccess
        ) {
            return bytes
        }
        require(count != -1L) { "device response ended before a complete JSON object" }
    }
    error("device response is too large")
}

internal class CodexDeviceLoginController(
    private val vault: CliSubscriptionCredentialVault,
    private val transport: CodexDeviceTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    fun start() = transport.requestDeviceCode()

    fun finish(
        attempt: CodexDeviceAttempt,
        cancellation: DeviceLoginCancellation,
    ) {
        var lastPollNetworkFailure: CodexDeviceNetworkException? = null
        while (true) {
            cancellation.check()
            if (clock() >= attempt.expiresAtEpochMillis) {
                throw lastPollNetworkFailure ?: CodexDeviceLoginException("expired")
            }
            sleep(attempt.intervalMillis)
            cancellation.check()
            val poll =
                try {
                    transport.poll(attempt)
                } catch (error: CodexDeviceNetworkException) {
                    if (error.stage != "poll") throw error
                    lastPollNetworkFailure = error
                    continue
                }
            lastPollNetworkFailure = null
            when (poll) {
                CodexDevicePoll.Pending -> {
                    Unit
                }

                is CodexDevicePoll.Authorized -> {
                    val session = transport.exchange(attempt, poll.authorization)
                    cancellation.check()
                    vault.save(CliSubscriptionProvider.CODEX, session)
                    return
                }
            }
        }
    }
}
