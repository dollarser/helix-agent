package com.helix.extensions.a2a

import com.helix.core.model.A2aAgentId
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataSensitivity
import com.helix.core.policy.EgressTarget
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class A2aDynamicToolBridgeTest {
    @Test
    fun dispatchFactsBindA2aEgressContractAndSessionCheckpoint() {
        val agentId = A2aAgentId("research")
        val skill =
            A2aEnabledSkill(
                agentId = agentId,
                skillId = "web/research",
                interfaceSnapshot =
                    A2aInterfaceSnapshot(
                        endpoint = NormalizedEndpoint.parse("https://agent.example/a2a"),
                        binding = A2aBinding.JSON_RPC,
                        protocolVersion = "1.0",
                        tenant = null,
                    ),
                cardHash = "a".repeat(64),
                skillHash = "b".repeat(64),
                inputModes = listOf("text/plain", "application/json"),
                outputModes = listOf("text/plain", "application/json"),
            )
        val bridge = A2aDynamicToolBridge(agentId, listOf(skill)) { _, _ -> error("not executed") }
        val descriptor = bridge.descriptors().single()
        val args = buildJsonObject { put("task", "summarize") }
        val tracker = A2aSessionCheckpointTracker()

        val first = bridge.dispatchFacts(descriptor, args, DataSensitivity.NORMAL, tracker)
        assertEquals(EgressTarget.A2a(agentId), first.egress.target)
        assertEquals("https://agent.example:443", first.sendSummary.origin)
        assertEquals(descriptor.contractHash.hex, first.sendSummary.contractHash)
        assertTrue(first.checkpointRequired)

        val repeated = bridge.dispatchFacts(descriptor, args, DataSensitivity.NORMAL, tracker)
        assertTrue(repeated.originSeenInSession)
        assertFalse(repeated.checkpointRequired)

        val sensitive = bridge.dispatchFacts(descriptor, args, DataSensitivity.SENSITIVE, tracker)
        assertTrue(sensitive.checkpointRequired)
        assertEquals(DataSensitivity.SENSITIVE, sensitive.egress.dataSensitivity)
    }

    @Test
    fun fixedSchemaRegistersAndAmbiguousDeliveryRequiresReview() {
        val agentId = A2aAgentId("research")
        val skill =
            A2aEnabledSkill(
                agentId = agentId,
                skillId = "web/research",
                interfaceSnapshot =
                    A2aInterfaceSnapshot(
                        endpoint = NormalizedEndpoint.parse("https://agent.example/a2a"),
                        binding = A2aBinding.JSON_RPC,
                        protocolVersion = "1.0",
                        tenant = null,
                    ),
                cardHash = "a".repeat(64),
                skillHash = "b".repeat(64),
                inputModes = listOf("text/plain", "application/json"),
                outputModes = listOf("text/plain", "application/json"),
            )
        val bridge =
            A2aDynamicToolBridge(agentId, listOf(skill)) { _, _ ->
                throw A2aNeedsReviewException("delivery unknown")
            }
        val registry = ToolRegistry(emptyList())
        val implementations = ToolImplementationRegistry()

        bridge.register(registry, implementations)

        val descriptor = bridge.descriptors().single()
        assertTrue(descriptor.name.value.startsWith("a2a.research.web_research_"))
        assertEquals(descriptor, registry.resolve(descriptor.name, descriptor.version))
        val result =
            implementations.resolve(ToolName(descriptor.name.value), ToolVersion(1)).execute(
                ExecutableToolCall(
                    toolCallId = "call-1",
                    toolName = descriptor.name.value,
                    toolVersion = "1",
                    args = buildJsonObject { put("task", "summarize") },
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    deadline = Instant.now().plusSeconds(5),
                    cancel = NoCancellation,
                    sessionId = "session-1",
                    turnId = "turn-1",
                ),
            )
        assertTrue((result as ToolExecutorResult.Failed).requiresReview)
    }
}
