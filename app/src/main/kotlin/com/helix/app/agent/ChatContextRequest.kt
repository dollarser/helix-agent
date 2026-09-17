package com.helix.app.agent

import com.helix.core.agent.PromptSnapshot
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort

/**
 * Unsent context can exceed wire message limits; compaction happens before wire validation.
 *
 * [prompt] is the per-request system-prompt snapshot (research doc section 4.4) — the resolved
 * section list, the exact content and its fingerprint. The agent loop records it on the model
 * call row and in the `prompt.assembled` audit event for every request sent with it; null for
 * requests built without a snapshot (the compaction summary call builds its own request).
 */
internal data class ChatContextRequest(
    val model: String,
    val messages: List<ModelMessage>,
    val tools: List<ModelToolSchema>,
    val maxOutputTokens: Long,
    val reasoning: ReasoningEffort,
    val prompt: PromptSnapshot? = null,
) {
    fun modelRequest(): ModelRequest =
        ModelRequest(
            model,
            messages,
            tools,
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoning,
        )

    fun inputTokens(): Long = ModelInputEstimate.of(messages, tools).total
}
