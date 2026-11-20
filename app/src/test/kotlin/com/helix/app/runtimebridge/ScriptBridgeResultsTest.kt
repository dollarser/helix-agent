package com.helix.app.runtimebridge

import com.helix.core.model.BoundToolResult
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.Sha256
import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScriptBridgeResultsTest {
    @Test fun receivedFailureKeepsOutputAndFailureStatus() {
        val result = encode("""{"reason":"invalid choice"}""")
        assertEquals("FAILED", result.getValue("status").jsonPrimitive.content)
        assertEquals(
            "invalid choice",
            result
                .getValue("output")
                .jsonObject
                .getValue("reason")
                .jsonPrimitive.content,
        )
        assertEquals("false", result.getValue("requiresReview").jsonPrimitive.content)
        assertEquals("false", result.getValue("sideEffectFree").jsonPrimitive.content)
    }

    @Test fun truncatedOutputRemainsTextAndOversizeNeverClaimsNoEffects() {
        val partial = encode("{partial", true)
        assertEquals("{partial", partial.getValue("output").jsonPrimitive.content)
        assertEquals("true", partial.getValue("truncated").jsonPrimitive.content)
        val large = encode("x".repeat(ScriptBridgeProtocol.MAX_BYTES), true)
        assertEquals("RESULT_TOO_LARGE", large.getValue("status").jsonPrimitive.content)
        assertEquals("false", large.getValue("sideEffectFree").jsonPrimitive.content)
        assertFalse(large.containsKey("output"))
    }

    private fun encode(
        payload: String,
        truncated: Boolean = false,
    ) = Json
        .parseToJsonElement(
            bridgeResult(
                "call",
                ToolDispatchOutcome.ExecutionFailed(
                    DispatchOutcomeCode.TOOL_FAILED,
                    "Tool returned a failure result",
                    result = BoundToolResult(payload, Sha256("b".repeat(64)), truncated, 1),
                ),
            ),
        ).jsonObject
}
