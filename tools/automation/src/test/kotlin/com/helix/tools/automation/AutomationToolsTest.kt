package com.helix.tools.automation

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
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
    fun registersExactTokenOnlySurfaceWithRiskAndCapability() {
        val descriptors = tools.descriptors()
        assertEquals(
            setOf(
                "ui.snapshot",
                "ui.find",
                "ui.click",
                "ui.long_click",
                "ui.set_text",
                "ui.scroll",
                "ui.back",
                "ui.home",
                "ui.wait",
            ),
            descriptors.map { it.name.value }.toSet(),
        )
        assertTrue(descriptors.all { it.requiredCapabilities == setOf(Capability.ACCESSIBILITY_AUTOMATION) })
        assertTrue(descriptors.all { it.executionTarget == ExecutionTargetType.LOCAL_ANDROID })
        assertEquals(RiskLevel.L1, descriptors.single { it.name.value == AutomationTools.SNAPSHOT }.baseRisk)
        assertEquals(
            ToolOperationClass.READ_ONLY,
            descriptors.single { it.name.value == AutomationTools.WAIT }.operationClass,
        )
        assertEquals(RiskLevel.L2, descriptors.single { it.name.value == AutomationTools.CLICK }.baseRisk)
        assertFalse(descriptors.any { it.inputSchema.toString().contains("coordinate", ignoreCase = true) })
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

    override fun snapshot() = snapshotResult

    override fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult {
        nodeRequests += request
        return actionResult
    }

    override fun globalAction(action: AutomationGlobalAction): AutomationActionResult {
        globalRequests += action
        return actionResult
    }
}
