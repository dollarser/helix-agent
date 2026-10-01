package com.helix.app.provider

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.runtime.cli.client.CliReplayEntry
import com.helix.runtime.cli.client.CliReplayMaintenance

/** Marks only one bounded candidate page. Fork and publication share this short local transaction. */
internal class SubscriptionReplayRetention(
    private val transaction: (() -> Unit) -> Unit,
    private val sessionPage: (String?, Int) -> List<String>,
    private val messagePage: (String?, Int) -> List<com.helix.core.storage.entity.MessageEntity>,
    private val readBody: (com.helix.core.storage.entity.MessageEntity, Int) -> String?,
) {
    constructor(storage: HelixStorage) : this(
        storage::withTransaction,
        storage.sessions::pageIds,
        { after, limit -> storage.messages.retainedByKindAfter(ChatHistoryBuilder.KIND_TOOL_CALLS, after, limit) },
        storage.messages::readContentBounded,
    )

    fun unreferenced(entries: List<CliReplayEntry>): List<CliReplayEntry> {
        require(entries.size <= CliReplayMaintenance.PAGE_SIZE)
        if (entries.none { it.fingerprint != null }) return emptyList()
        var result = emptyList<CliReplayEntry>()
        transaction {
            val candidates = entries.filter { it.fingerprint != null }.associateBy { it.key }.toMutableMap()
            var after: String? = null
            var sessions = 0
            while (true) {
                check(!Thread.currentThread().isInterrupted) { "REPLAY_MAINTENANCE_INTERRUPTED" }
                val page = sessionPage(after, PAGE_SIZE)
                if (page.isEmpty()) break
                sessions += page.size
                check(sessions <= MAX_ROWS) { "REPLAY_HISTORY_LIMIT" }
                val owners = page.map(CliReplayMaintenance::hash).toSet()
                candidates.entries.removeAll { it.value.owner in owners }
                after = page.last()
            }
            if (sessions > 0) candidates.entries.removeAll { it.value.owner == null }
            if (candidates.isNotEmpty()) markHistory(candidates)
            result = entries.filter { it.key in candidates }
        }
        return result
    }

    private fun markHistory(candidates: MutableMap<String, CliReplayEntry>) {
        var after: String? = null
        var rows = 0
        var bytes = 0L
        while (candidates.isNotEmpty()) {
            check(!Thread.currentThread().isInterrupted) { "REPLAY_MAINTENANCE_INTERRUPTED" }
            val page = messagePage(after, PAGE_SIZE)
            if (page.isEmpty()) break
            rows += page.size
            check(rows <= MAX_ROWS) { "REPLAY_HISTORY_LIMIT" }
            for (row in page) {
                val ref = ContentRef.parse(requireNotNull(row.contentRef) { "REPLAY_HISTORY_MISSING" })
                check(ref.size <= MAX_BODY_BYTES && ref.size <= MAX_SCAN_BYTES - bytes) { "REPLAY_HISTORY_LIMIT" }
                bytes += ref.size
                val content = requireNotNull(readBody(row, MAX_BODY_BYTES))
                val model =
                    ChatHistoryBuilder
                        .toModelMessagesStrict(
                            listOf(ChatHistoryBuilder.PersistedRow(row.turnId, row.role, row.kind, content)),
                        ).single()
                check(model.toolCalls.isNotEmpty()) { "REPLAY_HISTORY_INVALID" }
                candidates.remove(
                    CliReplayMaintenance.hash(
                        model.toolCalls
                            .first()
                            .id.value,
                    ),
                )
            }
            after = page.last().id
        }
    }

    private companion object {
        const val PAGE_SIZE = 128
        const val MAX_ROWS = 65_536
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_SCAN_BYTES = 64L * 1024 * 1024
    }
}
