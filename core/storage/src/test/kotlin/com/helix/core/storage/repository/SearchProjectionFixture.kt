package com.helix.core.storage.repository

import com.helix.core.storage.dao.SessionSearchDao
import com.helix.core.storage.dao.SessionSearchRow
import com.helix.core.storage.entity.MessageSearchEntity

internal class SearchProjectionFixture : SessionSearchDao {
    override fun locateMessage(
        sessionId: String,
        query: String,
        before: Long?,
        after: Long?,
        forward: Boolean,
    ): com.helix.core.storage.dao.MessageSearchLocation? = null

    val bodies = linkedMapOf<String, MessageSearchEntity>()
    val activity = mutableMapOf<String, Long>()
    var query: Pair<String, Int>? = null
    var results = emptyList<SessionSearchRow>()
    var failure: RuntimeException? = null

    override fun insert(row: MessageSearchEntity) {
        bodies[row.messageId] = row
    }

    override fun copy(
        sourceId: String,
        targetId: String,
        sessionId: String,
    ) {
        bodies[sourceId]?.let { insert(it.copy(messageId = targetId, sessionId = sessionId)) }
    }

    override fun touch(
        sessionId: String,
        timestamp: Long,
    ) {
        activity[sessionId] = maxOf(activity[sessionId] ?: 0, timestamp)
    }

    override fun search(
        query: String,
        limit: Int,
    ): List<SessionSearchRow> {
        failure?.let { throw it }
        this.query = query to limit
        return results.take(limit)
    }
}
