package com.helix.core.agent

import com.helix.core.model.ModelRequest

data class ContextCheckpoint(
    val coveredThrough: Long,
    val summary: String,
    val sourceCallId: String? = null,
    val estimatedInputTokens: Long? = null,
    val preservedMessageIds: Set<String> = emptySet(),
)

data class ContextCompactionPlan(
    val coveredThrough: Long,
    val request: ModelRequest,
    val retainedRequest: TurnContextRequest,
    val preservedMessageIds: Set<String> = emptySet(),
    val originalInputTokens: Long = Long.MAX_VALUE,
)
