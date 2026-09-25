package com.helix.app.engine

import com.helix.core.model.TurnState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnObservationHubTest {
    private fun observation(
        state: TurnState,
        text: String? = null,
    ) = TurnObservation("t1", state, streamingText = text)

    @Test
    fun untrackedTurnYieldsEmptyFlow() {
        val hub = TurnObservationHub()
        assertTrue(runBlocking { hub.observe("never-opened").toList() }.isEmpty())
    }

    @Test
    fun orphanObservationIsDropped() {
        val hub = TurnObservationHub()
        hub.emit(observation(TurnState.RECEIVING_MODEL, "orphan"))
        assertTrue(runBlocking { hub.observe("t1").toList() }.isEmpty())
    }

    @Test
    fun keepingUpConsumerReceivesFramesThroughTerminal() {
        val hub = TurnObservationHub()
        hub.open("t1")
        runBlocking {
            val received = mutableListOf<TurnObservation>()
            val collector = launch { received += hub.observe("t1").toList() }
            yield()
            hub.emit(observation(TurnState.RECEIVING_MODEL, "hello"))
            yield()
            hub.emit(observation(TurnState.COMPLETED))
            collector.join()

            assertEquals(listOf(TurnState.RECEIVING_MODEL, TurnState.COMPLETED), received.map { it.state })
            assertEquals("hello", received.first().streamingText)
        }
    }

    @Test
    fun slowConsumerStillReceivesTerminal() {
        val hub = TurnObservationHub()
        hub.open("t1")
        runBlocking {
            val received = mutableListOf<TurnObservation>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    received += hub.observe("t1").toList()
                }
            hub.emit(observation(TurnState.RECEIVING_MODEL, "hello"))
            hub.emit(observation(TurnState.COMPLETED))
            collector.join()

            assertTrue(received.isNotEmpty())
            assertEquals(TurnState.COMPLETED, received.last().state)
        }
    }

    @Test
    fun lateSubscriberToLiveTurnReceivesLatestObservation() {
        val hub = TurnObservationHub()
        hub.open("t1")
        hub.emit(observation(TurnState.RECEIVING_MODEL, "hello"))
        hub.emit(observation(TurnState.RUNNING_TOOL, "working"))

        val seen = runBlocking { hub.observe("t1").take(1).toList() }

        assertEquals(TurnState.RUNNING_TOOL, seen.single().state)
        assertEquals("working", seen.single().streamingText)
    }

    @Test
    fun parkedReviewCanCloseObservationWithoutPretendingTerminal() {
        val hub = TurnObservationHub()
        hub.open("t1")
        runBlocking {
            val received = mutableListOf<TurnObservation>()
            val collector = launch { received += hub.observe("t1").toList() }
            yield()
            hub.emit(observation(TurnState.NEEDS_REVIEW))
            hub.close("t1")
            collector.join()

            assertEquals(listOf(TurnState.NEEDS_REVIEW), received.map { it.state })
        }
        assertTrue(runBlocking { hub.observe("t1").toList() }.isEmpty())
    }

    @Test
    fun terminalObservationStopsTrackingTurn() {
        val hub = TurnObservationHub()
        hub.open("t1")
        hub.emit(observation(TurnState.FAILED))
        assertTrue(runBlocking { hub.observe("t1").toList() }.isEmpty())
    }
}
