package com.helix.feature.conversation.application.recovery

import com.helix.feature.conversation.presentation.SubscriptionRecoveryUi
import com.helix.provider.api.SubscriptionRecoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRecoveryQueryTest {
    @Test fun closedConversationDoesNotReadOrReusePreviousRows() {
        assertTrue(subscriptionRecoveriesFor(store(emptyList()), null, listOf(previous())).isEmpty())
    }

    @Test fun onlyTheExactOriginalTurnAndCallMayRetainPresentation() {
        val matching = SubscriptionRecoveryFact("original", "call", true)
        val differentTurn = SubscriptionRecoveryFact("other", "call", false)
        val result = subscriptionRecoveriesFor(store(listOf(matching, differentTurn)), "session", listOf(previous()))
        assertEquals(previous().copy(localResultAvailable = true), result[0])
        assertEquals(SubscriptionRecoveryUi("other", "call"), result[1])
    }

    private fun previous() = SubscriptionRecoveryUi("original", "call", status = SubscriptionRecoveryStatus.UNKNOWN)

    private fun store(facts: List<SubscriptionRecoveryFact>): RecoveryObservationStore =
        object : RecoveryObservationStore {
            override fun originalTurn(turnId: String): OriginalRecoveryTargets = error("Unexpected original lookup")

            override fun interruptedSubscriptions(sessionId: String): List<SubscriptionRecoveryFact> {
                assertEquals("session", sessionId)
                return facts
            }
        }
}
