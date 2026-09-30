package com.helix.app.chat

import com.helix.app.provider.SubscriptionRecoveredOutput
import com.helix.app.provider.SubscriptionRecoveryStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local output visibility and Runtime receipt delivery are independent postconditions. */
class SubscriptionAckCollectionTest {
    @Test fun cachedOutputDoesNotSuppressRetryOfAnUnconfirmedAcknowledgement() = runBlocking { verify(false) }

    @Test fun unavailableRuntimeDoesNotHideAlreadyVerifiedLocalOutput() = runBlocking { verify(true) }

    private suspend fun verify(unavailableFirst: Boolean) {
        val output = SubscriptionRecoveredOutput.fromText("verified output").copy(acknowledged = false)
        val row = SubscriptionRecoveryUi("turn", "model-call", output = output, localResultAvailable = true)
        val screen =
            MutableStateFlow(
                ChatScreenState(
                    sessions = emptyList(),
                    openSessionId = "session",
                    badge = null,
                    messages = emptyList(),
                    toolTimeline = emptyList(),
                    activeTurn = null,
                    pendingDisclosure = null,
                    blockedReason = null,
                    retryTargetTurnId = null,
                    subscriptionRecoveries = listOf(row),
                ),
            )
        var observations = 0
        var collections = 0
        val updates = mutableListOf<SubscriptionRecoveryUi>()
        coroutineScope {
            AutomaticRecoveryCollection(
                scope = this,
                screenState = screen,
                subscriptionRecoveryMutex = Mutex(),
                subscriptionRecovery = { _, _, _ ->
                    observations++
                    if (unavailableFirst && observations == 1) {
                        SubscriptionRecoveryStatus.UNKNOWN
                    } else {
                        SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED
                    }
                },
                subscriptionResultRecovery = { _, _, localOnly ->
                    if (localOnly) {
                        null
                    } else {
                        collections++
                        output.copy(acknowledged = unavailableFirst || collections > 1)
                    }
                },
                collectProot = { _, _, _ -> error("No PRoot call belongs to this fixture") },
                updateSubscriptionRecovery = {
                    updates += it
                    screen.value = screen.value.copy(subscriptionRecoveries = listOf(it))
                },
                prootStatus = { _, _ -> error("No PRoot call belongs to this fixture") },
            ).collect()
        }
        assertEquals(2, observations)
        assertEquals(if (unavailableFirst) 1 else 2, collections)
        assertEquals(output.pages, updates.first().output?.pages)
        assertFalse(updates.first().outputUnavailable)
        assertTrue(updates.last().output?.acknowledged == true)
        assertEquals(output.pages, updates.last().output?.pages)
    }
}
