package com.helix.app.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TerminalPageState(
    val hasSession: Boolean = false,
    val sessions: List<ManualTerminal.State> = emptyList(),
    val activeSessionId: String? = null,
    val session: ManualTerminal.State? = null,
    val connection: ManualTerminal.Connection? = null,
    val isWriter: Boolean = true,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val errorMessage: String? = null,
)

/** Owns the connection across rotation; finishing the page never stops the shell. */
internal class ManualTerminalViewModel(
    private val terminal: ManualTerminal,
) : ViewModel() {
    private val mutable = MutableStateFlow(TerminalPageState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        action {
            val all = terminal.sessions()
            val active = all.firstOrNull()
            mutable.value =
                mutable.value.copy(
                    hasSession = all.isNotEmpty(),
                    sessions = all,
                    activeSessionId = active?.sessionId,
                    session = active,
                )
        }
    }

    /** A null directory attaches the retained session; only an explicit directory starts one. */
    fun open(directoryToStart: String?) =
        action {
            if (directoryToStart != null) {
                val currentSessions = terminal.sessions()
                if (currentSessions.size >= 2) {
                    mutable.value = mutable.value.copy(errorMessage = "CAPACITY_FULL")
                    return@action
                }
                val newSession = terminal.start(directoryToStart)
                detachConnection(mutable)
                val all = terminal.sessions()
                mutable.value =
                    mutable.value.copy(
                        hasSession = true,
                        sessions = all,
                        activeSessionId = newSession.sessionId,
                        session = newSession,
                        errorMessage = null,
                    )
                connect(newSession.sessionId)
            } else {
                val activeId = mutable.value.activeSessionId ?: terminal.sessions().firstOrNull()?.sessionId
                if (activeId != null) {
                    connect(activeId)
                }
            }
        }

    fun switchSession(sessionId: String) =
        action {
            if (mutable.value.activeSessionId == sessionId && mutable.value.connection != null) return@action
            detachConnection(mutable)
            val all = terminal.sessions()
            val target = all.find { it.sessionId == sessionId } ?: terminal.query(sessionId)
            mutable.value =
                mutable.value.copy(
                    activeSessionId = sessionId,
                    session = target,
                    sessions = all,
                )
            connect(sessionId)
        }

    fun refresh() =
        action {
            val all = terminal.sessions()
            val activeId = mutable.value.activeSessionId
            val currentSession =
                if (activeId != null) {
                    all.find { it.sessionId == activeId } ?: if (all.isNotEmpty()) terminal.query(activeId) else null
                } else {
                    all.firstOrNull()
                }
            mutable.value =
                mutable.value.copy(
                    hasSession = all.isNotEmpty(),
                    sessions = all,
                    activeSessionId = currentSession?.sessionId,
                    session = currentSession,
                )
        }

    fun stop(sessionId: String? = null) =
        action {
            val targetId = sessionId ?: mutable.value.activeSessionId
            if (targetId != null) {
                val stopped = terminal.stop(targetId)
                val all = terminal.sessions()
                mutable.value =
                    mutable.value.copy(
                        sessions = all,
                        session = if (mutable.value.activeSessionId == targetId) stopped else mutable.value.session,
                    )
            }
        }

    fun settle(sessionId: String? = null) =
        action {
            val targetId = sessionId ?: mutable.value.activeSessionId
            if (targetId != null) {
                if (mutable.value.activeSessionId == targetId) {
                    detachConnection(mutable)
                }
                terminal.settle(targetId)
                val remaining = terminal.sessions()
                val nextActive = remaining.firstOrNull()
                mutable.value =
                    mutable.value.copy(
                        hasSession = remaining.isNotEmpty(),
                        sessions = remaining,
                        activeSessionId = nextActive?.sessionId,
                        session = nextActive,
                    )
                if (nextActive != null && mutable.value.connection == null) {
                    connect(nextActive.sessionId)
                }
            }
        }

    fun disconnect() = action { detachConnection(mutable) }

    @Suppress("TooGenericExceptionCaught") // Only reads; one outstanding query, no input/start replay.
    suspend fun observe(connection: ManualTerminal.Connection) {
        try {
            while (mutable.value.connection === connection &&
                mutable.value.session?.canSettle != true && mutable.value.session?.phase != "UNKNOWN"
            ) {
                delay(1000)
                mutex.withLock {
                    if (mutable.value.connection === connection) {
                        val currentId = mutable.value.activeSessionId
                        val updated = if (currentId != null) terminal.query(currentId) else terminal.query()
                        val all = terminal.sessions()
                        mutable.value =
                            mutable.value.copy(
                                session = updated,
                                sessions = all,
                            )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(failed = true)
        }
    }

    private suspend fun connect(sessionId: String? = null) {
        if (mutable.value.connection != null) return
        val targetId = sessionId ?: mutable.value.activeSessionId
        val queryState = if (targetId != null) terminal.query(targetId) else terminal.query()
        val all = terminal.sessions()
        mutable.value =
            mutable.value.copy(
                session = queryState,
                hasSession = true,
                sessions = all,
                activeSessionId = queryState.sessionId,
            )
        val connection = if (targetId != null) terminal.attach(targetId) else terminal.attach()
        // No suspension between acquiring the connection and assigning its owner.
        mutable.value =
            mutable.value.copy(
                connection = connection,
                isWriter = connection.isWriter,
            )
    }

    @Suppress("TooGenericExceptionCaught") // Surface failure; never retry ambiguous input or start.
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                mutable.value = mutable.value.copy(busy = true, failed = false)
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutable.value = mutable.value.copy(failed = true)
                } finally {
                    mutable.value = mutable.value.copy(busy = false)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Transport closes in detach's finally; there is no live UI after clear.
    override fun onCleared() {
        cleanup.launch {
            try {
                mutex.withLock { detachConnection(mutable) }
            } catch (failure: Exception) {
                android.util.Log.w("HelixTerminal", "Detach failed; retained session needs explicit query", failure)
                // The persisted session is deliberately retained for explicit query/settlement.
            } finally {
                cleanup.cancel()
            }
        }
    }
}

private suspend fun detachConnection(mutable: MutableStateFlow<TerminalPageState>) {
    val connection = mutable.value.connection
    mutable.value = mutable.value.copy(connection = null)
    connection?.detach()
}
