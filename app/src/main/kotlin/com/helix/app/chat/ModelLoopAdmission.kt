package com.helix.app.chat

import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnState

/** One admitted call owns its accounting identity through settlement. */
internal class ModelLoopAdmission(
    val request: ModelRequest?,
    val failure: ModelStreamTerminal?,
    private val settle: (ModelStreamState) -> ModelStreamTerminal? = { error("call was not admitted") },
) {
    fun finish(stream: ModelStreamState): ModelStreamTerminal? = settle(stream)

    companion object {
        @Suppress("ReturnCount") // Separate Turn and Goal admission failures precede an admitted call.
        fun prepare(
            request: ModelRequest,
            turnId: String,
            callId: String,
            turnBudget: TurnBudgetTracker,
            goalBudget: GoalModelCallBudget,
        ): ModelLoopAdmission {
            val admission = turnBudget.prepareCall(request)
            val code =
                when (admission.decision) {
                    TurnBudgetTracker.BeginDecision.MODEL_CALL_LIMIT -> "MODEL_CALL_LIMIT"
                    TurnBudgetTracker.BeginDecision.TOKEN_LIMIT -> "TOKEN_BUDGET_LIMIT"
                    TurnBudgetTracker.BeginDecision.ALLOWED -> null
                }
            if (code != null) return ModelLoopAdmission(null, failed(code))
            val prepared =
                goalBudget.prepare(turnId, callId, requireNotNull(admission.request))
                    ?: return ModelLoopAdmission(null, failed("GOAL_BUDGET_LIMIT"))
            return ModelLoopAdmission(prepared, null) { stream ->
                goalBudget.finish(turnId, callId, prepared, stream)
                when {
                    stream.finishedToolCalls.isNotEmpty() &&
                        !goalBudget.canContinue(
                            turnId,
                        )
                    -> failed("GOAL_BUDGET_LIMIT")

                    !turnBudget.finishCall(callId, prepared, stream) -> failed("TOKEN_BUDGET_LIMIT")

                    else -> null
                }
            }
        }

        private fun failed(code: String) = ModelStreamTerminal(TurnState.FAILED, code)
    }
}
