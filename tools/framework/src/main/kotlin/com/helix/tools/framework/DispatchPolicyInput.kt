package com.helix.tools.framework

import com.helix.core.model.A2aAgentId
import com.helix.core.model.Capability
import com.helix.core.model.McpServerId
import com.helix.core.policy.PolicyInput
import com.helix.core.policy.ToolCallSource

/** Builds policy facts only from the registered descriptor and trusted dispatch request. */
internal fun buildDispatchPolicyInput(
    request: ToolDispatchRequest,
    descriptor: ToolDescriptor,
    missingCapabilities: Set<Capability>,
): PolicyInput =
    PolicyInput(
        baseRisk = descriptor.baseRisk,
        operationClass = descriptor.operationClass,
        mode = request.mode,
        chatToolsEnabled = request.chatToolsEnabled,
        profile = request.profile,
        source = toolCallSourceOf(descriptor),
        executionTarget = request.executionTarget,
        dataOrigin = request.dataOrigin,
        scope = request.scope,
        overwritesExisting = request.overwritesExisting,
        codeOrCommandChanged = request.codeOrCommandChanged,
        sourceBindingChanged = request.sourceBindingChanged,
        missingCapabilities = missingCapabilities,
        egress = request.egress,
        originSeenInSession = request.originSeenInSession,
        lanScopes = request.lanScopes,
    )

private fun toolCallSourceOf(descriptor: ToolDescriptor): ToolCallSource =
    when (val origin = descriptor.origin) {
        ToolOrigin.BuiltInOrigin -> ToolCallSource.BuiltIn
        is ToolOrigin.McpOrigin -> ToolCallSource.Mcp(McpServerId(origin.serverId), origin.sourceSchemaHash)
        is ToolOrigin.A2aOrigin -> ToolCallSource.A2a(A2aAgentId(origin.agentId), origin.cardHash, origin.skillHash)
    }
