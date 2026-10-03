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
