package com.helix.app.agent

import com.helix.app.agent.ContextCompaction
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ProviderCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A single request snapshot, never cumulative turn billing or an inferred model limit. */
data class ChatContextUsage(
    val inputTokens: Long? = null,
    val windowTokens: Long? = com.helix.app.provider.ProviderContextSettings.DEFAULT_WINDOW,
    val estimatedAfterCompaction: Boolean = false,
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
        val window =
            providerService.contextSettingsStore
                .read(
                    providerId,
                    com.helix.core.model.NormalizedEndpoint
                        .parse(config.endpoint)
                        .full,
                    model,
                ).window
        // Only the newest call is relevant. Falling back to an older successful call would
        // falsely present stale usage after a failed request or a model switch.
        val turn = storage.turns.listBySession(session.id).lastOrNull()
        val call = turn?.let { storage.modelCalls.listByTurn(it.id).lastOrNull() }
        val input = call?.let { inputFor(it.providerSnapshot, it.usage, config.endpoint, model) }
        val checkpoint = ContextCompaction.checkpoint(storage, storage.messages.listBySession(session.id))
        val compactedInput = checkpoint?.takeIf { it.sourceCallId == call?.id && input != null }?.estimatedInputTokens
        return ChatContextUsage(compactedInput ?: input?.takeIf { it >= 0 }, window, compactedInput != null)
    }

    internal fun inputFor(
        snapshotJson: String,
        usageJson: String?,
        endpoint: String,
        model: String,
    ): Long? =
        runCatching {
            val snapshot = Json.parseToJsonElement(snapshotJson).jsonObject
            if (snapshot["endpoint"]?.jsonPrimitive?.content != endpoint ||
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
