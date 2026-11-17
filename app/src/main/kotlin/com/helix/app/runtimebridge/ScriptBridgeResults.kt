package com.helix.app.runtimebridge

import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preserve dispatch/effect truth; transport bounds never turn a completed action into "not executed". */
internal fun bridgeResult(
    id: String,
    result: ToolDispatchOutcome,
): String {
    val encoded =
        buildJsonObject {
            put("callId", id)
            when (result) {
                is ToolDispatchOutcome.Succeeded -> {
                    put("status", "SUCCEEDED")
                    putOutput(result.result)
                }

                is ToolDispatchOutcome.Denied -> {
                    put("status", "DENIED")
                    put("code", result.code.name)
                    put("detail", result.detail)
                    put("sideEffectFree", true)
                }

                ToolDispatchOutcome.Cancelled -> {
                    put("status", "CANCELLED")
                    put("sideEffectFree", true)
                }

                is ToolDispatchOutcome.ExecutionFailed -> {
                    put("status", "FAILED")
                    put("code", result.code.name)
                    put("detail", result.detail)
                    put("sideEffectFree", result.sideEffectFree)
                    put("requiresReview", result.requiresReview)
                    result.result?.let { putOutput(it) }
                }
            }
        }.toString()
    return if (encoded.toByteArray().size <= ScriptBridgeProtocol.MAX_BYTES) {
        encoded
    } else {
        buildJsonObject {
            put("status", "RESULT_TOO_LARGE")
            put("callId", id)
            put("sideEffectFree", false)
            put("inspectionRequired", true)
            put("detail", "Inspect the original durable tool result; never replay the action to obtain output.")
        }.toString()
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putOutput(output: com.helix.core.model.BoundToolResult) {
    put("outputHash", output.outputHash.hex)
    put("truncated", output.truncated)
    put("output", if (output.truncated) JsonPrimitive(output.payload) else Json.parseToJsonElement(output.payload))
}
