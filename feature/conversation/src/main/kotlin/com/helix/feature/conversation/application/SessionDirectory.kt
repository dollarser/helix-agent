package com.helix.feature.conversation.application

import com.helix.feature.conversation.presentation.SessionRowUi
import com.helix.feature.conversation.presentation.SessionSearchUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Session/history read interface. No Turn execution, permission or database types. */
interface SessionDirectory {
    val sessions: StateFlow<List<SessionRowUi>>
    val search: StateFlow<SessionSearchUiState>
    val readFailed: StateFlow<Boolean>

    fun refresh()

    fun search(query: String)

    fun clearSearch()
}

/** One owner for the directory's rows, bounded search and cancellation lifetime. */
@Suppress("TooGenericExceptionCaught") // Local read failures are displayed, never converted to empty success.
class SessionDirectoryService(
    private val scope: CoroutineScope,
    private val readRows: () -> List<SessionRowUi>,
    private val searchRows: (String, () -> Unit) -> SessionSearchUiState,
) : SessionDirectory {
    private val rows = MutableStateFlow<List<SessionRowUi>>(emptyList())
    override val sessions = rows.asStateFlow()
    private val searchState = MutableStateFlow(SessionSearchUiState())
    override val search = searchState.asStateFlow()
    private var searchJob: Job? = null
    private val refreshLock = Any()
    private val failed = MutableStateFlow(false)
    override val readFailed = failed.asStateFlow()

    override fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Caller uses the existing application work scope; Room never runs on the UI thread. */
    fun refreshNow() {
        // Keep read and publication together; search and unrelated writes do not take this lock.
        synchronized(refreshLock) {
            try {
                rows.value = readRows()
                failed.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve the last known rows; a read failure is not an empty directory.
                failed.value = true
            }
        }
    }

    @Synchronized
    override fun search(query: String) {
        searchJob?.cancel()
        searchJob = null
        searchState.value =
            SessionSearchUiState(query = query.takeUnless(String::isBlank).orEmpty(), loading = query.isNotBlank())
        if (query.isBlank()) return
        searchJob =
            scope.launch {
                delay(200)
                val context = currentCoroutineContext()
                val result =
                    try {
                        searchRows(query) { context.ensureActive() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SessionSearchUiState(query = query, failed = true)
                    }
                synchronized(this@SessionDirectoryService) {
                    context.ensureActive()
                    // Query normalization belongs to matching, never to the user's editable input.
                    searchState.value = result.copy(query = query, loading = false)
                }
            }
    }

    @Synchronized
    override fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        searchState.value = SessionSearchUiState()
    }
}
