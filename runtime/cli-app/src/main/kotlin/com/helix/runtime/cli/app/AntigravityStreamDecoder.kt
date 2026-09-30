package com.helix.runtime.cli.app

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.provider.api.StreamDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Frame-bounded SSE parser; cumulative text is spooled by the existing subscription reader. */
internal class AntigravityStreamDecoder(
    private val names: Map<String, String>,
    private val saveReplay: (ModelMessage, JsonArray) -> Unit,
) : StreamDecoder {
    private val line = ByteArrayOutputStream()
    private val frame = StringBuilder()
    private val history = AntigravityReplyHistory()
    private val calls = mutableListOf<AssistantToolCall>()
    private var ended = false
    private var observedContent = false
    private var firstLine = true
    override val protocolEnded: Boolean get() = ended

    override fun feed(chunk: ByteArray): List<ModelEvent> {
        if (ended) return emptyList()
        val events = mutableListOf<ModelEvent>()
        try {
            for (byte in chunk) {
                if (ended) break
                if (byte == 10.toByte()) {
                    consumeLine(events)
                } else {
                    require(line.size() < MAX_FRAME_BYTES) { "SSE line too large" }
                    line.write(byte.toInt())
                }
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ended = true
            events += ModelEvent.Error(ModelErrorCode.PROTOCOL, false)
        }
        return events
    }

    private fun consumeLine(events: MutableList<ModelEvent>) {
        var text = line.toByteArray().decodeToString(throwOnInvalidSequence = true).removeSuffix("\r")
        line.reset()
        if (firstLine) text = text.removePrefix("\uFEFF")
        firstLine = false
        if (text.isEmpty()) {
            if (frame.isNotEmpty()) {
                val data = frame.toString().removeSuffix("\n")
                frame.setLength(0)
                events += decodeFrame(Json.parseToJsonElement(data).jsonObject)
            }
        } else if (text.startsWith("data:")) {
            require(frame.length + text.length <= MAX_FRAME_BYTES) { "SSE frame too large" }
            frame.append(text.removePrefix("data:").removePrefix(" ")).append('\n')
        }
    }

    private fun decodeFrame(root: JsonObject): List<ModelEvent> {
        val response = (root["response"] as? JsonObject) ?: root
        val candidates = response["candidates"] as? JsonArray
        return when {
            response["error"] != null -> {
                terminate(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
            }

            response["promptFeedback"]?.jsonObject?.get("blockReason") != null -> {
                terminate(ModelEvent.Refusal())
            }

            candidates.isNullOrEmpty() -> {
                emptyList()
            }

            else -> {
                require(candidates.size == 1)
                decodeCandidate(candidates.single().jsonObject, response)
            }
        }
    }

    private fun decodeCandidate(
        candidate: JsonObject,
        response: JsonObject,
    ): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        val parts =
            candidate["content"]
                ?.jsonObject
                ?.get("parts")
                ?.jsonArray
                .orEmpty()
        parts.forEach { value -> consumePart(value.jsonObject, events) }
        candidate["finishReason"]?.jsonPrimitive?.content?.let { reason -> events += settle(reason, response) }
        return events
    }

    private fun consumePart(
        part: JsonObject,
        events: MutableList<ModelEvent>,
    ) {
        val thought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
        val text =
            part["text"]
                ?.jsonPrimitive
                ?.also { require(it.isString) }
                ?.content
                .orEmpty()
        history.append(part, text.takeUnless { thought }.orEmpty())
        if (text.isNotEmpty()) {
            observedContent = true
            antigravityChunks(text).forEach {
                events += if (thought) ModelEvent.ReasoningDelta(it) else ModelEvent.TextDelta(it)
            }
        }
        (part["functionCall"] as? JsonObject)?.let { function ->
            require(calls.size < 32)
            val name = requireNotNull(names[function.getValue("name").jsonPrimitive.content])
            val args = requireNotNull(function["args"] as? JsonObject)
            calls += AssistantToolCall(ToolCallId("agy_${UUID.randomUUID()}"), ToolName(name), args.toString())
            observedContent = true
        }
    }

    private fun settle(
        reason: String,
        response: JsonObject,
    ): List<ModelEvent> =
        when (reason) {
            "MAX_TOKENS" -> {
                terminate(AntigravityResponse.usage(response), ModelEvent.Completed("length"))
            }

            "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "RECITATION" -> {
                terminate(ModelEvent.Refusal())
            }

            else -> {
                require(reason == "STOP" && observedContent)
                complete(response)
            }
        }

    private fun complete(response: JsonObject): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        if (calls.isNotEmpty()) {
            val (message, parts) = history.forReplay(calls)
            saveReplay(message, parts)
            calls.forEachIndexed { index, call ->
                events += ModelEvent.ToolCallStarted(index, call.id, call.name.value)
                antigravityChunks(call.argumentsJson).forEach { events += ModelEvent.ToolArgumentsDelta(index, it) }
                events += ModelEvent.ToolCallFinished(index)
            }
        }
        ended = true
        events += AntigravityResponse.usage(response)
        events += ModelEvent.Completed(if (calls.isEmpty()) "stop" else "tool_calls")
        return events
    }

    private fun terminate(vararg events: ModelEvent): List<ModelEvent> {
        ended = true
        return events.toList()
    }

    override fun finish(): List<ModelEvent> {
        if (ended) return emptyList()
        val tail = feed("\n\n".toByteArray())
        return if (ended) tail else tail + terminate(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
    }

    private companion object {
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    }
}

/** Only future tool replay needs a bounded ModelMessage. Plain long replies never hit this history bound. */
private class AntigravityReplyHistory {
    private val text = StringBuilder()
    private val parts = mutableListOf<JsonObject>()
    private var bytes = 0L
    private var available = true

    fun append(
        part: JsonObject,
        visibleText: String,
    ) {
        if (!available) return
        bytes += part.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > 8 * 1024 * 1024 || text.length + visibleText.length > 262144) {
            available = false
            parts.clear()
            text.setLength(0)
        } else {
            parts += part
            text.append(visibleText)
        }
    }

    fun forReplay(calls: List<AssistantToolCall>): Pair<ModelMessage, JsonArray> {
        require(available) { "Signed tool history cannot fit the existing request envelope" }
        return ModelMessage(ModelRole.ASSISTANT, text.toString(), toolCalls = calls) to JsonArray(parts)
    }
}
