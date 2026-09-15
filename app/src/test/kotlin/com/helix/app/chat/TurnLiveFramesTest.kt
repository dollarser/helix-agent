package com.helix.app.chat

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

/**
 * Unit tests for [TurnLiveFrames] (research doc section 34; HX2-01 §2c): the pure per-turn
 * live-frame channel whose lifetime equals the turn's. A tracked turn streams its frames to
 * observers and ends them at its terminal (via a completion sentinel); a slow observer is
 * conflated to the latest frame but still receives the terminal; an untracked (unknown or ended)
 * turn yields an empty flow so the adapter can fall back to the turn's persisted state.
 */
class TurnLiveFramesTest {
    private fun frame(
        state: TurnState,
        text: String? = null,
    ) = TurnUi("t1", state, text, null, false)

    @Test
    fun anUntrackedTurnYieldsAnEmptyFlow() {
        val frames = TurnLiveFrames()
        val seen = runBlocking { frames.forTurn("never-opened").toList() }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun anEmitForAnUntrackedTurnIsDropped() {
        // A frame for a turn that was never opened is dropped, not a reason to create a flow.
        val frames = TurnLiveFrames()
        frames.emit("x", frame(TurnState.RECEIVING_MODEL, "orphan"))
        val seen = runBlocking { frames.forTurn("x").toList() }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aKeepingUpConsumerReceivesEveryLiveFrameUntilTheTerminal() {
        // A consumer that drains each frame promptly sees every frame: conflation only drops a
        // frame when the consumer is slower than the producer. The stream ends at the terminal.
        val frames = TurnLiveFrames()
        frames.open("t1")
        runBlocking {
            val received = mutableListOf<TurnUi>()
            val collector = launch { received += frames.forTurn("t1").toList() }
            yield() // let the collector start and suspend on the first (empty) receive
            frames.emit("t1", frame(TurnState.RECEIVING_MODEL, "hello"))
            yield() // let the collector drain RECEIVING_MODEL before the next frame conflates it
            frames.emit("t1", frame(TurnState.COMPLETED))
            collector.join()
            assertEquals(listOf(TurnState.RECEIVING_MODEL, TurnState.COMPLETED), received.map { it.state })
            assertEquals("hello", received[0].streamingText)
        }
    }

    @Test
    fun aSlowConsumerIsConflatedToTheLatestButStillReceivesTheTerminal() {
        // The critical guarantee: even when a slow observer cannot keep up (frames conflate away),
        // the turn's terminal is the LAST thing emitted and is retained, so the observer still
        // lands on the terminal and its stream ends.
        val frames = TurnLiveFrames()
        frames.open("t1")
        runBlocking {
            val received = mutableListOf<TurnUi>()
            // UNDISPATCHED suspends the collector before any drain, so intermediate frames
            // conflate away — the only invariant we can rely on is the terminal.
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    received += frames.forTurn("t1").toList()
                }
            frames.emit("t1", frame(TurnState.RECEIVING_MODEL, "hello"))
            frames.emit("t1", frame(TurnState.COMPLETED))
            collector.join()
            assertTrue(received.isNotEmpty())
            assertEquals(TurnState.COMPLETED, received.last().state)
        }
    }

    @Test
    fun aLateSubscriberToALiveTurnReceivesTheLatestFrame() {
        // replay = 1: a subscriber that joins after frames were already published still sees the
        // latest one, so an observer that starts mid-turn is not blind to the current state.
        val frames = TurnLiveFrames()
        frames.open("t1")
        frames.emit("t1", frame(TurnState.RECEIVING_MODEL, "hello"))
        frames.emit("t1", frame(TurnState.RUNNING_TOOL, "working"))
        val seen = runBlocking { frames.forTurn("t1").take(1).toList() }
        assertEquals(1, seen.size)
        assertEquals(TurnState.RUNNING_TOOL, seen[0].state)
        assertEquals("working", seen[0].streamingText)
    }

    @Test
    fun aTerminalFrameStopsTrackingTheTurn() {
        val frames = TurnLiveFrames()
        frames.open("t1")
        frames.emit("t1", frame(TurnState.FAILED))
        // Ended turns are no longer tracked: their flow is gone, so a subscriber gets an empty
        // flow and the adapter projects the persisted state instead.
        val seen = runBlocking { frames.forTurn("t1").toList() }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aDuplicateTerminalForAnEndedTurnIsDropped() {
        val frames = TurnLiveFrames()
        frames.open("t1")
        frames.emit("t1", frame(TurnState.CANCELLED))
        // A second terminal for the same (now ended) turn must not revive a zombie flow or throw.
        frames.emit("t1", frame(TurnState.CANCELLED))
        val seen = runBlocking { frames.forTurn("t1").toList() }
        assertTrue(seen.isEmpty())
    }
}
