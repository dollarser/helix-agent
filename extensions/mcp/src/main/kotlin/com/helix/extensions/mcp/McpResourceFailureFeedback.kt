package com.helix.extensions.mcp

import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** These checks run only after the corresponding resource-list responses have been received. */
internal enum class ResourceListingFailure(
    val guidance: String,
) {
    LISTING_CHANGED("Resource listing changed. Omit cursor to restart discovery; do not continue the old snapshot."),
    CURSOR_CYCLE("The server repeated a resource cursor. Listing is incomplete; inspect the server pagination."),
    LIMIT_EXCEEDED("Resource listing exceeds supported discovery limits. Listing is incomplete; narrow the source."),
}

internal class ResourceListingException(
    val reason: ResourceListingFailure,
) : IllegalStateException(reason.guidance)

internal fun requireResourcePage(
    condition: Boolean,
    reason: ResourceListingFailure,
) {
    if (!condition) throw ResourceListingException(reason)
}

/** Typed local facts, never classification by server-controlled error text. */
internal fun resourceFailureFeedback(
    cause: Throwable,
    sent: Boolean,
    attempts: Int?,
    callId: String,
): ToolExecutorResult.Failed? {
    val local = cause is ResourceCursorException && !sent && attempts == 0
    val listing = cause as? ResourceListingException
    val protocol = cause as? McpProtocolResponseException
    val code: String
    val guidance: String
    when {
        local -> {
            code = cause.reason.name
            guidance = cause.reason.guidance
        }

        listing != null -> {
            code = listing.reason.name
            guidance = listing.reason.guidance
        }

        protocol != null -> {
            code = "JSON_RPC_ERROR"
            guidance = protocolGuidance(protocol.code)
        }

        else -> {
            return null
        }
    }
    val state = if (local) "NOT_SENT" else "REPLIED"
    return ToolExecutorResult.Failed(
        detail = "MCP request=$state; originalCallId=$callId; $code: $guidance",
        sideEffectFree = local,
        requiresReview = false,
        auditDetail =
            buildJsonObject {
                put("requestSent", sent)
                put("connectionAttempts", attempts ?: 0)
                put("responseReceived", !local)
                put("errorCode", code)
                protocol?.let { put("protocolErrorCode", it.code) }
            },
    )
}

private fun protocolGuidance(code: Int): String =
    when (code) {
        -32602 -> "Server rejected parameters (-32602). Correct arguments; omit cursor to restart listing."
        -32601 -> "Server does not support this method (-32601). Inspect its supported capabilities."
        else -> "Server returned JSON-RPC error $code. Check server configuration and request before retrying."
    }
