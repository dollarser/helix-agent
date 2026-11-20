package com.helix.app.goal

import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalToolContractTest {
    @Test fun reportExplainsActiveTurnRequirementAndOrdinaryTaskCompletion() {
        val descriptor = GoalReportTool.descriptor()
        assertEquals("goal_report", descriptor.name.value)
        assertTrue(descriptor.description.contains("this turn is bound to an active persistent Goal"))
        assertTrue(descriptor.description.contains("current Goal instructions"))
        assertTrue(descriptor.description.contains("ordinary chat task"))
        assertTrue(descriptor.description.contains("normal reply"))
        assertTrue(descriptor.description.contains("do not create a Goal just to report completion"))
        assertTrue(descriptor.description.contains("This report does not grant permissions"))
    }

    @Test fun lifecycleInputsExplainSourcesAndReportingRequirements() {
        val registry = ToolRegistry()
        GoalLifecycleTools.register(registry) { ToolExecutorResult.Completed(JsonObject(emptyMap())) }
        for (descriptor in registry.all()) {
            val properties = descriptor.inputSchema.getValue("properties").jsonObject
            properties.filterKeys { it != "budgets" }.forEach { (name, schema) ->
                assertTrue("${descriptor.name.value}.$name", schema.jsonObject["description"] != null)
            }
        }
        val update =
            requireNotNull(
                registry.resolveLatest(
                    com.helix.core.model
                        .ToolName("goal_update"),
                ),
            )
        assertTrue(update.description.contains("No change grants tool permissions"))
        val revision =
            update.inputSchema
                .getValue("properties")
                .jsonObject
                .getValue("expected_revision")
                .jsonObject
        assertTrue(
            revision
                .getValue("description")
                .jsonPrimitive.content
                .contains("goal_get"),
        )
    }

    @Test fun outputContractSupportsNoGoalSnapshotsAndReportEchoes() {
        val empty = Json.parseToJsonElement("""{"goal":null}""")
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(GoalToolOutputSchema.forTool("goal_get"), empty),
        )
        val snapshot =
            Json.parseToJsonElement(
                """{"goal":{"id":"g","objective":"verify","state":"ACTIVE","revision":1,"armed":true,
            "pending_change":null,"last_outcome":null,"rounds_started":1,"model_calls":2,"tool_calls":3,
            "tokens":100,"runtime_millis":200,"max_goal_rounds":256,"blocker_code":null}}""",
            )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(GoalToolOutputSchema.forTool("goal_get"), snapshot),
        )
        val report =
            Json.parseToJsonElement(
                """{"id":"goal-1","expected_revision":2,"status":"in_progress","summary":"Verified progress"}""",
            )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(GoalToolOutputSchema.forTool("goal_update"), report),
        )
        assertTrue(
            ToolSchemaValidator.validate(
                GoalToolOutputSchema.forTool("goal_get"),
                report,
            ) is ToolSchemaValidation.Invalid,
        )
        val bad = Json.parseToJsonElement("""{"goal":{"revision":"2"}}""")
        assertTrue(
            ToolSchemaValidator.validate(GoalToolOutputSchema.forTool("goal_get"), bad) is ToolSchemaValidation.Invalid,
        )
    }
}
