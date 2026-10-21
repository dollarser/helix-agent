package com.helix.app.chat

import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Failure diagnostics are saved data, not verified success or authority for another action. */
internal object ToolFailureContent {
    fun encode(failure: ToolDispatchOutcome.ExecutionFailed): String {
        val structured =
            runCatching {
                Json.parseToJsonElement(DiagnosticRedaction.redact(failure.detail)) as? JsonObject
            }.getOrNull()
        return buildJsonObject {
            if (structured ==
                null
            ) {
                put("error", DiagnosticRedaction.redact(failure.detail))
            } else {
                structured.forEach { (key, value) -> put(key, value) }
            }
            put("failureCode", failure.code.name)
            put("sideEffectFree", failure.sideEffectFree)
            put("requiresReview", failure.requiresReview)
        }.toString()
    }
}
