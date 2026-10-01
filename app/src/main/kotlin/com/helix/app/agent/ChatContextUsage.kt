package com.helix.app.agent

import com.helix.app.agent.ContextCompaction
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.transportIdentity
import com.helix.provider.api.ProviderCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A single request snapshot, never cumulative turn billing or an inferred model limit. */
data class ChatContextUsage(
    val inputTokens: Long? = null,
    val windowTokens: Long? = com.helix.provider.api.ProviderContextSettings.DEFAULT_WINDOW,
    val estimatedAfterCompaction: Boolean = false,
    val estimatedWindow: Boolean = false,
) {
    val fraction: Float?
        get() =
            inputTokens?.takeIf { it >= 0 }?.let { used ->
                windowTokens?.takeIf { it > 0 }?.let { (used.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
            }

    val percentage: Long?
        get() =
            inputTokens?.takeIf { it >= 0 }?.let { used ->
                windowTokens?.takeIf { it > 0 }?.let { (used.toDouble() / it * PERCENT).toLong() }
            }

    /** Display whole percentages rounded down, including zero for usage below one percent. */
    val percentageLabel: String
        get() =
            when {
                percentage == null -> "?"
                else -> "$percentage%"
            }

    companion object {
        private const val PERCENT = 100
    }
}

internal object ChatContextProjection {
    /** Missing/corrupt telemetry is unknown, never zero; send-path validation remains separate. */
    fun read(
        storage: HelixStorage,
        sessionId: String?,
        providerService: com.helix.app.provider.ProviderService,
    ): ChatContextUsage {
        val session = sessionId?.let { storage.sessions.resolve(it) }
        val providerId = session?.providerId ?: return ChatContextUsage()
        val config = storage.providerConfigs.resolve(providerId)
        val model = session.modelId ?: config.model
        val stored =
            providerService.contextSettingsStore
                .read(
                    providerId,
                    config.transportIdentity,
                    model,
                )
        val settings = stored.withDetectedWindow(providerService.metadataFor(providerId, model)?.contextWindow)
        val checkpoint = ContextHistory.checkpoint(storage, session.id)
        val samples =
            storage.turns
                .listBySession(session.id)
                .asReversed()
                .asSequence()
                .filter { it.state in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED") }
                .flatMap {
                    storage.modelCalls
                        .listByTurn(it.id)
                        .asReversed()
                        .asSequence()
                }.map { call ->
                    InputSample(
                        call.providerSnapshot,
                        call.usage,
                        call.state == "COMPLETED",
                        isSummary(storage, call.id),
                        checkpoint?.takeIf { it.sourceCallId == call.id }?.estimatedInputTokens,
                    )
                }
        return select(samples, config.transportIdentity, model).copy(
            windowTokens = settings.window,
            estimatedWindow = settings.windowSource == "fallback",
        )
    }

    internal data class InputSample(
        val snapshot: String,
        val usage: String?,
        val completed: Boolean,
        val summary: Boolean,
        val committedEstimate: Long? = null,
    )

    internal fun select(
        samples: Sequence<InputSample>,
        endpoint: String,
        model: String,
    ): ChatContextUsage =
        samples.filter { it.completed }.firstNotNullOfOrNull { sample ->
            when {
                // Stop at another target rather than reuse its measurement.
                inputFor(sample.snapshot, """{"inputTokens":0}""", endpoint, model) == null -> {
                    ChatContextUsage()
                }

                sample.committedEstimate != null -> {
                    ChatContextUsage(
                        sample.committedEstimate,
                        estimatedAfterCompaction = true,
                    )
                }

                sample.summary -> {
                    null
                }

                else -> {
                    inputFor(sample.snapshot, sample.usage, endpoint, model)?.let { ChatContextUsage(it) }
                }
            }
        } ?: ChatContextUsage()

    internal fun isSummary(
        storage: HelixStorage,
        callId: String,
    ): Boolean =
        storage.auditEvents.listByCorrelation(callId).any { event ->
            event.type == "context.compaction" || (
                event.type == "budget.admitted" &&
                    runCatching {
                        Json
                            .parseToJsonElement(event.redactedPayload)
                            .jsonObject["kind"]
                            ?.jsonPrimitive
                            ?.content == "summary"
                    }.getOrDefault(false)
            )
        }

    internal fun inputFor(
        snapshotJson: String,
        usageJson: String?,
        endpoint: String,
        model: String,
    ): Long? =
        runCatching {
            val snapshot = Json.parseToJsonElement(snapshotJson).jsonObject
            if (snapshot["transportIdentity"]?.jsonPrimitive?.content != endpoint ||
                snapshot["model"]?.jsonPrimitive?.content != model
            ) {
                null
            } else {
                usageJson?.let {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["inputTokens"]
                        ?.jsonPrimitive
                        ?.longOrNull
                        ?.takeIf { n -> n >= 0 }
                }
            }
        }.getOrNull()
}
