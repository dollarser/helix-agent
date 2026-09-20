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
    val hasSession: Boolean = true,
    val session: ManualTerminal.State? = null,
    val connection: ManualTerminal.Connection? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
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
        action { mutable.value = mutable.value.copy(hasSession = terminal.hasSession()) }
    }

    /** A null directory attaches the retained session; only an explicit directory starts one. */
    fun open(directoryToStart: String?) =
        action {
            if (directoryToStart != null) {
                val session = terminal.start(directoryToStart)
                mutable.value = mutable.value.copy(hasSession = true, session = session)
            }
            connect()
        }

    fun refresh() =
        action {
            val exists = terminal.hasSession()
            mutable.value = mutable.value.copy(hasSession = exists, session = if (exists) terminal.query() else null)
        }

    fun stop() = action { mutable.value = mutable.value.copy(session = terminal.stop()) }

    fun settle() =
        action {
            detach()
            terminal.settle()
            mutable.value = TerminalPageState(hasSession = false)
        }

    fun disconnect() = action { detach() }

    @Suppress("TooGenericExceptionCaught") // Only reads; one outstanding query, no input/start replay.
    suspend fun observe(connection: ManualTerminal.Connection) {
        try {
            while (mutable.value.connection === connection &&
                mutable.value.session?.canSettle != true && mutable.value.session?.phase != "UNKNOWN"
            ) {
                delay(1000)
                mutex.withLock {
                    if (mutable.value.connection === connection) {
                        mutable.value = mutable.value.copy(session = terminal.query())
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(failed = true)
        }
    }

    private suspend fun connect() {
        if (mutable.value.connection != null) return
        mutable.value = mutable.value.copy(session = terminal.query(), hasSession = true)
        val connection = terminal.attach()
        // No suspension between acquiring the connection and assigning its owner.
        mutable.value = mutable.value.copy(connection = connection)
    }

    private suspend fun detach() {
        val connection = mutable.value.connection
        mutable.value = mutable.value.copy(connection = null)
        connection?.detach()
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
                mutex.withLock { detach() }
            } catch (failure: Exception) {
                android.util.Log.w("HelixTerminal", "Detach failed; retained session needs explicit query", failure)
                // The persisted session is deliberately retained for explicit query/settlement.
            } finally {
                cleanup.cancel()
            }
        }
    }
}
