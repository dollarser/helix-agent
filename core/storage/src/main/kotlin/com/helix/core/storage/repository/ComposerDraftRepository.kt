package com.helix.core.storage.repository

import com.helix.core.storage.dao.ComposerDraftDao
import com.helix.core.storage.entity.ComposerDraftEntity

/** Bounded local composer data. Callers run storage IO off the main thread. */
class ComposerDraftRepository(
    private val dao: ComposerDraftDao,
) {
    fun get(sessionId: String): ComposerDraftEntity? = dao.get(sessionId)

    fun save(
        draft: ComposerDraftEntity,
        expectedRevision: Long?,
    ): Boolean {
        require(draft.sessionId.isNotBlank() && draft.clientRequestId.length in 1..256)
        require(draft.revision >= 0 && draft.text.length <= 128_000 && '\u0000' !in draft.text)
        require(draft.attachmentIdsJson.length <= 16_384)
        return dao.save(draft, expectedRevision)
    }

    fun clear(
        sessionId: String,
        revision: Long,
        requestId: String,
    ): Boolean = dao.clear(sessionId, revision, requestId) == 1
}
