package com.helix.app.application.tools

import kotlinx.serialization.json.JsonObject

/** Durable commands finish before publication or execution. No external effect runs in a transaction. */
@Suppress("TooManyFunctions") // One consumer owns preparation, approval, execution and progress facts.
internal interface ToolGatewayStore {
    fun turn(turnId: String): ToolDispatchTurn

    fun batchTurn(
        turnId: String,
        sessionId: String,
        modelCallId: String,
    ): ToolDispatchTurn

    fun canReviewApproval(
        sessionId: String,
        turnId: String,
        callId: String,
    ): Boolean

    fun awaitingApproval(
        turnId: String,
        callId: String,
    ): String

    fun prepare(command: PrepareToolCall): ToolDispatchCall

    fun running(rowId: String): ToolCallFact

    fun directoryRef(sessionId: String): String?

    /**
     * Admission precedes validation because denied attempts consume capacity too. Preparation failures
     * leave a pending reservation for recovery; they never refund a possibly executed external effect.
     * A repeated reservation is refused, not permission to replay the call.
     */
    fun reserveGoalCall(
        turnId: String,
        callId: String,
    ): Boolean

    fun recordScriptParent(command: ScriptParentCommand)

    fun loopHistory(turnId: String): ToolLoopHistory

    fun resetLoopProgress(turnId: String)
}

internal data class ToolDispatchTurn(
    val id: String,
    val sessionId: String,
    val recoveryInspection: Boolean,
)

internal data class PrepareToolCall(
    val turnId: String,
    val callId: String,
    val name: String,
    val version: String,
    val argsJson: String,
    val modelIntent: String?,
    val origin: ToolSettlementOrigin?,
)

internal data class ScriptParentCommand(
    val sessionId: String,
    val turnId: String,
    val callId: String,
    val parentCallId: String,
    val parentName: String,
    val retainingExecution: Boolean,
)

internal data class ToolDispatchCall(
    val id: String,
    val turnId: String,
    val callId: String,
    val name: String,
    val version: String,
    val argsJson: String,
    val argsHash: String,
    val state: String,
    val modelIntent: String? = null,
)

internal data class ToolLoopResult(
    val id: String,
    val toolCallId: String,
    val status: String,
    val summary: String,
    val contentRef: String?,
    val verified: Boolean,
)

/** A bounded, consistent read including the latest user steering boundary and trusted audit facts. */
internal data class ToolLoopHistory(
    val calls: List<ToolDispatchCall>,
    val results: Map<String, ToolLoopResult>,
    val observations: Map<String, JsonObject>,
)
