package com.helix.feature.conversation.presentation

/** A session row for the session list (persisted state only). */
data class SessionRowUi(
    val id: String,
    val title: String,
    val createdAt: Long,
    val isArchived: Boolean,
    val providerName: String?,
    val model: String?,
    val directoryRef: String? = null,
    val providerId: String? = null,
    val lastActivityAt: Long = createdAt,
    val referenceMessageCount: Int? = null,
)

/** One session hit of the service's bounded session search (HXA-191 slice). */
data class SessionSearchHitUi(
    val sessionId: String,
    val title: String,
    val isArchived: Boolean,
    val matchesTitle: Boolean,
    val matchesMessage: Boolean,
    val messageSnippet: String?,
)

/**
 * The observable outcome of the service-owned session search (HXA-191 slice). [query] is
 * the exact typed query being shown ("" = not searching); [truncated] means more matching
 * sessions exist. Search covers all effective visible text, including older sessions.
 */
data class SessionSearchUiState(
    val query: String = "",
    val hits: List<SessionSearchHitUi> = emptyList(),
    val truncated: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val isSearching: Boolean get() = query.isNotEmpty()
}
