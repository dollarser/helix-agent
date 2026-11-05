package com.helix.app.eval

import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import org.junit.Assert.assertThrows
import org.junit.Test

class OwnedSkillModelCleanupDeviceTest {
    @Test fun unknownAndInFlightEffectsCannotBeErased() {
        assertThrows(IllegalStateException::class.java) {
            requireResolvedSkillFixture(listOf(TurnState.CANCELLED), listOf(ToolCallState.NEEDS_REVIEW))
        }
        assertThrows(IllegalStateException::class.java) {
            requireResolvedSkillFixture(listOf(TurnState.INTERRUPTED), listOf(ToolCallState.COMPLETED))
        }
        assertThrows(IllegalStateException::class.java) {
            requireResolvedSkillFixture(listOf(TurnState.COMPLETED), listOf(ToolCallState.RUNNING))
        }
        requireResolvedSkillFixture(listOf(TurnState.CANCELLED), listOf(ToolCallState.CANCELLED))
        requireResolvedSkillFixture(listOf(TurnState.COMPLETED), listOf(ToolCallState.COMPLETED, ToolCallState.DENIED))
    }
}
