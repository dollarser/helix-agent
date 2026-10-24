package com.helix.core.agent.turn

import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ConversationReferenceIntent
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.TurnTerminalCheckpoint

/** Prepared input bytes and exact accepted identity, not a grant or a database record. */
data class TurnSteeringInput(
    val inputId: String,
    val revision: Long,
    val configuration: TurnSteeringConfiguration,
    val content: String,
    val attachments: List<TurnInputAttachment>,
    val references: List<ConversationReferenceIntent> = emptyList(),
)

data class TurnSteeringConfiguration(
    val providerId: String,
    val modelId: String,
    val mode: String,
    val fingerprint: String,
)

data class TurnInputAttachment(
    val artifactId: String,
    val purpose: String,
    val boundSha256: String,
)

/** Returned only after the complete response/accepted-input transaction commits. */
data class ResponseCommitReceipt(
    val boundary: ResponseInputBoundary,
    val terminal: ModelStreamTerminal? = null,
) {
    init {
        require((boundary == ResponseInputBoundary.TERMINAL) == (terminal != null))
        require(terminal == null || terminal.state.isTerminal)
    }
}

enum class ToolResultsCommitReceipt { RECORDED_CANCELLED, NEXT_MODEL_OPENED }

/**
 * Bounded local atomic commands. A normal return proves commit; failure leaves the live round
 * unchanged. Infrastructure owns transactions; no transaction callback or stored entity crosses here.
 */
@Suppress("TooManyFunctions") // One method per atomic round command; no generic transaction callback.
interface TurnRoundStore {
    fun retryEmptyModelStream(
        checkpoint: TurnTerminalCheckpoint,
        nextModelCallId: String,
    )

    fun beginModelStream(checkpoint: TurnTerminalCheckpoint)

    fun beginToolBatch(checkpoint: TurnTerminalCheckpoint)

    fun commitModelToolStep(
        checkpoint: TurnTerminalCheckpoint,
        toolCallsJson: String,
    )

    fun openNextModelCall(
        checkpoint: TurnTerminalCheckpoint,
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    ): ToolResultsCommitReceipt

    fun appendSteeringBeforeRequest(
        checkpoint: TurnTerminalCheckpoint,
        draft: TurnSteeringInput,
    ): Boolean

    fun completeResponse(
        checkpoint: TurnTerminalCheckpoint,
        draft: TurnSteeringInput?,
        nextModelCallId: String,
    ): ResponseCommitReceipt

    fun recordDiagnostic(
        modelCallId: String,
        type: String,
        payload: String,
    )

    fun recordPromptSnapshot(
        modelCallId: String,
        snapshot: PromptSnapshot?,
        summary: Boolean,
    )

    fun recordRequestManifest(
        modelCallId: String,
        manifestJson: String?,
    )

    fun recordInputRequestStarted(
        checkpoint: TurnTerminalCheckpoint,
        messageIds: Set<String>,
    )

    fun commitCompaction(
        checkpoint: TurnTerminalCheckpoint,
        plan: ContextCompactionPlan,
        nextModelCallId: String?,
        notice: String?,
        failureReason: String?,
        saveSummary: Boolean,
    )
}
