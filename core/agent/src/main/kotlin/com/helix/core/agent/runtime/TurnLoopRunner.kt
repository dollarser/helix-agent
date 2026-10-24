package com.helix.core.agent.runtime

import com.helix.core.agent.TurnLoopResult

/** Host adapters supply models, tools, accounting and input delivery to the existing sole loop. */
fun interface TurnLoopRunner {
    suspend fun run(request: TurnExecutionRequest): TurnLoopResult
}

/** Diagnostics never determine lifecycle state or authorize a retry. */
fun interface TurnDiagnostics {
    fun report(
        message: String,
        failure: Throwable?,
    )
}
