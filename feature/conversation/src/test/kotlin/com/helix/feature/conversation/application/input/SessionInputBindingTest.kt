package com.helix.feature.conversation.application.input

import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.input.SessionInputDelivery
import com.helix.feature.conversation.input.ChatSubmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInputBindingTest {
    @Test fun receiptMatchesOriginalProducerAcrossPromotionButNeverAnotherIntentOrSession() {
        val request = ChatSubmission("session", 0, "input", "hello")
        val control = RunControlConfig(AgentMode.ACT, false, TurnBudgetBounds.DEFAULT)
        val configuration = SessionInputBinding.configuration(request, "provider", "model", "facts", control, "ACT")
        val receipt =
            AcceptedInputReceipt(
                InputDeliveryKey("input", "session", 9),
                SessionInputDelivery.STEER,
                "provider",
                configuration.fingerprint,
                null,
                true,
            )
        assertTrue(SessionInputBinding.matches(request, receipt))
        assertFalse(SessionInputBinding.matches(request.copy(text = "changed"), receipt))
        assertFalse(SessionInputBinding.matches(request.copy(clientRequestId = "another"), receipt))
        assertFalse(SessionInputBinding.matches(request.copy(sessionId = "another"), receipt))
        assertFalse(
            SessionInputBinding.matches(
                request.copy(delivery = SessionInputDelivery.STEER, expectedTurnId = "target"),
                receipt,
            ),
        )
        assertFalse(SessionInputBinding.matches(request, receipt.copy(key = receipt.key.copy(sessionId = "another"))))
        assertFalse(SessionInputBinding.matches(request, receipt.copy(configurationFingerprint = "unrelated")))
    }

    @Test fun futureDeliveryPreferenceDoesNotInvalidateAcceptedInputConfiguration() {
        val request = ChatSubmission("session", 0, "input", "hello")
        val control = RunControlConfig(AgentMode.ACT, false, TurnBudgetBounds.DEFAULT)

        fun fingerprint(value: RunControlConfig) =
            SessionInputBinding.configuration(request, "provider", "model", "facts", value, "ACT")
        assertEquals(fingerprint(control), fingerprint(control.copy(immediateMessages = true)))
        assertNotEquals(fingerprint(control), fingerprint(control.copy(mode = AgentMode.PLAN)))
    }
}
