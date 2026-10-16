package com.helix.app.chat

import com.helix.app.provider.ProviderService
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionSearchMatchKind
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

    fun refresh()

    fun search(query: String)

    fun clearSearch()
}

/** One owner for the directory's rows, bounded search and cancellation lifetime. */
internal class SessionDirectoryService(
    private val scope: CoroutineScope,
    private val readRows: () -> List<SessionRowUi>,
    private val searchRows: (String, () -> Unit) -> SessionSearchUiState,
) : SessionDirectory {
    private val rows = MutableStateFlow<List<SessionRowUi>>(emptyList())
    override val sessions = rows.asStateFlow()
    private val searchState = MutableStateFlow(SessionSearchUiState())
    override val search = searchState.asStateFlow()
    private var searchJob: Job? = null

    override fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Caller uses the existing application work scope; Room never runs on the UI thread. */
    fun refreshNow() {
        rows.value = readRows()
    }

    @Synchronized
    override fun search(query: String) {
        searchJob?.cancel()
        searchJob = null
        searchState.value = SessionSearchUiState(query = query.takeUnless(String::isBlank).orEmpty())
        if (query.isBlank()) return
        searchJob =
            scope.launch {
                delay(200)
                val context = currentCoroutineContext()
                val result = searchRows(query) { context.ensureActive() }
                synchronized(this@SessionDirectoryService) {
                    context.ensureActive()
                    // Query normalization belongs to matching, never to the user's editable input.
                    searchState.value = result.copy(query = query)
                }
            }
    }

    @Synchronized
    override fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        searchState.value = SessionSearchUiState()
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            storage: HelixStorage,
            providers: ProviderService,
        ) = SessionDirectoryService(
            scope,
            readRows = {
                val names = providers.rows.value.associate { it.id to it.displayName }
                storage.sessions.list().map { SessionRowUi.from(it, it.providerId?.let(names::get)) }
            },
            searchRows = { query, checkActive ->
                val result = storage.sessionSearch.search(query, checkActive = checkActive)
                SessionSearchUiState(
                    query = query,
                    hits =
                        result.hits.map { hit ->
                            SessionSearchHitUi(
                                sessionId = hit.sessionId,
                                title = hit.sessionTitle,
                                isArchived = hit.isArchived,
                                matchesTitle = SessionSearchMatchKind.TITLE in hit.matchedKinds,
                                matchesMessage = SessionSearchMatchKind.MESSAGE in hit.matchedKinds,
                                messageSnippet = hit.messageSnippet,
                            )
                        },
                    truncated = result.truncated,
                )
            },
        )
    }
}
