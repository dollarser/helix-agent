package com.helix.app.application.tools

import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ToolCallState

/** A commit returns only after all durable result and Goal-accounting facts are committed. */
internal interface ToolSettlementStore {
    fun persist(command: ToolSettlementCommand)

    fun persistDenied(command: PreDispatchDenialCommand)

    fun callFact(
        rowId: String,
        callId: String,
        name: String,
    ): ToolCallFact
}

internal data class ToolSettlementCommand(
    val rowId: String,
    val state: ToolCallState,
    val status: String,
    val summary: String,
    val content: String?,
    val verified: Boolean,
)

internal data class ToolSettlementOrigin(
    val modelCallId: String,
    val callIndex: Int,
)

internal data class PreDispatchDenialCommand(
    val turnId: String,
    val toolCallId: String,
    val toolName: String,
    val rawArgs: String,
    val version: String,
    val code: DispatchOutcomeCode,
    val detail: String,
    val kind: PreDispatchDenialKind,
    val modelIntent: String?,
    val origin: ToolSettlementOrigin?,
)
