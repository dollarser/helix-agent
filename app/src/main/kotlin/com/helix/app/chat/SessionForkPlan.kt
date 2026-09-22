package com.helix.app.chat

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.ContextHistory
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.entity.MessageEntity

/** A fork contains a protocol-complete prefix, not an execution snapshot. */
internal data class SessionForkPlan(
    val rows: List<MessageEntity>,
    val checkpoint: ContextCompaction.Checkpoint?,
    val checkpointId: String?,
    val boundaryId: String,
) {
    companion object {
        const val KIND = "SESSION_FORK"
        const val MAX_ROWS = 8192

        fun prepare(
            storage: HelixStorage,
            sessionId: String,
            messageId: String,
            checkActive: () -> Unit,
        ): SessionForkPlan {
            val target = storage.messages.resolve(messageId)
            require(target.sessionId == sessionId && target.kind != KIND) { "FORK_TARGET" }
            val rows = prefix(storage, sessionId, target.sequence, checkActive)
            val checkpointRow = rows.lastOrNull { it.kind == ContextCompaction.KIND }
            val checkpoint = ContextCompaction.checkpoint(storage, listOfNotNull(checkpointRow))
            val active = ContextCompaction.retained(rows, checkpoint).filter { it.kind != KIND }
            val boundary = completeBoundary(storage, active, checkActive)
            require(boundary >= 0) { "FORK_EMPTY" }
            val copied = rows.filter { it.sequence <= boundary && it.kind != KIND }
            require(checkpointRow == null || checkpointRow.sequence <= boundary) { "FORK_CHECKPOINT" }
            return SessionForkPlan(copied, checkpoint, checkpointRow?.id, copied.last().id)
        }

        private fun prefix(
            storage: HelixStorage,
            sessionId: String,
            through: Long,
            checkActive: () -> Unit,
        ): List<MessageEntity> {
            val rows = mutableListOf<MessageEntity>()
            var after = -1L
            while (after < through) {
                checkActive()
                val page = storage.messages.pageAfter(sessionId, after, 256).takeWhile { it.sequence <= through }
                require(page.isNotEmpty()) { "FORK_TARGET" }
                require(rows.size + page.size <= MAX_ROWS) { "FORK_LIMIT" }
                rows.addAll(page)
                after = page.last().sequence
            }
            return rows
        }

        private fun completeBoundary(
            storage: HelixStorage,
            rows: List<MessageEntity>,
            checkActive: () -> Unit,
        ): Long {
            val pending = mutableSetOf<String>()
            var boundary = -1L
            var bytes = 0L
            for (row in rows) {
                checkActive()
                val size = row.contentRef?.let { ContentRef.parse(it).size } ?: 0
                require(size <= ContextHistory.MAX_BODY_BYTES - bytes) { "FORK_LIMIT" }
                bytes += size
                val persisted =
                    ChatHistoryBuilder.PersistedRow(
                        null,
                        row.role,
                        row.kind,
                        ContextHistory.read(storage, row),
                    )
                val mapped = ChatHistoryBuilder.toModelMessagesStrict(listOf(persisted)).singleOrNull()
                if (row.kind in setOf(ChatHistoryBuilder.KIND_TOOL_CALLS, ChatHistoryBuilder.KIND_TOOL_RESULT)) {
                    require(mapped != null) { "FORK_PROTOCOL" }
                }
                if (mapped != null) {
                    if (mapped.role == ModelRole.TOOL) {
                        require(pending.remove(mapped.toolCallId?.value)) { "FORK_PROTOCOL" }
                    } else {
                        require(pending.isEmpty()) { "FORK_PROTOCOL" }
                        val callIds = mapped.toolCalls.map { it.id.value }
                        require(callIds.distinct().size == callIds.size) { "FORK_PROTOCOL" }
                        pending.addAll(mapped.toolCalls.map { it.id.value })
                    }
                }
                if (pending.isEmpty()) boundary = row.sequence
            }
            return boundary
        }
    }
}
