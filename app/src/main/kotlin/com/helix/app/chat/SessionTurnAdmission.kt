package com.helix.app.chat

import kotlinx.coroutines.Job

/**
 * Per-session in-flight turn admission (HXA-048). Holds at most ONE active turn per session: a
 * second send into a session that already has an in-flight turn is refused, while a turn in one
 * session never blocks a turn in another. This is the documented "one active turn per session"
 * model — the previous single global job made a send in session B vanish (silently dropped) while
 * session A's turn ran, breaking the "the send must never vanish" invariant the send path relies on.
 *
 * The [ChatService] calls [hasActive] from inside its admission gate and [register] right after it
 * launches the turn's [Job]; each entry self-removes when its [Job] completes, so a finished turn
 * never blocks the next one. The type is pure (no storage / provider / coroutine-context
 * reference) so it is unit-testable on the JVM, where the heavy [ChatService] (concrete Room-backed
 * deps, no Robolectric) cannot be constructed.
 */
internal class SessionTurnAdmission {
    private val activeBySession = java.util.concurrent.ConcurrentHashMap<String, ActiveTurn>()

    /** True when [sessionId] already has an in-flight (not yet completed) turn. */
    fun hasActive(sessionId: String): Boolean = activeBySession[sessionId]?.job?.isActive == true

    /**
     * Records [job] (turn [turnId]) as [sessionId]'s in-flight turn. When [job] completes the entry
     * is removed; a superseding registration for the same session only ever removes the entry it
     * actually replaced (identity-guarded), so a stale completion can never drop a live turn.
     */
    fun register(
        sessionId: String,
        job: Job,
        turnId: String,
    ) {
        val active = ActiveTurn(job, turnId)
        activeBySession[sessionId] = active
        job.invokeOnCompletion {
            if (activeBySession[sessionId] === active) activeBySession.remove(sessionId)
        }
    }

    /** [sessionId]'s in-flight turn (its [Job] to cancel and turn id to find the cancel signal). */
    fun activeTurn(sessionId: String): ActiveTurn? = activeBySession[sessionId]?.takeIf { it.job.isActive }

    /** A session's in-flight turn: the [Job] to cancel and the turn id to index its cancel signal. */
    internal class ActiveTurn(
        val job: Job,
        val turnId: String,
    )
}
