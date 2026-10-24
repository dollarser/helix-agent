package com.helix.app.application.agent

import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SubmitTurnCommand

/** Application facts only: presentation translates these reasons; they grant no capability. */
internal enum class TurnSubmissionBlock {
    SESSION_UNAVAILABLE,
    PROVIDER_CHANGED,
    SESSION_BUSY,
    RECEIPT_CONFLICT,
    INPUT_CONFIGURATION_CHANGED,
    INPUT_NO_LONGER_CONSUMABLE,
    CONTINUATION_DEFERRED,
    CONTINUATION_REFUSED,
    INVALID_HISTORY_MUTATION,
    FRESH_GUARD_REJECTED,
    GOAL_REFUSED,
    RECOVERY_UNAVAILABLE,
}

internal sealed interface TurnSubmissionResult {
    data class Started(
        val turnId: String,
        val deduplicated: Boolean = false,
    ) : TurnSubmissionResult

    data class Blocked(
        val reason: TurnSubmissionBlock,
    ) : TurnSubmissionResult
}

internal fun interface TurnSubmissionCommands {
    suspend fun submit(command: SubmitTurnCommand): TurnSubmissionResult
}

internal fun SubmitTurnCommand.runControl(): RunControlConfig =
    RunControlConfig(
        mode = mode,
        chatToolsEnabled = chatToolsEnabled,
        budgets = budgets,
        reasoning = reasoning,
        goalBudgets = goalBudgets ?: GoalBudgetDefaults.VALUE,
    )

internal fun TurnSubmissionResult.turnIdOrNull(): String? = (this as? TurnSubmissionResult.Started)?.turnId

internal class TurnStartBlocked(
    val sessionId: String,
    val reason: TurnSubmissionBlock,
) : RuntimeException("Turn submission refused: $reason")

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
