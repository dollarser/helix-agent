package com.helix.app.chat

import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailureContentTest {
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
                    put("stdout", kotlinx.serialization.json.JsonPrimitive("a".repeat(20000)))
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
