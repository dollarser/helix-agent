package com.helix.app.chat

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.takeWhile

/**
 * The per-turn live-frame channel behind [AgentTurnHost.observeTurnFrames] (research doc section
 * 34; HX2-01 §2c): a [TurnUi] stream whose lifetime equals the turn's, independent of the open
 * session's UI screen. This is what lets the app-layer runtime's `observe` stream a turn in a
 * NON-open (background) session to its terminal — the open-session [ChatService] screen only
 * reflects the one session the user is looking at, so it cannot be the live-frame source for a
 * turn the user is not viewing.
 *
 * Pure (kotlinx-coroutines only; no storage / provider / Android / service reference) so the frame
 * lifecycle is unit-testable on the JVM, where the heavy [ChatService] cannot be constructed.
 *
 * Each live turn owns a [MutableSharedFlow] of its frames. It is a SharedFlow (not a Channel) so
 * any number of observers can follow the same turn; `replay = 1` with DROP_OLDEST keeps only the
 * LATEST frame (O(1) memory, mirroring the open-session [StateFlow] activeTurn) rather than
 * buffering every frame. Because a [MutableSharedFlow] cannot be completed, the turn's terminal is
 * signalled with a `null` sentinel emission: [forTurn] ends each observer on it. The map therefore
 * holds only LIVE turns (naturally bounded by the number of concurrent turns) and a turn that has
 * ended is no longer tracked — [forTurn] for an ended turn returns an empty flow, so the adapter
 * falls back to the turn's persisted state.
 */
internal class TurnLiveFrames {
    private val lock = Any()
    private val flows = HashMap<String, MutableSharedFlow<TurnUi?>>()

    /**
     * Start tracking a turn's live frames. Called once when the turn starts. Idempotent: an
     * already-tracked turn keeps its existing flow.
     */
    fun open(turnId: String) {
        synchronized(lock) {
            flows.getOrPut(turnId) {
                // replay = 1 so a subscriber that joins mid-turn still receives the latest frame;
                // DROP_OLDEST means a slow consumer is conflated to the latest instead of blocking
                // the producer. The terminal is what an observer must not lose, and it is the LAST
                // thing emitted before the null sentinel, so it is retained in the replay slot.
                MutableSharedFlow(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            }
        }
    }

    /**
     * The live-frame flow for [turnId], or an empty (already-ended) flow when the turn is not
     * live — it has not started yet, or it has ended and stopped being tracked.
     */
    fun forTurn(turnId: String): Flow<TurnUi> {
        val source = synchronized(lock) { flows[turnId] } ?: return emptyFlow()
        // End each observer at the turn's completion sentinel (a null frame) and drop the sentinel
        // itself, so the returned flow yields only live frames and completes when the turn ends.
        // takeWhile is non-inclusive: it stops BEFORE the null, and the terminal frame (the last
        // live frame) is always emitted before the sentinel — so an observer always lands on the
        // terminal before the flow completes.
        return source.takeWhile { it != null }.filterNotNull()
    }

    /**
     * Publish [frame] to [turnId]'s observers. A terminal frame also emits the `null` completion
     * sentinel (ending every observer) and stops tracking the turn. A frame for a turn that is not
     * live — never [open]ed, or already ended (a duplicate terminal) — is dropped rather than
     * reviving a zombie flow.
     */
    fun emit(
        turnId: String,
        frame: TurnUi,
    ) {
        val source = synchronized(lock) { flows[turnId] } ?: return
        source.tryEmit(frame)
        if (frame.state.isTerminal) {
            synchronized(lock) { flows.remove(turnId) }
            source.tryEmit(null)
        }
    }
}
