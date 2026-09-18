package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.openai.responses.ImagePayload
import com.helix.provider.openai.responses.ResponsesRequestEncoder
import com.helix.provider.openai.responses.ResponsesStreamDecoder
import com.helix.runtime.cli.client.CliImageSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class CodexModelExecution(
    val model: String,
    val events: List<ModelEvent>,
)

internal class CodexSubscriptionModel(
    private val vault: CliSubscriptionCredentialVault,
    private val oauth: CodexLoginController,
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
    images: List<CliImageSnapshot> = emptyList(),
    private val eventDirectory: java.io.File? = null,
) : Closeable {
    private val cancelled =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val startedAt = System.nanoTime()
    private val client =
        client
            .newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    private val imageSnapshots = images.associate { it.reference to it.base64 }
    private val encoder =
        ResponsesRequestEncoder { reference ->
            ImagePayload.Base64(requireNotNull(imageSnapshots[reference]) { "image snapshot missing" })
        }

    fun run(
        request: ModelRequest,
        onEvents: (List<ModelEvent>) -> Unit = {},
    ): CodexModelExecution =
        try {
            runAuthenticated(request, onEvents)
        } catch (failure: SubscriptionTransportFailure) {
            reportTransportFailure("headers", failure, 0)
            CodexModelExecution(request.model, listOf(ModelEvent.Error(failure.code, true)))
        }

    private fun runAuthenticated(
        request: ModelRequest,
        onEvents: (List<ModelEvent>) -> Unit,
    ): CodexModelExecution {
        val names = CodexToolNames(request)
        var session = vault.load(CliSubscriptionProvider.CODEX)
        var response = execute(request, session)
        if (response.code == 401) {
            response.close()
            oauth.refresh()
            session = vault.load(CliSubscriptionProvider.CODEX)
            response = execute(request, session)
        }
        response.use { http ->
            if (!http.isSuccessful) {
                android.util.Log.w("HelixSubscriptionIo", "phase=headers httpStatus=${http.code}")
            }
            return CodexModelExecution(
                request.model,
                names.decode(
                    readSubscriptionEvents(
                        http,
                        ResponsesStreamDecoder(),
                        onReadFailure = { failure, eventCount -> reportTransportFailure("body", failure, eventCount) },
                        eventDirectory = eventDirectory,
                    ) { onEvents(names.decode(it)) },
                ),
            )
        }
    }

    override fun close() {
        cancelled.set(true)
        client.dispatcher.cancelAll()
    }

    private fun reportTransportFailure(
        phase: String,
        failure: SubscriptionTransportFailure,
        events: Int,
    ) {
        // Closed classifications only: no throwable messages, URLs, tokens or conversation text.
        val causes = generateSequence(failure.cause) { it.cause }.take(6).toList()
        val reason =
            when {
                causes.any { it is javax.net.ssl.SSLException } -> "tls"
                causes.any { it is java.net.UnknownHostException } -> "dns"
                causes.any { it is java.io.InterruptedIOException } -> "timeout_or_interrupt"
                causes.any { it.message.orEmpty().contains("abort", ignoreCase = true) } -> "socket_aborted"
                causes.any { it.message.orEmpty().contains("reset", ignoreCase = true) } -> "connection_reset"
                causes.any { it.message.orEmpty().contains("closed", ignoreCase = true) } -> "socket_closed"
                else -> "io"
            }
        android.util.Log.w(
            "HelixSubscriptionIo",
            "phase=$phase reason=$reason causes=${causes.joinToString(",") { it.javaClass.simpleName }} " +
                "events=$events elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} " +
                "appCancelled=${cancelled.get()} threadInterrupted=${Thread.currentThread().isInterrupted}",
        )
    }

    private fun execute(
        request: ModelRequest,
        session: CliSubscriptionSession,
    ): okhttp3.Response {
        val accountId = session.accountId ?: throw CodexSmokeException("credential")
        val base = Json.parseToJsonElement(encodeSubscriptionRequest(request, encoder)).jsonObject
        val body =
            buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("store", false)
            }.toString()
        val call =
            Request
                .Builder()
                .url(CodexSubscriptionSmoke.RESPONSES_URL)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("chatgpt-account-id", accountId)
                .header("originator", "codex_cli_rs")
                .header("session-id", UUID.randomUUID().toString())
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(JSON))
                .build()
        return subscriptionNetwork { client.newCall(call).execute() }
    }

    internal companion object {
        const val MAX_STREAM_BYTES = 2L * 1024L * 1024L
        const val MAX_EVENTS = 2048
        val JSON = "application/json".toMediaType()

        /** The consumer-subscription endpoint rejects the public API output-token field. */
        fun encodeSubscriptionRequest(
            request: ModelRequest,
            encoder: ResponsesRequestEncoder =
                ResponsesRequestEncoder {
                    error("image references are rejected by the IPC codec")
                },
        ): String {
            val instructions =
                request.messages
                    .filter { it.role == com.helix.core.model.ModelRole.SYSTEM }
                    .joinToString("\n\n") { it.text }
            val wireRequest =
                request.copy(
                    messages = request.messages.filter { it.role != com.helix.core.model.ModelRole.SYSTEM },
                    maxOutputTokens = null,
                )
            // Keep tool aliases when removing system messages. The subscription endpoint
            // expects harness instructions in its top-level field, not input system items.
            val encoded =
                Json
                    .parseToJsonElement(
                        encoder.encode(CodexToolNames(request).encode(wireRequest)),
                    ).jsonObject
            return buildJsonObject {
                encoded.forEach { (key, value) -> put(key, value) }
                if (instructions.isNotEmpty()) put("instructions", instructions)
            }.toString()
        }
    }
}
