package com.helix.app.chat

import com.helix.app.provider.SubscriptionRecoveryStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionCollectionPolicyTest {
    @Test fun everyStateHasAnExplicitNonReplayDisposition() {
        SubscriptionRecoveryStatus.entries.forEach { state ->
            val expected =
                when (state) {
                    SubscriptionRecoveryStatus.RUNNING, SubscriptionRecoveryStatus.STOP_REQUESTED -> {
                        AutomaticRuntimeCollection.Observation.RUNNING
                    }

                    SubscriptionRecoveryStatus.UNKNOWN -> {
                        AutomaticRuntimeCollection.Observation.RETRY
                    }

                    SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED -> {
                        null
                    }

                    else -> {
                        AutomaticRuntimeCollection.Observation.COMPLETE
                    }
                }
            assertEquals(state.name, expected, SubscriptionCollectionPolicy.beforeCollect(state))
        }
        assertNull(SubscriptionCollectionPolicy.beforeCollect(SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED))
    }

    @Test fun longRunningSubscriptionIsNotACollectionFailure() =
        runBlocking {
            var queried = 0
            var fetched = 0
            coroutineScope {
                AutomaticRuntimeCollection(this, onPaused = { error("Running job was treated as failure") }) {}
                    .request("original-subscription") {
                        val status =
                            if (++queried < 16) {
                                SubscriptionRecoveryStatus.RUNNING
                            } else {
                                SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED
                            }
                        SubscriptionCollectionPolicy.beforeCollect(status) ?: run {
                            fetched++
                            AutomaticRuntimeCollection.Observation.COMPLETE
                        }
                    }
            }
            assertEquals(16, queried)
            assertEquals(1, fetched)
        }
}
