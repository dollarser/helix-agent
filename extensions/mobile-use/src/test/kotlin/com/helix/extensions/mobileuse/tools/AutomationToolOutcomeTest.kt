package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationToolOutcomeTest {
    @Test fun unsupportedActionExplainsRecoveryWithoutClaimingAFocusCause() {
        val result =
            AutomationActionResult(AutomationActionStatus.ACTION_NOT_SUPPORTED).toToolOutcome()
                as ToolExecutorResult.Failed
        assertTrue(result.detail.startsWith("ACTION_NOT_SUPPORTED:"))
        assertTrue(result.detail.contains("ui.snapshot"))
        assertTrue(result.detail.contains("scrollable container"))
        assertFalse(result.detail.contains("focus"))
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
    }

    @Test fun uncertainActionsKeepReviewAndNeverSuggestReplay() {
        listOf(AutomationActionStatus.ACTION_FAILED, AutomationActionStatus.ACTION_OUTCOME_UNKNOWN).forEach { status ->
            val result = AutomationActionResult(status).toToolOutcome() as ToolExecutorResult.Failed
            assertEquals(status.name, result.detail)
            assertFalse(result.sideEffectFree)
            assertTrue(result.requiresReview)
        }
    }

    @Test fun completedInputCannotClaimTargetAcceptanceOrBusinessSuccess() {
        val result = AutomationActionResult(AutomationActionStatus.SUCCEEDED).toToolOutcome()
        assertTrue(result is ToolExecutorResult.Completed)
        val output = (result as ToolExecutorResult.Completed).output.jsonObject
        assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
        assertEquals("UNVERIFIED", output.getValue("effectStatus").jsonPrimitive.content)
        assertEquals("true", output.getValue("observationRequired").jsonPrimitive.content)
        assertFalse(output.containsKey("rejectionReason"))
        assertFalse(output.containsKey("touchFiltered"))
        assertTrue(
            output
                .getValue("actionHint")
                .jsonPrimitive.content.length <= 384,
        )
        for (backend in listOf(false, true)) {
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(automationActionOutputSchema(backend), output),
            )
        }
    }

    @Test fun schemasRejectFalseSuccessClaimsAndMissingEffectQualification() {
        val completed =
            AutomationActionResult(AutomationActionStatus.SUCCEEDED).toToolOutcome()
                as ToolExecutorResult.Completed
        val output = completed.output.jsonObject
        val invalid =
            listOf(
                JsonObject(output + ("status" to JsonPrimitive("SUCCEEDED"))),
                JsonObject(output + ("effectStatus" to JsonPrimitive("VERIFIED"))),
                JsonObject(output - "effectStatus"),
                JsonObject(output + ("observationRequired" to JsonPrimitive(false))),
                JsonObject(output + ("touchFiltered" to JsonPrimitive(true))),
            )
        invalid.forEach {
            assertTrue(ToolSchemaValidator.validate(automationActionOutputSchema(), it) is ToolSchemaValidation.Invalid)
        }
    }

    @Test fun knownRefusalsAndUncertainEffectsRetainTheirOriginalRecoveryTruth() {
        val uncertain = setOf(AutomationActionStatus.ACTION_FAILED, AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        AutomationActionStatus.entries.filter { it != AutomationActionStatus.SUCCEEDED }.forEach { status ->
            val result = AutomationActionResult(status).toToolOutcome()
            assertTrue("$status", result is ToolExecutorResult.Failed)
            val failed = result as ToolExecutorResult.Failed
            assertTrue(failed.detail.startsWith(status.name))
            assertEquals(status !in uncertain, failed.sideEffectFree)
            assertEquals(status in uncertain, failed.requiresReview)
        }
    }

    @Test fun localSensitiveRefusalDoesNotPretendToHaveAnAndroidFilteringReceipt() {
        val result =
            AutomationActionResult(AutomationActionStatus.SENSITIVE_UI).toToolOutcome()
                as ToolExecutorResult.Failed
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
        assertTrue(result.detail.contains("Helix refused"))
        assertTrue(result.detail.contains("before dispatch"))
        assertFalse(result.detail.contains("Android filtered"))
    }
}
