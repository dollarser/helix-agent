package com.helix.core.agent

/**
 * Semantic checkpoints of one admitted Turn. The host commits each operation atomically before
 * updating the shared BatchTurnRuntime projection. No DAO, Room entity or transaction callback
 * crosses this port; per-slot settlement observations do not authorize an execution.
 */
@Suppress("TooManyFunctions") // One Turn journal keeps the atomic owner's related semantic operations together.
interface AgentTurnJournal {
    val id: String

    fun snapshot(): BatchTurnSnapshot

    fun currentStream(): ModelStreamState

    suspend fun beginModelStream(compacting: Boolean = false): ModelStreamState

    suspend fun retryEmptyModelStream(nextModelCallId: String)

    suspend fun recordDiagnostic(
        kind: String,
        body: String,
    )

    suspend fun recordPromptSnapshot(
        prompt: PromptSnapshot?,
        compacting: Boolean,
    )

    suspend fun recordRequestManifest(manifestJson: String?)

    suspend fun recordInputRequestStarted(messageIds: Set<String>)

    suspend fun beginToolBatch(callIds: List<String>)

    suspend fun commitModelToolStep(toolCallsJson: String)

    fun settleBatchCall(
        callId: String,
        sideEffectUnknown: Boolean,
    )

    suspend fun openNextModelCall(
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    )

    suspend fun commitCompaction(
        plan: ContextCompactionPlan,
        nextModelCallId: String?,
        notice: String? = null,
        failureReason: String? = null,
        saveSummary: Boolean = true,
    )
}

/** A single semantic input boundary; the host linearizes it with the terminal transaction. */
interface AgentInputBoundary {
    suspend fun appendBeforeRequest(
        sessionId: String,
        turnId: String,
    ): Boolean

    suspend fun finishResponse(
        sessionId: String,
        turnId: String,
    ): ResponseInputBoundary

    suspend fun requestStarting(
        sessionId: String,
        turnId: String,
        modelCallId: String,
        messageIds: Set<String>,
    )
}
