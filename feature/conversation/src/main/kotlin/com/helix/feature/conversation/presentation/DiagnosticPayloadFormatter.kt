package com.helix.feature.conversation.presentation

import com.helix.core.policy.DiagnosticRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyDiagnostic = Json { prettyPrint = true }

/** Explicit technical diagnostics retain their original redacted fields, outside the conversation UI. */
fun formatToolPayload(value: String): String =
    try {
        prettyDiagnostic.encodeToString(
            JsonElement.serializer(),
            Json.parseToJsonElement(DiagnosticRedaction.redact(value)),
        )
    } catch (_: IllegalArgumentException) {
        DiagnosticRedaction.redact(value)
    }
