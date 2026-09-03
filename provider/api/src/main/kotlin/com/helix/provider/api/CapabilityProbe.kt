package com.helix.provider.api

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

/**
 * The HXA-025 connection test (provider doc section 2.4): runs the five phases
 * IN ORDER against a live [ModelProvider] and derives [ProviderCapabilities]
 * with [CapabilitySource.PROBED]:
 *
 * 1. transport/TLS/HTTP + authentication ([ModelProvider.validateConfiguration]);
 * 2. the model list ([ModelProvider.listModels] — [ModelCatalogResult.Unsupported]
 *    counts as a pass: not every service exposes a list);
 * 3. a minimal text stream (one user message, tiny budget, must end in a
 *    legitimate terminal);
 * 4. a minimal tool call (one offered tool, must end with `tool_calls` after a
 *    started+finished call index);
 * 5. (HXA-055) a minimal VISION request (one 1x1 probe image, [VISION_PROBE_REF]):
 *    a Completed/Refusal terminal proves the endpoint accepts images
 *    ([ProviderCapabilities.vision] = true); any error terminal fails the phase.
 *
 * The first failing phase stops the probe: later phases do not run, and no
 * [ProviderCapabilities] is produced ([ProbeOutcome.Failed] carries the phase,
 * the closed failure class and the retryability). A passing probe never claims
 * a capability it did not exercise: parallel tool calls, vision, reasoning and
 * JSON-schema output stay `false` and [ProviderCapabilities.maxContextTokens]
 * stays `null` (conservative defaults — the doc's rule is to rely on capability
 * tests, never on product names).
 */
