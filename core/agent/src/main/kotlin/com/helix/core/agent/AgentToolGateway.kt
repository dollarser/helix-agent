package com.helix.core.agent

import com.helix.core.model.ModelToolSchema
import com.helix.core.workspace.FileScopePath

/** Request-scoped trusted identity; model arguments cannot choose a session or Turn owner. */
data class ToolBatchRequest(
    val sessionId: String,
    val turnId: String,
    val calls: List<BufferedModelToolCall>,
    val control: RunControlConfig,
) {
    init {
        require(sessionId.isNotBlank() && turnId.isNotBlank())
        require(calls.isNotEmpty() && calls.map { it.callId }.distinct().size == calls.size)
    }
}

/** Reports only a slot already durably settled by the host; never grants permission or starts work. */
fun interface ToolBatchObserver {
    fun settled(
        callId: String,
        sideEffectUnknown: Boolean,
    )
}

/** Execution and binding port, deliberately separate from history representation and Room state. */
interface AgentToolGateway {
    fun loopProgress(turnId: String): ToolLoopProgress.Decision

    fun resetLoopProgress(turnId: String)

    fun prepareModelCalls(
        calls: List<BufferedModelToolCall>,
        directory: FileScopePath?,
        exposedTools: List<ModelToolSchema>,
    ): List<BufferedModelToolCall>

    suspend fun executeBatch(
        request: ToolBatchRequest,
        observer: ToolBatchObserver,
    ): SettledBatch
}

/** Converts domain data to bounded history drafts; it cannot execute or approve a tool. */
interface ToolMessageMaterializer {
    fun assistantToolStepJson(batch: LocalToolCallBatch): String

    fun toolResultDraft(settled: SettledCall): TurnMessageDraft

    fun progressDraft(decision: ToolLoopProgress.Decision): TurnMessageDraft?
}
