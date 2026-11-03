package com.helix.app.chat

import com.helix.app.R
import com.helix.core.agent.ModelStreamState
import com.helix.core.model.ModelErrorCode
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One presentation for live termination and reopened history, using durable failure facts. */
internal object ModelFailureLabels {
    fun forTurn(
        storage: HelixStorage,
        turnId: String,
        code: String,
        base: String,
        strings: (Int, Array<out Any>) -> String,
    ): String {
        val model = storage.modelCalls.listByTurn(turnId).lastOrNull()
        val payload =
            model?.let {
                storage.auditEvents
                    .recentByCorrelation(it.id, "model.failure", 1)
                    .firstOrNull()
                    ?.redactedPayload
            }
        return format(code, base, payload, strings)
    }

    @Suppress("CyclomaticComplexMethod") // Closed error-stage and recovery facts mapped to localized labels.
    fun format(
        code: String,
        base: String,
        payload: String?,
        strings: (Int, Array<out Any>) -> String,
    ): String {
        if (ModelErrorCode.entries.none { it.name == code } && code !in STREAM_ERRORS) return base
        val facts = payload?.let(::parse)
        val reason = (facts?.get("reason") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val detailId =
            when (reason) {
                "DNS" -> {
                    R.string.model_failure_dns
                }

                "TLS" -> {
                    R.string.model_failure_tls
                }

                "MISSING_TERMINAL" -> {
                    R.string.model_failure_missing_terminal
                }

                "INVALID_JSON", "INVALID_SSE", "INVALID_SCHEMA" -> {
                    R.string.model_failure_invalid_response
                }

                "INVALID_TOOL_CALL", "INVALID_FINISH_REASON", "AFTER_TERMINAL" -> {
                    R.string.model_failure_invalid_response
                }

                else -> {
                    null
                }
            }
        var detail = detailId?.let { strings(it, emptyArray()) } ?: base
        val status = (facts?.get("httpStatus") as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
        if (status != null &&
            status in 100..599
        ) {
            detail = strings(R.string.model_failure_http, arrayOf<Any>(status, detail))
        }
        val partial =
            (facts?.get("recoveryDecision") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ==
                "PARTIAL_OR_TERMINAL_STREAM" ||
                listOf("receiving", "hasText", "hasToolFragments").any {
                    (facts?.get(it) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true"
                }
        val label =
            strings(
                if (partial) R.string.model_response_interrupted else R.string.model_request_failed,
                arrayOf(detail),
            )
        val attempt = (facts?.get("attempt") as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 1
        return if (attempt in
            2..6
        ) {
            strings(R.string.model_failure_retry_count, arrayOf<Any>(label, attempt - 1))
        } else {
            label
        }
    }

    private fun parse(payload: String): JsonObject? =
        try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    private val STREAM_ERRORS =
        setOf(
            ModelStreamState.TOOL_STREAM_TRUNCATED,
            ModelStreamState.TOOL_STREAM_INVALID,
            ModelStreamState.TOOL_ARGUMENTS_OVERFLOW,
            ModelStreamState.TOOL_CALL_COUNT_OVERFLOW,
            ModelStreamState.MODEL_TEXT_OVERFLOW,
        )
}
