package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.dao.SessionDao

/** The facet of a session that a search query matched. */
enum class SessionSearchMatchKind { TITLE, MESSAGE }

/**
 * One session hit of [SessionSearchRepository.search]. [messageSnippet] is a bounded
 * window around the first (most recent in scan order) matched message body; null when
 * only the title matched.
 */
data class SessionSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val isArchived: Boolean,
    val createdAt: Long,
    val matchedKinds: Set<SessionSearchMatchKind>,
    val messageSnippet: String?,
)

/**
 * The bounded outcome of one search pass (HXA-191). [scannedMessages] is how many
 * message bodies were actually read, [skippedMessages] how many could not be read
 * (missing body or over the byte bound), and [truncated] is true when the candidate cap
 * was reached so the caller can state that older messages were not searched.
 */
data class SessionSearchResult(
    val query: String,
    val hits: List<SessionSearchHit>,
    val scannedMessages: Int,
    val skippedMessages: Int,
    val truncated: Boolean,
)

/**
 * Read-only session/history search (HXA-191 slice). Titles are matched against the
 * existing session rows; message bodies are matched by reading at most
 * [maxMessageCandidates] of the most recent content-addressed bodies (newest session
 * first, newest message within a session) from [contentStore]. Pure JVM — the caller
 * runs this off the UI thread. No writes and no second session-state source: both
 * inputs are the existing Room DAOs plus the content store.
 */
class SessionSearchRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val contentStore: ContentStore,
) {
    fun search(
        rawQuery: String,
        maxMessageCandidates: Int = DEFAULT_MAX_MESSAGE_CANDIDATES,
        maxContentBytes: Int = DEFAULT_MAX_CONTENT_BYTES,
        maxHits: Int = DEFAULT_MAX_HITS,
    ): SessionSearchResult {
        require(maxMessageCandidates in 0..MAX_MESSAGE_CANDIDATES) { "maxMessageCandidates out of bounds" }
        require(maxContentBytes > 0) { "maxContentBytes must be positive" }
        require(maxHits > 0) { "maxHits must be positive" }
        val query = rawQuery.trim()
        if (query.isEmpty()) return SessionSearchResult("", emptyList(), 0, 0, false)
        val sessions = sessionDao.list().associateBy { it.id }
        val candidates = messageDao.contentSearchCandidates(maxMessageCandidates)
        var scanned = 0
        var skipped = 0
        val snippets = HashMap<String, String>()
        for (message in candidates) {
            val ref = message.contentRef?.let { encoded -> runCatching { ContentRef.parse(encoded) }.getOrNull() }
            val body = ref?.let { runCatching { contentStore.readBounded(it, maxContentBytes) }.getOrNull() }
            if (body == null) {
                skipped++
                continue
            }
            scanned++
            if (body.contains(query, ignoreCase = true)) {
                snippets.putIfAbsent(message.sessionId, snippetAround(body, query))
            }
        }
        val hits =
            sessions
                .values
                .map { session ->
                    val titleHit = session.title.contains(query, ignoreCase = true)
                    val snippet = snippets[session.id]
                    if (!titleHit && snippet == null) {
                        null
                    } else {
                        SessionSearchHit(
                            sessionId = session.id,
                            sessionTitle = session.title,
                            isArchived = session.archivedAt != null,
                            createdAt = session.createdAt,
                            matchedKinds =
                                buildSet {
                                    if (titleHit) add(SessionSearchMatchKind.TITLE)
                                    if (snippet != null) add(SessionSearchMatchKind.MESSAGE)
                                },
                            messageSnippet = snippet,
                        )
                    }
                }.filterNotNull()
                .sortedWith(compareByDescending<SessionSearchHit> { it.createdAt }.thenBy { it.sessionId })
                .take(maxHits)
                .toList()
        return SessionSearchResult(
            query = query,
            hits = hits,
            scannedMessages = scanned,
            skippedMessages = skipped,
            truncated = maxMessageCandidates > 0 && candidates.size >= maxMessageCandidates,
        )
    }

    private fun snippetAround(
        body: String,
        query: String,
    ): String {
        val index = body.lowercase().indexOf(query.lowercase())
        require(index >= 0) { "snippet requested for a non-matching body" }
        val start = (index - SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
        val end = (index + query.length + SNIPPET_CONTEXT_CHARS).coerceAtMost(body.length)
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < body.length) ELLIPSIS else ""
        return prefix + body.substring(start, end) + suffix
    }

    private companion object {
        const val DEFAULT_MAX_MESSAGE_CANDIDATES = 500
        const val DEFAULT_MAX_CONTENT_BYTES = 256 * 1024
        const val DEFAULT_MAX_HITS = 50
        const val MAX_MESSAGE_CANDIDATES = 5000
        const val SNIPPET_CONTEXT_CHARS = 60
        const val ELLIPSIS = "…"
    }
}
