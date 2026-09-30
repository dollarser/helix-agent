package com.helix.app.chat

import com.helix.app.proot.ProotRecoveryStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeCollectionRecoveryTest {
    @Test fun cooldownAllowsFreshObservationWithoutAnImmediateRetryStorm() =
        runBlocking {
            var now = 0L
            var calls = 0
            val pauses = mutableListOf<String>()
            coroutineScope {
                val collector = AutomaticRuntimeCollection(this, { now }, { pauses.add(it) }) {}
                collector.request("job") {
                    calls++
                    AutomaticRuntimeCollection.Observation.RETRY
                }
                yield()
                assertEquals(3, calls)
                collector.request("job") { error("must respect cooldown") }
                now = 31_000_000_000L
                collector.request("job") {
                    calls++
                    AutomaticRuntimeCollection.Observation.COMPLETE
                }
                yield()
                collector.resetPaused()
                collector.request("job") { error("completed job must not be recollected") }
            }
            assertEquals(4, calls)
            assertEquals(listOf("OBSERVATION_RETRY_LIMIT"), pauses)
        }

    @Test fun reopeningResetsPausedButNeverCompletedObservation() =
        runBlocking {
            var calls = 0
            coroutineScope {
                val collector = AutomaticRuntimeCollection(this, { 0L }) {}
                collector.request("job") {
                    calls++
                    AutomaticRuntimeCollection.Observation.RETRY
                }
                yield()
                collector.resetPaused()
                collector.request("job") {
                    calls++
                    AutomaticRuntimeCollection.Observation.COMPLETE
                }
            }
            assertEquals(4, calls)
        }

    @Test fun runningProotNeverConsumesTheFailureRetryBudget() =
        runBlocking {
            var observations = 0
            var collections = 0
            coroutineScope {
                AutomaticRuntimeCollection(this) {}.request("proot") {
                    observations++
                    val status = if (observations < 12) ProotRecoveryStatus.RUNNING else ProotRecoveryStatus.SUCCEEDED
                    ProotCollectionPolicy.beforeCollect(status) ?: run {
                        collections++
                        AutomaticRuntimeCollection.Observation.COMPLETE
                    }
                }
            }
            assertEquals(12, observations)
            assertEquals(1, collections)
            assertEquals(
                AutomaticRuntimeCollection.Observation.RETRY,
                ProotCollectionPolicy.beforeCollect(ProotRecoveryStatus.UNKNOWN),
            )
            assertEquals(
                AutomaticRuntimeCollection.Observation.COMPLETE,
                ProotCollectionPolicy.beforeCollect(ProotRecoveryStatus.TERMINAL),
            )
            assertNull(ProotCollectionPolicy.beforeCollect(ProotRecoveryStatus.SUCCEEDED))
        }
}
