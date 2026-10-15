package com.helix.extensions.mobileuse

import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import java.time.Instant

/** Confirmed facts only: never apply these results after dispatching an action or publishing an image. */
internal object AutomationTermination {
    private val cancelledBeforeDispatch =
        ToolExecutorResult.CancelledWithEffectTruth(
            "AUTOMATION_CANCELLED_BEFORE_DISPATCH",
            sideEffectFree = true,
            requiresReview = false,
        )
    private val deadlineBeforeDispatch =
        ToolExecutorResult.TimedOutWithEffectTruth(
            "AUTOMATION_DEADLINE_BEFORE_DISPATCH",
            sideEffectFree = true,
            requiresReview = false,
        )
    val observationCancelled =
        ToolExecutorResult.CancelledWithEffectTruth(
            "AUTOMATION_WAIT_CANCELLED",
            sideEffectFree = true,
            requiresReview = false,
        )
    val observationTimedOut =
        ToolExecutorResult.TimedOutWithEffectTruth(
            "AUTOMATION_DEADLINE_BEFORE_OBSERVATION",
            sideEffectFree = true,
            requiresReview = false,
        )

    fun beforeDispatch(call: ExecutableToolCall): ToolExecutorResult? =
        when {
            call.cancel.isCancelled() -> cancelledBeforeDispatch
            !Instant.now().isBefore(call.deadline) -> deadlineBeforeDispatch
            else -> null
        }
}
