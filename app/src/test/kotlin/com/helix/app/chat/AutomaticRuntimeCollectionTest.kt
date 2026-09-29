package com.helix.app.chat

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticRuntimeCollectionTest {
    @Test fun runningJobCanCompleteAfterTheInitialRetryWindow() =
        runBlocking {
            var calls = 0
            val pauses = mutableListOf<Long>()
            coroutineScope {
                val collector = AutomaticRuntimeCollection(this) { pauses.add(it) }
                collector.request("slow-job") {
                    calls++
                    if (calls == 12) {
                        AutomaticRuntimeCollection.Observation.COMPLETE
                    } else {
                        AutomaticRuntimeCollection.Observation.RUNNING
                    }
                }
            }
            assertEquals(12, calls)
            assertEquals(10_000L, pauses.maxOrNull())
        }

    @Test fun permanentlyRunningJobHasABoundedObservationCost() =
        runBlocking {
            var calls = 0
            coroutineScope {
                AutomaticRuntimeCollection(this) { }.request("running") {
                    calls++
                    AutomaticRuntimeCollection.Observation.RUNNING
                }
            }
            assertEquals(60, calls)
        }

    @Test fun leavingAndReopeningTheConversationCanResumeObservation() =
        runBlocking {
            var calls = 0
            coroutineScope {
                val collector = AutomaticRuntimeCollection(this) { }
                collector.request("hidden") { AutomaticRuntimeCollection.Observation.DEFERRED }
                yield()
                collector.request("hidden") {
                    calls++
                    AutomaticRuntimeCollection.Observation.COMPLETE
                }
            }
            assertEquals(1, calls)
        }

    @Test fun cancellationNeverBecomesAnAutomaticRetry() =
        runBlocking {
            var calls = 0
            coroutineScope {
                AutomaticRuntimeCollection(this) { }.request("cancelled") {
                    calls++
                    throw kotlinx.coroutines.CancellationException("stopped")
                }
            }
            assertEquals(1, calls)
        }

    @Test fun duplicateRefreshDoesNotDuplicateCollectionAndFailuresAreBounded() =
        runBlocking {
            var calls = 0
            coroutineScope {
                val collector = AutomaticRuntimeCollection(this) { }
                repeat(10) {
                    collector.request("job") {
                        calls++
                        AutomaticRuntimeCollection.Observation.RETRY
                    }
                }
                yield()
                assertEquals(3, calls)
                collector.request("job") {
                    calls++
                    AutomaticRuntimeCollection.Observation.COMPLETE
                }
            }
            assertEquals(3, calls)
        }

    @Test fun successEndsCollectionWithoutAnotherAttempt() =
        runBlocking {
            var calls = 0
            coroutineScope {
                val collector = AutomaticRuntimeCollection(this) { }
                collector.request("job") {
                    calls++
                    AutomaticRuntimeCollection.Observation.COMPLETE
                }
            }
            assertEquals(1, calls)
        }
}
