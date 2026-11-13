package com.helix.app.infrastructure.tools

import com.helix.app.application.tools.PrepareToolCall
import com.helix.app.application.tools.ScriptParentCommand
import com.helix.app.application.tools.ToolCallFact
import com.helix.app.application.tools.ToolDispatchCall
import com.helix.app.application.tools.ToolDispatchTurn
import com.helix.app.application.tools.ToolGatewayStore
import com.helix.app.application.tools.ToolLoopHistory
import com.helix.app.application.tools.ToolLoopResult
import com.helix.app.infrastructure.agent.AutomaticRecoveryPolicy
import com.helix.core.agent.ToolLoopProgress
import com.helix.core.model.Clock
import com.helix.core.model.ToolCallState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.repository.ToolCallOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Storage ownership checks and writes share one transaction; scheduler and UI stay outside it. */
@Suppress("TooManyFunctions") // One adapter implements the gateway persistence boundary.
internal class RoomToolGatewayStore(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val ids: () -> String,
) : ToolGatewayStore {
    override fun turn(turnId: String): ToolDispatchTurn {
        val row = storage.turns.resolve(turnId)
        return ToolDispatchTurn(row.id, row.sessionId, AutomaticRecoveryPolicy.isInspection(row))
    }

    override fun batchTurn(
        turnId: String,
        sessionId: String,
        modelCallId: String,
    ): ToolDispatchTurn =
        transaction {
            val turn = turn(turnId)
            require(turn.sessionId == sessionId) { "TOOL_BATCH_SESSION_MISMATCH" }
            require(storage.modelCalls.resolve(modelCallId).turnId == turn.id) { "MODEL_CALL_TURN_MISMATCH" }
            turn
        }

    override fun canReviewApproval(
        sessionId: String,
        turnId: String,
        callId: String,
    ): Boolean =
        transaction {
            val turn = storage.turns.resolve(turnId)
            turn.sessionId == sessionId && turn.state == "RUNNING_TOOL" &&
                storage.toolCalls.byTurnAndCallId(turnId, callId)?.state == ToolCallState.AWAITING_APPROVAL.name
        }

    override fun awaitingApproval(
        turnId: String,
        callId: String,
    ): String =
        transaction {
            val turn = storage.turns.resolve(turnId)
            storage.toolCalls.byTurnAndCallId(turnId, callId)?.let { row ->
                storage.toolCalls.updateState(row, ToolCallState.AWAITING_APPROVAL)
            }
            turn.sessionId
        }

    override fun prepare(command: PrepareToolCall): ToolDispatchCall =
        storage.toolCalls
            .append(
                id = command.callId,
                turnId = command.turnId,
                callId = command.callId,
                name = command.name,
                version = command.version,
                argsJson = command.argsJson,
                state = ToolCallState.PENDING.name,
                modelIntent = command.modelIntent,
                origin = command.origin?.let { ToolCallOrigin(it.modelCallId, it.callIndex) },
            ).fact()

    override fun running(rowId: String): ToolCallFact =
        transaction {
            val row = storage.toolCalls.resolve(rowId)
            storage.toolCalls.updateState(row, ToolCallState.RUNNING)
            ToolCallFact(
                storage.turns.resolve(row.turnId).sessionId,
                row.turnId,
                row.callId,
                row.name,
                row.argsJson,
                row.modelIntent,
            )
        }

    override fun directoryRef(sessionId: String): String? = storage.sessions.resolve(sessionId).directoryRef

    override fun reserveGoalCall(
        turnId: String,
        callId: String,
    ): Boolean = transaction { RoomGoalToolCallBudget(storage, clock).reserve(turnId, callId) }

    override fun recordScriptParent(command: ScriptParentCommand) {
        storage.withTransaction {
            require(storage.turns.resolve(command.turnId).sessionId == command.sessionId) { "SCRIPT_OWNER_MISMATCH" }
            val original = storage.toolCalls.byTurnAndCallId(command.turnId, command.parentCallId)
            require(
                original != null && original.name == command.parentName &&
                    (
                        original.state == ToolCallState.RUNNING.name ||
                            (command.retainingExecution && original.state == ToolCallState.COMPLETED.name)
                    ),
            ) { "SCRIPT_PARENT_NOT_RUNNING" }
            storage.auditEvents.append(
                ids(),
                command.callId,
                "script.bridge.call",
                "platform",
                buildJsonObject {
                    put("parentToolCallId", command.parentCallId)
                    put("turnId", command.turnId)
                }.toString(),
                clock.now().toEpochMilli(),
            )
        }
    }

    override fun loopHistory(turnId: String): ToolLoopHistory =
        transaction {
            val reset =
                storage.auditEvents
                    .recentByCorrelation(turnId, RESET, 1)
                    .singleOrNull()
                    ?.redactedPayload
            val recent = storage.toolCalls.recentByTurn(turnId, ToolLoopProgress.WINDOW)
            val start = if (reset == null) 0 else recent.indexOfLast { it.id == reset } + 1
            val calls = recent.drop(start)
            val results =
                calls
                    .mapNotNull { call ->
                        storage.toolResults.byToolCall(call.id)?.let {
                            call.id to
                                ToolLoopResult(
                                    it.id,
                                    it.toolCallId,
                                    it.status,
                                    it.summary,
                                    it.contentRef,
                                    it.verified,
                                )
                        }
                    }.toMap()
            val observations =
                calls
                    .mapNotNull { call ->
                        if (call.state == "COMPLETED" && results[call.id]?.verified == true) {
                            trustedObservation(call)?.let { call.id to it }
                        } else {
                            null
                        }
                    }.toMap()
            ToolLoopHistory(calls.map { it.fact() }, results, observations)
        }

    override fun resetLoopProgress(turnId: String) {
        storage.withTransaction {
            val last = storage.toolCalls.recentByTurn(turnId, 1).lastOrNull() ?: return@withTransaction
            storage.auditEvents.append(ids(), turnId, RESET, "agent", last.id, clock.now().toEpochMilli())
        }
    }

    private fun trustedObservation(call: ToolCallEntity): JsonObject? {
        val event =
            storage.auditEvents
                .recentByCorrelation(call.callId, "tool_dispatch", 1)
                .singleOrNull()
                ?.takeIf { it.actor == "dispatcher" } ?: return null
        return try {
            val payload = Json.parseToJsonElement(event.redactedPayload).jsonObject
            val trusted =
                payload["jobObservation"] == JsonPrimitive(true) &&
                    payload["turnId"] == JsonPrimitive(call.turnId) &&
                    payload["toolName"] == JsonPrimitive(call.name) &&
                    payload["toolVersion"] == JsonPrimitive(call.version) &&
                    payload["code"] == JsonPrimitive("SUCCESS")
            if (trusted) payload["executionDetail"] as? JsonObject else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun ToolCallEntity.fact() =
        ToolDispatchCall(
            id,
            turnId,
            callId,
            name,
            version,
            argsJson,
            argsHash,
            state,
            modelIntent,
        )

    private fun <T> transaction(block: () -> T): T {
        var result: Result<T>? = null
        storage.withTransaction { result = Result.success(block()) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val RESET = "loop.user_steering"
    }
}
