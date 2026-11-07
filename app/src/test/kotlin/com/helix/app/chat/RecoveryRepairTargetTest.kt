package com.helix.app.chat

import com.helix.feature.conversation.application.recovery.RecoveryRepairDestination
import com.helix.feature.conversation.application.recovery.RecoveryRepairTarget
import com.helix.feature.conversation.presentation.RecoveryBlockClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRepairTargetTest {
    @Test fun oldTargetCannotNavigateAnotherSessionOrTurn() {
        val target =
            RecoveryRepairTarget(
                "session-a",
                "turn-a",
                RecoveryRepairDestination.MODELS,
                RecoveryBlockClass.NETWORK,
            )
        assertTrue(target.matches("session-a", "turn-a"))
        assertFalse(target.matches("session-b", "turn-a"))
        assertFalse(target.matches("session-a", "turn-b"))
        assertFalse(target.matches(null, "turn-a"))
    }
}
