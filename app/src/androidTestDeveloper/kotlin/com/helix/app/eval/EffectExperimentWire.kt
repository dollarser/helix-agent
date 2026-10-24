package com.helix.app.eval

import com.helix.app.application.diagnostics.DiagnosticRedaction
import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** Test-only transport budget; it cannot grant a tool scope or replay a rejected generation. */
internal class EffectWireBudget {
    var requests = 0
        private set
    var bytes = 0L
        private set

    @Synchronized fun admit(size: Int) {
        if (requests >= 40 || bytes + size > 2_097_152) throw IOException("EXPERIMENT_TOTAL_BUDGET")
        requests++
        bytes += size
    }
}

/** Original production bytes and diagnostic sent bytes are explicitly separate; never records headers. */
internal class EffectExperimentWire(
    private val delegate: WireClient,
    private val output: File,
    private val budget: EffectWireBudget,
    private val transform: (JsonObject) -> JsonObject = { it },
) : WireClient {
    var calls = 0
        private set
    var sentBytes = 0L
        private set
    val statuses = mutableListOf<Int>()

    override suspend fun open(request: WireRequest): WireResponse {
        if (request.method != "POST" || request.url != BuiltInModel.GENERATION_URL) {
            return delegate.open(request)
        }
        if (calls >= 4) throw IOException("EXPERIMENT_CASE_BUDGET")
        val original = requireNotNull(request.body)
        val body = Json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
        val sent = transform(body)
        check(body.filterKeys { it != "messages" } == sent.filterKeys { it != "messages" })
        val bytes = if (sent == body) original else sent.toString().toByteArray()
        budget.admit(bytes.size)
        val ordinal = calls++
        sentBytes += bytes.size
        val evidence =
            buildJsonObject {
                put("original", body)
                put("sent", sent)
                put("bytes", bytes.size)
                put("exactProductionBytes", bytes.contentEquals(original))
            }
        File(output, "request-$ordinal.json").writeText(
            DiagnosticRedaction.redact(evidence.toString(), emptySet()),
        )
        val response = delegate.open(WireRequest(request.method, request.url, request.headers, bytes))
        statuses += response.status
        return response
    }
}

/** Replace only already-authorized summary blocks. No block means no implicit Memory access. */
internal fun memoryIndexProjection(
    body: JsonObject,
    indexes: Map<String, String>,
): JsonObject {
    val messages = body.getValue("messages") as JsonArray
    val block =
        Regex(
            """\[UNTRUSTED_MEMORY scope=(global|project) [^\]]*]\n[\s\S]*?\n\[/UNTRUSTED_MEMORY]""",
        )
    val scopes =
        messages.flatMap { value ->
            val message = value.jsonObject
            if (message["role"]?.jsonPrimitive?.content != "system") return@flatMap emptyList()
            val content =
                message["content"] as? JsonPrimitive
                    ?: error("Unsupported experiment SYSTEM encoding")
            block.findAll(content.content).map { it.groupValues[1] }.toList()
        }
    check(scopes.distinct().size == scopes.size) { "Ambiguous experiment Memory scope markers" }
    val changed =
        messages.map { value ->
            val message = value.jsonObject
            if (message["role"]?.jsonPrimitive?.content != "system") return@map value
            val content =
                message["content"] as? JsonPrimitive
                    ?: error("Unsupported experiment SYSTEM encoding")
            val text =
                block.replace(content.content) { match ->
                    indexes[match.groupValues[1]]?.let { index ->
                        val header = match.value.substringBefore("]\n") + "]\n"
                        header + "bodiesOmitted=true; this is an index, not topic bodies.\n" +
                            index + "\n[/UNTRUSTED_MEMORY]"
                    } ?: match.value
                }
            JsonObject(message + ("content" to JsonPrimitive(text)))
        }
    return JsonObject(body + ("messages" to JsonArray(changed)))
}
