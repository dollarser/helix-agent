package com.helix.app.eval

import com.helix.app.AppContainer
import com.helix.app.chat.ModelToolExposureOrder
import com.helix.app.goal.GoalLifecycleTools
import com.helix.core.agent.ModePolicy
import com.helix.core.agent.ToolModeProfile
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in fixture observation uses the exact saved Conversation grant, never a global session. */
internal fun evaluationAutomationPort(
    center: com.helix.tools.automation.AutomationPermissionCenter,
    conversationId: String,
): com.helix.tools.automation.AutomationToolPort {
    val grant = requireNotNull(center.conversationGrant(conversationId))
    val call =
        com.helix.tools.framework.ExecutableToolCall(
            "fixture-observe",
            "ui.snapshot",
            "4",
            JsonObject(emptyMap()),
            com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
            java.time.Instant
                .now()
                .plusSeconds(120),
            com.helix.tools.framework.NoCancellation,
            conversationId,
            "fixture-setup",
            grant.scope.toScopeRef(),
        )
    return com.helix.tools.automation
        .PermissionCenterAutomationToolPort(center)
        .forCall(call)
}

/** Records every exposed contract, including tools the model did not select. */
internal fun exposedEvaluationTools(
    container: AppContainer,
    mode: AgentMode,
): JsonObject {
    val preferUi =
        com.helix.app.automation.AutomationModule
            .scopeFor("ui.snapshot", container.chatService.screen.value.openSessionId) != null
    val latest =
        container.toolPipeline.registry.all().groupBy { it.name }.values.map { versions ->
            versions.maxBy { it.version.value }
        }
    val control = container.chatService.runControl.value
    val session = requireNotNull(container.chatService.screen.value.openSessionId)
    val admitted =
        ModePolicy
            .filterTools(mode, latest, control.chatToolsEnabled) {
                ToolModeProfile(it.operationClass)
            }.filter { !it.name.value.startsWith("memory.") || container.memory?.enabled == true }
            .filter { it.name.value !in GoalLifecycleTools.names || mode != AgentMode.PLAN }
    val exposed =
        container.toolPipeline.mcpDiscovery
            .visible(
                session,
                admitted,
                ModelToolExposureOrder.defaultNames(preferUi),
            ).filter { container.toolPipeline.disabledToolFilter?.invoke(session, it) ?: true }
            .filter { !it.name.value.startsWith("memory.") || container.memory?.enabled == true }
            .filter { it.name.value !in GoalLifecycleTools.names || mode != AgentMode.PLAN }
            .let {
                ModelToolExposureOrder.prioritize(
                    it,
                    preferUi = preferUi,
                )
            }.take(ModelRequest.MAX_TOOLS)
    return buildJsonObject { exposed.forEach { put(it.name.value, it.version.value) } }
}

internal fun evaluationDevice(): JsonObject =
    buildJsonObject {
        put("model", android.os.Build.MODEL)
        put("api", android.os.Build.VERSION.SDK_INT)
        put(
            "abi",
            android.os.Build.SUPPORTED_ABIS
                .first(),
        )
        put("pageSizeBytes", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
    }

/** Counts observed durable rows only; unmeasured recovery/first-pass metrics stay absent. */
internal fun evaluationTrajectory(
    container: AppContainer,
    sessionId: String,
): JsonObject {
    val storage = container.storage
    val turns = storage.turns.listBySession(sessionId)
    val calls = turns.flatMap { storage.toolCalls.listByTurn(it.id) }
    val approvals = calls.mapNotNull { storage.approvals.byToolCall(it.callId) }
    return buildJsonObject {
        put("turns", turns.size)
        put("modelCalls", turns.sumOf { storage.modelCalls.listByTurn(it.id).size })
        put("toolCalls", calls.size)
        put("approvalsRequired", approvals.size)
        put("approvalsAnswered", approvals.count { it.decision != null })
        put("unknownEffects", calls.count { storage.toolResults.byToolCall(it.callId)?.status == "NEEDS_REVIEW" })
    }
}
