package com.helix.core.agent

import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass

/**
 * The two tool facts the mode filter needs. Dynamic risk is computed per call by the Policy
 * engine (architecture doc section 8); the mode filter only consumes it and never computes it.
 * (The full ToolDescriptor lives in the Tool framework, HXA-030; keeping the profile small
 * keeps `core:agent` decoupled from it.)
 */
data class ToolModeProfile(
    val operationClass: ToolOperationClass,
    val dynamicRisk: RiskLevel,
)

/** Stable denial reasons for UI, audit and tests. */
enum class ModeDenialCode {
    /** Chat mode without explicit user tool enablement: the tool table is empty. */
    TOOLS_DISABLED,

    /** The operation class is not READ_ONLY (Chat and Plan). */
    OPERATION_CLASS_NOT_READ_ONLY,

    /** The dynamic risk exceeds the mode cap (Chat: L0, Plan: L1). */
    RISK_LEVEL_TOO_HIGH,
}

/** Result of the mode-level tool check. */
sealed interface ModeDecision {
    /** The mode allows the tool into this turn's model tool table. */
    data object Allowed : ModeDecision

    data class Denied(
        val code: ModeDenialCode,
        val detail: String,
    ) : ModeDecision
}

/**
 * Mode-level tool-table filter (architecture doc section 5.1; modes doc section 6.1).
 *
 * This is the first stage of Tool Registry filtering - it decides which tools may enter the
 * model tool table. [ModeDecision.Allowed] never grants permission to execute: every call of
 * a table tool still goes through the full pipeline (schema validation, dynamic risk, Policy
 * decision, approval).
 *
 * - Chat: no tools by default; after explicit user enablement only READ_ONLY at dynamic risk L0.
 * - Plan: READ_ONLY only, at dynamic risk up to L1. The operation-class check is primary and
 *   must never be substituted by a risk-level check, so L1 mutations, HTTP calls and page
 *   actions stay denied (architecture doc section 5.1).
 * - Act / Goal: the mode imposes no extra restriction; the per-call Policy decides.
 */
object ModePolicy {
    fun evaluate(
        mode: AgentMode,
        profile: ToolModeProfile,
        chatToolsEnabled: Boolean = false,
    ): ModeDecision =
        when (mode) {
            AgentMode.CHAT -> evaluateChat(profile, chatToolsEnabled)
            AgentMode.PLAN -> evaluatePlan(profile)
            AgentMode.ACT -> ModeDecision.Allowed
            AgentMode.GOAL -> ModeDecision.Allowed
        }

    /** Keeps exactly the tools the mode admits into this turn's model tool table. */
    fun <T> filterTools(
        mode: AgentMode,
        tools: List<T>,
        chatToolsEnabled: Boolean = false,
        profileOf: (T) -> ToolModeProfile,
    ): List<T> = tools.filter { tool -> evaluate(mode, profileOf(tool), chatToolsEnabled) is ModeDecision.Allowed }

    private fun evaluateChat(
        profile: ToolModeProfile,
        toolsEnabled: Boolean,
    ): ModeDecision =
        when {
            !toolsEnabled -> {
                ModeDecision.Denied(
                    ModeDenialCode.TOOLS_DISABLED,
                    "Chat has no tools until the user explicitly enables them.",
                )
            }

            profile.operationClass != ToolOperationClass.READ_ONLY -> {
                ModeDecision.Denied(
                    ModeDenialCode.OPERATION_CLASS_NOT_READ_ONLY,
                    "Chat mode allows only READ_ONLY tools.",
                )
            }

            profile.dynamicRisk != RiskLevel.L0 -> {
                ModeDecision.Denied(ModeDenialCode.RISK_LEVEL_TOO_HIGH, "Chat mode allows only dynamic risk L0.")
            }

            else -> {
                ModeDecision.Allowed
            }
        }

    private fun evaluatePlan(profile: ToolModeProfile): ModeDecision =
        when {
            profile.operationClass != ToolOperationClass.READ_ONLY -> {
                ModeDecision.Denied(
                    ModeDenialCode.OPERATION_CLASS_NOT_READ_ONLY,
                    "Plan mode allows only READ_ONLY tools; a risk-level check cannot substitute this.",
                )
            }

            profile.dynamicRisk > RiskLevel.L1 -> {
                ModeDecision.Denied(ModeDenialCode.RISK_LEVEL_TOO_HIGH, "Plan mode allows dynamic risk up to L1.")
            }

            else -> {
                ModeDecision.Allowed
            }
        }
}
