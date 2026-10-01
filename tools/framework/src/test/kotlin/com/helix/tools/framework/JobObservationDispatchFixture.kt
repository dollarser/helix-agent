package com.helix.tools.framework

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityResolver
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.GrantState
import com.helix.core.policy.PolicyEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal class JobObservationDispatchFixture : AutoCloseable {
    val observations = JobObservationFixture().apply { values["a"] = value("a") }
    val service = observations.service()
    val registry = ToolRegistry()
    val events = CopyOnWriteArrayList<DispatchAuditEvent>()
    private val broker =
        object : ApprovalBroker {
            override fun acquire(request: ApprovalRequest): ApprovalAcquisition = error("Unexpected approval request")

            override fun consume(proof: ApprovalProof) = error("Unexpected approval consumption")

            override fun reMint(proof: ApprovalProof): ApprovalProof? = error("Unexpected retry approval")
        }
    private val resolver =
        object : CapabilityResolver {
            override fun resolve(capability: Capability) =
                CapabilityGrant(
                    capability = capability,
                    state = GrantState.UNAVAILABLE,
                    grantedBySystem = false,
                    userScope = null,
                    checkedAt = observations.clock.now(),
                )
        }
    private val sink =
        object : AuditSink {
            override fun record(event: DispatchAuditEvent) {
                events += event
            }
        }
    val dispatcher =
        ToolDispatcher(
            observations.clock,
            registry,
            CapabilityCenter(resolver),
            PolicyEngine(observations.clock),
            broker,
            sink,
        )
    val scheduler = ToolScheduler(observations.clock, dispatcher, registry, maxConcurrency = 1, resourceGate = { 1 })

    fun descriptor(
        name: String = "jobs.await",
        output: String = "{\"type\":\"object\"}",
    ) = ToolDescriptor(
        name = ToolName(name),
        version = ToolVersion(1),
        description = "Observe a previously launched fixture job",
        inputSchema = json("{\"type\":\"object\"}"),
        outputSchema = json(output),
        operationClass = ToolOperationClass.READ_ONLY,
        timeout = 30.seconds,
        maxOutputBytes = 16_384,
        requiredCapabilities = emptySet(),
        idempotency = Idempotency.IDEMPOTENT,
        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
        origin = ToolOrigin.BuiltInOrigin,
    )

    fun register(
        executor: ToolExecutor = service.awaitExecutor(),
        descriptor: ToolDescriptor = descriptor(),
    ) {
        registry.register(descriptor, executor)
    }

    fun call(
        id: String = "observe",
        name: String = "jobs.await",
        args: JsonObject = observations.call().args,
    ) = ToolDispatchRequest(
        toolCallId = id,
        turnId = "observer-turn",
        sessionId = "s",
        toolName = ToolName(name),
        toolVersion = ToolVersion(1),
        args = args,
        mode = AgentMode.ACT,
        profile = SafetyProfile.STANDARD,
        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
        dataOrigin = DataOrigin.WORKSPACE,
        scope = null,
        uiToken = "fixture-ui",
    )

    override fun close() {
        service.close()
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
