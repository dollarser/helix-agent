package com.helix.core.agent.runtime

import com.helix.core.model.TurnState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.takeWhile

/** Engine-owned process-local projection for one live Turn. Durable state remains in the lifecycle store. */
data class TurnObservation(
    val turnId: String,
    val state: TurnState,
    val streamingText: String? = null,
    val errorLabel: String? = null,
    val retryable: Boolean = false,
)

/** Read-only durable facts; the App's real lifecycle store implements this port. */
interface TurnStateReader {
    fun persistedPhase(turnId: String): TurnState?

    fun persistedAssistantText(turnId: String): String?
}

interface TurnRuntimeView : TurnStateReader {
    fun observe(turnId: String): Flow<TurnObservation>
}

/**
 * Conflated per-Turn observation channels. A terminal observation closes the channel; a parked
 * non-terminal Turn is explicitly closed after its final observation.
 */
class TurnObservationHub {
    private val lock = Any()
    private val flows = HashMap<String, MutableSharedFlow<TurnObservation?>>()

    fun open(turnId: String) {
        synchronized(lock) {
            flows.getOrPut(turnId) {
                MutableSharedFlow(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            }
        }
    }

    fun observe(turnId: String): Flow<TurnObservation> {
        val source = synchronized(lock) { flows[turnId] } ?: return emptyFlow()
        return source.takeWhile { it != null }.filterNotNull()
    }

    fun emit(observation: TurnObservation) {
        val source = synchronized(lock) { flows[observation.turnId] } ?: return
        source.tryEmit(observation)
        if (observation.state.isTerminal) {
            synchronized(lock) { flows.remove(observation.turnId) }
            source.tryEmit(null)
        }
    }

    fun close(turnId: String) {
        val source = synchronized(lock) { flows.remove(turnId) } ?: return
        source.tryEmit(null)
    }
}
