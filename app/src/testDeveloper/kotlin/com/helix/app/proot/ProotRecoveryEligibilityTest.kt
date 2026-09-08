package com.helix.app.proot

import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class ProotRecoveryEligibilityTest {
    @Test
    fun onlyStoppedCallsWithUnresolvedEffectsCanBeReconciled() {
        val allowed =
            setOf(
                TurnState.INTERRUPTED to ToolCallState.INTERRUPTED,
                TurnState.INTERRUPTED to ToolCallState.NEEDS_REVIEW,
                TurnState.FAILED to ToolCallState.NEEDS_REVIEW,
                TurnState.COMPLETED to ToolCallState.COMPLETED,
            )
        for (turn in TurnState.entries) {
            for (call in ToolCallState.entries) {
                assertEquals(
                    "${turn.name}/${call.name}",
                    (turn to call) in allowed,
                    prootRecoveryEligible(turn.name, call.name),
                )
            }
        }
    }
}
