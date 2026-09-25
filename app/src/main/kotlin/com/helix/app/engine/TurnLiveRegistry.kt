package com.helix.app.engine

import com.helix.app.agent.GoalTimeBudget
import com.helix.app.agent.TurnCancelSignal
import com.helix.app.agent.TurnExecutionHandles
import com.helix.app.runcontrol.RunControlConfig
import kotlinx.coroutines.Job

/**
 * Process-local live execution ownership for admitted Turns.
 *
 * Room remains durable truth. This registry only owns ephemeral execution handles and is always
 * identity-guarded so a stale Job completion cannot release a newer Turn for the same session.
 */
internal class TurnLiveRegistry {
    private val lock = Any()
    private val bySession = HashMap<String, LiveTurnHandle>()
    private val byTurn = HashMap<String, LiveTurnHandle>()

    fun claim(
        sessionId: String,
        turnId: String,
        job: Job,
        control: RunControlConfig,
    ): LiveTurnHandle {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(!job.isCompleted) { "cannot claim a completed Job" }
        val handle =
            synchronized(lock) {
                removeCompletedLocked(sessionId)
                require(bySession[sessionId] == null) { "SESSION_LIVE_TURN_CONFLICT: $sessionId" }
                require(byTurn[turnId] == null) { "TURN_LIVE_OWNER_CONFLICT: $turnId" }
                LiveTurnHandle(sessionId, turnId, job, control).also {
                    bySession[sessionId] = it
                    byTurn[turnId] = it
                }
            }
        job.invokeOnCompletion { release(handle) }
        return handle
    }

    fun active(sessionId: String): LiveTurnHandle? =
        synchronized(lock) {
            removeCompletedLocked(sessionId)
            bySession[sessionId]
        }

    fun activeTurns(): List<LiveTurnHandle> =
        synchronized(lock) {
            bySession.keys.toList().forEach(::removeCompletedLocked)
            bySession.values.toList()
        }

    fun hasActive(sessionId: String): Boolean = active(sessionId) != null

    val handles: TurnExecutionHandles =
        object : TurnExecutionHandles {
            override fun cancelSignal(turnId: String): TurnCancelSignal? = byTurn(turnId)?.cancel

            override fun goalTime(turnId: String): GoalTimeBudget? = byTurn(turnId)?.goalTime()

            override fun installGoalTime(
                turnId: String,
                timer: GoalTimeBudget,
            ) {
                requireNotNull(byTurn(turnId)) { "TURN_NOT_LIVE: $turnId" }.installGoalTime(timer)
            }

            override fun clearGoalTime(
                turnId: String,
                timer: GoalTimeBudget,
            ) {
                byTurn(turnId)?.clearGoalTime(timer)
            }
        }

    fun byTurn(turnId: String): LiveTurnHandle? =
        synchronized(lock) {
            val handle = byTurn[turnId]
            if (handle?.job?.isCompleted == true) {
                removeLocked(handle)
                null
            } else {
                handle
            }
        }

    /**
     * Releases process-local ownership after the durable terminal/park transaction commits.
     * A later Job completion is harmless because removal is identity-guarded.
     */
    fun release(
        sessionId: String,
        turnId: String,
    ): LiveTurnHandle? =
        synchronized(lock) {
            bySession[sessionId]
                ?.takeIf { it.turnId == turnId }
                ?.also(::removeLocked)
        }

    private fun release(handle: LiveTurnHandle) {
        synchronized(lock) {
            if (bySession[handle.sessionId] === handle || byTurn[handle.turnId] === handle) {
                removeLocked(handle)
            }
        }
    }

    private fun removeCompletedLocked(sessionId: String) {
        val current = bySession[sessionId] ?: return
        if (current.job.isCompleted) removeLocked(current)
    }

    private fun removeLocked(handle: LiveTurnHandle) {
        bySession.remove(handle.sessionId, handle)
        byTurn.remove(handle.turnId, handle)
        handle.close()
    }
}

/** One live Turn's process-local handles. Durable state is never stored here. */
internal class LiveTurnHandle(
    val sessionId: String,
    val turnId: String,
    val job: Job,
    val control: RunControlConfig,
) {
    @Volatile
    private var timer: GoalTimeBudget? = null

    val cancel = TurnCancelSignal { timer?.expiredCode() != null }

    fun signalCancel() = cancel.cancel()

    fun installGoalTime(goalTime: GoalTimeBudget) {
        synchronized(this) {
            require(timer == null || timer === goalTime) { "TURN_GOAL_TIME_CONFLICT: $turnId" }
            timer = goalTime
        }
    }

    fun goalTime(): GoalTimeBudget? = timer

    fun clearGoalTime(goalTime: GoalTimeBudget) {
        synchronized(this) {
            if (timer === goalTime) timer = null
        }
    }

    internal fun close() {
        synchronized(this) { timer = null }
    }
}
