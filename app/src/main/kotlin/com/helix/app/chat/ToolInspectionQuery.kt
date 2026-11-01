package com.helix.app.chat

import com.helix.app.application.diagnostics.DiagnosticRedaction
import com.helix.core.storage.HelixStorage

data class ToolInspectionContent(
    val arguments: String,
    val result: String?,
)

/** Read-only user inspection of the original session-owned result. Never dispatches or acknowledges work. */
internal class ToolInspectionQuery(
    private val storage: HelixStorage,
) {
    @Suppress("ReturnCount") // Original-session, settled-call and available-result guards refuse independently.
    fun read(
        sessionId: String,
        turnId: String,
        callId: String,
    ): ToolInspectionContent? {
        if (storage.turns.resolve(turnId).sessionId != sessionId) return null
        val call = storage.toolCalls.byTurnAndCallId(turnId, callId) ?: return null
        if (call.state !in
            setOf("COMPLETED", "FAILED", "NEEDS_REVIEW", "INTERRUPTED", "DENIED", "CANCELLED")
        ) {
            return null
        }
        val result = storage.toolResults.byToolCall(call.id)
        val content = result?.let { storage.toolResults.readContent(it, 1_048_576) }
        val secrets =
            storage.secrets
                .aliases()
                .map { storage.secrets.get(it) }
                .toSet()
        return ToolInspectionContent(
            DiagnosticRedaction.redact(call.argsJson, secrets),
            content?.let {
                DiagnosticRedaction.redact(it, secrets)
            },
        )
    }
}
