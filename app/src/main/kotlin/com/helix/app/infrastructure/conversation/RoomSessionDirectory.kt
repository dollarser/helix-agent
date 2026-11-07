package com.helix.app.infrastructure.conversation

import com.helix.app.provider.ProviderService
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionSearchMatchKind
import com.helix.feature.conversation.application.SessionDirectoryService
import com.helix.feature.conversation.presentation.SessionRowUi
import com.helix.feature.conversation.presentation.SessionSearchHitUi
import com.helix.feature.conversation.presentation.SessionSearchUiState
import kotlinx.coroutines.CoroutineScope

/** Maps the persisted directory and bounded text index into conversation display facts. */
internal fun roomSessionDirectory(
    scope: CoroutineScope,
    storage: HelixStorage,
    providers: ProviderService,
): SessionDirectoryService =
    SessionDirectoryService(
        scope,
        readRows = {
            val names = providers.rows.value.associate { it.id to it.displayName }
            storage.sessions.referenceDirectory().map { row ->
                val session = row.session
                SessionRowUi(
                    id = session.id,
                    title = session.title,
                    createdAt = session.createdAt,
                    isArchived = session.archivedAt != null,
                    providerName = session.providerId?.let(names::get),
                    model = session.modelId,
                    directoryRef = session.directoryRef,
                    providerId = session.providerId,
                    lastActivityAt = session.lastActivityAt,
                    referenceMessageCount = row.messageCount,
                )
            }
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
