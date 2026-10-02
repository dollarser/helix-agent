package com.helix.tools.automation

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationToolsTest {
    private val port = FakeAutomationPort()
    private val tools = AutomationTools(port)

    @Test
    fun progressContractRemainsAnApprovedExternalActionWithNumericValue() {
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.SET_PROGRESS }
        assertEquals(ToolOperationClass.EXTERNAL_ACTION, descriptor.operationClass)
        val input =
            buildJsonObject {
                put("token", JsonPrimitive(TOKEN))
                put("value", JsonPrimitive(25))
            }
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, input))
        assertTrue(
            ToolSchemaValidator.validate(
                descriptor.inputSchema,
                args("token" to TOKEN, "value" to "25"),
            ) is ToolSchemaValidation.Invalid,
        )
        completed(execute(AutomationTools.SET_PROGRESS, input))
        assertEquals(25.0, port.nodeRequests.single().progress)
        assertEquals(AutomationNodeAction.SET_PROGRESS, port.nodeRequests.single().action)
    }

    @Test
    fun waitReturnsImmediatelyWithRecoveryEvidenceInsteadOfPollingAPausedSession() {
        port.snapshotResult =
            AutomationSnapshotResult(
                AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED,
                pauseReason = AutomationPauseReason.TARGET_CHANGED,
                targetPackage = "com.other.app",
            )
        val result = completed(execute(AutomationTools.WAIT, args("text" to "Continue")))
        assertEquals(1, port.snapshots)
        assertEquals("com.other.app", result["targetPackage"]?.jsonPrimitive?.content)
        assertEquals("true", result["requiresAuthorization"]?.jsonPrimitive?.content)
        assertEquals("TARGET_CHANGED", result["pauseReason"]?.jsonPrimitive?.content)
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.WAIT }
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, result))
    }

    @Test
    fun registersExactTokenOnlySurfaceWithRiskAndCapability() {
        val descriptors = tools.descriptors()
        assertEquals(
            setOf(
                "ui.snapshot",
                "ui.find",
                "ui.click",
                "ui.long_click",
                "ui.set_text",
                "ui.set_progress",
                "ui.scroll",
                "ui.back",
                "ui.home",
                "ui.wait",
            ),
            descriptors.map { it.name.value }.toSet(),
        )
        assertTrue(descriptors.all { it.requiredCapabilities == setOf(Capability.ACCESSIBILITY_AUTOMATION) })
        assertTrue(descriptors.all { it.executionTarget == ExecutionTargetType.LOCAL_ANDROID })
        assertEquals(
            ToolOperationClass.READ_ONLY,
            descriptors
                .single {
                    it.name.value == AutomationTools.SNAPSHOT
                }.operationClass,
        )
        assertEquals(
            ToolOperationClass.READ_ONLY,
            descriptors.single { it.name.value == AutomationTools.WAIT }.operationClass,
        )
        assertEquals(
            ToolOperationClass.EXTERNAL_ACTION,
            descriptors
                .single {
                    it.name.value == AutomationTools.CLICK
                }.operationClass,
        )
        assertFalse(descriptors.any { it.inputSchema.toString().contains("coordinate", ignoreCase = true) })
    }

    @Test
    fun aHostPluginCanBindTheSameContractsToPluginProvenance() {
        val origin = ToolOrigin.PluginOrigin("mobile-use", "0.1.0", "mobile-use")
        val descriptors = AutomationTools(port, origin).descriptors()
        assertTrue(descriptors.all { it.origin == origin })
        assertTrue(
            descriptors.all {
                it.contractHash !=
                    tools.descriptors().single { base -> base.name == it.name }.contractHash
            },
        )
    }

    @Test
    fun snapshotAndFindExposeOnlyIssuedNodeTokens() {
        port.snapshotResult = successfulSnapshot()
        val snapshot = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
        assertTrue(snapshot.toString().contains(TOKEN))
        val found = completed(execute(AutomationTools.FIND, args("text" to "Continue")))
        assertEquals("FOUND", found["status"]?.jsonPrimitive?.content)
        assertTrue(found.toString().contains(TOKEN))
    }

    @Test
    fun actionsForwardTokensAndStableRefusalsNeverBecomeSuccess() {
        completed(execute(AutomationTools.CLICK, args("token" to TOKEN)))
        assertEquals(AutomationNodeAction.CLICK, port.nodeRequests.single().action)
        assertEquals(TOKEN, port.nodeRequests.single().token)

        port.actionResult = AutomationActionResult(AutomationActionStatus.STALE_TOKEN)
        val failed = execute(AutomationTools.CLICK, args("token" to TOKEN)) as ToolExecutorResult.Failed
        assertEquals("STALE_TOKEN", failed.detail)
        assertTrue(failed.sideEffectFree)
        assertFalse(failed.requiresReview)
    }

    @Test
    fun uncertainPlatformOutcomesNeverAdvertiseSafeTechnicalRetry() {
        for (status in listOf(AutomationActionStatus.ACTION_FAILED, AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)) {
            port.actionResult = AutomationActionResult(status)
            for (name in listOf(AutomationTools.CLICK, AutomationTools.BACK, AutomationTools.HOME)) {
                val result = execute(name, args("token" to TOKEN)) as ToolExecutorResult.Failed
                assertEquals(status.name, result.detail)
                assertFalse(result.sideEffectFree)
                assertTrue(result.requiresReview)
            }
        }
    }

    @Test
    fun setTextAndScrollHaveTypedArgumentsAndNoBlindCoordinates() {
        execute(AutomationTools.SET_TEXT, args("token" to TOKEN, "text" to "hello"))
        assertEquals("hello", port.nodeRequests.last().text)
        execute(AutomationTools.SCROLL, args("token" to TOKEN, "direction" to "forward"))
        assertEquals(AutomationNodeAction.SCROLL_FORWARD, port.nodeRequests.last().action)
        assertTrue(
            execute(
                AutomationTools.SCROLL,
                args("token" to TOKEN, "direction" to "diagonal"),
            ) is ToolExecutorResult.Failed,
        )
    }

    @Test
    fun globalActionsRemainExplicitAndBounded() {
        execute(AutomationTools.BACK, buildJsonObject {})
        execute(AutomationTools.HOME, buildJsonObject {})
        assertEquals(listOf(AutomationGlobalAction.BACK, AutomationGlobalAction.HOME), port.globalRequests)
    }

    @Test fun scrollSchemaOnlyAdmitsDirectionsTheExecutorImplements() {
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.SCROLL }
        assertEquals(2, descriptor.version.value)
        val schema = descriptor.inputSchema
        listOf("forward", "backward").forEach {
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(
                    schema,
                    args(
                        "token" to TOKEN,
                        "direction" to it,
                    ),
                ),
            )
        }
        listOf("up", "down", "UP", "left", "right", "next").forEach {
            assertTrue(
                ToolSchemaValidator.validate(
                    schema,
                    args("token" to TOKEN, "direction" to it),
                ) is ToolSchemaValidation.Invalid,
            )
        }
        val direction =
            schema
                .getValue("properties")
                .jsonObject
                .getValue("direction")
                .jsonObject
        assertEquals(
            kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("forward"), JsonPrimitive("backward"))),
            direction["enum"],
        )
        execute(AutomationTools.SCROLL, args("token" to TOKEN, "direction" to "backward"))
        assertEquals(AutomationNodeAction.SCROLL_BACKWARD, port.nodeRequests.last().action)
    }

    private fun execute(
        name: String,
        args: JsonObject,
    ) = tools.executor(name).execute(
        ExecutableToolCall(
            "call",
            name,
            "1",
            args,
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(15),
            NoCancellation,
        ),
    )

    private fun completed(result: ToolExecutorResult) = (result as ToolExecutorResult.Completed).output.jsonObject

    private fun args(vararg pairs: Pair<String, String>) =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }

    private fun successfulSnapshot() =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "com.example.fixture",
                4,
                7,
                Instant.EPOCH,
                listOf(
                    AutomationSnapshotNode(
                        TOKEN,
                        null,
                        0,
                        "Button",
                        "Continue",
                        null,
                        "continue",
                        AutomationNodeBounds(0, 0, 10, 10),
                        true,
                        false,
                        false,
                        false,
                        true,
                    ),
                ),
                false,
            ),
        )

    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}

private class FakeAutomationPort : AutomationToolPort {
    var snapshotResult = AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
    var actionResult = AutomationActionResult(AutomationActionStatus.SUCCEEDED)
    val nodeRequests = mutableListOf<AutomationNodeActionRequest>()
    val globalRequests = mutableListOf<AutomationGlobalAction>()

    var snapshots = 0

    override fun snapshot(): AutomationSnapshotResult {
        snapshots++
        return snapshotResult
    }

    override fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult {
        nodeRequests += request
        return actionResult
    }

    override fun globalAction(action: AutomationGlobalAction): AutomationActionResult {
        globalRequests += action
        return actionResult
    }
}
