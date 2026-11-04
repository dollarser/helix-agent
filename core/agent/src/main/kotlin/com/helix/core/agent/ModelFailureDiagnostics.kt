package com.helix.core.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Correlated with the original ModelCall; explains the actual decision, not a proposed retry. */
object ModelFailureDiagnostics {
    const val TYPE = "model.failure"
    const val MAX_BYTES = 4096

    @Suppress("CyclomaticComplexMethod") // Closed structural fields, never arbitrary diagnostic payloads.
    fun record(
        code: String?,
        stream: ModelStreamState,
        providerAllowsRetry: Boolean,
        retryDelayMillis: Long?,
        retries: Int,
    ): String =
        buildJsonObject {
            put("code", code ?: "UNKNOWN")
            put("retryable", stream.retryableError)
            put("providerAllowsRetry", providerAllowsRetry)
            put("attempt", retries + 1)
            put("receiving", stream.receiving)
            put("hasText", stream.text.isNotBlank())
            put("hasToolFragments", stream.hasToolCallFragments)
            put("completedToolCalls", stream.finishedToolCalls.size)
            put("streamCompleted", stream.completed)
            put(
                "recoveryDecision",
                when {
                    retryDelayMillis != null -> "RETRY_SCHEDULED"
                    !providerAllowsRetry -> "PROVIDER_RETRY_DISABLED"
                    !stream.retryableError -> "NOT_RETRYABLE"
                    stream.completed || stream.receiving || stream.hasToolCallFragments -> "PARTIAL_OR_TERMINAL_STREAM"
                    else -> "TECHNICAL_RECOVERY_EXHAUSTED"
                },
            )
            retryDelayMillis?.let { put("retryDelayMillis", it) }
            stream.failureDiagnostic?.let { failure ->
                put("stage", failure.stage.name)
                put("reason", failure.reason.name)
                failure.httpStatus?.let { put("httpStatus", it) }
                failure.responseType?.let { put("responseType", it.name) }
                put("receivedBytes", failure.receivedBytes)
                put("receivedChunks", failure.receivedChunks)
                put("elapsedMillis", failure.elapsedMillis)
                failure.requestIdHash?.let { put("requestIdHash", it) }
                failure.adapterRetryable?.let { put("adapterRetryable", it) }
                failure.serverError?.let { put("serverError", it.name) }
                failure.errorBodyState?.let { put("errorBodyState", it.name) }
                failure.retryAfterMillis?.let { put("retryAfterMillis", it) }
                failure.serverMessage?.let { put("serverMessage", it) }
                failure.serverCode?.let { put("serverCode", it) }
                failure.errorParameter?.let { put("errorParameter", it) }
            }
        }.toString().also { check(it.toByteArray().size <= MAX_BYTES) }
}
