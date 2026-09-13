package com.helix.app.provider

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolName
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Synthetic production-path probes; the echo fixture never enters the device Tool Dispatcher. */
internal class CodexCapabilityProbe(
    private val provider: CodexSubscriptionProvider,
    private val allReasoningEfforts: Boolean = true,
    private val onPhase: (String) -> Unit = {},
) {
    @Suppress("ReturnCount") // Stop at the first failed phase without claiming later checks passed.
    suspend fun run(): ProbeOutcome {
        reportPhase("catalog")
        val models = provider.listModels()
        if (models is ModelCatalogResult.Failed) {
            return ProbeOutcome.Failed(2, models.code, models.detail, models.retryable)
        }
        val info = provider.modelInfo() ?: return failure(2, "selected model is absent from catalog")
        reportPhase("stream")
        val text = collect(request("Write the numbers 1 through 40, separated by spaces, with no other text."))
        failed(text, 3)?.let { return it }
        if (!provider.observedIncrementalDelivery) {
            return failure(3, "Runtime did not deliver incremental output before completion")
        }
        reportPhase("tool")
        checkTool()?.let { return it }
        val vision = info.vision != false
        if (vision) {
            reportPhase("vision")
            checkVision()?.let { return it }
        }
        val efforts = info.reasoningEfforts.orEmpty().filter { it != "none" }
        // A capability check proves reasoning works; exhaustive per-level compatibility is diagnostic.
        checkReasoning(if (allReasoningEfforts) efforts else efforts.take(1))?.let { return it }
        val capabilities =
            ProviderCapabilities(
                streaming = true,
                toolCalls = true,
                parallelToolCalls = false,
                vision = vision,
                reasoning = efforts.isNotEmpty(),
                jsonSchemaOutput = false,
                maxContextTokens = info.contextWindow,
                source = CapabilitySource.PROBED,
            )
        return ProbeOutcome.Ok(capabilities, (models as ModelCatalogResult.Listed).models)
    }

    private suspend fun checkVision(): ProbeOutcome.Failed? {
        val image = ImageReference(ArtifactVisionImageSource.COLOR_PROBE_REF, "image/png")
        val prompt = "Name the dominant color of the image. Reply with one English color word only."
        val message = ModelMessage(ModelRole.USER, prompt, images = listOf(image))
        val events = collect(ModelRequest(provider.descriptor.model, listOf(message)))
        val recognized = text(events).lowercase().trim(' ', '.', '"', '\n') == "red"
        return failed(events, 5) ?: if (recognized) null else failure(5, "image color was not recognized")
    }

    private suspend fun checkReasoning(efforts: List<String>): ProbeOutcome.Failed? {
        for (wire in efforts) {
            reportPhase("reasoning:$wire")
            val effort = requireNotNull(ReasoningEffort.fromWire(wire))
            val events = collect(request("Compute 17 * 19. Reply only with the result.").copy(reasoning = effort))
            val error =
                failed(events, 6)?.copy(detail = "reasoning effort $wire failed")
                    ?: if (text(events).trim() == "323") null else failure(6, "reasoning result did not verify")
            if (error != null) return error
        }
        return null
    }

    @Suppress("ReturnCount") // Identity, arguments and backfill are separate protocol checks.
    private suspend fun checkTool(): ProbeOutcome.Failed? {
        val nonce = "HELIX_" + UUID.randomUUID().toString().replace("-", "")
        val tool =
            ModelToolSchema(
                ToolName("helix_probe.echo"),
                "Return the supplied text unchanged.",
                """{"type":"object","properties":{"text":{"type":"string"}},
                "required":["text"],"additionalProperties":false}""",
            )
        val prompt =
            "Call the provided echo tool once with text $nonce. " +
                "After receiving its result, reply exactly with that result."
        val first = request(prompt).copy(tools = listOf(tool))
        val events = collect(first)
        failed(events, 4)?.let { return it }
        val call =
            events.filterIsInstance<ModelEvent.ToolCallStarted>().singleOrNull()
                ?: return failure(4, "expected exactly one tool call")
        val closed = events.any { it is ModelEvent.ToolCallFinished && it.index == call.index }
        if (call.name != tool.name.value || !closed) return failure(4, "tool identity or completion did not verify")
        val args =
            events
                .filterIsInstance<ModelEvent.ToolArgumentsDelta>()
                .filter { it.index == call.index }
                .joinToString("") { it.jsonFragment }
        val echoed =
            runCatching {
                Json
                    .parseToJsonElement(args)
                    .jsonObject["text"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
        if (echoed != nonce) return failure(4, "tool arguments did not verify")
        val callMessage =
            ModelMessage(
                ModelRole.ASSISTANT,
                "",
                toolCalls = listOf(AssistantToolCall(call.id, tool.name, args)),
            )
        val resultMessage = ModelMessage(ModelRole.TOOL, nonce, toolCallId = call.id, toolName = tool.name)
        val result = collect(first.copy(messages = first.messages + listOf(callMessage, resultMessage)))
        val matched = text(result).trim() == nonce
        return failed(result, 4) ?: if (matched) null else failure(4, "tool result round trip did not verify")
    }

    private fun request(text: String) =
        ModelRequest(provider.descriptor.model, listOf(ModelMessage(ModelRole.USER, text)))

    private fun reportPhase(phase: String) {
        onPhase(phase)
    }

    private suspend fun collect(request: ModelRequest) = provider.stream(request).toList()

    private fun text(events: List<ModelEvent>) =
        events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }

    private fun failed(
        events: List<ModelEvent>,
        phase: Int,
    ): ProbeOutcome.Failed? {
        val error = events.filterIsInstance<ModelEvent.Error>().lastOrNull()
        return when {
            error != null -> {
                ProbeOutcome.Failed(phase, error.code, "capability request failed", error.retryable)
            }

            events.size > 2048 || events.lastOrNull() !is ModelEvent.Completed -> {
                failure(phase, "response did not complete")
            }

            else -> {
                null
            }
        }
    }

    private fun failure(
        phase: Int,
        detail: String,
    ) = ProbeOutcome.Failed(phase, ModelErrorCode.PROTOCOL, detail, false)
}
