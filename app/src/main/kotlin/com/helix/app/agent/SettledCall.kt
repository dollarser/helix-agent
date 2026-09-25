package com.helix.app.agent

import com.helix.tools.framework.ToolDispatchOutcome

/**
 * A settled tool call: the model call id, its tool name, the durable outcome. Lives in the
 * agent package (not [com.helix.app.chat.ChatToolCalls]) because it is the loop's port
 * currency (HX2-02): [TurnToolExecutor.runToolBatch] returns them and
 * [TurnToolExecutor.toolResultDraft] turns each into a persisted message draft.
 */
internal data class SettledCall(
    val callId: String,
    val toolName: String,
    val outcome: ToolDispatchOutcome,
    val resultReference: String? = null,
)

/** One fully persisted tool batch. UNKNOWN calls are explicit loop control, not exceptions. */
internal data class SettledBatch(
    val calls: List<SettledCall>,
    val reviewCallIds: List<String>,
) {
    init {
        require(reviewCallIds.distinct().size == reviewCallIds.size) { "duplicate review call id" }
        val settledIds = calls.mapTo(hashSetOf()) { it.callId }
        require(reviewCallIds.all { it in settledIds }) { "review call must belong to settled batch" }
    }

    val requiresReview: Boolean
        get() = reviewCallIds.isNotEmpty()
}
