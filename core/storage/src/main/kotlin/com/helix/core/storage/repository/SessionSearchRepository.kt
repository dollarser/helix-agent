package com.helix.core.storage.repository

import com.helix.core.storage.SearchText
import com.helix.core.storage.dao.SessionSearchDao

enum class SessionSearchMatchKind { TITLE, MESSAGE }

data class SessionSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val isArchived: Boolean,
    val createdAt: Long,
    val lastActivityAt: Long,
    val matchedKinds: Set<SessionSearchMatchKind>,
    val messageSnippet: String?,
)

/** All effective visible text is searchable; truncated means additional matching sessions exist. */
data class SessionSearchResult(
    val query: String,
    val hits: List<SessionSearchHit>,
    val truncated: Boolean,
)

class SessionSearchRepository(
    private val dao: SessionSearchDao,
) {
    fun locateMessage(
        sessionId: String,
        query: String,
        before: Long?,
        after: Long?,
    ): com.helix.core.storage.dao.MessageSearchLocation? {
        require(before == null || after == null)
        if (query.isBlank()) return null
        val folded = SearchText.fold(query.trim())
        val match = dao.locateMessage(sessionId, folded, before, after, after != null)
        return if (match != null || (before == null && after == null)) {
            match
        } else {
            dao.locateMessage(sessionId, folded, null, null, after != null)
        }
    }

    fun search(
        rawQuery: String,
        maxHits: Int = 50,
        checkActive: () -> Unit = {},
    ): SessionSearchResult {
        require(maxHits in 1..200) { "maxHits out of bounds" }
        val query = rawQuery.trim()
        if (query.isEmpty()) return SessionSearchResult("", emptyList(), false)
        checkActive()
        val rows = dao.search(SearchText.fold(query), maxHits + 1)
        checkActive()
        val hits =
            rows.take(maxHits).map { row ->
                checkActive()
                SessionSearchHit(
                    sessionId = row.id,
                    sessionTitle = row.title,
                    isArchived = row.archivedAt != null,
                    createdAt = row.createdAt,
                    lastActivityAt = row.lastActivityAt,
                    matchedKinds =
                        buildSet {
                            if (row.titleMatch) add(SessionSearchMatchKind.TITLE)
                            if (row.messageSnippet != null) add(SessionSearchMatchKind.MESSAGE)
                        },
                    messageSnippet = row.messageSnippet,
                )
            }
        return SessionSearchResult(query, hits, rows.size > maxHits)
    }
}
