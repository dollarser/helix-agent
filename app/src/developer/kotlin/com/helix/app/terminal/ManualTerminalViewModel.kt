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
    val capacityRejected: Boolean = false,
    val maxSessions: Int = 2,
    val displayRevision: Int = 0,
)

/** Owns the connection across rotation; finishing the page never stops the shell. */
@Suppress("TooManyFunctions") // One owner serializes page entry, explicit controls and lifecycle cleanup.
internal class ManualTerminalViewModel(
    private val terminal: ManualTerminal,
    private val actionScope: CoroutineScope? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(TerminalPageState(maxSessions = terminal.maxSessions))
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialEntryAttempted = false

    init {
        action {
            val all = visibleSessions()
            val active = all.preferredTerminal()
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
        action(com.helix.app.diagnostics.OperationFailureLog.Stage.TERMINAL_ENTER) {
            if (mutable.value.connection != null) return@action
            val mayStart = !initialEntryAttempted
            initialEntryAttempted = true
            val all = visibleSessions()
            val active = all.find { it.sessionId == mutable.value.activeSessionId } ?: all.preferredTerminal()
            mutable.value =
                mutable.value.copy(
                    hasSession = all.isNotEmpty(),
                    sessions = all,
                    activeSessionId = active?.sessionId,
                    session = active,
                )
            if (active != null) {
                if (active.canConnect) {
                    connect(active.sessionId)
                } else if (mayStart) {
                    // Enter a fresh shell, never reinterpret or replay the unknown old execution.
                    connect(startSession(terminal, mutable, directory))
                }
            } else if (mayStart) {
                connect(startSession(terminal, mutable, directory))
            }
        }

    /** A null directory attaches the retained session; only an explicit directory starts one. */
    fun open(directoryToStart: String?) =
        action(com.helix.app.diagnostics.OperationFailureLog.Stage.TERMINAL_OPEN) {
            if (directoryToStart != null) {
                connect(startSession(terminal, mutable, directoryToStart))
            } else {
                val activeId = mutable.value.activeSessionId ?: visibleSessions().preferredTerminal()?.sessionId
                if (activeId != null) {
                    connect(activeId)
                }
            }
        }

    fun switchSession(sessionId: String) =
        action {
            if (mutable.value.activeSessionId == sessionId && mutable.value.connection != null) return@action
            val all = visibleSessions()
            val target = all.find { it.sessionId == sessionId } ?: return@action
            detachConnection(mutable)
            mutable.value = mutable.value.copy(activeSessionId = sessionId, session = target, sessions = all)
            connect(sessionId)
        }

    fun refresh() = action { refreshVisibleSessions() }

    fun stop(sessionId: String? = null) =
        action {
            val targetId = sessionId ?: mutable.value.activeSessionId
            if (targetId != null) {
                val stopped = terminal.stop(targetId)
                refreshVisibleSessions(stopped)
            }
        }

    fun closeSession(sessionId: String? = mutable.value.activeSessionId) =
        action {
            val targetId = sessionId
            if (targetId != null) {
                var observed = terminal.query(targetId)
                if (!observed.canSettle && observed.phase != "UNKNOWN") {
                    observed = terminal.stop(targetId)
                    val deadline = System.nanoTime() + CLOSE_WAIT_NANOS
                    while (!observed.canSettle && observed.phase != "UNKNOWN" && System.nanoTime() < deadline) {
                        delay(100)
                        observed = terminal.query(targetId)
                    }
                }
                if (!observed.canSettle && observed.phase != "UNKNOWN") {
                    mutable.value =
                        mutable.value.copy(
                            session =
                                if (mutable.value.activeSessionId == targetId) observed else mutable.value.session,
                            errorMessage = "CLOSE_UNCONFIRMED",
                        )
                    return@action
                }
                // Closing the UI is not deletion of retained Runtime facts or Agent audit.
                refreshVisibleSessions(observed)
                mutable.value = mutable.value.copy(errorMessage = null)
            }
        }

    /** Local presentation only: no shell input, ownership release, or audit/history mutation. */
    fun dismissCapacityNotice() {
        mutable.value = mutable.value.copy(capacityRejected = false)
    }

    fun clearDisplay() {
        mutable.value = mutable.value.copy(displayRevision = mutable.value.displayRevision + 1)
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
                        refreshVisibleSessions(updated)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(failed = true)
        }
    }

    private suspend fun visibleSessions(): List<ManualTerminal.State> = terminal.sessions().filter { it.canConnect }

    private suspend fun refreshVisibleSessions(observed: ManualTerminal.State? = null) {
        if (observed != null && !observed.canConnect) {
            presentSessions(
                mutable.value.sessions.filter { it.sessionId != observed.sessionId && it.canConnect },
                false,
            )
        }
        val all =
            visibleSessions()
                .map { if (observed != null && it.sessionId == observed.sessionId) observed else it }
                .filter { it.canConnect }
        presentSessions(all)
    }

    @Suppress("TooGenericExceptionCaught") // A closed transport cannot resurrect a proven-ended UI tab.
    private suspend fun presentSessions(all: List<ManualTerminal.State>, attach: Boolean = true) {
        val old = mutable.value
        val selected = all.find { it.sessionId == old.activeSessionId } ?: all.preferredTerminal()
        val disconnect =
            old.connection != null &&
                (selected?.sessionId != old.activeSessionId || selected?.canConnect != true)
        mutable.value =
            mutable.value.copy(
                hasSession = all.isNotEmpty(),
                sessions = all,
                activeSessionId = selected?.sessionId,
                session = selected,
                connection = if (disconnect) null else old.connection,
            )
        if (disconnect) {
            try {
                old.connection?.detach()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(errorMessage = "OLD_CONNECTION_UNCONFIRMED")
            }
        }
        if (attach && selected?.canConnect == true && mutable.value.connection == null) connect(selected.sessionId)
        terminal.reclaim()
    }

    private suspend fun connect(sessionId: String? = null) {
        if (mutable.value.connection != null) return
        val targetId = sessionId ?: mutable.value.activeSessionId
        val queryState = if (targetId != null) terminal.query(targetId) else terminal.query()
        if (queryState.canSettle) {
            refreshVisibleSessions(queryState)
            return
        }
        mutable.value =
            mutable.value.copy(
                session = queryState,
                hasSession = true,
                sessions = listOf(queryState) + mutable.value.sessions.filter { it.sessionId != queryState.sessionId },
                activeSessionId = queryState.sessionId,
            )
        if (queryState.canConnect) {
            val connection = if (targetId != null) terminal.attach(targetId) else terminal.attach()
            // No suspension between acquiring the connection and assigning its owner.
            mutable.value =
                mutable.value.copy(
                    connection = connection,
                    isWriter = connection.isWriter,
                )
        }
    }

    @Suppress("TooGenericExceptionCaught") // Surface failure; never retry ambiguous input or start.
    private fun action(
        stage: com.helix.app.diagnostics.OperationFailureLog.Stage =
            com.helix.app.diagnostics.OperationFailureLog.Stage.TERMINAL_CONTROL,
        block: suspend () -> Unit,
    ) {
        (actionScope ?: viewModelScope).launch {
            mutex.withLock {
                mutable.value =
                    mutable.value.copy(busy = true, failed = false, errorMessage = null, capacityRejected = false)
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: com.helix.app.proot.ExecutionControlUnreadable) {
                    com.helix.app.diagnostics.OperationFailureLog
                        .record(stage, failure)
                    mutable.value = mutable.value.copy(errorMessage = "OLD_STATE_INCOMPATIBLE")
                } catch (failure: Exception) {
                    com.helix.app.diagnostics.OperationFailureLog
                        .record(stage, failure)
                    mutable.value =
                        if (failure.message == "CAPACITY_FULL") {
                            // START admission refused before a new session exists. Keep selection and viewport intact.
                            mutable.value.copy(capacityRejected = true)
                        } else if (failure.message in
                            setOf("SHELL_START_FAILED", "SHELL_START_TIMEOUT") ||
                            failure.message in com.helix.runtime.proot.ipc.PtySessionProtocol.START_REFUSALS
                        ) {
                            mutable.value.copy(errorMessage = failure.message)
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

// Only old transport detach fails; the verified new shell retains its own identity.
@Suppress("TooGenericExceptionCaught")
private suspend fun startSession(
    terminal: ManualTerminal,
    mutable: MutableStateFlow<TerminalPageState>,
    directory: String,
): String {
    val newSession = terminal.start(directory)
    val oldConnection = mutable.value.connection
    mutable.value =
        mutable.value.copy(
            connection = null,
            hasSession = true,
            sessions = listOf(newSession) + mutable.value.sessions,
            activeSessionId = newSession.sessionId,
            session = newSession,
            errorMessage = null,
        )
    try {
        oldConnection?.detach()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        mutable.value = mutable.value.copy(errorMessage = "OLD_CONNECTION_UNCONFIRMED")
    }
    return newSession.sessionId
}
