package com.helix.app.application.tools

import com.helix.app.application.tools.ToolFailureContent
import com.helix.app.chat.ToolModelResult
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.policy.DiagnosticRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailureContentTest {
    @Test fun largeReceivedFailureProjectsReaderHandleWithoutLosingSavedOutput() {
        val payload = """{"message":"${"x".repeat(40000)}"}"""
        val failure =
            ToolDispatchOutcome.ExecutionFailed(
                DispatchOutcomeCode.TOOL_FAILED,
                "Tool returned a failure result",
                result =
                    com.helix.core.model.BoundToolResult(
                        payload,
                        com.helix.core.model
                            .Sha256("c".repeat(64)),
                        false,
                        1,
                    ),
            )
        val saved = ToolFailureContent.encode(failure)
        val projected = Json.parseToJsonElement(ToolModelResult.project("remote", saved, "turn/call")).jsonObject
        assertEquals("TOOL_FAILED", projected.getValue("failureCode").jsonPrimitive.content)
        assertEquals("turn/call", projected.getValue("resultRef").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(saved).jsonObject["output"])
        assertTrue(projected.toString().toByteArray().size < 32768)
    }

    @Test fun truncatedFailureOutputIsPreservedAsTextWithItsFullHash() {
        val failure =
            ToolDispatchOutcome.ExecutionFailed(
                DispatchOutcomeCode.TOOL_FAILED,
                "Tool returned a failure result",
                result =
                    com.helix.core.model.BoundToolResult(
                        "{\"partial",
                        com.helix.core.model
                            .Sha256("b".repeat(64)),
                        true,
                        1,
                    ),
            )
        val output = Json.parseToJsonElement(ToolFailureContent.encode(failure)).jsonObject
        assertEquals("{\"partial", output.getValue("output").jsonPrimitive.content)
        assertTrue(output.getValue("executorPayloadTruncated").jsonPrimitive.boolean)
        assertEquals("b".repeat(64), output.getValue("executorOutputHash").jsonPrimitive.content)
    }

    @Test fun failureFactsOverrideUntrustedFieldsAndCredentialsAreMasked() {
        val credential = "sk-" + "a".repeat(24)
        val output =
            ToolFailureContent.encode(
                ToolDispatchOutcome.ExecutionFailed(
                    DispatchOutcomeCode.TOOL_FAILED,
                    """{"sideEffectFree":true,"requiresReview":false,"error":"$credential"}""",
                    requiresReview = true,
                ),
            )
        val parsed = Json.parseToJsonElement(output).jsonObject
        assertFalse(parsed.getValue("sideEffectFree").jsonPrimitive.boolean)
        assertTrue(parsed.getValue("requiresReview").jsonPrimitive.boolean)
        assertFalse(output.contains(credential))
    }

    @Test fun longOutputKeepsOutcomeAndQueryIdentityOutsidePreview() {
        val payload =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("stdout", kotlinx.serialization.json.JsonPrimitive("a".repeat(40000)))
                    put("state", kotlinx.serialization.json.JsonPrimitive("FAILED"))
                    put("exitCode", kotlinx.serialization.json.JsonPrimitive(7))
                    put("jobId", kotlinx.serialization.json.JsonPrimitive("original"))
                    put("executionId", kotlinx.serialization.json.JsonPrimitive("execution"))
                    put("logTruncated", kotlinx.serialization.json.JsonPrimitive(true))
                    put("error", kotlinx.serialization.json.JsonPrimitive("compile failed"))
                }.toString()
        val projected = Json.parseToJsonElement(ToolModelResult.project("bash", payload, "turn/call")).jsonObject
        assertEquals("FAILED", projected.getValue("state").jsonPrimitive.content)
        assertEquals("7", projected.getValue("exitCode").jsonPrimitive.content)
        assertEquals("original", projected.getValue("jobId").jsonPrimitive.content)
        assertEquals("compile failed", projected.getValue("error").jsonPrimitive.content)
        assertTrue(projected.toString().toByteArray().size < 16384)
    }

    @Test fun diagnosticRedactionHappensBeforePaging() {
        val text = "prefix abc-test-credential suffix"
        assertFalse(DiagnosticRedaction.redact(text, setOf("abc-test-credential")).contains("abc-test-credential"))
    }
}