@Suppress("TooManyFunctions") // one function per closed probe phase + its private helpers
public class CapabilityProbe(
    private val textMaxOutputTokens: Long = TEXT_MAX_OUTPUT_TOKENS,
    private val toolMaxOutputTokens: Long = TOOL_MAX_OUTPUT_TOKENS,
) {
    /**
     * Runs all five phases in order; the first failing phase wins. Phase 5 (HXA-055) sends a
     * 1x1 probe image: a legitimate terminal (Completed/Refusal) is the proof the endpoint
     * accepts images and sets [ProviderCapabilities.vision] = true; ANY error terminal fails
     * the probe — the probe only ever PROVES vision by seeing a successful image completion
     * (conservative: an unsupported-image 400 and a transient 5xx both keep vision
     * unconfirmed, and the user can then declare it manually — the ADR's second source).
     */
    public suspend fun probe(provider: ModelProvider): ProbeOutcome {
        val phases: List<suspend (ModelProvider) -> ProbeOutcome?> =
            listOf(::phase1, ::phase2, ::phase3, ::phase4, ::phase5)
        for (phase in phases) {
            val failure = phase(provider)
            if (failure != null) return failure
        }
        return ProbeOutcome.Ok(capabilities = PROBED_CAPABILITIES.withVisionProved(), models = null)
    }

    /** Phase 1: transport/TLS/HTTP + authentication. */
    private suspend fun phase1(provider: ModelProvider): ProbeOutcome? =
        when (val check = provider.validateConfiguration()) {
            is ProviderCheckResult.Ok -> {
                null
            }

            is ProviderCheckResult.Failed -> {
                ProbeOutcome.Failed(1, check.code, check.detail, check.retryable)
            }
        }

    /** Phase 2: model list (unsupported services count as a pass). */
    private suspend fun phase2(provider: ModelProvider): ProbeOutcome? =
        when (val models = provider.listModels()) {
            is ModelCatalogResult.Listed, is ModelCatalogResult.Unsupported -> {
                null
            }

            is ModelCatalogResult.Failed -> {
                ProbeOutcome.Failed(2, models.code, models.detail, models.retryable)
            }
        }

    /** Phase 3: minimal text stream. */
    private suspend fun phase3(provider: ModelProvider): ProbeOutcome? {
        val events = collectBounded(provider.stream(textRequest(provider)))
        if (events == null) {
            return ProbeOutcome.Failed(
                3,
                ModelErrorCode.PROTOCOL,
                "probe stream exceeded $MAX_PROBE_EVENTS events",
                false,
            )
        }
        return when (val terminal = terminalOf(events)) {
            null -> {
                ProbeOutcome.Failed(3, ModelErrorCode.PROTOCOL, "text stream had no terminal", true)
            }

            is ModelEvent.Error -> {
                ProbeOutcome.Failed(
                    3,
                    terminal.code,
                    "text stream error: ${terminal.code}",
                    terminal.retryable,
                )
            }

            else -> {
                null
            }
        }
    }

    /** Phase 4: minimal tool call. */
    private suspend fun phase4(provider: ModelProvider): ProbeOutcome? {
        val events = collectBounded(provider.stream(toolRequest(provider)))
        if (events == null) {
            return ProbeOutcome.Failed(
                4,
                ModelErrorCode.PROTOCOL,
                "probe stream exceeded $MAX_PROBE_EVENTS events",
                false,
            )
        }
        return when (val terminal = terminalOf(events)) {
            null -> {
                ProbeOutcome.Failed(4, ModelErrorCode.PROTOCOL, "tool stream had no terminal", true)
            }

            is ModelEvent.Error -> {
                ProbeOutcome.Failed(
                    4,
                    terminal.code,
                    "tool stream error: ${terminal.code}",
                    terminal.retryable,
                )
            }

            is ModelEvent.Completed -> {
                val pair = closedToolIndex(events)
                if (pair == null || terminal.finishReason != "tool_calls") {
                    ProbeOutcome.Failed(
                        4,
                        ModelErrorCode.PROTOCOL,
                        "tool fixture did not complete a tool call (finishReason=${terminal.finishReason})",
                        false,
                    )
                } else {
                    null
                }
            }

            is ModelEvent.Refusal -> {
                ProbeOutcome.Failed(
                    4,
                    ModelErrorCode.PROTOCOL,
                    "the model refused the tool fixture",
                    false,
                )
            }

            else -> {
                null
            }
        }
    }

    /**
     * Phase 5 (HXA-055): minimal VISION request — one user message with the reserved 1x1
     * probe image ([VISION_PROBE_REF]; the production image source resolves it to a built-in
     * 67-byte PNG, so no user data ever rides the probe). A Completed or Refusal terminal
     * proves the endpoint accepts images (vision = true); any Error terminal fails the phase.
     */
    private suspend fun phase5(provider: ModelProvider): ProbeOutcome? {
        val events = collectBounded(provider.stream(visionRequest(provider)))
        if (events == null) {
            return ProbeOutcome.Failed(
                5,
                ModelErrorCode.PROTOCOL,
                "probe stream exceeded $MAX_PROBE_EVENTS events",
                false,
            )
        }
        return when (val terminal = terminalOf(events)) {
            null -> {
                ProbeOutcome.Failed(5, ModelErrorCode.PROTOCOL, "vision stream had no terminal", true)
            }

            is ModelEvent.Error -> {
                ProbeOutcome.Failed(
                    5,
                    terminal.code,
                    "vision probe error: ${terminal.code}",
                    terminal.code in RETRYABLE_ERROR_CODES,
                )
            }

            else -> {
                // Completed or Refusal: the image was accepted — vision is proved.
                null
            }
        }
    }

    private fun visionRequest(provider: ModelProvider): ModelRequest =
        ModelRequest(
            model = provider.descriptor.model,
            messages =
                listOf(
                    ModelMessage(
                        role = ModelRole.USER,
                        text = "Reply with the single word: ok",
                        images = listOf(ImageReference(VISION_PROBE_REF, "image/png")),
                    ),
                ),
            maxOutputTokens = textMaxOutputTokens,
        )

    private fun textRequest(provider: ModelProvider): ModelRequest =
        ModelRequest(
            model = provider.descriptor.model,
            messages = listOf(ModelMessage(ModelRole.USER, "Reply with the single word: ok")),
            maxOutputTokens = textMaxOutputTokens,
        )

    private fun toolRequest(provider: ModelProvider): ModelRequest =
        ModelRequest(
            model = provider.descriptor.model,
            messages =
                listOf(
                    ModelMessage(
                        ModelRole.USER,
                        "Call the echo tool with the text argument set to: probe",
                    ),
                ),
            tools =
                listOf(
                    ModelToolSchema(
                        ToolName("echo"),
                        "Return the given text unchanged.",
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}," +
                            "\"required\":[\"text\"]}",
                    ),
                ),
            maxOutputTokens = toolMaxOutputTokens,
        )

    /** The collected events, or null when the stream exceeded the event bound. */
    private suspend fun collectBounded(flow: Flow<ModelEvent>): List<ModelEvent>? {
        val events = flow.toList()
        if (events.size > MAX_PROBE_EVENTS) return null
        return events
    }

    private fun terminalOf(events: List<ModelEvent>): ModelEvent? =
        events.firstOrNull {
            it is ModelEvent.Completed || it is ModelEvent.Refusal || it is ModelEvent.Error
        }

    /** A tool-call index that was both started and finished; null when none. */
    private fun closedToolIndex(events: List<ModelEvent>): Int? {
        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>().map { it.index }.toSet()
        val finished = events.filterIsInstance<ModelEvent.ToolCallFinished>().map { it.index }.toSet()
        return started.intersect(finished).firstOrNull()
    }

    public companion object {
        const val TEXT_MAX_OUTPUT_TOKENS = 16L
        const val TOOL_MAX_OUTPUT_TOKENS = 64L
        const val MAX_PROBE_EVENTS = 10_000

        /**
         * The reserved ref of the built-in 1x1 probe image (HXA-055). The app's production
         * image source recognizes this ref and serves the built-in probe PNG — no session
         * artifact, no user data.
         */
        val VISION_PROBE_REF = ArtifactRef("helix:vision-probe")

        /** The error codes that make a failing probe phase worth a retry. */
        private val RETRYABLE_ERROR_CODES =
            setOf(
                ModelErrorCode.SERVER_ERROR,
                ModelErrorCode.RATE_LIMITED,
                ModelErrorCode.TIMEOUT,
                ModelErrorCode.TRANSPORT,
            )

        private val PROBED_CAPABILITIES =
            ProviderCapabilities(
                streaming = true,
                toolCalls = true,
                parallelToolCalls = false, // not exercised by the fixture
                vision = false, // proved true by phase 5 when it passes
                reasoning = false, // not exercised
                jsonSchemaOutput = false, // not exercised
                maxContextTokens = null, // unknown until declared
                source = CapabilitySource.PROBED,
            )

        /** [PROBED_CAPABILITIES] with vision proved by the passing phase 5. */
        private fun ProviderCapabilities.withVisionProved(): ProviderCapabilities = copy(vision = true)
    }
}

/**
 * Outcome of the four-phase connection test (HXA-025).
 */
public sealed interface ProbeOutcome {
    /**
     * All four phases passed; [capabilities] is the [CapabilitySource.PROBED]
     * snapshot to persist (the app stores it as the strict JSON in
     * `provider_configs.capability_snapshot`).
     */
    public data class Ok(
        val capabilities: ProviderCapabilities,
        val models: List<String>?,
    ) : ProbeOutcome

    /**
     * Phase [phase] (1..5) failed; [code]/[detail]/[retryable] describe the
     * closed failure — the same phase may be re-run when [retryable].
     */
    public data class Failed(
        val phase: Int,
        val code: ModelErrorCode,
        val detail: String,
        val retryable: Boolean,
    ) : ProbeOutcome
}
