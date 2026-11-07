package com.helix.app.application.agent

import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SubmitTurnCommand

internal fun SubmitTurnCommand.runControl(): RunControlConfig =
    RunControlConfig(
        mode = mode,
        chatToolsEnabled = chatToolsEnabled,
        budgets = budgets,
        reasoning = reasoning,
        goalBudgets = goalBudgets ?: GoalBudgetDefaults.VALUE,
    )

/** Approved human-request facts retained only for their original Turn; not a new approval source. */
internal data class GoalUserRequest(
    val sessionId: String,
    val text: String,
    val providerId: String,
    val control: RunControlConfig,
    val providerSnapshot: String,
    val sourceMessageId: String? = null,
    val inputId: String? = null,
)
