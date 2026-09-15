package com.helix.app.chat

import com.helix.app.agent.GoalTimeBudget
import com.helix.app.agent.TurnCancelSignal
import com.helix.app.automation.AutomationModule
import com.helix.app.root.RootModule
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataOrigin
import com.helix.core.storage.entity.TurnEntity
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.JsonObject

/** Builds a trusted request from live facts; cancellation and dispatch state stay with their owners. */
internal class ChatDispatchRequests(
    private val toolPipeline: ToolPipeline,
    private val turnCancels: java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>,
    private val goalTimes: java.util.concurrent.ConcurrentHashMap<String, GoalTimeBudget>,
    private val lanScopes: () -> Set<com.helix.core.policy.NetworkOriginScope>,
) {
    /**
     * The trusted dispatch request (doc 11: the dispatcher receives the contract target —
     * the app cannot lower a tool's isolation. Root and Accessibility tools bind their current
     * short-lived user scope and data origin here; dynamic MCP/A2A egress stays separately bound.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    fun build(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolName: ToolName,
        descriptor: ToolDescriptor?,
        args: JsonObject,
        profile: SafetyProfile,
        mode: AgentMode,
        chatToolsEnabled: Boolean,
    ): ToolDispatchRequest {
        val mcpFacts =
            descriptor?.let {
                toolPipeline.mcpDispatchFacts(
                    sessionId = turn.sessionId,
                    toolCallId = toolCallId,
                    descriptor = it,
                    arguments = args,
                    sensitivity = com.helix.core.policy.DataSensitivity.NORMAL,
                )
            }
        val a2aFacts =
            descriptor?.let {
                toolPipeline.a2aDispatchFacts(
                    sessionId = turn.sessionId,
                    descriptor = it,
                    arguments = args,
                    sensitivity = com.helix.core.policy.DataSensitivity.NORMAL,
                )
            }
        val egressFacts =
            mcpFacts?.let {
                Triple(
                    it.egress,
                    it.originSeenInSession,
                    it.sourceBindingChanged || (it.checkpointRequired && it.originSeenInSession),
                )
            }
                ?: a2aFacts?.let {
                    Triple(
                        it.egress,
                        it.originSeenInSession,
                        it.sourceBindingChanged || (it.checkpointRequired && it.originSeenInSession),
                    )
                }
        return ToolDispatchRequest(
            toolCallId = toolCallId,
            turnId = turn.id,
            sessionId = turn.sessionId,
            toolName = toolName,
            toolVersion = descriptor?.version ?: ToolVersion(0),
            args = args,
            mode = mode,
            chatToolsEnabled = chatToolsEnabled,
            profile = profile,
            executionTarget = descriptor?.executionTarget ?: ExecutionTargetType.LOCAL_ANDROID,
            dataOrigin =
                when {
                    descriptor?.name?.value?.startsWith("ui.") == true -> DataOrigin.ACCESSIBILITY
                    descriptor?.name?.value?.startsWith("root.") == true -> DataOrigin.ROOT
                    else -> DataOrigin.WORKSPACE
                },
            scope = RootModule.scopeFor(descriptor?.name?.value) ?: AutomationModule.scopeFor(descriptor?.name?.value),
            uiToken = "chat:${turn.id}",
            egress = egressFacts?.first,
            originSeenInSession = egressFacts?.second ?: true,
            lanScopes = lanScopes(),
            overwritesExisting = false,
            codeOrCommandChanged = false,
            sourceBindingChanged = egressFacts?.third ?: false,
            cancel = turnCancels.getOrPut(turn.id) { TurnCancelSignal { goalTimes[turn.id]?.expiredCode() != null } },
            remainingExecutionMillis =
                goalTimes[turn.id]?.let { timer -> { timer.remainingExecutionMillis() } }
                    ?: { Long.MAX_VALUE },
        )
    }
}
