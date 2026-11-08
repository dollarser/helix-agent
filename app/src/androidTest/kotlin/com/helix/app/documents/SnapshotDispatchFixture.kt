package com.helix.app.documents

import com.helix.core.model.AgentMode
import com.helix.core.model.CancelSignal
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityResolver
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.PolicyEngine
import com.helix.core.policy.WorkspaceScope
import com.helix.core.workspace.FileScopePath
import com.helix.extensions.documents.DocumentParser
import com.helix.extensions.documents.DocumentResult
import com.helix.extensions.documents.DocumentTools
import com.helix.tools.framework.ApprovalAcquisition
import com.helix.tools.framework.ApprovalBroker
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.AuditSink
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolDispatcher
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real registry/dispatcher/document executor; parsing is irrelevant to the blocked snapshot boundary. */
internal class SnapshotDispatchFixture(
    snapshots: RoomDocumentSnapshots,
    private val scope: String,
) {
    val entered = AtomicInteger()
    val exited = AtomicInteger()
    val audit = CopyOnWriteArrayList<DispatchAuditEvent>()
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.now()
        }
    private val registry = ToolRegistry()
    private val dispatcher: ToolDispatcher

    init {
        val binding =
            DocumentTools
                .bindings(
                    snapshots,
                    DocumentParser { _, _, _ -> DocumentResult(buildJsonObject { put("pageCount", 1) }) },
                    ToolImagePublication { _, _, _ -> error("Inspect must not publish an image") },
                ).single { it.descriptor.name.value == "documents.inspect" }
        registry.register(
            binding.descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    entered.incrementAndGet()
                    try {
                        return binding.executor.execute(call)
                    } finally {
                        exited.incrementAndGet()
                    }
                }
            },
        )
        dispatcher =
            ToolDispatcher(
                clock,
                registry,
                CapabilityCenter(
                    object : CapabilityResolver {
                        override fun resolve(capability: Capability): CapabilityGrant =
                            error("Document inspection has no platform capability grant")
                    },
                ),
                PolicyEngine(clock),
                object : ApprovalBroker {
                    override fun acquire(request: ApprovalRequest): ApprovalAcquisition =
                        error("Synthetic scoped read must not ask for new access")

                    override fun consume(proof: ApprovalProof) = error("No approval was acquired")
                },
                object : AuditSink {
                    override fun record(event: DispatchAuditEvent) {
                        audit += event
                    }
                },
            )
    }

    fun dispatch(
        id: String,
        session: String,
        cancel: CancelSignal,
        budgetMillis: Long = 20_000,
    ) = dispatcher.dispatch(
        ToolDispatchRequest(
            toolCallId = id,
            turnId = "turn-$id",
            sessionId = session,
            toolName = ToolName("documents.inspect"),
            toolVersion = ToolVersion(1),
            args = buildJsonObject { put("path", FileScopePath(scope, "report.pdf").toModelReference()) },
            mode = AgentMode.ACT,
            profile = SafetyProfile.STANDARD,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = WorkspaceScope(scope),
            uiToken = "private-snapshot-fixture",
            cancel = cancel,
            remainingExecutionMillis = { budgetMillis },
        ),
    )
}
