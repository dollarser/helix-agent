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
    val maxSessions: Int = 2,
)

/** Owns the connection across rotation; finishing the page never stops the shell. */
@Suppress("TooManyFunctions") // One owner serializes page entry, explicit controls and lifecycle cleanup.
internal class ManualTerminalViewModel(
    private val terminal: ManualTerminal,
) : ViewModel() {
    private val mutable = MutableStateFlow(TerminalPageState(maxSessions = terminal.maxSessions))
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialEntryAttempted = false

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

    /** Enter the user-operated page. Never repeat an ambiguous automatic start on resume. */
    fun enter(directory: String) =
        action {
            if (mutable.value.connection != null) return@action
            val mayStart = !initialEntryAttempted
            initialEntryAttempted = true
            val all = terminal.sessions()
            val active = all.find { it.sessionId == mutable.value.activeSessionId } ?: all.firstOrNull()
            mutable.value =
                mutable.value.copy(
                    hasSession = all.isNotEmpty(),
                    sessions = all,
                    activeSessionId = active?.sessionId,
                    session = active,
                )
            if (active != null) {
                if (active.phase != "UNKNOWN" && !active.canSettle) connect(active.sessionId)
            } else if (mayStart) {
                connect(startSession(terminal, mutable, directory))
            }
        }

    /** A null directory attaches the retained session; only an explicit directory starts one. */
    fun open(directoryToStart: String?) =
        action {
            if (directoryToStart != null) {
                val currentSessions = terminal.sessions()
                if (currentSessions.count { !it.canSettle } >= terminal.maxSessions) {
                    mutable.value = mutable.value.copy(errorMessage = "CAPACITY_FULL")
                    return@action
                }
                connect(startSession(terminal, mutable, directoryToStart))
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
            if (currentSession?.phase == "UNKNOWN") detachConnection(mutable)
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

    fun settle(sessionId: String? = mutable.value.activeSessionId) =
        action {
            val targetId = sessionId
            if (targetId != null) {
                var observed = terminal.query(targetId)
                if (!observed.canSettle) {
                    observed = terminal.stop(targetId)
                    val deadline = System.nanoTime() + CLOSE_WAIT_NANOS
                    while (!observed.canSettle && observed.phase != "UNKNOWN" && System.nanoTime() < deadline) {
                        delay(100)
                        observed = terminal.query(targetId)
                    }
                }
                if (!observed.canSettle) {
                    mutable.value = mutable.value.copy(session = observed, errorMessage = "CLOSE_UNCONFIRMED")
                    return@action
                }
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
                        errorMessage = null,
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
                        if (updated.phase == "UNKNOWN") detachConnection(mutable)
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
        if (!queryState.canConnect) return
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
                } catch (failure: Exception) {
                    mutable.value =
                        if (failure.message == "CAPACITY_FULL") {
                            mutable.value.copy(errorMessage = "CAPACITY_FULL")
                        } else {
                            mutable.value.copy(failed = true)
                        }
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

    companion object {
        private const val CLOSE_WAIT_NANOS = 15_000_000_000L
    }
}

private suspend fun detachConnection(mutable: MutableStateFlow<TerminalPageState>) {
    val connection = mutable.value.connection
    mutable.value = mutable.value.copy(connection = null)
    connection?.detach()
}

private suspend fun startSession(
    terminal: ManualTerminal,
    mutable: MutableStateFlow<TerminalPageState>,
    directory: String,
): String {
    val newSession = terminal.start(directory)
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
    return newSession.sessionId
}
