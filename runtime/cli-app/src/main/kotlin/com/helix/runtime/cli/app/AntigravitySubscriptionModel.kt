package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.runtime.cli.client.CliImageSnapshot
import com.helix.runtime.cli.client.CliModelCatalog
import com.helix.runtime.cli.client.CliModelInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException

/** Existing Job owner handles cancellation, persistence and result ACK. This adapter never executes a tool. */
internal class AntigravitySubscriptionModel(
    private val vault: CliSubscriptionCredentialVault,
    private val replay: AntigravityReplayStore,
    private val images: List<CliImageSnapshot> = emptyList(),
    private val http: AntigravityHttp = AntigravityHttp(),
    private val eventDirectory: java.io.File? = null,
    client: AntigravityClientConfig = AntigravityClientConfig.build(),
) : Closeable {
    private val auth = AntigravityAuth(http, client)

    @Suppress("TooGenericExceptionCaught") // Untrusted wire/storage failures become typed failure, never success.
    fun run(
        request: ModelRequest,
        onEvents: (List<ModelEvent>) -> Unit = {},
    ): CodexModelExecution {
        val events =
            try {
                AntigravityWireDiagnostic.report(AntigravityWireDiagnostic.Event.START)
                val account = auth.current(vault)
                val wire = AntigravityRequest(request, images) { replay.read(request.model, account.revision, it) }
                val payload = wire.encode(requireNotNull(account.session.accountId))
                AntigravityWireDiagnostic.report(AntigravityWireDiagnostic.Event.ENCODED)
                check(vault.snapshot(CliSubscriptionProvider.ANTIGRAVITY).revision == account.revision)
                val decoder =
                    AntigravityStreamDecoder(wire.names) { message, parts ->
                        replay.save(request.model, account.revision, message, parts)
                    }
                http.generate(payload, account.session.accessToken, decoder, eventDirectory, onEvents)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AntigravityWireDiagnostic.report(AntigravityWireDiagnostic.Event.EXCEPTION)
                listOf(ModelEvent.Error(errorCode(error), false))
            }
        return CodexModelExecution(request.model, events)
    }

    @Suppress("TooGenericExceptionCaught") // Same total error boundary; cancellation propagates.
    fun catalog(): CliModelCatalog =
        try {
            val account = auth.current(vault)
            val payload = buildJsonObject { put("project", requireNotNull(account.session.accountId)) }
            val root = http.call("fetchAvailableModels", payload, account.session.accessToken)
            check(vault.snapshot(CliSubscriptionProvider.ANTIGRAVITY).revision == account.revision)
            decodeCatalog(root)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CliModelCatalog.Failed(errorCode(error), false)
        }

    override fun close() = http.close()

    companion object {
        fun decodeCatalog(root: JsonObject): CliModelCatalog.Listed {
            val models = root["models"] as? JsonObject ?: error("Model catalog missing")
            require(models.size in 1..128)
            return CliModelCatalog.Listed(
                models.map { (id, entry) ->
                    val row = entry.jsonObject
                    val context = (row["inputTokenLimit"] ?: row["maxInputTokens"])?.jsonPrimitive?.longOrNull
                    val reasoning = (row["reasoningEfforts"] as? JsonArray)?.map { it.jsonPrimitive.content }
                    CliModelInfo(id, row["supportsImages"]?.jsonPrimitive?.booleanOrNull, reasoning, context)
                },
            )
        }

        private fun errorCode(error: Exception): ModelErrorCode =
            when (error) {
                is AntigravityClientNotConfigured -> {
                    ModelErrorCode.AUTH
                }

                is AntigravityHttpException -> {
                    when (error.status) {
                        401, 403 -> ModelErrorCode.AUTH
                        429 -> ModelErrorCode.RATE_LIMITED
                        in 500..599 -> ModelErrorCode.SERVER_ERROR
                        else -> ModelErrorCode.HTTP_ERROR
                    }
                }

                is SocketTimeoutException -> {
                    ModelErrorCode.TIMEOUT
                }

                is IOException -> {
                    ModelErrorCode.TRANSPORT
                }

                else -> {
                    ModelErrorCode.PROTOCOL
                }
            }
    }
}
