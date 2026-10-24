package com.helix.app.application.tools

import com.helix.core.model.ApprovalDecision
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolCallState
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.EgressRequest
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonObject

/** Immutable projection input; no card, localized label, UI state or permission grant. */
internal data class ToolCallFact(
    val sessionId: String,
    val turnId: String,
    val callId: String,
    val name: String,
    val arguments: String,
    val modelIntent: String?,
)

internal sealed interface ToolPresentationFact {
    val callId: String

    data class Prepared(
        val call: ToolCallFact,
    ) : ToolPresentationFact {
        override val callId get() = call.callId
    }

    data class Running(
        val call: ToolCallFact,
    ) : ToolPresentationFact {
        override val callId get() = call.callId
    }

    data class Settled(
        val call: ToolCallFact,
        val state: ToolCallState,
        val summary: String?,
        val durationMs: Long?,
        val denialCode: DispatchOutcomeCode? = null,
        val origin: ToolOrigin? = null,
        val preDispatch: Boolean = false,
    ) : ToolPresentationFact {
        override val callId get() = call.callId
    }

    data class ApprovalRequested(
        val approvalId: String,
        val call: ToolCallFact,
        val request: ApprovalRequest,
        val descriptor: ToolDescriptor,
        val arguments: JsonObject,
        val profile: SafetyProfile,
        val dataOrigin: DataOrigin,
        val egress: EgressRequest?,
    ) : ToolPresentationFact {
        override val callId get() = call.callId
    }

    data class ApprovalDecided(
        val approvalId: String,
        val decision: ApprovalDecision,
    ) : ToolPresentationFact {
        override val callId get() = approvalId
    }

    data class ApprovalDecisionFailed(
        val approvalId: String,
    ) : ToolPresentationFact {
        override val callId get() = approvalId
    }

    data class CallFinished(
        override val callId: String,
    ) : ToolPresentationFact

    data class TurnFinished(
        val turnId: String,
    ) : ToolPresentationFact {
        override val callId get() = turnId
    }
}

/** ApprovalRequested must be truthfully renderable or fail closed; other events are disposable. */
internal fun interface ToolPresentationSink {
    fun publish(fact: ToolPresentationFact)
}
