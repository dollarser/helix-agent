package com.helix.feature.conversation.presentation

import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ToolCallState

/** Already mapped display facts. They carry neither an executor, authority nor a dispatch request. */
data class ToolDisplayCall(
    val sessionId: String,
    val turnId: String,
    val callId: String,
    val name: String,
    val arguments: String,
    val modelIntent: String?,
)

sealed interface ToolDisplayEvent {
    data class Prepared(
        val call: ToolDisplayCall,
    ) : ToolDisplayEvent

    data class Running(
        val call: ToolDisplayCall,
    ) : ToolDisplayEvent

    data class Settled(
        val call: ToolDisplayCall,
        val state: ToolCallState,
        val summary: String?,
        val durationMs: Long?,
        val preDispatch: Boolean,
        val denialLabel: Int?,
        val failureLabel: Int?,
        val reviewLabel: Int?,
        val ledger: List<LedgerItemUi>,
    ) : ToolDisplayEvent {
        val callId get() = call.callId
    }

    data class ApprovalRequested(
        val call: ToolDisplayCall,
        val card: ApprovalCardUi,
    ) : ToolDisplayEvent {
        val callId get() = call.callId
    }

    data class ApprovalDecided(
        val approvalId: String,
        val decision: ApprovalDecision,
        val deniedLabel: Int,
    ) : ToolDisplayEvent

    data class ApprovalDecisionFailed(
        val approvalId: String,
        val failureLabel: Int,
    ) : ToolDisplayEvent

    data class CallFinished(
        val callId: String,
    ) : ToolDisplayEvent

    data class TurnFinished(
        val turnId: String,
    ) : ToolDisplayEvent
}
