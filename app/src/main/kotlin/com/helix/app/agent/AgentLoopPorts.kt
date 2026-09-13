package com.helix.app.agent

import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.storage.entity.TurnEntity

// The two execution ports of [AgentLoop] (research doc section 34; HX2-02): the loop itself is
// chat-free; the host provides context construction and tool execution. In production the
// chat-layer [com.helix.app.chat.ChatRequestAssembler] / [com.helix.app.chat.ChatToolCalls]
// implement these directly, so every producer (Chat / Goal / Share / Voice / Widget / Channel)
// drives the SAME loop. [TurnContextAssembler] is the single context-construction entry (the
// doc section 34 ContextEngine; HX2-03): the production assembler IS the whole context trunk —
// the dormant first-version core ContextBuilder was retired, no parallel context system remains.

/**
 * The agent loop's context-construction port — the single context-construction entry (doc
 * section 34 ContextEngine; HX2-03).
 */
internal interface TurnContextAssembler {
    /** The first model request of the turn: persisted history ending with the user message. */
    suspend fun build(
        sessionId: String,
        retryTurnId: String?,
        control: RunControlConfig,
    ): ChatContextRequest

    /** The request after a compaction commit. */
    suspend fun rebuild(
        sessionId: String,
        retryTurnId: String?,
        control: RunControlConfig,
        previous: ChatContextRequest,
    ): ChatContextRequest

    /** The next tool-loop request: the persisted history ending with the just-settled tool results. */
    suspend fun buildBackfill(
        sessionId: String,
        control: RunControlConfig,
    ): ChatContextRequest
}

/** The agent loop's tool-execution port: one bounded-parallel round per call, settled in call order. */
internal interface TurnToolExecutor {
    /** The assistant tool step the loop persists before running the batch. */
    fun assistantToolStepJson(batch: LocalToolCallBatch): String

    /** The persisted message draft for one settled call. */
    fun toolResultDraft(settled: SettledCall): TurnMessageDraft

    /**
     * Runs ONE tool round: persists each call's row, runs the batch through the dispatcher
     * (bounded-parallel, deterministic call-order settlement) and returns the durable outcomes.
     */
    fun runToolBatch(
        turn: TurnEntity,
        turnId: String,
        calls: List<BufferedModelToolCall>,
        coordinator: TurnCoordinator,
        control: RunControlConfig,
    ): List<SettledCall>
}
