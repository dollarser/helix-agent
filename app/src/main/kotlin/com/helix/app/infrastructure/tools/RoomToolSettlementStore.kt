package com.helix.app.infrastructure.tools

import com.helix.app.application.tools.PreDispatchDenialCommand
import com.helix.app.application.tools.ToolCallFact
import com.helix.app.application.tools.ToolSettlementCommand
import com.helix.app.application.tools.ToolSettlementStore
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ToolCallOrigin
import com.helix.tools.framework.AuditSink
import com.helix.tools.framework.DispatchAuditEvent

/** Owns complete Room commits; result, call, audit and Goal charge cannot be committed separately. */
internal class RoomToolSettlementStore(
    private val storage: HelixStorage,
    private val audit: AuditSink,
    private val clock: Clock,
    private val idGenerator: () -> String,
) : ToolSettlementStore {
    override fun persist(command: ToolSettlementCommand) {
        RoomToolSettlementWriter(storage, clock, idGenerator).persist(
            command.rowId,
            command.state,
            command.status,
            command.summary,
            command.content,
            command.verified,
        )
    }

    override fun callFact(
        rowId: String,
        callId: String,
        name: String,
    ): ToolCallFact {
        val row = storage.toolCalls.resolve(rowId)
        return ToolCallFact(
            storage.turns.resolve(row.turnId).sessionId,
            row.turnId,
            callId,
            name,
            row.argsJson,
            row.modelIntent,
        )
    }

    @Suppress("LongMethod") // One transaction owns rejection identity, audit, result and budget.
    override fun persistDenied(command: PreDispatchDenialCommand) {
        val toolCallId = command.toolCallId
        val toolNameRaw = command.toolName
        val rawArgs = command.rawArgs
        val version = command.version
        val code = command.code
        val detail = command.detail
        val kind = command.kind
        val modelIntent = command.modelIntent
        val origin = command.origin?.let { ToolCallOrigin(it.modelCallId, it.callIndex) }
        val startedAt = clock.now().toEpochMilli()
        val finishedAt = clock.now().toEpochMilli()
        storage.withTransaction {
            val turn = storage.turns.resolve(command.turnId)
            storage.toolCalls.append(
                id = toolCallId,
                turnId = turn.id,
                callId = toolCallId,
                name = toolNameRaw,
                version = version,
                argsJson = rawArgs,
                state = kind.state.name,
                modelIntent = modelIntent,
                origin = origin,
            )
            storage.toolResults.append(
                id = idGenerator(),
                toolCallId = toolCallId,
                status = kind.state.name,
                summary = detail,
                content = null,
            )
            audit.record(
                DispatchAuditEvent(
                    correlationId = toolCallId,
                    turnId = turn.id,
                    sessionId = turn.sessionId,
                    toolName = toolNameRaw,
                    toolVersion = version,
                    code = code,
                    decisionSource = kind.decisionSource,
                    operationClass = null,
                    bindingHash = null,
                    actionFingerprint = null,
                    outputHash = null,
                    outputTruncated = false,
                    startedAt = startedAt,
                    policyDecidedAt = finishedAt,
                    approvalAcquiredAt = null,
                    executionStartedAt = null,
                    finishedAt = finishedAt,
                ),
            )
            RoomGoalToolCallBudget(storage, clock).finish(toolCallId)
        }
    }
}
