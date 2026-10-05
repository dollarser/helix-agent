package com.helix.app.chat

import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionInputBindingTest {
    @Test fun futureDeliveryPreferenceDoesNotInvalidateAcceptedInputConfiguration() {
        val request = ChatSubmission("session", 0, "input", "hello")
        val control = RunControlConfig(AgentMode.ACT, false, TurnBudgetBounds.DEFAULT)

        fun fingerprint(value: RunControlConfig) =
            SessionInputBinding.configuration(request, "provider", "model", "facts", value, "ACT")
        assertEquals(fingerprint(control), fingerprint(control.copy(immediateMessages = true)))
        assertNotEquals(fingerprint(control), fingerprint(control.copy(mode = AgentMode.PLAN)))
    }
}
