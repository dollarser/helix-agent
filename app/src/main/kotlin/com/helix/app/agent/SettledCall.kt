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
)
