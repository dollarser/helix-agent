package com.helix.app.agent

import com.helix.core.agent.ContextSegments
import com.helix.core.agent.ContextSourceRow
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.MessageEntity

/** Storage and persisted-history parsing only; selection is owned by Core's ContextSegments. */
internal object ContextHistoryMapping {
    fun rows(
        storage: HelixStorage,
        rows: List<MessageEntity>,
    ): List<ContextSourceRow> =
        rows.map { row ->
            val content = ContextHistory.read(storage, row)
            // Decode failure is a fact, not an empty successful history. Raise it only if the
            // same selection/recovery algorithm actually consumes this row; storage errors propagate.
            var failure: String? = null
            val messages =
                try {
                    ChatHistoryBuilder.toModelMessagesStrict(
                        listOf(ChatHistoryBuilder.PersistedRow(row.turnId, row.role, row.kind, content, row.id)),
                    )
                } catch (invalid: IllegalArgumentException) {
                    failure = invalid.message ?: "CONTEXT_HISTORY_INVALID"
                    emptyList()
                }
            ContextSourceRow(
                row.id,
                row.turnId,
                row.sequence,
                row.role,
                row.kind,
                content,
                storage.messageAttachments.listByMessage(row.id).size,
                row.kind == ChatHistoryBuilder.KIND_TOOL_CALLS,
                messages,
                failure,
            )
        }

    fun mapped(
        storage: HelixStorage,
        row: MessageEntity,
    ) = ChatHistoryBuilder.toModelMessagesStrict(
        listOf(
            ChatHistoryBuilder.PersistedRow(
                row.turnId,
                row.role,
                row.kind,
                ContextHistory.read(storage, row),
                row.id,
            ),
        ),
    )
}
