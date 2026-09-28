package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.isReviewModeAdmitted

/** Exposure uses trusted operation contracts; execution still checks scope and current authorization. */
data class ToolModeProfile(
    val operationClass: ToolOperationClass,
)

enum class ModeDenialCode { TOOLS_DISABLED, OPERATION_CLASS_NOT_READ_ONLY }

sealed interface ModeDecision {
    data object Allowed : ModeDecision

    data class Denied(
        val code: ModeDenialCode,
        val detail: String,
    ) : ModeDecision
}

object ModePolicy {
    fun evaluate(
        mode: AgentMode,
        profile: ToolModeProfile,
        chatToolsEnabled: Boolean = false,
    ): ModeDecision =
        when {
            mode == AgentMode.CHAT && !chatToolsEnabled -> {
                ModeDecision.Denied(ModeDenialCode.TOOLS_DISABLED, "Chat tools require user enablement.")
            }

            mode in setOf(AgentMode.CHAT, AgentMode.PLAN) && !profile.operationClass.isReviewModeAdmitted -> {
                ModeDecision.Denied(
                    ModeDenialCode.OPERATION_CLASS_NOT_READ_ONLY,
                    "This mode permits reads and closed metadata operations only.",
                )
            }

            else -> {
                ModeDecision.Allowed
            }
        }

    fun <T> filterTools(
        mode: AgentMode,
        tools: List<T>,
        chatToolsEnabled: Boolean = false,
        profileOf: (T) -> ToolModeProfile,
    ): List<T> = tools.filter { evaluate(mode, profileOf(it), chatToolsEnabled) is ModeDecision.Allowed }
}
