package com.helix.app.application.tools

import com.helix.core.model.AgentMode
import com.helix.core.model.CancelSignal
import com.helix.core.model.ToolDispatchOutcome
import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDescriptor
import kotlinx.serialization.json.JsonObject

/** App execution entry points; conversation presentation does not own dispatch. */
interface ToolExecution {
    fun onApprovalCard(
        approvalId: String,
        request: ApprovalRequest,
    ): Pair<ToolDescriptor, PluginTaskIdentity>

    // Preserve explicit original-call identity, authorization context and cancellation.
    @Suppress("LongParameterList")
    fun dispatchToolCall(
        toolCallId: String,
        turnId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        mode: AgentMode = AgentMode.ACT,
        chatToolsEnabled: Boolean = false,
        parent: ExecutableToolCall? = null,
        callbackCancel: CancelSignal? = null,
    ): ToolDispatchOutcome

    fun dispatchScriptToolCall(
        parent: ExecutableToolCall,
        id: String,
        tool: String,
        arguments: JsonObject,
        cancel: CancelSignal,
    ): ToolDispatchOutcome
}
