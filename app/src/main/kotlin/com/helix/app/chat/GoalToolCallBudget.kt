package com.helix.app.chat

import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage

/** Tool-call attempts reserve Goal capacity before entering the scheduler, including denied attempts. */
internal class GoalToolCallBudget(
    private val storage: HelixStorage,
    private val clock: Clock,
) {
    fun reserve(
        turnId: String,
        callId: String,
    ): Boolean {
        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return true
        return GoalUsageReservations(storage).reserve(
            GoalUsageReservations.Request(reservationId(callId), binding.runId, GoalUsageReservations.Kind.TOOL),
        )
    }

    fun finish(callId: String) {
        val id = reservationId(callId)
        if (storage.goalUsageReservations.byId(id) != null) {
            GoalUsageReservations(storage).settle(id, 0, 0, clock.now().toEpochMilli())
        }
    }

    private fun reservationId(callId: String): String = "goal-tool:$callId"
}
