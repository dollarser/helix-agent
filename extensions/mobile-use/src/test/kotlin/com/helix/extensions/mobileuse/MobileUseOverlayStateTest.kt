package com.helix.extensions.mobileuse

import com.helix.core.model.TurnState
import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.extensions.plugin.PluginTaskSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUseOverlayStateTest {
    private val first = PluginTaskIdentity("conversation-a", "turn-a")
    private val second = PluginTaskIdentity("conversation-b", "turn-b")
    private val active = PluginTaskSnapshot(TurnState.WAITING_MODEL)

    @Test fun navigationAndForegroundVisibilityNeverStopOrRebindTheTask() {
        val state = MobileUseOverlayState()
        state.bind(first)
        for (snapshot in listOf(active, PluginTaskSnapshot(TurnState.RUNNING_TOOL, true))) {
            assertTrue(state.canNavigate(first))
            assertFalse(state.canNavigate(second))
            assertFalse(state.visible(first, snapshot, conversationVisible = true))
            assertTrue(state.allowed())
            assertTrue(state.visible(first, snapshot, conversationVisible = false))
        }
        assertTrue(state.takeOver(first))
        assertFalse(state.allowed())
        state.close()
        assertFalse(state.canNavigate(first))
    }

    @Test fun approvalIsScopedToCurrentOwnerAndExactRequest() {
        val state = MobileUseOverlayState()
        state.bind(first)
        val pending =
            PluginTaskSnapshot(
                TurnState.RUNNING_TOOL,
                true,
                listOf(
                    com.helix.extensions.plugin
                        .PluginTaskApproval("approval-a", "ui.click", "Click", "token"),
                ),
            )
        assertTrue(state.canReview(first, pending, "approval-a"))
        assertFalse(state.canReview(second, pending, "approval-a"))
        assertFalse(state.canReview(first, pending, "approval-b"))
        assertFalse(state.canReview(first, pending.copy(approvals = emptyList()), "approval-a"))
        assertFalse(state.canReview(first, pending.copy(state = TurnState.CANCELLING), "approval-a"))
        assertFalse(state.canReview(first, pending.copy(state = TurnState.COMPLETED), "approval-a"))
        val hidden = state.suppress()
        assertFalse(state.canReview(first, pending, "approval-a"))
        state.release(hidden)
        assertTrue(state.canReview(first, pending, "approval-a"))
        state.takeOver(first)
        assertFalse(state.canReview(first, pending, "approval-a"))
        state.close()
        assertFalse(state.canReview(first, pending, "approval-a"))
    }

    @Test fun activeOwnerIsVisibleAndMissingHostTaskIsHidden() {
        val state = MobileUseOverlayState()
        assertTrue(state.bind(first))
        assertTrue(state.visible(first, active))
        assertFalse(state.visible(first, null))
        assertFalse(state.visible(second, active))
    }

    @Test fun staleControlCannotStopAnotherConversation() {
        val state = MobileUseOverlayState()
        state.bind(first)
        state.bind(second)
        assertFalse(state.takeOver(first))
        assertTrue(state.allowed())
        assertTrue(state.takeOver(second))
        assertFalse(state.allowed())
    }

    @Test fun takeoverRejectsRebindingStoppedTurnButAllowsNewTurn() {
        val state = MobileUseOverlayState()
        state.bind(first)
        assertTrue(state.takeOver(first))
        assertFalse(state.takeOver(first))
        assertFalse(state.bind(first))
        state.hide()
        assertFalse(state.bind(first))
        assertTrue(state.bind(second))
        assertTrue(state.allowed())
    }

    @Test fun successiveTakeoversNeverReviveEarlierStoppedTurns() {
        val state = MobileUseOverlayState()
        state.bind(first)
        state.takeOver(first)
        state.bind(second)
        state.takeOver(second)
        assertFalse(state.bind(first))
        assertFalse(state.bind(second))
    }

    @Test fun overlappingPhysicalOperationsStayHiddenUntilBothFinish() {
        val state = MobileUseOverlayState()
        state.bind(first)
        val screenshot = state.suppress()
        val gesture = state.suppress()
        state.release(screenshot)
        state.release(screenshot)
        assertFalse(state.visible(first, active))
        state.release(gesture)
        assertTrue(state.visible(first, active))
    }

    @Test fun inFlightOperationAlsoSuppressesReboundOwner() {
        val state = MobileUseOverlayState()
        state.bind(first)
        val ticket = state.suppress()
        state.bind(second)
        assertFalse(state.visible(second, active))
        state.release(ticket)
        assertTrue(state.visible(second, active))
    }

    @Test fun closedServiceCannotBeResurrectedByLateCallback() {
        val state = MobileUseOverlayState()
        state.bind(first)
        val ticket = state.suppress()
        state.close()
        state.release(ticket)
        assertFalse(state.allowed())
        assertFalse(state.bind(second))
        assertFalse(state.visible(first, active))
    }

    @Test fun consecutiveOperationsCoalesceRestorationWithoutExtendingDuplicateRelease() {
        var now = 0L
        val state = MobileUseOverlayState(150) { now }
        state.bind(first)
        val firstRead = state.suppress()
        state.release(firstRead)
        assertFalse(state.visible(first, active))
        now = 100
        val secondRead = state.suppress()
        state.release(secondRead)
        now = 150
        assertFalse(state.visible(first, active))
        state.release(secondRead)
        now = 249
        assertFalse(state.visible(first, active))
        now = 250
        assertTrue(state.visible(first, active))
    }

    @Test fun approvalBypassesRestorationDelayButNeverPhysicalSuppression() {
        val state = MobileUseOverlayState(150) { 0 }
        state.bind(first)
        val pending = PluginTaskSnapshot(TurnState.RUNNING_TOOL, true)
        val ticket = state.suppress()
        assertFalse(state.visible(first, pending))
        state.release(ticket)
        assertTrue(state.visible(first, pending))
        assertFalse(state.visible(first, active))
        assertFalse(state.visible(first, pending, conversationVisible = true))
        state.close()
        assertFalse(state.visible(first, pending))
    }

    @Test fun reviewAndInterruptedTasksDoNotShowAsRunning() {
        val state = MobileUseOverlayState()
        state.bind(first)
        assertFalse(state.visible(first, PluginTaskSnapshot(TurnState.NEEDS_REVIEW)))
        assertFalse(state.visible(first, PluginTaskSnapshot(TurnState.INTERRUPTED)))
        for (terminal in TurnState.entries.filter { it.isTerminal }) {
            assertFalse(state.visible(first, PluginTaskSnapshot(terminal)))
        }
    }
}
