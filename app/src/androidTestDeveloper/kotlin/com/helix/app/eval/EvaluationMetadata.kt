package com.helix.app.eval

import com.helix.app.AppContainer
import com.helix.core.agent.ModePolicy
import com.helix.core.agent.ToolModeProfile
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Records every exposed contract, including tools the model did not select. */
internal fun exposedEvaluationTools(
    container: AppContainer,
    mode: AgentMode,
): JsonObject {
    val latest =
        container.toolPipeline.registry.all().groupBy { it.name }.values.map { versions ->
            versions.maxBy { it.version.value }
        }
    val control = container.chatService.runControl.value
    val exposed =
        ModePolicy
            .filterTools(mode, latest, control.chatToolsEnabled) {
                ToolModeProfile(it.operationClass, it.baseRisk)
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
