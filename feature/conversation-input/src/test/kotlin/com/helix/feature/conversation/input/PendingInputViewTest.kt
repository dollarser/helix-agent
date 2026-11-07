package com.helix.feature.conversation.input

import com.helix.core.model.input.SessionInputDelivery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInputViewTest {
    @Test fun queuedInputCanBeExplicitlySent() {
        assertTrue(input(SessionInputDelivery.QUEUE).canSend)
    }

    @Test fun pendingSteerCannotBeSentAgain() {
        assertFalse(input(SessionInputDelivery.STEER).canSend)
    }

    @Test fun eitherDeliveryCanBeRetriedWhenItNeedsAttention() {
        SessionInputDelivery.entries.forEach { delivery ->
            assertTrue(input(delivery).copy(needsAttention = true).canSend)
        }
    }

    private fun input(delivery: SessionInputDelivery) =
        PendingInputView(
            inputId = "input",
            revision = 1,
            delivery = delivery,
            needsAttention = false,
        )
}
